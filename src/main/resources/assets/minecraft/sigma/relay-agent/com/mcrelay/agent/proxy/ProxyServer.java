package com.mcrelay.agent.proxy;

import com.mcrelay.agent.RelayCoordinator;
import com.mcrelay.agent.adapter.RelayAdapter;
import com.mcrelay.agent.util.McLog;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;

/**
 * 代理服务器 P：在 A 进程内监听端口，接受 B 的连接。
 *
 * B 侧管道（按 addLast 顺序）：
 *   frame_decoder → decompress → frame_encoder → compress → client_app
 * 入站：frame_decoder → decompress → client_app
 * 出站：client_app → compress → frame_encoder → socket
 *
 * 同时通过 multicast 广播 LAN 游戏信息，使 B 的服务器列表底部出现局域网条目。
 */
public class ProxyServer {

    private final RelayCoordinator coordinator;
    private final RelayAdapter adapter;
    private final int port;
    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private io.netty.channel.Channel serverChannel;
    private Thread lanBroadcastThread;
    private volatile boolean running = true;

    public ProxyServer(RelayCoordinator coordinator, RelayAdapter adapter, int port) {
        this.coordinator = coordinator;
        this.adapter = adapter;
        this.port = port;
    }

    public void start() {
        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup(1);
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, worker)
         .channel(NioServerSocketChannel.class)
         .option(io.netty.channel.ChannelOption.SO_REUSEADDR, true)
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 McCompress.Decompressor decompressor = new McCompress.Decompressor();
                 McCompress.Compressor compressor = new McCompress.Compressor();
                 ch.pipeline().addLast("frame_decoder", new McFraming.FrameDecoder());
                 ch.pipeline().addLast("decompress", decompressor);
                 ch.pipeline().addLast("frame_encoder", new McFraming.FrameEncoder());
                 ch.pipeline().addLast("compress", compressor);
                 ch.pipeline().addLast("client_app", new ClientHandler(coordinator, adapter, compressor, decompressor));
             }
         });

        try {
            serverChannel = b.bind(port).syncUninterruptibly().channel();
            McLog.info("P 监听 0.0.0.0:" + port + " 就绪");
        } catch (Throwable t) {
            McLog.error("P 绑定端口 " + port + " 失败", t);
        }

        // 启动 LAN 广播线程
        startLanBroadcast();
    }

    /**
     * Minecraft LAN 广播：每 1.5 秒向 224.0.2.60:4445 发送 multicast 数据包。
     * 格式: [MOTD]§r§r§r§r§r<MOTD文本>§r§r§r§r§r[AD]§r§r§r§r§r<端口>§r§r§r§r§r
     *
     * 这使 B 的多人游戏服务器列表底部出现局域网条目。
     */
    private void startLanBroadcast() {
        lanBroadcastThread = new Thread(() -> {
            // MC LAN 广播地址
            final String multicastGroup = "224.0.2.60";
            final int multicastPort = 4445;

            // 广播消息格式
            String motd = "MC-Relay Proxy";
            String message = "[MOTD]" + motd + "[/MOTD][AD]" + port + "[/AD]";
            byte[] data = message.getBytes(StandardCharsets.UTF_8);

            McLog.info("LAN 广播线程启动: " + multicastGroup + ":" + multicastPort);

            while (running) {
                DatagramSocket socket = null;
                try {
                    socket = new DatagramSocket();
                    socket.setBroadcast(true);
                    InetAddress group = InetAddress.getByName(multicastGroup);
                    DatagramPacket packet = new DatagramPacket(data, data.length, group, multicastPort);
                    socket.send(packet);
                } catch (Throwable t) {
                    // 静默忽略广播失败（可能网络不支持 multicast）
                } finally {
                    if (socket != null) {
                        try { socket.close(); } catch (Throwable ignored) {}
                    }
                }

                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            McLog.info("LAN 广播线程已停止");
        }, "mc-relay-lan-broadcast");
        lanBroadcastThread.setDaemon(true);
        lanBroadcastThread.start();
    }

    public void stop() {
        running = false;
        if (lanBroadcastThread != null) {
            lanBroadcastThread.interrupt();
        }
        if (serverChannel != null) {
            try { serverChannel.close().syncUninterruptibly(); } catch (Throwable ignored) {}
            serverChannel = null;
        }
        if (boss != null) boss.shutdownGracefully();
        if (worker != null) worker.shutdownGracefully();
    }
}
