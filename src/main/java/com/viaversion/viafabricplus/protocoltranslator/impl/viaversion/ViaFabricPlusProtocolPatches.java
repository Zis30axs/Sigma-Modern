/*
 * This file is part of ViaFabricPlus - https://github.com/ViaVersion/ViaFabricPlus
 * Copyright (C) 2021-2026 the original authors
 *                         - Florian Reuth <git@florianreuth.de>
 *                         - RK_01/RaphiMC
 * Copyright (C) 2023-2026 ViaVersion and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.viaversion.viafabricplus.protocoltranslator.impl.viaversion;

import com.viaversion.viafabricplus.ViaFabricPlusImpl;
import com.viaversion.viafabricplus.settings.impl.DebugSettings;
import com.viaversion.viafabricplus.util.NotificationUtil;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.minecraft.GameMode;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_19_3to1_19_4.packet.ServerboundPackets1_19_4;
import com.viaversion.viaversion.protocols.v1_20_2to1_20_3.packet.ServerboundPackets1_20_3;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.Protocol1_20_3To1_20_5;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.packet.ServerboundPackets1_20_5;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.Protocol1_20To1_20_2;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.packet.ServerboundConfigurationPackets1_20_2;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.packet.ServerboundPackets1_20_2;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.storage.ProtocolStorables1_20_2;
import com.viaversion.viaversion.protocols.v1_21_4to1_21_5.packet.ServerboundPackets1_21_5;
import com.viaversion.viaversion.protocols.v1_21_5to1_21_6.Protocol1_21_5To1_21_6;
import com.viaversion.viaversion.protocols.v1_21_5to1_21_6.packet.ServerboundPackets1_21_6;
import java.util.Locale;
import com.viaversion.viaversion.protocols.v1_19_1to1_19_3.packet.ClientboundPackets1_19_3;
import com.viaversion.viaversion.protocols.v1_19_3to1_19_4.Protocol1_19_3To1_19_4;
import com.viaversion.viaversion.protocols.v1_19_3to1_19_4.packet.ClientboundPackets1_19_4;
import com.viaversion.viaversion.protocols.v1_21to1_21_2.Protocol1_21To1_21_2;
import com.viaversion.viaversion.protocols.v1_21to1_21_2.packet.ServerboundPackets1_21_2;

/**
 * MODIFIED for porting: replaces the ViaFabricPlus mixins whose {@code @Mixin} target is a ViaVersion
 * class instead of a Minecraft class. Sigma-Modern has no Mixin runtime and cannot edit the ViaVersion
 * jar, so the equivalent behaviour is installed through ViaVersion's own public protocol API right after
 * {@code ProtocolManager#registerProtocols} has run (see {@link ViaFabricPlusPlatformLoader#load()}).
 *
 * <p>Only library mixins whose behaviour is reachable through the public API live here. The remaining
 * ones are listed with their exact reason in VFP_AUDIT.md.
 */
public final class ViaFabricPlusProtocolPatches {

    private ViaFabricPlusProtocolPatches() {
    }

    public static void apply() {
        final ProtocolManager protocolManager = Via.getManager().getProtocolManager();

        // was VFP features/movement/packet/MixinEntityPacketRewriter1_21_2#dontCancelIdlePacket
        // (@Redirect no-oping PacketWrapper#cancel in lambda$registerPackets$14, the
        // MOVE_PLAYER_STATUS_ONLY handler). That handler drops the idle movement packet whenever the
        // on-ground state did not change, which starves <= 1.21 servers of the per-tick movement packet
        // they expect. Appending to the same handler chain undoes only that cancel.
        final Protocol1_21To1_21_2 protocol1_21To1_21_2 = protocolManager.getProtocol(Protocol1_21To1_21_2.class);
        if (protocol1_21To1_21_2 != null) {
            protocol1_21To1_21_2.appendServerbound(ServerboundPackets1_21_2.MOVE_PLAYER_STATUS_ONLY, wrapper -> wrapper.setCancelled(false));
        } else {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocol1_21To1_21_2 is not registered, idle movement packets will be dropped for <= 1.21 targets");
        }

        // was VFP features/networking/packet_handling/MixinEntityPacketRewriter1_19_4#fixTeleportBehaviour
        // (@Inject registerPackets RETURN). Replaces the entity rewriter's TELEPORT_ENTITY handler with a
        // plain passthrough so the 1.19.4 teleport is not turned into a relative move.
        final Protocol1_19_3To1_19_4 protocol1_19_3To1_19_4 = protocolManager.getProtocol(Protocol1_19_3To1_19_4.class);
        if (protocol1_19_3To1_19_4 != null) {
            protocol1_19_3To1_19_4.registerClientbound(ClientboundPackets1_19_3.TELEPORT_ENTITY, ClientboundPackets1_19_4.TELEPORT_ENTITY, wrapper -> {
            }, true);
        } else {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocol1_19_3To1_19_4 is not registered, entity teleport behaviour will not be fixed");
        }

        applyRemoveSignedCommands(protocolManager);
        applyConfigStatePacketQueue(protocolManager);
    }

    // was VFP features/networking/remove_signed_commands/MixinProtocol1_20_3To1_20_5#removeCommandHandlers
    // and MixinProtocol1_21_5To1_21_6#cancelInvalidPackets (both @Inject registerPackets RETURN, and both
    // doing nothing but registerServerbound(..., override = true) - which is public API).
    // Signing is implemented client-side in ClientPacketListener#sendCommand / #sendUnattendedCommand, so
    // ViaVersion's own chat-acknowledgement and signature handling has to be taken out of the way.
    private static void applyRemoveSignedCommands(final ProtocolManager protocolManager) {
        final Protocol1_20_3To1_20_5 protocol1_20_3To1_20_5 = protocolManager.getProtocol(Protocol1_20_3To1_20_5.class);
        if (protocol1_20_3To1_20_5 != null) {
            // Remove any special handling for chat acknowledgements
            protocol1_20_3To1_20_5.registerServerbound(ServerboundPackets1_20_5.CHAT, ServerboundPackets1_20_3.CHAT, null, true);
            protocol1_20_3To1_20_5.registerServerbound(ServerboundPackets1_20_5.CHAT_ACK, ServerboundPackets1_20_3.CHAT_ACK, null, true);
            protocol1_20_3To1_20_5
                .registerServerbound(ServerboundPackets1_20_5.CHAT_SESSION_UPDATE, ServerboundPackets1_20_3.CHAT_SESSION_UPDATE, null, true);

            // Map the signed command packet to the normal one, since the client always signs commands now
            protocol1_20_3To1_20_5
                .registerServerbound(ServerboundPackets1_20_5.CHAT_COMMAND_SIGNED, ServerboundPackets1_20_3.CHAT_COMMAND, null, true);

            // Don't allow mods to send the packet directly - ClientPacketListener#sendCommand is the way in
            protocol1_20_3To1_20_5.registerServerbound(ServerboundPackets1_20_5.CHAT_COMMAND, ServerboundPackets1_20_3.CHAT_COMMAND, wrapper -> {
                NotificationUtil.warnIncompatibilityPacket("1.20.5", "CHAT_COMMAND", "ClientPacketListener#sendCommand");
                wrapper.cancel();
            }, true);
        } else {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocol1_20_3To1_20_5 is not registered, command signing will stay ViaVersion-side");
        }

        final Protocol1_21_5To1_21_6 protocol1_21_5To1_21_6 = protocolManager.getProtocol(Protocol1_21_5To1_21_6.class);
        if (protocol1_21_5To1_21_6 != null) {
            protocol1_21_5To1_21_6.registerServerbound(ServerboundPackets1_21_6.CHANGE_GAME_MODE, ServerboundPackets1_21_5.CHAT_COMMAND, wrapper -> {
                if (wrapper.user().getProtocolInfo().serverProtocolVersion().olderThanOrEqualTo(ProtocolVersion.v1_20_3)) {
                    // Unsigned commands must not be produced inside VV protocols, because signatures cannot be
                    // fixed up there in every case - signing lives on the client side.
                    NotificationUtil.warnIncompatibilityPacket("1.21.6", "CHANGE_GAME_MODE", null);
                    wrapper.cancel();
                    return;
                }

                final int gameMode = wrapper.read(Types.VAR_INT);
                final GameMode mode = GameMode.getById(gameMode);
                wrapper.write(Types.STRING, "gamemode " + mode.name().toLowerCase(Locale.ROOT));
            }, true);
        } else {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocol1_21_5To1_21_6 is not registered, the game mode command will stay ViaVersion-side");
        }
    }

    // was VFP features/networking/config_state/MixinProtocol1_20To1_20_2#dontQueueConfigPackets
    // (@Inject HEAD cancellable on the queueServerboundPacket lambda). With DebugSettings.queueConfigPackets
    // off, a custom-payload / keep-alive / pong sent during the emulated configuration phase is re-typed to
    // its 1.19.4 play equivalent and sent immediately instead of being parked until the play state - which is
    // what keeps a transaction or keep-alive answer from arriving after the configuration -> play switch.
    // The queueing branch reproduces ViaVersion's own handler exactly (Protocol1_20To1_20_2.java:385-392).
    private static void applyConfigStatePacketQueue(final ProtocolManager protocolManager) {
        final Protocol1_20To1_20_2 protocol = protocolManager.getProtocol(Protocol1_20To1_20_2.class);
        if (protocol == null) {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocol1_20To1_20_2 is not registered, queueConfigPackets has no effect");
            return;
        }

        registerConfigurationPacket(protocol, ServerboundConfigurationPackets1_20_2.CUSTOM_PAYLOAD, ServerboundPackets1_20_2.CUSTOM_PAYLOAD,
            ServerboundPackets1_19_4.CUSTOM_PAYLOAD);
        registerConfigurationPacket(protocol, ServerboundConfigurationPackets1_20_2.KEEP_ALIVE, ServerboundPackets1_20_2.KEEP_ALIVE,
            ServerboundPackets1_19_4.KEEP_ALIVE);
        registerConfigurationPacket(protocol, ServerboundConfigurationPackets1_20_2.PONG, ServerboundPackets1_20_2.PONG,
            ServerboundPackets1_19_4.PONG);
    }

    private static void registerConfigurationPacket(
        final Protocol1_20To1_20_2 protocol,
        final ServerboundConfigurationPackets1_20_2 configurationType,
        final ServerboundPackets1_20_2 queuedType,
        final ServerboundPackets1_19_4 immediateType
    ) {
        protocol.registerServerbound(State.CONFIGURATION, configurationType.getId(), -1, wrapper -> {
            if (DebugSettings.INSTANCE.queueConfigPackets.getValue()) {
                wrapper.setPacketType(queuedType);
                final ProtocolStorables1_20_2 storables = wrapper.user().storables(Protocol1_20To1_20_2.class);
                storables.configurationState().addServerboundPacketToQueue(wrapper);
                wrapper.cancel();
            } else {
                wrapper.setPacketType(immediateType);
            }
        }, true);
    }

}
