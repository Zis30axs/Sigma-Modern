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
import com.viaversion.viafabricplus.features.classic.world_height.WorldHeightSupport;
import com.viaversion.viafabricplus.features.limitation.max_chat_length.MaxChatLength;
import com.viaversion.viafabricplus.settings.impl.DebugSettings;
import com.viaversion.viafabricplus.util.NotificationUtil;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.minecraft.GameMode;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.exception.InformativeException;
import com.viaversion.viaversion.api.minecraft.BlockChangeRecord;
import com.viaversion.viaversion.api.minecraft.BlockChangeRecord1_16_2;
import com.viaversion.viaversion.protocols.v1_10to1_11.Protocol1_10To1_11;
import com.viaversion.viaversion.protocols.v1_15_2to1_16.packet.ClientboundPackets1_16;
import com.viaversion.viaversion.protocols.v1_16_1to1_16_2.Protocol1_16_1To1_16_2;
import com.viaversion.viaversion.protocols.v1_16_1to1_16_2.packet.ClientboundPackets1_16_2;
import com.viaversion.viaversion.protocols.v1_16_4to1_17.Protocol1_16_4To1_17;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import com.viaversion.viaversion.protocols.v1_19_3to1_19_4.packet.ServerboundPackets1_19_4;
import com.viaversion.viaversion.protocols.v1_20_2to1_20_3.packet.ServerboundPackets1_20_3;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.Protocol1_20_3To1_20_5;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.packet.ServerboundPackets1_20_5;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.Protocol1_20To1_20_2;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.packet.ServerboundConfigurationPackets1_20_2;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.packet.ServerboundPackets1_20_2;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.storage.ProtocolStorables1_20_2;
import com.viaversion.viaversion.protocols.v1_9_1to1_9_3.packet.ServerboundPackets1_9_3;
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
 * jar, so the equivalent behaviour is installed through ViaVersion's own public protocol API.
 *
 * <p>Timing matters. {@code ProtocolManager#registerProtocols} only puts the instances in a map - the work
 * that installs each protocol's own packet handlers ({@code loadMappingData()} -> {@code initialize()} ->
 * {@code registerPackets()}) is submitted to Via's mapping-loader pool and runs asynchronously. Patching a
 * protocol before that finishes either throws (a {@code replace*} call finds no handler to replace), gets
 * silently overwritten (an {@code override = true} registration is replaced by ViaVersion's own), or turns
 * an {@code append*} into a first registration that later collides. So every protocol is joined on its
 * mapping-loader future first, and {@link #apply()} is called after {@code ViaManagerImpl.initAndLoad}
 * returns rather than from {@code ViaFabricPlusPlatformLoader#load()}, which runs inside that bootstrap.
 *
 * <p>Only library mixins whose behaviour is reachable through the public API live here. The remaining
 * ones are listed with their exact reason in VFP_AUDIT.md.
 */
public final class ViaFabricPlusProtocolPatches {

    private ViaFabricPlusProtocolPatches() {
    }

    public static void apply() {
        final ProtocolManager protocolManager = Via.getManager().getProtocolManager();
        awaitMappings(protocolManager,
            Protocol1_21To1_21_2.class,
            Protocol1_19_3To1_19_4.class,
            Protocol1_20_3To1_20_5.class,
            Protocol1_21_5To1_21_6.class,
            Protocol1_20To1_20_2.class,
            Protocol1_10To1_11.class,
            Protocol1_16_4To1_17.class,
            Protocol1_16_1To1_16_2.class);

        // was VFP features/movement/packet/MixinEntityPacketRewriter1_21_2#dontCancelIdlePacket
        // (@Redirect no-oping PacketWrapper#cancel in lambda$registerPackets$14, the
        // MOVE_PLAYER_STATUS_ONLY handler). That handler cancels the idle movement packet when the
        // on-ground state is unchanged AND the horizontal-collision flag changed, which starves <= 1.21
        // servers of the per-tick movement packet they expect. Appending to the same handler chain undoes
        // only that one cancel - the handler contains no other.
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
        applyMaxChatLength(protocolManager);
        applyClassicWorldHeight(protocolManager);
    }

    // was VFP features/classic/world_height MixinEntityPacketRewriter1_17#handleClassicWorldHeight,
    // MixinWorldPacketRewriter1_17#handleClassicWorldHeight and
    // MixinWorldPacketRewriter1_16_2#modifySectionCountToSupportClassicWorldHeight.
    //
    // The first two wrap ViaVersion's own handler; WorldHeightSupport#handleJoinGame / #handleRespawn /
    // #handleChunkData all run the parent handler FIRST and then patch the wrapper, which is exactly
    // Protocol#appendClientbound semantics - so they are appended with a no-op parent, reusing the in-tree
    // bodies verbatim.
    //
    // WorldHeightSupport#handleUpdateLight runs INSTEAD of ViaVersion's handler for classic and falls back
    // to it otherwise. The public Protocol interface cannot read an already-registered handler, so the
    // fallback is supplied as a verbatim re-implementation of ViaVersion's own LIGHT_UPDATE handler
    // (WorldPacketRewriter1_17.java:58-100) - see vfpLightUpdate1_17 below. replaceClientbound keeps the
    // mapped type, so the packet id stays correct.
    private static void applyClassicWorldHeight(final ProtocolManager protocolManager) {
        final Protocol1_16_4To1_17 protocol1_16_4To1_17 = protocolManager.getProtocol(Protocol1_16_4To1_17.class);
        if (protocol1_16_4To1_17 != null) {
            protocol1_16_4To1_17.appendClientbound(ClientboundPackets1_16_2.LOGIN, WorldHeightSupport.handleJoinGame(wrapper -> {
            }));
            protocol1_16_4To1_17.appendClientbound(ClientboundPackets1_16_2.RESPAWN, WorldHeightSupport.handleRespawn(wrapper -> {
            }));
            protocol1_16_4To1_17.appendClientbound(ClientboundPackets1_16_2.LEVEL_CHUNK, WorldHeightSupport.handleChunkData(wrapper -> {
            }));
            protocol1_16_4To1_17.replaceClientbound(ClientboundPackets1_16_2.LIGHT_UPDATE, WorldHeightSupport.handleUpdateLight(vfpLightUpdate1_17()));
        } else {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocol1_16_4To1_17 is not registered, classic world height will be clamped");
        }

        // The 1.16 -> 1.16.2 multi-block-change split is hardcoded to 16 chunk sections upstream; classic
        // worlds can be taller, so the same handler is re-registered with a 64-section array. replaceClientbound
        // keeps the existing mapped type (SECTION_BLOCKS_UPDATE), so packet ids stay correct.
        final Protocol1_16_1To1_16_2 protocol1_16_1To1_16_2 = protocolManager.getProtocol(Protocol1_16_1To1_16_2.class);
        if (protocol1_16_1To1_16_2 != null) {
            protocol1_16_1To1_16_2.replaceClientbound(ClientboundPackets1_16.CHUNK_BLOCKS_UPDATE, wrapper -> {
                wrapper.cancel();

                final int chunkX = wrapper.read(Types.INT);
                final int chunkZ = wrapper.read(Types.INT);

                long chunkPosition = 0;
                chunkPosition |= (chunkX & 0x3FFFFFL) << 42;
                chunkPosition |= (chunkZ & 0x3FFFFFL) << 20;

                @SuppressWarnings("unchecked")
                final List<BlockChangeRecord>[] sectionRecords = new List[64];
                for (final BlockChangeRecord record : wrapper.read(Types.BLOCK_CHANGE_ARRAY)) {
                    final int chunkY = record.getY() >> 4;
                    if (chunkY < 0 || chunkY >= sectionRecords.length) {
                        continue;
                    }

                    List<BlockChangeRecord> list = sectionRecords[chunkY];
                    if (list == null) {
                        sectionRecords[chunkY] = list = new ArrayList<>();
                    }

                    final int blockId = protocol1_16_1To1_16_2.getMappingData().getNewBlockStateId(record.getBlockId());
                    list.add(new BlockChangeRecord1_16_2(record.getSectionX(), record.getSectionY(), record.getSectionZ(), blockId));
                }

                for (int chunkY = 0; chunkY < sectionRecords.length; chunkY++) {
                    final List<BlockChangeRecord> sectionRecord = sectionRecords[chunkY];
                    if (sectionRecord == null) {
                        continue;
                    }

                    final PacketWrapper newPacket = wrapper.create(ClientboundPackets1_16_2.SECTION_BLOCKS_UPDATE);
                    newPacket.write(Types.LONG, chunkPosition | (chunkY & 0xFFFFFL));
                    newPacket.write(Types.BOOLEAN, false); // Ignore light updates
                    newPacket.write(Types.VAR_LONG_BLOCK_CHANGE_ARRAY, sectionRecord.toArray(new BlockChangeRecord[0]));
                    newPacket.send(Protocol1_16_1To1_16_2.class);
                }
            });
        } else {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocol1_16_1To1_16_2 is not registered, tall classic worlds will drop block changes above y=255");
        }
    }

    // Verbatim re-implementation of ViaVersion's 1.16.2 -> 1.17 LIGHT_UPDATE handler
    // (WorldPacketRewriter1_17.java:58-100). It is the non-classic fallback of
    // WorldHeightSupport#handleUpdateLight, which cannot reach the registered original through public API.
    private static PacketHandler vfpLightUpdate1_17() {
        return wrapper -> {
            wrapper.passthrough(Types.VAR_INT); // x
            wrapper.passthrough(Types.VAR_INT); // y
            wrapper.passthrough(Types.BOOLEAN); // trust edges

            final int skyLightMask = wrapper.read(Types.VAR_INT);
            final int blockLightMask = wrapper.read(Types.VAR_INT);
            // Now all written as a representation of BitSets
            wrapper.write(Types.LONG_ARRAY_PRIMITIVE, new long[]{skyLightMask}); // Sky light mask
            wrapper.write(Types.LONG_ARRAY_PRIMITIVE, new long[]{blockLightMask}); // Block light mask
            wrapper.write(Types.LONG_ARRAY_PRIMITIVE, new long[]{wrapper.read(Types.VAR_INT)}); // Empty sky light mask
            wrapper.write(Types.LONG_ARRAY_PRIMITIVE, new long[]{wrapper.read(Types.VAR_INT)}); // Empty block light mask

            vfpWriteLightArrays1_17(wrapper, skyLightMask);
            vfpWriteLightArrays1_17(wrapper, blockLightMask);
        };
    }

    private static void vfpWriteLightArrays1_17(final PacketWrapper wrapper, final int bitMask) throws InformativeException {
        final List<byte[]> light = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            if ((bitMask & 1 << i) != 0) {
                light.add(wrapper.read(Types.BYTE_ARRAY_PRIMITIVE));
            }
        }

        // Now needs the length of the bytearray-array
        wrapper.write(Types.VAR_INT, light.size());
        for (final byte[] bytes : light) {
            wrapper.write(Types.BYTE_ARRAY_PRIMITIVE, bytes);
        }
    }

    // was VFP features/limitation/max_chat_length/MixinProtocol1_10To1_11#changeMaxChatLength
    // (@ModifyConstant replacing the hardcoded 100 in the anonymous Protocol1_10To1_11$6 CHAT handler with
    // MaxChatLength.getChatLength()). Re-registering that serverbound handler is public API and reproduces
    // ViaVersion's own body (Protocol1_10To1_11.java:175-187) with the constant swapped, so classic servers
    // advertising LONGER_MESSAGES and Bedrock targets are no longer cut at 100 characters.
    private static void applyMaxChatLength(final ProtocolManager protocolManager) {
        final Protocol1_10To1_11 protocol = protocolManager.getProtocol(Protocol1_10To1_11.class);
        if (protocol == null) {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocol1_10To1_11 is not registered, chat stays capped at 100 characters");
            return;
        }

        protocol.registerServerbound(ServerboundPackets1_9_3.CHAT, ServerboundPackets1_9_3.CHAT, wrapper -> {
            final String message = wrapper.passthrough(Types.STRING);
            final int limit = MaxChatLength.getChatLength();
            if (message.length() > limit) {
                wrapper.set(Types.STRING, 0, message.substring(0, limit).trim());
            }
        }, true);
    }

    // Blocks until each protocol's registerPackets() has run, so the patches below cannot race it.
    // getMappingLoaderFuture returns null once the mappings are loaded, which is the "already done" case.
    // This never deadlocks: apply() runs on Util.backgroundExecutor(), not on a Via-Mappingloader thread.
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
                ViaFabricPlusImpl.INSTANCE.getLogger().error("Failed to load mappings for {}, its ViaFabricPlus patches may not apply", protocol.getSimpleName(), e);
            }
        }
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
