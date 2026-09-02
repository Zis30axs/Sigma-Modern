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
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.data.Attributes1_20_5;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.packet.ClientboundPackets1_20_5;
import com.viaversion.viaversion.protocols.v1_20_5to1_21.Protocol1_20_5To1_21;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.raphimc.vialegacy.api.LegacyProtocolVersion;

/**
 * MODIFIED for porting: replaces the ViaFabricPlus mixin in {@code features/entity/attribute} whose
 * {@code @Mixin} target is a ViaVersion class. Sigma-Modern has no Mixin runtime and cannot edit the
 * ViaVersion jar, so the equivalent behaviour is installed through ViaVersion's own public protocol API.
 *
 * <p>Called from {@link ViaFabricPlusProtocolPatches#apply()}, i.e. after {@code ViaManagerImpl.initAndLoad}
 * returns; see the timing note there. The protocol touched here is joined on its own mapping-loader future
 * below, because {@code replaceClientbound} needs the handler it replaces to already be registered.
 */
public final class EntityAttributePatches {

    private static final String BLOCK_INTERACTION_RANGE = "player.block_interaction_range";
    private static final String ENTITY_INTERACTION_RANGE = "player.entity_interaction_range";

    private EntityAttributePatches() {
    }

    public static void apply() {
        final ProtocolManager protocolManager = Via.getManager().getProtocolManager();
        awaitMappings(protocolManager, Protocol1_20_5To1_21.class);
        applyLegacyRangeAttributes(protocolManager);
    }

    // was VFP features/entity/attribute/MixinEntityPacketRewriter1_20_5#useLegacyValues (@Redirect on all
    // three EntityPacketRewriter1_20_5#writeAttribute INVOKEs inside sendRangeAttributes).
    //
    // ViaVersion synthesises the two 1.20.5 reach attributes for every <= 1.20.3 server, with base 4.5 and
    // modifier 0.5 for player.block_interaction_range and base 3.0 with modifier 1.0 (<= 1.13.2) or 2.0
    // (newer) for player.entity_interaction_range. Upstream overrides those for the oldest servers: 4.0 / 1.0
    // when the server is older than 1.0.0, and 3.0 / 3.0 when it is 1.6.4 or older - so survival block reach
    // becomes 4.0 instead of 4.5 on pre-1.0 servers and creative entity reach 6.0 instead of 4.0/5.0 on
    // <= 1.6.4 ones, which is what those servers actually accept.
    //
    // sendRangeAttributes ends in wrapper.scheduleSend(Protocol1_20_3To1_20_5.class), so the packet skips that
    // protocol's own handlers and enters the chain at the next protocol, Protocol1_20_5To1_21. Its clientbound
    // UPDATE_ATTRIBUTES handler is therefore the first one to see the synthesised values, and it is replaced
    // here by a verbatim re-implementation (Protocol1_20_5To1_21.java:116-132) carrying the override - the same
    // technique already used for vfpLightUpdate1_17 in ViaFabricPlusProtocolPatches. replaceClientbound keeps
    // the existing mapped type, so packet ids stay correct.
    //
    // Keying on the unmapped 1.20.5 attribute id is exact: sendRangeAttributes is the only place in ViaVersion
    // that ever writes player.block_interaction_range or player.entity_interaction_range, and no <= 1.6.4
    // server can send them itself - they did not exist before 1.20.5.
    private static void applyLegacyRangeAttributes(final ProtocolManager protocolManager) {
        final Protocol1_20_5To1_21 protocol = protocolManager.getProtocol(Protocol1_20_5To1_21.class);
        if (protocol == null) {
            ViaFabricPlusImpl.INSTANCE.getLogger()
                .warn("Protocol1_20_5To1_21 is not registered, legacy interaction ranges stay at their ViaVersion defaults");
            return;
        }

        final MappingData mappingData = protocol.getMappingData();
        if (mappingData == null) {
            ViaFabricPlusImpl.INSTANCE.getLogger()
                .warn("Protocol1_20_5To1_21 has no mapping data, legacy interaction ranges stay at their ViaVersion defaults");
            return;
        }

        final int blockRangeId = Attributes1_20_5.keyToId(BLOCK_INTERACTION_RANGE);
        final int entityRangeId = Attributes1_20_5.keyToId(ENTITY_INTERACTION_RANGE);

        protocol.replaceClientbound(ClientboundPackets1_20_5.UPDATE_ATTRIBUTES, wrapper -> {
            final ProtocolVersion serverVersion = wrapper.user().getProtocolInfo().serverProtocolVersion();
            final boolean legacyBlockRange = serverVersion.olderThan(LegacyProtocolVersion.r1_0_0tor1_0_1);
            final boolean legacyEntityRange = serverVersion.olderThanOrEqualTo(LegacyProtocolVersion.r1_6_4);

            wrapper.passthrough(Types.VAR_INT); // Entity ID

            final int size = wrapper.passthrough(Types.VAR_INT);
            for (int i = 0; i < size; i++) {
                final int attributeId = wrapper.read(Types.VAR_INT);
                wrapper.write(Types.VAR_INT, mappingData.getNewAttributeId(attributeId));

                final boolean useLegacyBlockRange = legacyBlockRange && attributeId == blockRangeId;
                final boolean useLegacyEntityRange = legacyEntityRange && attributeId == entityRangeId;

                final double base = wrapper.read(Types.DOUBLE);
                if (useLegacyBlockRange) {
                    wrapper.write(Types.DOUBLE, 4D);
                } else if (useLegacyEntityRange) {
                    wrapper.write(Types.DOUBLE, 3D);
                } else {
                    wrapper.write(Types.DOUBLE, base);
                }

                final int modifierSize = wrapper.passthrough(Types.VAR_INT);
                for (int j = 0; j < modifierSize; j++) {
                    final UUID uuid = wrapper.read(Types.UUID);
                    wrapper.write(Types.STRING, Protocol1_20_5To1_21.mapAttributeUUID(uuid, null));

                    final double amount = wrapper.read(Types.DOUBLE);
                    if (useLegacyBlockRange) {
                        wrapper.write(Types.DOUBLE, 1D);
                    } else if (useLegacyEntityRange) {
                        wrapper.write(Types.DOUBLE, 3D);
                    } else {
                        wrapper.write(Types.DOUBLE, amount);
                    }

                    wrapper.passthrough(Types.BYTE); // Operation
                }
            }
        });
    }

    // Same barrier as ViaFabricPlusProtocolPatches#awaitMappings, which is private there. Blocks until the
    // protocol's registerPackets() has run, so replaceClientbound cannot race it - it throws when the handler
    // it replaces is not registered yet. getMappingLoaderFuture returns null once the mappings are loaded,
    // which is the "already done" case. This never deadlocks: apply() runs on Util.backgroundExecutor(), not
    // on a Via-Mappingloader thread.
    @SafeVarargs
    private static void awaitMappings(final ProtocolManager protocolManager, final Class<? extends Protocol>... protocols) {
        for (final Class<? extends Protocol> protocol : protocols) {
            final CompletableFuture<Void> future = protocolManager.getMappingLoaderFuture(protocol);
            if (future == null) {
                continue;
            }

            try {
                future.join();
            } catch (final CompletionException | CancellationException e) {
                ViaFabricPlusImpl.INSTANCE.getLogger()
                    .error("Failed to load mappings for {}, its ViaFabricPlus patches may not apply", protocol.getSimpleName(), e);
            }
        }
    }

}
