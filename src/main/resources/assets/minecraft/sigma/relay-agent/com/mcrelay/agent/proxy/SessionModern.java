package com.mcrelay.agent.proxy;

import com.mcrelay.agent.RelayCoordinator;
import com.mcrelay.agent.adapter.RelayAdapter;
import com.mcrelay.agent.util.McLog;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

/**
 * 1.20.2+（协议 764 及以上，含 1.21.8 / 772）的登录流程。
 *
 * 和 1.20.1 的结构性差异：login 和 play 之间多了 **configuration 阶段**。
 *
 *   LoginStart
 *   → SetCompression, LoginSuccess
 *   → B 回 LoginAcknowledged            ← 1.20.1 没有这一步
 *   ── configuration 阶段 ──
 *   → RegistryData（注册表移到这里发，不再随 JoinGame）
 *   → FinishConfiguration
 *   → B 回 FinishConfigurationAck
 *   ── play 阶段 ──
 *   → JoinGame（不含注册表）→ 世界数据
 *
 * 包 ID 全部从 A 的 MC 自己的协议表读（PacketIdResolver），不硬编码 ——
 * 1.20.2 之后 ID 变动频繁，硬编码必错。
 */
public class SessionModern implements LoginSession {

    private enum Phase { LOGIN, CONFIGURATION, PLAY }

    private final ChannelHandlerContext ctx;
    private final RelayCoordinator coordinator;
    private final RelayAdapter adapter;
    private final McCompress.Compressor compressor;
    private final McCompress.Decompressor decompressor;
    private final int compressionThreshold;
    private final PacketSink sink;
    private final int clientProtocol;

    private Phase phase = Phase.LOGIN;

    // C→S 包 ID。这几个在 764+ 一直很稳定，且 A 的协议表里查不到 B 侧的
    // login/configuration 阶段 ID（那两个阶段的表和 play 不同），所以按协议常量走。
    private static final int C2S_LOGIN_ACKNOWLEDGED = 0x03;
    private static final int C2S_CONFIG_CLIENT_INFO = 0x00;
    private static final int C2S_CONFIG_COOKIE_RESP = 0x01;
    private static final int C2S_CONFIG_PLUGIN_MSG  = 0x02;
    private static final int C2S_CONFIG_FINISH_ACK  = 0x03;
    private static final int C2S_CONFIG_KEEPALIVE   = 0x04;
    private static final int C2S_CONFIG_PONG        = 0x05;
    private static final int C2S_CONFIG_KNOWN_PACKS = 0x07;

    // S→C configuration 阶段包 ID
    private static final int S2C_CONFIG_FINISH      = 0x03;
    private static final int S2C_CONFIG_KNOWN_PACKS = 0x0E;

    // S→C PLAY 阶段的 StartConfiguration。B 侧的 PLAY ID 空间和 A 的一致
    // （同协议号），所以直接用 A 的协议表查，不硬编码。
    private static int s2cStartConfiguration() {
        int id = com.mcrelay.agent.adapter.PacketIdResolver.resolve(
            "net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket");
        return id;
    }

    public SessionModern(ChannelHandlerContext ctx, RelayCoordinator coordinator, RelayAdapter adapter,
                         McCompress.Compressor compressor, McCompress.Decompressor decompressor,
                         int compressionThreshold, PacketSink sink, int clientProtocol) {
        this.ctx = ctx;
        this.coordinator = coordinator;
        this.adapter = adapter;
        this.compressor = compressor;
        this.decompressor = decompressor;
        this.compressionThreshold = compressionThreshold;
        this.sink = sink;
        this.clientProtocol = clientProtocol;
        // 切子服时由 InboundCopy 触发；同时让协调器知道 B 何时才能收 PLAY 字节。
        coordinator.setReconfigureHook(this::beginReconfigure);
        coordinator.setPlayGate(this::inPlay);
    }

    @Override
    public boolean inPlay() { return phase == Phase.PLAY; }

    @Override
    public void handle(int id, ByteBuf buf) throws Exception {
        switch (phase) {
            case LOGIN:
                if (id == PacketBuilder.C2S_LOGIN_START) {
                    McFraming.readVarInt(buf);   // 跳过包 ID
                    onLoginStart(buf);
                } else if (id == C2S_LOGIN_ACKNOWLEDGED) {
                    onLoginAcknowledged();
                } else {
                    McLog.warn("login 阶段忽略 B 的包 0x" + Integer.toHexString(id));
                }
                break;

            case CONFIGURATION:
                onConfigurationPacket(id, buf);
                break;

            case PLAY:
                // 刚给 B 发过 StartConfiguration：它的下一个包就是
                // ConfigurationAcknowledged，收到就切阶段。
                //
                // 不能按 ID 认这个包：B 侧的 ID 未必等于 A 侧的。实测 B 是 1.16.4 +
                // ViaVersion，翻译后发来的是 0xF，而 A 的协议表里
                // SERVERBOUND_CONFIGURATION_ACKNOWLEDGED 是 0xE。按 ID 比就永远不匹配，
                // 于是被"configuration 阶段忽略 B 的包 0xf"吃掉，B 干等到超时断开。
                if (awaitingReconfigureAck) {
                    awaitingReconfigureAck = false;
                    McLog.info("B 已确认 StartConfiguration（包 0x" + Integer.toHexString(id)
                        + "），进入 configuration 阶段重发注册表");
                    phase = Phase.CONFIGURATION;
                    reconfiguring = true;
                    onLoginAcknowledged();   // 重走 KnownPacks → 注册表 → Finish
                    break;
                }
                // 整段转发，含包 ID —— buf 的 readerIndex 仍在包首。
                // OutboundDrop 已丢掉 A 的出站 Packet，服务器的 KeepAlive、传送确认
                // 全靠 B 回应；拦哪一类，哪一类就没人应答。
                coordinator.sendToServer(buf.duplicate().retain());
                break;
        }
    }

    /**
     * 切子服：把 B 也带回 configuration 阶段，重走一遍注册表同步。
     *
     * 服务器给 A 发 StartConfiguration 时，A 会回 ConfigurationAcknowledged 然后
     * 重新接收新子服的注册表。B 必须做同样的事，否则它还停在 PLAY 阶段，
     * 后续 configuration 格式的字节它解析不了。
     *
     * 之前的做法是直接断开 B，但那样每次切子服都要手动重连。
     * 正确做法是把同一套握手转发给 B：StartConfiguration → 等它 Acknowledge →
     * 重发注册表 → FinishConfiguration → 重建世界。全程 B 的连接不断。
     */
    public void beginReconfigure() {
        if (phase != Phase.PLAY) return;
        int id = s2cStartConfiguration();
        if (id < 0) {
            McLog.warn("切子服：查不到 StartConfiguration 的包 ID，无法把 B 带回配置阶段");
            return;
        }
        awaitingReconfigureAck = true;
        reconfiguring = true;
        McLog.info("切子服：把 B 也拉回 configuration 阶段（连接保持）");
        ByteBuf b = io.netty.buffer.Unpooled.buffer();
        McFraming.writeVarInt(b, id);
        sink.send(b);
    }

    /** 是否正处在"切子服重配置"流程里（决定 FinishConfiguration 后要不要重建世界）。 */
    private boolean reconfiguring;

    /** 已给 B 发 StartConfiguration，等它回确认（B 侧 ID 未知，靠这个标志认）。 */
    private boolean awaitingReconfigureAck;

    private void onLoginStart(ByteBuf buf) {
        String name = McFraming.readString(buf, 16);
        java.util.UUID bUuid = null;
        if (buf.readableBytes() >= 16) {
            try {
                bUuid = McFraming.readUuid(buf);
            } catch (Throwable t) {
                McLog.warn("读取 B 的 UUID 失败，忽略: " + t.getMessage());
            }
        }
        McLog.info("B Login Start: name=" + name + " uuid=" + bUuid + " protocol=" + clientProtocol);

        // 适配器要按 B 的协议决定 JoinGame 的字段结构
        adapter.setClientProtocol(clientProtocol);

        RelayAdapter.PlayerProfile profile = adapter.readProfile();
        String loginName = profile.name != null ? profile.name : name;
        java.util.UUID loginUuid = profile.uuid != null ? profile.uuid : PacketBuilder.offlineUuid(loginName);

        // SetCompression 必须在压缩启用前以未压缩格式发出
        ByteBuf setCompressBuf = PacketBuilder.setCompression(compressionThreshold);
        ctx.writeAndFlush(setCompressBuf).addListener(f -> {
            if (!f.isSuccess()) McLog.error("SetCompression 发送失败", f.cause());
        });
        compressor.setThreshold(compressionThreshold);
        decompressor.setThreshold(compressionThreshold);
        McLog.info("压缩已启用 threshold=" + compressionThreshold);

        sink.send(PacketBuilder.loginSuccess(loginUuid, loginName, profile.properties, clientProtocol));
        McLog.info("→B LoginSuccess(继承宿主 uuid=" + loginUuid + ", name=" + loginName + ")，等待 B 的 LoginAcknowledged");
        // 不切阶段 —— 要等 B 明确回 LoginAcknowledged 才能进 configuration
    }

    /**
     * B 确认登录，进入 configuration 阶段。
     *
     * 第一步必须是 Known Packs 握手：服务端发自己认识的数据包列表，等 B 回一份它也
     * 认识的子集，之后才能发注册表。原版客户端在收到 Known Packs 回应前不会继续，
     * 顺序反了会直接卡住。
     */
    private void onLoginAcknowledged() throws Exception {
        phase = Phase.CONFIGURATION;
        McLog.info("B 已 LoginAcknowledged，进入 configuration 阶段 (protocol=" + clientProtocol + ")");

        // 1.20.2 - 1.20.4 (764 - 765) 尚无 SelectKnownPacks 握手，直接下发注册表与 FinishConfiguration
        if (clientProtocol < 766) {
            onKnownPacksResponse();
            return;
        }

        // 1.20.5+ (766+) Known Packs: 只声明 minecraft:core —— 原版客户端要求有它才能正常登录。
        // 声明得越少越安全：凡是没在这里"双方都认识"的数据包，注册表里就必须带完整
        // NBT，而我们本来就是全量发的。
        int knownPacksId = com.mcrelay.agent.adapter.PacketIdResolver.resolveConfiguration(
            "net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks");
        if (knownPacksId < 0) knownPacksId = S2C_CONFIG_KNOWN_PACKS;
        ByteBuf known = io.netty.buffer.Unpooled.buffer();
        McFraming.writeVarInt(known, knownPacksId);
        McFraming.writeVarInt(known, 1);              // 1 个 pack
        McFraming.writeString(known, "minecraft");    // namespace
        McFraming.writeString(known, "core");         // id
        McFraming.writeString(known, mcVersion());    // version
        sink.send(known);
        McLog.info("→B KnownPacks(minecraft:core)，等待 B 回应后再发注册表");
    }

    /** B 回了 Known Packs（或 1.20.2–1.20.4 直接进入），现在可以发注册表了。 */
    private void onKnownPacksResponse() throws Exception {
        // 切子服时 A 自己也在重连，ClientPacketListener 会短暂为 null，
        // 注册表读不出来。这不是故障，等一下就好 —— 早先没等，
        // 直接走下面"发不出注册表就断开"的分支把 B 踢了。
        int sent = adapter.sendConfigurationPackets(sink::send);
        if (sent == 0 && reconfiguring) {
            sent = retryConfigurationUntilReady();
        }
        McLog.info("configuration 阶段已发送 " + sent + " 个包（含注册表）");

        // 一个注册表都没发出去的话，B 会因为查不到维度/生物群系定义，
        // 在解析 JoinGame 时直接崩（表现为 "packet was larger than I expected"）。
        // 与其让它连上再崩、看不懂原因，不如在这里明确告知。
        if (sent == 0) {
            McLog.error("configuration: 注册表一个都没发出去，主动断开 B 并说明原因");
            sink.send(PacketBuilder.loginDisconnect(
                "{\"text\":\"MC-Relay: 无法从 A 读取注册表（协议 " + clientProtocol
                + "）。\\n包 ID 表未能解析，请把 mc-relay-agent.log 里 PacketIdResolver 的\\n"
                + "警告发给开发者。\",\"color\":\"red\"}"));
            ctx.close();
            return;
        }

        int finishId = com.mcrelay.agent.adapter.PacketIdResolver.resolveConfiguration(
            "net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket");
        if (finishId < 0) {
            finishId = clientProtocol >= 766 ? S2C_CONFIG_FINISH : 0x02;
        }
        ByteBuf finish = io.netty.buffer.Unpooled.buffer();
        McFraming.writeVarInt(finish, finishId);
        sink.send(finish);
        McLog.info("→B FinishConfiguration(0x" + Integer.toHexString(finishId) + ")，等待 B 确认");
    }

    /**
     * 切子服后等 A 重新连上，再读注册表。
     *
     * A 切子服的瞬间 ClientPacketListener 是 null，此时读不到任何注册表。
     * 服务器换子服通常 1~3 秒完成，这里最多等 15 秒，每 250ms 试一次。
     * 等不到就返回 0，由调用方按原逻辑断开 B 并说明原因。
     */
    private int retryConfigurationUntilReady() {
        McLog.info("切子服：A 尚未就绪（读不到注册表），等待 A 重连后再发…");
        long deadline = System.currentTimeMillis() + 15000;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
            if (!ctx.channel().isActive()) {
                McLog.warn("切子服：等待期间 B 已断开，放弃");
                return 0;
            }
            attempt++;
            int sent;
            try {
                sent = adapter.sendConfigurationPackets(sink::send);
            } catch (Throwable t) {
                continue;   // A 还在重连，继续等
            }
            if (sent > 0) {
                McLog.info("切子服：A 已就绪（第 " + attempt + " 次重试），注册表可以发了");
                return sent;
            }
        }
        McLog.warn("切子服：等了 15 秒 A 仍读不到注册表");
        return 0;
    }

    /** A 所在客户端的 MC 版本号，用于 Known Packs 的 version 字段。 */
    private String mcVersion() {
        try {
            return com.mcrelay.agent.VersionDetect.detect().version;
        } catch (Throwable t) {
            return "1.21.8";
        }
    }

    private void onConfigurationPacket(int id, ByteBuf buf) throws Exception {
        if (clientProtocol < 766) {
            // 1.20.2 - 1.20.4 (764 - 765): 0=ClientInfo, 1=CustomPayload, 2=FinishConfiguration, 3=KeepAlive, 4=Pong, 5=ResourcePack
            if (id == 0x02) {
                enterPlay();
                return;
            }
            if (id == 0x00 || id == 0x01 || id == 0x03 || id == 0x04 || id == 0x05) {
                return;
            }
            McLog.warn("configuration(764-765) 阶段忽略 B 的包 0x" + Integer.toHexString(id));
            return;
        }
        if (id == C2S_CONFIG_KNOWN_PACKS) {
            McLog.info("B 已回应 KnownPacks");
            onKnownPacksResponse();
            return;
        }
        if (id == C2S_CONFIG_FINISH_ACK) {
            enterPlay();
            return;
        }
        // 这些是 B 对 configuration 阶段的应答，代理自己吃掉 ——
        // 转给真服务器会打乱 A 的会话（真服务器和 A 早就过了这个阶段）。
        if (id == C2S_CONFIG_CLIENT_INFO || id == C2S_CONFIG_PLUGIN_MSG
            || id == C2S_CONFIG_KEEPALIVE || id == C2S_CONFIG_PONG
            || id == C2S_CONFIG_COOKIE_RESP || id == 0x06 || id == 0x08 || id == 0x09) {
            return;
        }
        McLog.warn("configuration 阶段忽略 B 的包 0x" + Integer.toHexString(id));
    }

    /** 进 play 态：发 JoinGame + 世界数据。切子服重配置后也走这里，重发一遍即可。 */
    private void enterPlay() {
        boolean wasReconfigure = reconfiguring;
        phase = Phase.PLAY;
        reconfiguring = false;
        McLog.info(wasReconfigure ? "B 已确认重配置完成，重新进入 play 阶段"
                                  : "B 已确认 configuration 完成，进入 play 阶段");

        ByteBuf joinGame;
        try {
            joinGame = adapter.buildJoinGame();
            McLog.info("JoinGame 现场构造成功: " + joinGame.readableBytes() + " bytes");
        } catch (Throwable t) {
            McLog.error("构造 JoinGame 失败", t);
            ctx.close();
            return;
        }
        McLog.info("JoinGame hex: " + ClientHandler.hexDump(joinGame, 32));
        sink.send(joinGame);

        coordinator.onClientConnected(ctx.channel());

        // 重建在独立线程跑（2 秒以上），期间入站线程还在往同一个 channel 写实时包。
        // 不隔开的话 B 收到的是"区块、实时包、区块…"的交织流，区块解析不出来。
        // 必须在起线程之前就开始暂存 —— 线程调度慢一拍就漏掉开头几十个包。
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
}
