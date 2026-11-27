package com.mcrelay.agent.proxy;

import com.mcrelay.agent.RelayCoordinator;
import com.mcrelay.agent.adapter.RelayAdapter;
import com.mcrelay.agent.util.McLog;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

/**
 * 1.13 – 1.20.1（协议 393 – 763）的登录流程。
 *
 * 序列：LoginStart → SetCompression → LoginSuccess → JoinGame → 世界数据
 * 这一代没有 configuration 阶段，注册表随 JoinGame 一起发。
 *
 * 本类是从原 ClientHandler.onLogin 原样搬来的，逻辑未作改动 ——
 * 1.20.1 已经调通，加新版本支持不能动它。
 */
public class Session1201 implements LoginSession {

    private final ChannelHandlerContext ctx;
    private final RelayCoordinator coordinator;
    private final RelayAdapter adapter;
    private final McCompress.Compressor compressor;
    private final McCompress.Decompressor decompressor;
    private final int compressionThreshold;
    private final PacketSink sink;
    private final int clientProtocol;

    private boolean play;

    public Session1201(ChannelHandlerContext ctx, RelayCoordinator coordinator, RelayAdapter adapter,
                       McCompress.Compressor compressor, McCompress.Decompressor decompressor,
                       int compressionThreshold, PacketSink sink) {
        this(ctx, coordinator, adapter, compressor, decompressor, compressionThreshold, sink, 763);
    }

    public Session1201(ChannelHandlerContext ctx, RelayCoordinator coordinator, RelayAdapter adapter,
                       McCompress.Compressor compressor, McCompress.Decompressor decompressor,
                       int compressionThreshold, PacketSink sink, int clientProtocol) {
        this.ctx = ctx;
        this.coordinator = coordinator;
        this.adapter = adapter;
        this.compressor = compressor;
        this.decompressor = decompressor;
        this.compressionThreshold = compressionThreshold;
        this.sink = sink;
        this.clientProtocol = clientProtocol > 0 ? clientProtocol : 763;
    }

    @Override
    public boolean inPlay() { return play; }

    @Override
    public void handle(int id, ByteBuf buf) {
        if (play) {
            // 整段转发，含包 ID —— buf 的 readerIndex 仍在包首。
            // OutboundDrop 已丢掉 A 的出站 Packet，服务器的 KeepAlive、传送确认
            // 全靠 B 回应；拦哪一类，哪一类就没人应答。
            coordinator.sendToServer(buf.duplicate().retain());
            return;
        }
        if (id != PacketBuilder.C2S_LOGIN_START) {
            McLog.warn("登录态忽略 B 的包 0x" + Integer.toHexString(id));
            return;
        }
        McFraming.readVarInt(buf);   // 跳过包 ID，后面按字段读
        onLoginStart(buf);
    }

    private void onLoginStart(ByteBuf buf) {
        String name = McFraming.readString(buf, 16);
        // 1.19–1.20.1 的 LoginStart 在名字后可能跟可选签名/可选 UUID（1.19.3+ 为 boolean+UUID），
        // 我们统一继承宿主 A 的真实档案，无需强读尾巴字节。
        McLog.info("B Login Start: name=" + name + " protocol=" + clientProtocol
            + " (buf remaining=" + buf.readableBytes() + ")");

        // 1) 先设置客户端实际协议号，再从 A 现场构造对应版本的 JoinGame
        adapter.setClientProtocol(clientProtocol);

        ByteBuf joinGame;
        try {
            joinGame = adapter.buildJoinGame();
            McLog.info("JoinGame 现场构造成功: " + joinGame.readableBytes() + " bytes");
        } catch (Throwable t) {
            McLog.error("构造 JoinGame 失败", t);
            String errMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            sink.send(PacketBuilder.loginDisconnect(
                    "{\"text\":\"MC-Relay: JoinGame 构造失败 (" + escape(errMsg) +
                    ")\",\"color\":\"red\"}"));
            ctx.close();
            return;
        }

        // 2) 完整继承宿主 A 的真实 AuthCl 账号档案（真实 UUID + 用户名 + 皮肤属性），不受 B 自身账号影响
        RelayAdapter.PlayerProfile profile = adapter.readProfile();
        String loginName = profile.name != null ? profile.name : name;
        java.util.UUID loginUuid = profile.uuid != null ? profile.uuid : PacketBuilder.offlineUuid(loginName);
        McLog.info("Login Success 继承宿主档案: uuid=" + loginUuid + " name=" + loginName
            + " props=" + profile.properties.size());

        // 3) 发送 SetCompression（不压缩），然后启用压缩
        ByteBuf setCompressBuf = PacketBuilder.setCompression(compressionThreshold);
        McLog.info("→B SetCompression: threshold=" + compressionThreshold +
            " payload=" + setCompressBuf.readableBytes() + " bytes (压缩前)");
        ctx.writeAndFlush(setCompressBuf).addListener(f -> {
            if (!f.isSuccess()) {
                McLog.error("SetCompression 发送失败", f.cause());
                return;
            }
            McLog.info("SetCompression 已写入 socket，启用压缩 threshold=" + compressionThreshold);
        });

        // 启用压缩（writeAndFlush 在 EventLoop 线程中同步执行编码，此处 threshold 修改在编码之后）
        compressor.setThreshold(compressionThreshold);
        decompressor.setThreshold(compressionThreshold);
        McLog.info("压缩已启用: compressor.threshold=" + compressor.getThreshold() +
            " decompressor.threshold=" + decompressor.getThreshold());

        // 4) 发送 LoginSuccess（压缩格式，携带宿主真实 UUID 与 AuthCl 皮肤属性）
        ByteBuf loginSuccessBuf = PacketBuilder.loginSuccess(loginUuid, loginName, profile.properties, clientProtocol);
        McLog.info("→B LoginSuccess: " + loginSuccessBuf.readableBytes() + " bytes (压缩后由 Compressor 编码)");
        sink.send(loginSuccessBuf);

        // 5) 切到 PLAY，发 JoinGame
        play = true;
        McLog.info("→B JoinGame: " + joinGame.readableBytes() + " bytes (pktId=0x" +
            Integer.toHexString(joinGame.getByte(0) & 0xFF) + " 压缩后由 Compressor 编码)");
        McLog.info("JoinGame hex: " + ClientHandler.hexDump(joinGame, 32));
        sink.send(joinGame);

        // 6) 注册 B 通道到协调器
        coordinator.onClientConnected(ctx.channel());

        // 7) 异步发送初始世界数据。
        // 每次 B 连入都完整跑一遍，全部从 A 的当前内存现读现发，不用任何缓存 ——
        // 缓存的数据会随 A 跑图、换维度而过期，B 看到的世界就和 A 对不上。
        //
        // 重建期间暂存服务器实时包：两条流同时写 B 会交织，区块解析不出来。
        coordinator.beginWorldRebuild();

        Thread t = new Thread(() -> {
            try {
                adapter.sendRemainingInitialPackets(sink::send);
            } catch (Throwable e) {
                McLog.error("发送初始包失败", e);
            } finally {
                coordinator.endWorldRebuild();
            }
        }, "mc-relay-init");
        t.setDaemon(true);
        t.start();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
