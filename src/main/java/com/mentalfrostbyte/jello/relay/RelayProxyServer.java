package com.mentalfrostbyte.jello.relay;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import org.jspecify.annotations.Nullable;

/**
 * Embedded Netty TCP server for Sigma Relay ({@code P}), optionally paired with {@link RelayLanBroadcast}
 * so nearby clients discover the relay session automatically under Multiplayer LAN Worlds.
 */
public final class RelayProxyServer {

    private final int requestedPort;
    private final boolean allowLan;
    private final RelayCoordinator coordinator;
    private @Nullable EventLoopGroup bossGroup;
    private @Nullable EventLoopGroup workerGroup;
    private @Nullable Channel serverChannel;
    private @Nullable RelayLanBroadcast lanBroadcast;
    private volatile int actualPort = -1;

    public RelayProxyServer(final int requestedPort, final boolean allowLan, final RelayCoordinator coordinator) {
        this.requestedPort = requestedPort;
        this.allowLan = allowLan;
        this.coordinator = coordinator;
    }

    public synchronized void start() throws InterruptedException {
        if (isRunning()) {
            return;
        }
        this.bossGroup = new MultiThreadIoEventLoopGroup(1, daemonFactory("Sigma-Relay-Boss"), NioIoHandler.newFactory());
        this.workerGroup = new MultiThreadIoEventLoopGroup(2, daemonFactory("Sigma-Relay-Worker"), NioIoHandler.newFactory());

        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(this.bossGroup, this.workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        ch.pipeline()
                            .addLast("timeout", new ReadTimeoutHandler(30))
                            .addLast("splitter", new Varint21FrameDecoder(null))
                            .addLast("prepender", new Varint21LengthFieldPrepender())
                            .addLast("relay_session", new RelayProxySession(RelayProxyServer.this.coordinator));
                    }
                });

            InetSocketAddress bindAddress = this.allowLan
                ? new InetSocketAddress(this.requestedPort)
                : new InetSocketAddress("127.0.0.1", this.requestedPort);

            Channel bound = bootstrap.bind(bindAddress).sync().channel();
            this.serverChannel = bound;
            this.actualPort = ((InetSocketAddress) bound.localAddress()).getPort();

            if (this.allowLan) {
                RelayLanBroadcast broadcast = new RelayLanBroadcast(this.actualPort, this.coordinator::buildCurrentMotd);
                broadcast.start();
                this.lanBroadcast = broadcast;
            }
        } catch (final RuntimeException | InterruptedException failure) {
            stop();
            throw failure;
        }
    }

    public synchronized void stop() {
        RelayLanBroadcast broadcast = this.lanBroadcast;
        this.lanBroadcast = null;
        if (broadcast != null) {
            broadcast.stop();
        }

        Channel ch = this.serverChannel;
        this.serverChannel = null;
        if (ch != null) {
            ch.close().awaitUninterruptibly(2L, TimeUnit.SECONDS);
        }

        EventLoopGroup boss = this.bossGroup;
        this.bossGroup = null;
        if (boss != null) {
            boss.shutdownGracefully(0L, 1L, TimeUnit.SECONDS);
        }

        EventLoopGroup worker = this.workerGroup;
        this.workerGroup = null;
        if (worker != null) {
            worker.shutdownGracefully(0L, 1L, TimeUnit.SECONDS);
        }
        this.actualPort = -1;
    }

    public boolean isRunning() {
        Channel ch = this.serverChannel;
        return ch != null && ch.isActive();
    }

    public int port() {
        return this.actualPort > 0 ? this.actualPort : this.requestedPort;
    }

    private static ThreadFactory daemonFactory(final String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
