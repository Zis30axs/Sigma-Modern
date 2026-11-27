package com.mcrelay.agent.proxy;

import com.mcrelay.agent.RelayCoordinator;
import com.mcrelay.agent.adapter.RelayAdapter;
import com.mcrelay.agent.util.McLog;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

/**
 * B 侧应用处理器：只做收发和状态派发，登录/进游戏的具体流程交给 LoginSession。
 *
 * 入站消息是"去掉外层长度、解压后"的载荷 ByteBuf = [包ID VarInt][字段...]，
 * 与 A 侧出站注入点格式一致，因此 play 态可整段直接转发。
 *
 * 流程按协议世代分派（见 SessionFactory）：各世代的握手序列差别是结构性的，
 * 硬塞进一份代码会变成满是版本判断的意大利面，改一处坏一片。
 */
public class ClientHandler extends ChannelInboundHandlerAdapter {

    private enum State { HANDSHAKE, STATUS, LOGIN }

    private final RelayCoordinator coordinator;
    private final RelayAdapter adapter;
    private final McCompress.Compressor compressor;
    private final McCompress.Decompressor decompressor;
    private final int compressionThreshold = 256;

    private State state = State.HANDSHAKE;
    private ChannelHandlerContext ctx;

    /** B 声明的协议号（Handshake 里带的），决定用哪套流程。 */
    private int clientProtocol = -1;
    private LoginSession session;

    public ClientHandler(RelayCoordinator coordinator, RelayAdapter adapter,
                         McCompress.Compressor compressor, McCompress.Decompressor decompressor) {
        this.coordinator = coordinator;
        this.adapter = adapter;
        this.compressor = compressor;
        this.decompressor = decompressor;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        McLog.info("B 已连接到代理服务器 P (remote=" + ctx.channel().remoteAddress() + ")");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        ByteBuf buf = (ByteBuf) msg;
        try {
            switch (state) {
                case HANDSHAKE: onHandshake(buf); break;
                case STATUS:    onStatus(buf); break;
                case LOGIN:     onSessionPacket(buf); break;
            }
        } catch (Throwable t) {
            McLog.error("处理 B 的包失败 (state=" + state + ")", t);
            ctx.close();
        } finally {
            ReferenceCountUtil.release(buf);
        }
    }

    private void onHandshake(ByteBuf buf) {
        int id = McFraming.readVarInt(buf);
        if (id != PacketBuilder.C2S_HANDSHAKE) {
            throw new IllegalStateException("期望 Handshake，得到 0x" + Integer.toHexString(id));
        }
        int protocol = McFraming.readVarInt(buf);
        String addr = McFraming.readString(buf, 255);
        int port = buf.readUnsignedShort();
        int next = McFraming.readVarInt(buf);
        McLog.info("B Handshake: protocol=" + protocol + " addr=" + addr + ":" + port + " nextState=" + next);
        clientProtocol = protocol;
        state = (next == 1) ? State.STATUS : State.LOGIN;
    }

    private void onStatus(ByteBuf buf) {
        int id = McFraming.readVarInt(buf);
        McLog.info("B Status Request: id=0x" + Integer.toHexString(id));
        if (id == PacketBuilder.C2S_STATUS_REQUEST) {
            ByteBuf resp = PacketBuilder.statusResponse();
            McLog.info("→B Status Response: " + resp.readableBytes() + " bytes (threshold=" + compressor.getThreshold() + ")");
            send(resp);
        } else if (id == PacketBuilder.C2S_STATUS_PING) {
            long payload = buf.readLong();
            McLog.info("B Status Ping: payload=" + payload);
            send(PacketBuilder.statusPong(payload));
            ctx.close();
        }
    }

    /** 登录之后的所有包都交给 session；它自己知道当前处在哪一步。 */
    private void onSessionPacket(ByteBuf buf) throws Exception {
        if (session == null) {
            session = SessionFactory.create(clientProtocol, ctx, coordinator, adapter,
                compressor, decompressor, compressionThreshold, this::send);
        }
        // 只“看”一眼包 ID，不消耗 readerIndex ——
        // play 态要把**完整**的 buf（含包 ID）整段转发给真服务器，
        // 提前读掉 ID 会让服务器收到一堆缺首字节的畸形包，直接把 A 踢下线。
        int idx = buf.readerIndex();
        int id;
        try {
            id = McFraming.readVarInt(buf);
        } finally {
            buf.readerIndex(idx);
        }
        // B 的 CustomPayload 不转发：客户端身份/频道订阅归 A 所有（A 的那一份已在
        // OutboundDrop 放行）。B 中途接管，它的 MC|Brand 和频道注册和服务器侧
        // 记录的 A 的状态对不上，转过去会把 A 的频道状态覆盖掉。
        //
        // 只在 PLAY 态挡：login/configuration 阶段同一个 ID 是别的包。
        // CustomPayload 不是应答类包，挡掉不会让服务器干等（KeepAlive、
        // 传送确认那些绝对不能挡 —— A 的已被丢，全靠 B 回）。
        int payloadId = bCustomPayloadId();
        int chatSessionId = com.mcrelay.agent.adapter.PacketIdResolver.chatSessionUpdateIdByProtocol(clientProtocol);
        if (session.inPlay() && ((payloadId >= 0 && id == payloadId) || (chatSessionId >= 0 && id == chatSessionId))) {
            if (payloadDropReported.compareAndSet(false, true)) {
                McLog.info("已拦截 B 的身份/聊天会话包（0x" + Integer.toHexString(id) + "），保留宿主 A 的 AuthCl 身份");
            }
            return;
        }
        session.handle(id, buf);
    }

    /** 只报一次"开始丢弃"，避免刷屏。 */
    private final java.util.concurrent.atomic.AtomicBoolean payloadDropReported =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** -1 表示未解析，Integer.MIN_VALUE 表示还没算过。 */
    private int customPayloadId = Integer.MIN_VALUE;

    private int bCustomPayloadId() {
        if (customPayloadId != Integer.MIN_VALUE) return customPayloadId;
        customPayloadId = resolveCustomPayloadId();
        return customPayloadId;
    }

    private int resolveCustomPayloadId() {
        int aProtocol = -1;
        try {
            aProtocol = com.mcrelay.agent.VersionDetect.detect().protocol;
        } catch (Throwable ignored) {}
        if (aProtocol > 0 && aProtocol == clientProtocol) {
            int id = com.mcrelay.agent.adapter.PacketIdResolver.resolveServerbound(
                "net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket");
            if (id < 0) {
                id = com.mcrelay.agent.adapter.PacketIdResolver.resolveServerbound(
                    "net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket");
            }
            if (id < 0) {
                id = com.mcrelay.agent.adapter.PacketIdResolver.resolveServerbound(
                    "net.minecraft.class_2817");
            }
            if (id >= 0) {
                return id;
            }
        }
        int byTable = com.mcrelay.agent.adapter.PacketIdResolver.customPayloadIdByProtocol(clientProtocol);
        if (byTable >= 0) {
            McLog.info("按协议表锁定 B(" + clientProtocol + ") 的 CustomPayload ID = 0x" + Integer.toHexString(byTable));
            return byTable;
        }
        return -1;
    }

    /**
     * 发送一个 payload（id+字段）给 B，经 Compressor+FrameEncoder 组帧。
     *
     * 必须回到 channel 自己的 eventLoop 再写：世界重建跑在 mc-relay-init 线程，
     * 同时 Netty 线程也在转发服务器实时包。两条线程并发 writeAndFlush 会让
     * 出站 handler（Compressor）的调用交错，压缩流互相污染，B 那边表现为
     * "Packet was larger than I expected"、实体 UUID 位置读出 minecraft:brand。
     * 交给 eventLoop 串行化后，写入顺序即入队顺序，包边界不会再错。
     */
    private void send(ByteBuf payload) {
        if (ctx == null || !ctx.channel().isActive()) {
            payload.release();
            return;
        }
        io.netty.channel.EventLoop loop = ctx.channel().eventLoop();
        if (loop.inEventLoop()) {
            doSend(payload);
        } else {
            loop.execute(() -> doSend(payload));
        }
    }

    private void doSend(ByteBuf payload) {
        if (ctx == null || !ctx.channel().isActive()) {
            payload.release();
            return;
        }
        ctx.writeAndFlush(payload).addListener(f -> {
            if (!f.isSuccess()) McLog.warn("发往 B 失败: " + f.cause().getMessage());
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (session != null && session.inPlay()) {
            coordinator.onClientDisconnected();
            McLog.info("B 连接已断开（PLAY 态），恢复 A 操控权");
        } else {
            McLog.info("B 连接已断开（state=" + state + "，非 PLAY 态，不影响 A）");
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        McLog.error("B 通道异常", cause);
        ctx.close();
    }

    /** 生成 ByteBuf 前 N 字节的 hex dump（不消耗 readerIndex）。 */
    static String hexDump(ByteBuf buf, int maxBytes) {
        int len = Math.min(buf.readableBytes(), maxBytes);
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(len).append("/").append(buf.readableBytes()).append("] ");
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", buf.getByte(buf.readerIndex() + i) & 0xFF));
        }
        if (buf.readableBytes() > maxBytes) sb.append(" ...");
        return sb.toString();
    }
}
