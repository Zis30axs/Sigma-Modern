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
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
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
    }

}
