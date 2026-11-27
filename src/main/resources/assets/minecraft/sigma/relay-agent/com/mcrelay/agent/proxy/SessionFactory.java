package com.mcrelay.agent.proxy;

import com.mcrelay.agent.RelayCoordinator;
import com.mcrelay.agent.adapter.RelayAdapter;
import com.mcrelay.agent.util.McLog;
import io.netty.channel.ChannelHandlerContext;

/**
 * 按 B 声明的协议号挑选登录流程。
 *
 * 分界线是「configuration 阶段」——1.20.2（协议 764）起 Mojang 在 login 和 play
 * 之间插了一个新阶段，注册表不再随 JoinGame 发，而是在那个阶段单独发。
 * 这是流程上的结构性差异，所以按它分家：
 *
 *   ≤ 763  (1.13 – 1.20.1)   Session1201 —— 已在 1.20.1 Forge 上调通，不要改
 *   ≥ 764  (1.20.2 – 1.21.x) SessionModern —— 带 configuration 阶段
 */
public final class SessionFactory {

    /** 1.20.2，configuration 阶段的起点。 */
    public static final int PROTOCOL_CONFIGURATION = 764;

    private SessionFactory() {}

    public static LoginSession create(int clientProtocol, ChannelHandlerContext ctx,
                                      RelayCoordinator coordinator, RelayAdapter adapter,
                                      McCompress.Compressor compressor,
                                      McCompress.Decompressor decompressor,
                                      int compressionThreshold, PacketSink sink) {
        if (clientProtocol >= PROTOCOL_CONFIGURATION) {
            McLog.info("SessionFactory: B 协议 " + clientProtocol + " → SessionModern（含 configuration 阶段）");
            return new SessionModern(ctx, coordinator, adapter, compressor, decompressor,
                compressionThreshold, sink, clientProtocol);
        }
        McLog.info("SessionFactory: B 协议 " + clientProtocol + " → Session1201");
        return new Session1201(ctx, coordinator, adapter, compressor, decompressor,
            compressionThreshold, sink, clientProtocol);
    }
}
