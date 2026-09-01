package net.minecraft.network;

import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;

public interface ProtocolSwapHandler {
    static void handleInboundTerminalPacket(final ChannelHandlerContext ctx, final Packet<?> packet) {
        if (packet.isTerminal()) {
            // VFP 4.6.3 brackets old emulated configuration transitions in the packet listeners instead.
            if (!ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_20_3)) {
                ctx.channel().config().setAutoRead(false);
            }
            ctx.pipeline().addBefore(ctx.name(), "inbound_config", new UnconfiguredPipelineHandler.Inbound());
            ctx.pipeline().remove(ctx.name());
        }
    }

    static void handleOutboundTerminalPacket(final ChannelHandlerContext ctx, final Packet<?> packet) {
        if (packet.isTerminal()) {
            ctx.pipeline().addAfter(ctx.name(), "outbound_config", new UnconfiguredPipelineHandler.Outbound());
            ctx.pipeline().remove(ctx.name());
        }
    }
}