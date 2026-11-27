package com.mcrelay.agent;

import com.mcrelay.agent.adapter.PacketIdResolver;
import com.mcrelay.agent.proxy.McFraming;
import com.mcrelay.agent.util.McLog;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;

import java.util.Map;

/**
 * 字节级中继 Handler，安装到 A 的 Netty 管道。三个 handler：
 *
 * 管道顺序（head/socket → tail/应用层）：
 *   ... → prepender → compress → encoder → DROP → INJECT → 应用层
 *
 * 出站（tail → head，即应用层 → socket）：
 *   A 的 Packet → INJECT(透传) → DROP(丢弃Packet) → encoder → compress → prepender → socket
 *   B 的 ByteBuf → INJECT.writeAndFlush → DROP(放行ByteBuf) → encoder(放行) → compress → socket
 *
 *  1) InboundCopy   —— addBefore(PacketDecoder)
 *     入站 S→A：在包解码前拿到明文未压缩的"单个包载荷 ByteBuf"，复制一份转给 B，
 *     原份继续交给 A 的解码器，使 A 仍正常渲染。
 *
 *  2) OutboundDrop  —— addAfter(PacketEncoder)
 *     出站 A→S：B 在线时丢弃非 ByteBuf（A 的 Packet 对象），放行 ByteBuf（B 注入的）。
 *     位置在 encoder 之后（应用层侧），所以 A 的 Packet 到达时还是 Packet 对象。
 *
 *  3) OutboundInject —— addAfter(OutboundDrop)
 *     出站注入点：coordinator.sendToServer 在此 ctx.writeAndFlush 写入 B 的字节。
 *     位置在 DROP 之后（更靠近应用层），注入的 ByteBuf 往 head 方向走会经过 DROP（放行）。
 */
public final class RelayHandler {

    public static void install(Channel channel, RelayCoordinator coordinator) {
        coordinator.setServerChannel(channel);
        channel.eventLoop().execute(() -> doInstall(channel, coordinator));

        // 注入 ctx 在 handlerAdded 时设置（见 OutboundInject）
    }

    /**
     * 协议阶段切换（PLAY ↔ CONFIGURATION）后重新锚定。
     *
     * Connection.setupInboundProtocol/setupOutboundProtocol 的字节码里有
     * pipeline.remove("decoder") / remove("encoder")，之后再 addBefore/addAfter 重建。
     * 我们的三个 handler 是锚在 decoder/encoder 上的，它们一被移除重建，
     * mcrelay_inbound 就跑到新 decoder 后面，拿到的不再是原始字节 ——
     * 实测表现为切子服时 A 卡在"重新配置中"直到超时，AB 一起掉线。
     *
     * 所以每次切换后必须重新插一遍。B 是否在线都要做：A 单独在线时管道错位，
     * 下次 B 连入同样是坏的。
     */
    private static void doInstall(Channel channel, RelayCoordinator coordinator) {
            ChannelPipeline p = channel.pipeline();
            try {
                // 先移除可能存在的旧 handler（重装场景）
                removeIfExists(p, "mcrelay_inject");
                removeIfExists(p, "mcrelay_drop");
                removeIfExists(p, "mcrelay_inbound");

                String encoder = findHandler(p, "PacketEncoder");
                String decoder = findHandler(p, "PacketDecoder");

                if (encoder == null || decoder == null) {
                    McLog.error("无法定位 PacketEncoder/PacketDecoder，管道内容: " + p.names());
                    return;
                }

                // 管道顺序（head→tail）：... encoder → DROP → INJECT → 应用层
                // 出站（tail→head）：A Packet → INJECT(透传) → DROP(丢弃) → encoder → socket
                //                    B ByteBuf → INJECT.writeAndFlush → DROP(放行) → encoder(放行) → socket
                p.addAfter(encoder, "mcrelay_drop", new OutboundDrop(coordinator));
                p.addAfter("mcrelay_drop", "mcrelay_inject", new OutboundInject(coordinator));
                p.addBefore(decoder, "mcrelay_inbound", new InboundCopy(coordinator));

                installProtocolWatch(p, channel, coordinator);

                McLog.info("管道插入完成: encoder=" + encoder + ", decoder=" + decoder + " channel=" + channel);
                McLog.info("完整管道(head→tail): " + p.names());
            } catch (Throwable t) {
                McLog.error("安装中继 Handler 失败", t);
            }
    }

    /**
     * 盯住 decoder/encoder 的增删，一旦被 MC 重建就重新锚定我们的 handler。
     *
     * 用 ChannelPipeline 自身的 handlerAdded 事件判断：新的 decoder 被加进来时，
     * 说明协议阶段刚切过，此时重排一次即可。延后到下一个 eventLoop tick 执行 ——
     * MC 正在遍历管道的过程中改管道会抛 ConcurrentModification。
     */
    private static void installProtocolWatch(ChannelPipeline p, Channel channel,
                                             RelayCoordinator coordinator) {
        if (p.get("mcrelay_watch") != null) return;
        p.addLast("mcrelay_watch", new ProtocolWatch(channel, coordinator));
    }

    /** 监听协议阶段切换，重新插入被打散的中继 handler。 */
    static final class ProtocolWatch extends ChannelInboundHandlerAdapter {
        private final Channel channel;
        private final RelayCoordinator coord;
        private volatile boolean scheduled;

        ProtocolWatch(Channel ch, RelayCoordinator c) { this.channel = ch; this.coord = c; }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            recheck(ctx);
            ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            recheck(ctx);
            ctx.fireChannelRead(msg);
        }

        /** 我们的 handler 掉了就补回去；没掉则零开销。 */
        private void recheck(ChannelHandlerContext ctx) {
            if (scheduled) return;
            ChannelPipeline p = ctx.pipeline();
            if (p.get("mcrelay_inbound") != null
                && p.get("mcrelay_drop") != null
                && p.get("mcrelay_inject") != null) {
                return;
            }
            scheduled = true;
            ctx.channel().eventLoop().execute(() -> {
                try {
                    McLog.info("检测到协议阶段切换（decoder/encoder 被重建），重新锚定中继 handler");
                    doInstall(channel, coord);
                } finally {
                    scheduled = false;
                }
            });
        }
    }

    /** 移除管道中已存在的 handler（重装时调用）。 */
    private static void removeIfExists(ChannelPipeline p, String name) {
        try {
            if (p.get(name) != null) {
                p.remove(name);
                McLog.info("移除旧 handler: " + name);
            }
        } catch (Throwable ignored) {}
    }

    /** 在管道里查找编码器/解码器 handler 名（兼容 1.8.9–26.2 原版混淆 / Forge / Fabric）。 */
    private static String findHandler(ChannelPipeline p, String simpleName) {
        boolean wantEncoder = simpleName.contains("Encoder");
        String standardKey = wantEncoder ? "encoder" : "decoder";
        if (p.get(standardKey) != null) {
            return standardKey;
        }
        String[] aliases = wantEncoder
            ? new String[]{"PacketEncoder", "MessageSerializer", "NettyPacketEncoder", "class_2545"}
            : new String[]{"PacketDecoder", "MessageDeserializer", "NettyPacketDecoder", "class_2543"};
        for (Map.Entry<String, ChannelHandler> e : p) {
            Class<?> c = e.getValue().getClass();
            while (c != null) {
                String sn = c.getSimpleName();
                for (String alias : aliases) {
                    if (sn.equals(alias)) return e.getKey();
                }
                c = c.getSuperclass();
            }
            String fn = e.getValue().getClass().getName();
            for (String alias : aliases) {
                if (fn.contains(alias)) return e.getKey();
            }
        }
        return null;
    }

    /** 入站复制：把 S→A 的实时包字节复制给 B（不做任何缓存）。 */
    static final class InboundCopy extends ChannelInboundHandlerAdapter {
        private final RelayCoordinator coord;
        private int relayed, skipped;
        private long lastReport = System.currentTimeMillis();

        InboundCopy(RelayCoordinator c) { this.coord = c; }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;

                // 不缓存任何东西：所有初始状态都在 B 连入时从 A 的活内存现场读取。
                // 缓存会带来"A 跑图后 B 拿到旧区块""换维度后 JoinGame 过期"这类问题。

                // 切子服会走 StartConfiguration：服务器把 A 拉回 configuration 阶段。
                // 本 handler 在 decoder 之前，看到的是裸字节、不知道阶段已变。
                //
                // A 一进 configuration，同一个 0x01 的含义就变了 ——
                // 源码里 ConfigurationProtocols 的 CLIENTBOUND 注册顺序是
                // 0x00=CUSTOM_PAYLOAD, 0x01=DISCONNECT, ... 0x06=REGISTRY_DATA，
                // 而 PLAY 的 0x01 是 ADD_ENTITY。继续按 PLAY 转发，B 就会把
                // custom_payload 当 add_entity 解析 —— 实测 ViaVersion 在 UUID
                // 位置读出 6d696e65…6410 即字符串 "minecraft:brand"，
                // 正是 CustomPayload 的频道名。之后包边界全乱，B 被踢。
                //
                // 所以标记 A 已离开 PLAY，立刻停止转发，直到 A 重新进 PLAY。
                if (isStartConfiguration(ctx, buf)) {
                    McLog.warn("检测到服务器发来 StartConfiguration（切子服/换维度），"
                        + "暂停 S→B 转发，并把 B 一起带回 configuration 阶段");
                    coord.setServerInConfiguration(true);
                    if (coord.isClientConnected()) {
                        coord.beginClientReconfigure();
                    }
                    ctx.fireChannelRead(msg);
                    return;
                }

                // A 回到 PLAY 的信号是 JoinGame（login 包）—— 服务器换完子服会重发它。
                if (coord.isServerInConfiguration() && isJoinGame(ctx, buf)) {
                    McLog.info("A 已收到新子服的 JoinGame，恢复 S→B 转发");
                    coord.setServerInConfiguration(false);
                    // JoinGame 本身不转发：B 的世界由 sendWorld 从 A 内存现场重建，
                    // 转发这一份会和重建出来的那份冲突。
                    ctx.fireChannelRead(msg);
                    return;
                }

                // A 不在 PLAY 阶段时，字节的 ID 空间和 B 的 PLAY 不同，转发必然错乱。
                if (coord.isServerInConfiguration()) {
                    skipped++;
                    ctx.fireChannelRead(msg);
                    return;
                }

                // B 在线时复制 S→A 包给 B
                if (coord.isClientConnected()) {
                    ByteBuf copy = buf.duplicate().retain();
                    coord.sendToClient(copy);
                    relayed++;
                } else {
                    skipped++;
                }
                long now = System.currentTimeMillis();
                if (now - lastReport > 5000) {
                    lastReport = now;
                    McLog.info("入站统计: 5 秒内 S→A 收到 " + (relayed + skipped)
                        + " 个包，转发给 B " + relayed + " 个");
                    relayed = 0;
                    skipped = 0;
                }
            }
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * 出站抑制：B 在线时丢弃 A 的 Packet 对象，放行 ByteBuf（B 注入的）。
     *
     * 位置：addAfter(PacketEncoder)，在 encoder 和应用层之间。
     * - A 的出站消息到达时是 Packet 对象（还没被 encoder 编码）→ 丢弃
     * - B 的注入字节从 INJECT 的 ctx.writeAndFlush 往 head 方向流，到达本 handler 时是 ByteBuf → 放行
     *
     * B 断开后 isClientConnected() 返回 false，全部放行，A 恢复操控。
     *
     * 例外：协议层握手包必须放行。切子服时服务器发 StartConfiguration，
     * 客户端必须回 ConfigurationAcknowledged 才能继续；这个回复被丢掉，
     * 服务器就一直等，A 卡在"重新配置中"直到超时，B 跟着一起掉线。
     * 它属于协议状态机，不是"A 的操作"，抑制它没有任何意义。
     */
    @ChannelHandler.Sharable
    static final class OutboundDrop extends ChannelOutboundHandlerAdapter {
        private final RelayCoordinator coord;
        OutboundDrop(RelayCoordinator c) { this.coord = c; }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (!coord.isClientConnected()) {
                // B 不在线，A 正常发包
                ctx.write(msg, promise);
                return;
            }
            // B 在线：只丢 A 的"玩家操作"包，其余一律放行。
            //
            // 判据必须是白名单（只丢 Packet），不能是黑名单 ——
            // 出站消息里除了 Packet 还有管道控制消息：MC 切协议阶段时会把
            // UnconfiguredPipelineHandler$OutboundConfigurationTask（一个 lambda，
            // 不是 Packet）write 下来，由下游执行来重建 encoder/decoder。
            // 我早先把它当"A 的包"丢了，A 的管道就永远切不到 configuration 阶段，
            // 表现为切子服时 A 卡在"重新配置中"直到超时，B 跟着掉线。
            if (!isPlayerPacket(msg) || isProtocolHandshake(msg) || isCustomPayload(msg)) {
                ctx.write(msg, promise);
            } else {
                logDropped(msg);
                ReferenceCountUtil.release(msg);
                promise.trySuccess();
            }
        }
    }

    /** 是不是 MC 的 Packet 对象（只有它才代表"A 的操作"，才有资格被抑制）。 */
    private static boolean isPlayerPacket(Object msg) {
        if (msg == null || msg instanceof ByteBuf) return false;
        if (!packetClassResolved) {
            packetClass = com.mcrelay.agent.util.Reflect.tryCls("net.minecraft.network.protocol.Packet");
            if (packetClass == null) {
                packetClass = com.mcrelay.agent.util.Reflect.tryCls("net.minecraft.network.Packet");
            }
            if (packetClass == null) {
                packetClass = com.mcrelay.agent.util.Reflect.tryCls("net.minecraft.class_2596");
            }
            packetClassResolved = true;
            McLog.info("OutboundDrop: Packet 接口 = "
                + (packetClass == null ? "按运行时协议表反查混淆 Packet" : packetClass.getName()));
        }
        if (packetClass != null) {
            return packetClass.isInstance(msg);
        }
        // 原版完全混淆环境：若该类登记在 PLAY/SERVERBOUND 协议表中，则它一定是出站玩家 Packet（同时缓存其接口）
        if (PacketIdResolver.serverboundPlayIdOf(msg.getClass()) >= 0) {
            Class<?>[] ifaces = msg.getClass().getInterfaces();
            if (ifaces.length == 1) {
                packetClass = ifaces[0];
            }
            return true;
        }
        return false;
    }

    private static volatile Class<?> packetClass;
    private static volatile boolean packetClassResolved;

    private static final java.util.Set<String> DROP_REPORTED =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void logDropped(Object msg) {
        String n = msg.getClass().getName();
        if (DROP_REPORTED.add(n)) {
            McLog.info("OutboundDrop: 丢弃 A 的出站包类型 " + n);
        }
    }

    private static boolean isStartConfiguration(ChannelHandlerContext ctx, ByteBuf buf) {
        int id = startConfigId();
        if (id < 0 || !buf.isReadable()) return false;
        int mark = buf.readerIndex();
        try {
            return McFraming.readVarInt(buf) == id;
        } catch (Throwable ignored) {
            return false;
        } finally {
            buf.readerIndex(mark);
        }
    }

    private static int startConfigCache = Integer.MIN_VALUE;

    private static int startConfigId() {
        if (startConfigCache != Integer.MIN_VALUE) return startConfigCache;
        int id = PacketIdResolver.resolve(
            "net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket");
        startConfigCache = id;
        McLog.info("StartConfiguration 包 ID = " + (id < 0 ? "不存在(旧版本)" : "0x" + Integer.toHexString(id)));
        return id;
    }

    /** JoinGame（ClientboundLoginPacket）—— A 重新进入 PLAY 阶段的标志。 */
    private static boolean isJoinGame(ChannelHandlerContext ctx, ByteBuf buf) {
        int id = joinGameId();
        if (id < 0 || !buf.isReadable()) return false;
        int mark = buf.readerIndex();
        try {
            return McFraming.readVarInt(buf) == id;
        } catch (Throwable ignored) {
            return false;
        } finally {
            buf.readerIndex(mark);
        }
    }

    private static int joinGameCache = Integer.MIN_VALUE;

    private static int joinGameId() {
        if (joinGameCache != Integer.MIN_VALUE) return joinGameCache;
        joinGameCache = PacketIdResolver.resolve(
            "net.minecraft.network.protocol.game.ClientboundLoginPacket");
        return joinGameCache;
    }

    private static boolean isProtocolHandshake(Object msg) {
        if (msg == null) return false;
        String cls = msg.getClass().getName();
        return cls.contains(".protocol.configuration.")
            || cls.contains(".protocol.login.")
            || cls.contains(".network.login.")
            || cls.endsWith("ServerboundConfigurationAcknowledgedPacket")
            || cls.endsWith("ServerboundFinishConfigurationPacket")
            || cls.endsWith("ServerboundSelectKnownPacks")
            || cls.endsWith("ServerboundLoginAcknowledgedPacket")
            || cls.endsWith(".class_8734") // Fabric ConfigurationAcknowledged
            || cls.endsWith(".class_8733") // Fabric FinishConfiguration
            || cls.endsWith(".class_9250") // Fabric SelectKnownPacks
            || cls.endsWith(".class_8592"); // Fabric LoginAcknowledged
    }

    /**
     * 放行宿主 A 自己的身份鉴权/AuthCl 心跳/模组通道/聊天密钥状态包（即使在原版混淆或 Fabric Intermediary 下也能精确识别）。
     */
    private static boolean isCustomPayload(Object msg) {
        if (msg == null) return false;
        String cls = msg.getClass().getName();
        if (cls.endsWith("C17PacketCustomPayload")
            || cls.endsWith("CPacketCustomPayload")
            || cls.endsWith("C19PacketResourcePackStatus")
            || cls.endsWith("CPacketResourcePackStatus")
            || cls.endsWith("ServerboundCustomPayloadPacket")
            || cls.endsWith("ServerboundResourcePackPacket")
            || cls.endsWith("ServerboundChatSessionUpdatePacket")
            || cls.endsWith("ServerboundChatAckPacket")
            || cls.endsWith(".class_2817") // Fabric ServerboundCustomPayloadPacket
            || cls.endsWith(".class_2856") // Fabric ServerboundResourcePackPacket
            || cls.endsWith(".class_7861") // Fabric ServerboundChatSessionUpdatePacket
            || cls.endsWith(".class_7640")) { // Fabric ServerboundChatAckPacket
            return true;
        }

        // 原版 ProGuard 混淆或自定义 AuthCl 客户端：直接从 PLAY 协议表反查该类的真实协议 ID
        int sbId = PacketIdResolver.serverboundPlayIdOf(msg.getClass());
        if (sbId >= 0) {
            int hostProto = detectHostProtocolCached();
            if (hostProto > 0) {
                if (sbId == PacketIdResolver.customPayloadIdByProtocol(hostProto)
                    || sbId == PacketIdResolver.chatSessionUpdateIdByProtocol(hostProto)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int cachedHostProtocol = Integer.MIN_VALUE;

    private static int detectHostProtocolCached() {
        if (cachedHostProtocol != Integer.MIN_VALUE) return cachedHostProtocol;
        try {
            cachedHostProtocol = VersionDetect.detect().protocol;
        } catch (Throwable ignored) {
            cachedHostProtocol = -1;
        }
        return cachedHostProtocol;
    }

    /** 出站注入点：透传；coordinator.sendToServer 在此写入 B 的字节。 */
    @ChannelHandler.Sharable
    static final class OutboundInject extends ChannelOutboundHandlerAdapter {
        private final RelayCoordinator coord;
        OutboundInject(RelayCoordinator c) { this.coord = c; }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            // 由协调器持有该 ctx，供 B→S 注入
            coord.setInjectCtx(ctx);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ctx.write(msg, promise);
        }
    }

    private RelayHandler() {}
}
