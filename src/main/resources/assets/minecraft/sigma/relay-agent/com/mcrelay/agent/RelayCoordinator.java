package com.mcrelay.agent;

import com.mcrelay.agent.util.ChatHelper;
import com.mcrelay.agent.util.McLog;
import com.mcrelay.agent.proxy.McFraming;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;

/**
 * 中继协调器：维护 B 的连接状态，提供两个方向的字节转发。
 *
 *  - sendToClient(buf):  A 收到的服务器包(S→A) 复制一份发给 B
 *  - sendToServer(buf):  B 发来的客户端包(B→P) 注入到 A 的出站，发往真实服务器
 *
 * A→S 的抑制由 RelayHandler.OutboundDrop 负责（B 在线时丢弃 A 的 Packet）。
 */
public class RelayCoordinator {

    /** B 的 Netty 通道（代理服务端侧），B 未连入时为 null。 */
    private volatile Channel clientChannel;

    /** A 的出站注入点（位于 PacketEncoder 之后），用于把 B 的字节塞进 A→S 的链路。 */
    private volatile ChannelHandlerContext injectCtx;

    /** A 的 Netty 通道（真实服务器连接）。 */
    private volatile Channel serverChannel;

    /** B→S 转发连续失败计数器，用于检测 stale injectCtx。 */
    private volatile int bToSFailCount = 0;

    /** 回调：当服务器通道变化时重新安装 handler。 */
    public interface ChannelReinstallCallback {
        void onChannelChanged(Channel newChannel);
    }

    private volatile ChannelReinstallCallback reinstallCallback;

    public void setReinstallCallback(ChannelReinstallCallback cb) { this.reinstallCallback = cb; }

    public void setServerChannel(Channel ch) {
        this.serverChannel = ch;
        this.bToSFailCount = 0; // 重置失败计数
    }

    public void setInjectCtx(ChannelHandlerContext ctx) { this.injectCtx = ctx; }

    public synchronized void onClientConnected(Channel bChannel) {
        this.clientChannel = bChannel;
        McLog.info("B 已连入，开始抑制 A 的发包，移交操控权给 B");
        ChatHelper.send("§b[MC-Relay] §a§lB 已连入! A 冻结观战，B 获得操控权");
    }

    public synchronized void onClientDisconnected() {
        this.clientChannel = null;
        endWorldRebuild();
        McLog.info("B 已断开，恢复 A 的操控权");
        ChatHelper.send("§b[MC-Relay] §eB 已断开，A 恢复操控权");
    }

    /**
     * 世界重建期间把服务器的实时包排队，重建完再按序补发。
     *
     * 重建要 2 秒多，期间入站线程还在往 B 写实时包，两条流会交织：
     * B 收到的是"JoinGame、实时包、区块、实时包、区块…"的混合流，
     * 区块被夹在错误的上下文里解析，结果就是区块不渲染。
     * 实测丢一颗末影珍珠会好 —— 那只是服务器重发了周边区块，
     * 那时重建已结束、流不再交织，掩盖了真正的竞态。
     */
    private volatile boolean rebuilding;
    private final java.util.List<ByteBuf> pending = new java.util.ArrayList<>();

    /** 排队上限：约 2 秒的服务器流量。超了说明重建卡死，丢弃并放行，别把内存吃光。 */
    private static final int PENDING_MAX = 4096;

    public synchronized void beginWorldRebuild() {
        rebuilding = true;
        McLog.info("世界重建开始，暂存服务器实时包");
    }

    public synchronized void endWorldRebuild() {
        if (!rebuilding) return;
        rebuilding = false;
        flushPending("世界重建结束");
    }

    /**
     * 把排队的实时包按序补发给 B。
     *
     * 两个地方会排队：世界重建期间、B 还没进 PLAY 期间。两者都可能同时成立，
     * 所以补发的前提是"两个条件都已解除" —— 只要还有一个成立就继续攒着，
     * 否则会在 B 仍处于 configuration 阶段时把 PLAY 字节灌给它。
     *
     * 调用点只有 endWorldRebuild()：它跑在重建线程末尾，那时 phase 已经是 PLAY，
     * 两个条件同时解除，正好把 configuration 期间和重建期间攒的包一起放出去。
     */
    private synchronized void flushPending(String reason) {
        if (rebuilding) return;
        PlayGate g = playGate;
        if (g != null && !g.clientInPlay()) return;

        if (pending.isEmpty()) {
            McLog.info(reason + "，期间无实时包需要补发");
            return;
        }
        Channel b = clientChannel;
        int n = pending.size();
        for (ByteBuf buf : pending) {
            if (b != null && b.isActive()) {
                b.writeAndFlush(buf);
            } else {
                buf.release();
            }
        }
        pending.clear();
        McLog.info(reason + "，补发暂存的 " + n + " 个实时包");
    }

    /**
     * 服务器把 A 拉回 configuration 阶段（切子服/换维度）时，把 B 一起带过去。
     *
     * B 此刻在 PLAY 阶段，而 A 后续收到的是 configuration 格式的字节，
     * 直接转发 B 解析不了、超时，再连锁把 A 一起拖掉线。
     *
     * 早先的做法是断开 B，但那样每次切子服都得手动重连。正确做法是让 B 走
     * 同一套握手：StartConfiguration → B 确认 → 重发注册表 → FinishConfiguration
     * → 重建世界。全程连接不断，和 1.20.1 的体验一致。
     *
     * 转发在此期间由 session.inPlay() 挡住（见 sendToClient），
     * 等 B 重新进 play 再恢复。
     */
    public void beginClientReconfigure() {
        ReconfigureHook h = reconfigureHook;
        if (h == null) {
            McLog.warn("切子服：没有注册重配置回调，只能断开 B");
            disconnectClientForReconfigure();
            return;
        }
        // 切子服意味着旧世界作废：排队里攒的是**上一个子服**的 PLAY 字节，
        // 补发过去只会让 B 收到已经失效的区块/实体。直接丢掉，
        // 让 B 重走 configuration 后从新子服的 A 内存里重建。
        synchronized (this) {
            rebuilding = false;
            if (!pending.isEmpty()) {
                McLog.info("切子服：丢弃排队中的 " + pending.size() + " 个旧子服实时包");
                for (ByteBuf b0 : pending) b0.release();
                pending.clear();
            }
        }
        try {
            h.onServerReconfigure();
        } catch (Throwable t) {
            McLog.error("切子服：把 B 带回配置阶段失败，改为断开", t);
            disconnectClientForReconfigure();
        }
    }

    /** 由当前会话注册：服务器要求重配置时被调用。 */
    public interface ReconfigureHook { void onServerReconfigure(); }

    private volatile ReconfigureHook reconfigureHook;

    public void setReconfigureHook(ReconfigureHook h) { this.reconfigureHook = h; }

    /** B 是否已在 PLAY 阶段 —— 只有这时才能收 PLAY 字节。 */
    public interface PlayGate { boolean clientInPlay(); }

    private volatile PlayGate playGate;

    public void setPlayGate(PlayGate g) { this.playGate = g; }

    /**
     * 服务器把 A 拉回 configuration 阶段时的兜底：直接断开 B。
     * 只在拿不到会话回调、或重配置流程本身出错时用。
     */
    public void disconnectClientForReconfigure() {
        Channel b;
        synchronized (this) {
            b = clientChannel;
            clientChannel = null;
            endWorldRebuild();
        }
        if (b != null && b.isActive()) {
            b.close();
        }
        ChatHelper.send("§b[MC-Relay] §e检测到切换子服务器，已断开 B（请让 B 重新连接）");
    }

    /**
     * A 是否处于 configuration 阶段（切子服中）。
     *
     * 这期间 S→A 的字节属于 configuration 的 ID 空间，和 B 的 PLAY 完全不同，
     * 一律不能转发 —— 源码里 configuration 的 0x00 是 CUSTOM_PAYLOAD，
     * 而 PLAY 的 0x01 是 ADD_ENTITY，转过去 B 就把频道名当 UUID 读。
     */
    private volatile boolean serverInConfiguration;

    public boolean isServerInConfiguration() { return serverInConfiguration; }

    public void setServerInConfiguration(boolean v) { this.serverInConfiguration = v; }

    public boolean isClientConnected() {
        return clientChannel != null && clientChannel.isActive();
    }

    /** A 侧入站调用：把服务器包字节转发给 B。buf 为 retainedDuplicate，本方法负责释放。 */
    public void sendToClient(ByteBuf buf) {
        Channel b = clientChannel;
        if (b == null || !b.isActive()) {
            buf.release();
            return;
        }
        // B 不在 PLAY 阶段时（登录中、切子服重配置中）不能转发 PLAY 字节 ——
        // 它按 configuration 的 ID 空间解析，必然错乱后断开。这段窗口很短，
        // 排队等它进 play 再补发。
        //
        // 世界重建期间**不排队**，保持实时转发。之前排队是为了绕开
        // "同一区块收到两份、渲染用后到的、碰撞用先到的"，但那个症状的真因是
        // 我发的区块光照掩码传了 null（见 ModernAdapter.chunkPacket）。
        // 光照修好后不需要牺牲实时性。
        PlayGate g = playGate;
        if (g != null && !g.clientInPlay()) {
            synchronized (this) {
                if (pending.size() < PENDING_MAX) {
                    pending.add(buf);
                } else {
                    buf.release();
                }
            }
            return;
        }
        b.writeAndFlush(buf);
    }

    /**
     * B 侧调用：把 B 的客户端包字节注入到 A→S 链路。buf 由调用方传入，本方法负责释放。
     *
     * 注入路径：injectCtx.writeAndFlush → PacketEncoder(放行ByteBuf) → OutboundDrop(放行ByteBuf)
     *          → compressor → prepender → socket
     *
     * OutboundDrop 已修改为：B 在线时丢弃 A 的 Packet 对象，但放行 ByteBuf（B 注入的）。
     */
    public void sendToServer(ByteBuf buf) {
        ChannelHandlerContext ctx = injectCtx;
        if (ctx == null) {
            McLog.warn("转发 B→S 失败: injectCtx 为 null (handler 未安装或 channel 已变更)");
            buf.release();
            return;
        }

        // 检查 injectCtx 对应的 channel 是否仍然 active
        if (!ctx.channel().isActive()) {
            McLog.warn("转发 B→S 失败: injectCtx 的 channel 已关闭 (A 可能重连了)");
            bToSFailCount++;
            buf.release();

            // 连续失败多次，触发重装
            if (bToSFailCount >= 3 && reinstallCallback != null) {
                McLog.info("B→S 连续失败 " + bToSFailCount + " 次，触发 channel 重装检测");
                bToSFailCount = 0;
                try {
                    reinstallCallback.onChannelChanged(null);
                } catch (Throwable t) {
                    McLog.warn("channel 重装回调失败: " + t.getMessage());
                }
            }
            return;
        }

        // 记录包ID用于调试（不消耗 readerIndex）
        int pktId = -1;
        int rIdx = buf.readerIndex();
        try {
            pktId = McFraming.readVarInt(buf);
            buf.readerIndex(rIdx);
        } catch (Throwable ignored) {
            buf.readerIndex(rIdx);
        }

        final int debugPktId = pktId;
        ctx.writeAndFlush(buf).addListener(f -> {
            if (!f.isSuccess()) {
                Throwable cause = f.cause();
                String msg = cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown (cause=null)";
                McLog.warn("转发 B→S 失败 (pktId=0x" + Integer.toHexString(debugPktId) + "): " + msg);
                bToSFailCount++;
            } else {
                bToSFailCount = 0;
            }
        });
    }

    public Channel getServerChannel() { return serverChannel; }
}
