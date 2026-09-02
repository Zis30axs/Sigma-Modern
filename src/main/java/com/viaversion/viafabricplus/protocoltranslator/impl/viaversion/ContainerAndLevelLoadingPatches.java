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
import com.viaversion.viafabricplus.protocoltranslator.translator.TextComponentTranslator;
import com.viaversion.viafabricplus.util.NotificationUtil;
import com.viaversion.viafabricplus.util.network.SyncTasks;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.data.entity.EntityTracker;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.protocols.v1_12_2to1_13.packet.ClientboundPackets1_13;
import com.viaversion.viaversion.protocols.v1_13_2to1_14.Protocol1_13_2To1_14;
import com.viaversion.viaversion.protocols.v1_13_2to1_14.packet.ClientboundPackets1_14;
import com.viaversion.viaversion.protocols.v1_16_1to1_16_2.packet.ServerboundPackets1_16_2;
import com.viaversion.viaversion.protocols.v1_16_4to1_17.Protocol1_16_4To1_17;
import com.viaversion.viaversion.protocols.v1_16_4to1_17.packet.ServerboundPackets1_17;
import com.viaversion.viaversion.protocols.v1_20_2to1_20_3.Protocol1_20_2To1_20_3;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.packet.ClientboundPackets1_20_2;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_2to1_21_4.packet.ServerboundPackets1_21_4;
import com.viaversion.viaversion.protocols.v1_21_4to1_21_5.Protocol1_21_4To1_21_5;
import com.viaversion.viaversion.protocols.v1_21_4to1_21_5.packet.ServerboundPackets1_21_5;
import com.viaversion.viaversion.protocols.v26_1to26_2.Protocol26_1To26_2;
import com.viaversion.viaversion.rewriter.text.JsonNBTComponentRewriter;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.util.Mth;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;

/**
 * MODIFIED for porting: companion to {@link ViaFabricPlusProtocolPatches} for the container-clicking,
 * large-container and level-loading ViaFabricPlus mixins whose {@code @Mixin} target is a ViaVersion class.
 * Sigma-Modern has no Mixin runtime and cannot edit the ViaVersion jar, so the equivalent behaviour is
 * installed through ViaVersion's own public protocol API.
 *
 * <p>Same timing rule as {@link ViaFabricPlusProtocolPatches}: every protocol touched here is joined on its
 * mapping-loader future first, because {@code registerPackets()} runs asynchronously on Via's mapping-loader
 * pool and a {@code replace*} call would otherwise find no handler to replace.
 */
public final class ContainerAndLevelLoadingPatches {

    // Verbatim re-creation of ItemPacketRewriter1_14's private static COMPONENT_REWRITER
    // (ItemPacketRewriter1_14.java:50-60), needed because the OPEN_SCREEN handler below is a full rebuild of
    // ViaVersion's own and that handler runs it over the window title.
    private static final JsonNBTComponentRewriter<ClientboundPackets1_13> OPEN_SCREEN_COMPONENT_REWRITER =
        new JsonNBTComponentRewriter<ClientboundPackets1_13>(null, JsonNBTComponentRewriter.ReadType.JSON) {
            @Override
            protected void handleTranslate(final JsonObject object, final String translate) {
                super.handleTranslate(object, translate);
                // Mojang decided to remove .name from inventory titles
                if (translate.startsWith("block.") && translate.endsWith(".name")) {
                    object.addProperty("translate", translate.substring(0, translate.length() - 5));
                }
            }
        };

    private ContainerAndLevelLoadingPatches() {
    }

    public static void apply() {
        final ProtocolManager protocolManager = Via.getManager().getProtocolManager();
        awaitMappings(protocolManager,
            Protocol1_21_4To1_21_5.class,
            Protocol1_16_4To1_17.class,
            Protocol1_13_2To1_14.class,
            Protocol1_20_2To1_20_3.class,
            Protocol26_1To26_2.class);

        applyContainerClickBlocking(protocolManager);
        applyAlwaysInstaBuild(protocolManager);
        applyLargeContainers(protocolManager);
        applyLevelChunksSentGameEvent(protocolManager);
    }

    // Same barrier as ViaFabricPlusProtocolPatches#awaitMappings - kept local so this class does not depend on
    // the caller having listed its protocols there. getMappingLoaderFuture returns null once the mappings are
    // loaded, which is the "already done" case, and joining a completed future is free.
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

    private static void applyContainerClickBlocking(final ProtocolManager protocolManager) {
        // was VFP features/interaction/container_clicking/MixinBlockItemPacketRewriter1_21_5
        // #removeContainerClickHandler (@Inject registerPackets RETURN, whose whole body is a
        // registerServerbound(..., override = true) - which is public API). ViaVersion's own handler
        // (BlockItemPacketRewriter1_21_5.java:166) still translates a raw ServerboundContainerClickPacket for
        // every target <= 1.21.4, which would bypass the version-correct writer MultiPlayerGameMode builds by
        // hand, so the packet is refused instead. Upstream's warning names the pre-26.2 method
        // handleInventoryMouseClick; in this tree it is MultiPlayerGameMode#handleContainerInput
        // (MultiPlayerGameMode.java:674).
        final Protocol1_21_4To1_21_5 protocol1_21_4To1_21_5 = protocolManager.getProtocol(Protocol1_21_4To1_21_5.class);
        if (protocol1_21_4To1_21_5 != null) {
            protocol1_21_4To1_21_5.registerServerbound(ServerboundPackets1_21_5.CONTAINER_CLICK, ServerboundPackets1_21_4.CONTAINER_CLICK, wrapper -> {
                NotificationUtil.warnIncompatibilityPacket("1.21.5", "CONTAINER_CLICK", "MultiPlayerGameMode#handleContainerInput");
                wrapper.cancel();
            }, true);
        } else {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocol1_21_4To1_21_5 is not registered, raw 1.21.5 container clicks stay translatable");
        }

        // was VFP features/interaction/container_clicking/MixinItemPacketRewriter1_17
        // #removeContainerClickHandler (identical shape on the 1.17 path, displacing the replaceServerbound at
        // ItemPacketRewriter1_17.java:47). Applies to every target <= 1.16.4, where the action id and the
        // pre-click item are written by hand too.
        final Protocol1_16_4To1_17 protocol1_16_4To1_17 = protocolManager.getProtocol(Protocol1_16_4To1_17.class);
        if (protocol1_16_4To1_17 != null) {
            protocol1_16_4To1_17.registerServerbound(ServerboundPackets1_17.CONTAINER_CLICK, ServerboundPackets1_16_2.CONTAINER_CLICK, wrapper -> {
                NotificationUtil.warnIncompatibilityPacket("1.17", "CONTAINER_CLICK", "MultiPlayerGameMode#handleContainerInput");
                wrapper.cancel();
            }, true);
        } else {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocol1_16_4To1_17 is not registered, raw 1.17 container clicks stay translatable");
        }
    }

    // was VFP features/interaction/container_clicking/MixinEntityTrackerBase#canInstaBuild (@Overwrite
    // returning true unconditionally, so ViaVersion never cancels a serverbound SET_CREATIVE_MODE_SLOT because
    // its own cached game mode went stale). EntityTrackerBase lives in the jar and cannot be subclassed into an
    // already-built connection, but the flag it returns is public API: EntityTracker#setInstaBuild.
    //
    // ViaVersion only ever WRITES the flag from the clientbound LOGIN / RESPAWN / GAME_EVENT (id 3) /
    // PLAYER_ABILITIES handlers (EntityRewriter.java:421/435/443/452, EntityPacketRewriter1_20_5.java
    // :286/303/351/356) and only ever READS it while translating a serverbound SET_CREATIVE_MODE_SLOT
    // (ItemRewriter.java:261, StructuredItemRewriter.java:496, BlockItemPacketRewriter1_20_5.java:349,
    // BlockItemPacketRewriter1_21_5.java:155). Forcing every tracker of the connection back to true right after
    // those four packets therefore makes canInstaBuild() observably always true for every reader.
    //
    // One hop is enough: UserConnection#getEntityTrackers covers every protocol's tracker at once, and
    // Protocol26_1To26_2 is the newest hop, so it is the LAST protocol to run on a clientbound packet
    // (ProtocolPipelineImpl#refreshReversedList adds non-base protocols in reverse order for CLIENTBOUND). It is
    // in every pipeline whose target is older than 26.2, which is the only case in which any reader above
    // exists at all.
    private static void applyAlwaysInstaBuild(final ProtocolManager protocolManager) {
        final Protocol26_1To26_2 protocol = protocolManager.getProtocol(Protocol26_1To26_2.class);
        if (protocol == null) {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocol26_1To26_2 is not registered, creative inventory edits may be dropped by ViaVersion");
            return;
        }

        final PacketHandler forceInstaBuild = wrapper -> {
            for (final EntityTracker tracker : wrapper.user().getEntityTrackers()) {
                tracker.setInstaBuild(true);
            }
        };

        protocol.appendClientbound(ClientboundPackets26_1.LOGIN, forceInstaBuild);
        protocol.appendClientbound(ClientboundPackets26_1.RESPAWN, forceInstaBuild);
        protocol.appendClientbound(ClientboundPackets26_1.GAME_EVENT, forceInstaBuild);
        protocol.appendClientbound(ClientboundPackets26_1.PLAYER_ABILITIES, forceInstaBuild);
    }

    // was VFP features/large_container/MixinItemPacketRewriter1_14#supportLargeContainers (@Inject into
    // ViaVersion's OPEN_SCREEN lambda at the ProtocolLogger#warning invocation, cancellable, with @Local
    // captures of windowId / type / title / slots). The injection point is only reached when typeId stayed -1,
    // i.e. when ViaVersion has no 1.14 menu type for the packet; a minecraft:container or minecraft:chest with
    // more than 54 or no slots is turned into a sync task that builds the chest menu client-side instead of
    // being logged and dropped. Applies to every target <= 1.13.2.
    //
    // @Local captures inside a library lambda have no public-API equivalent, so ViaVersion's own handler
    // (ItemPacketRewriter1_14.java:67-109) is rebuilt verbatim with the branch inserted at the same place - the
    // shape already used for LIGHT_UPDATE in ViaFabricPlusProtocolPatches#applyClassicWorldHeight.
    // replaceClientbound keeps the registered mapped type (null - the handler picks the packet type itself), so
    // packet ids stay correct.
    private static void applyLargeContainers(final ProtocolManager protocolManager) {
        final Protocol1_13_2To1_14 protocol = protocolManager.getProtocol(Protocol1_13_2To1_14.class);
        if (protocol == null) {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocol1_13_2To1_14 is not registered, legacy chests above 54 slots will not open");
            return;
        }

        protocol.replaceClientbound(ClientboundPackets1_13.OPEN_SCREEN, wrapper -> {
            final Short windowId = wrapper.read(Types.UNSIGNED_BYTE);
            final String type = wrapper.read(Types.STRING);
            final JsonElement title = wrapper.read(Types.COMPONENT);
            OPEN_SCREEN_COMPONENT_REWRITER.processText(wrapper.user(), title);
            final Short slots = wrapper.read(Types.UNSIGNED_BYTE);

            if (type.equals("EntityHorse")) {
                wrapper.setPacketType(ClientboundPackets1_14.HORSE_SCREEN_OPEN);
                final int entityId = wrapper.read(Types.INT);
                wrapper.write(Types.UNSIGNED_BYTE, windowId);
                wrapper.write(Types.VAR_INT, slots.intValue());
                wrapper.write(Types.INT, entityId);
            } else {
                wrapper.setPacketType(ClientboundPackets1_14.OPEN_SCREEN);
                wrapper.write(Types.VAR_INT, windowId.intValue());

                int typeId = -1;
                switch (type) {
                    case "minecraft:crafting_table" -> typeId = 11;
                    case "minecraft:furnace" -> typeId = 13;
                    case "minecraft:dropper", "minecraft:dispenser" -> typeId = 6;
                    case "minecraft:enchanting_table" -> typeId = 12;
                    case "minecraft:brewing_stand" -> typeId = 10;
                    case "minecraft:villager" -> typeId = 18;
                    case "minecraft:beacon" -> typeId = 8;
                    case "minecraft:anvil" -> typeId = 7;
                    case "minecraft:hopper" -> typeId = 15;
                    case "minecraft:shulker_box" -> typeId = 19;
                    default -> {
                        if (slots > 0 && slots <= 54) {
                            typeId = slots / 9 - 1;
                        }
                    }
                }

                if (typeId == -1) {
                    if ((type.equals("minecraft:container") || type.equals("minecraft:chest")) && (slots > 54 || slots <= 0)) {
                        final String uuid = SyncTasks.executeSyncTask(data -> {
                            final Minecraft mc = Minecraft.getInstance();

                            try {
                                final int syncId = data.readUnsignedByte();
                                final int size = data.readUnsignedByte();
                                final Component mcTitle = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(data);

                                final ChestMenu screenHandler = new ChestMenu(null, syncId, mc.player.getInventory(), new SimpleContainer(size), Mth.ceil(size / 9F));
                                mc.player.containerMenu = screenHandler;
                                mc.gui.setScreen(new ContainerScreen(screenHandler, mc.player.getInventory(), mcTitle));
                            } catch (final Throwable t) {
                                throw new RuntimeException("Failed to handle OpenWindow packet data", t);
                            }
                        });

                        wrapper.clearPacket();
                        wrapper.setPacketType(ClientboundPackets1_14.CUSTOM_PAYLOAD);
                        wrapper.write(Types.STRING, SyncTasks.PACKET_SYNC_IDENTIFIER); // sync task header
                        wrapper.write(Types.STRING, uuid); // sync task id
                        wrapper.write(Types.UNSIGNED_BYTE, windowId);
                        wrapper.write(Types.UNSIGNED_BYTE, slots);
                        wrapper.write(Types.TAG, TextComponentTranslator.via1_14toViaLatest(title));
                        return;
                    }

                    protocol.getLogger().warning("Can't open inventory for player! Type: " + type + " Size: " + slots);
                }

                wrapper.write(Types.VAR_INT, typeId);
                wrapper.write(Types.COMPONENT, title);
            }
        });
    }

    // was VFP features/networking/level_loading/MixinEntityPacketRewriter1_20_3#sendChunksSentGameEvent
    // (@Overwrite emptying EntityPacketRewriter1_20_3.java:91-100). That private helper is the last handler of
    // the clientbound LOGIN (:73), RESPAWN (:83) and INITIALIZE_BORDER (:88) registrations; it send()s and
    // cancel()s the wrapper and then fabricates the 1.20.3-only GAME_EVENT id 13 (LEVEL_CHUNKS_LOAD_START,
    // value 0.0f), ungated, for every target <= 1.20.2. Emptying it means those three registrations keep only
    // their field maps and the world-data tracker, which is public API to rebuild - so all three handlers are
    // replaced with exactly that. PacketHandlers#map(Type) is wrapper.passthrough(Type), so the STRING indices
    // worldDataTrackerHandlerByKey() reads (dimension key at 0, world at 1) are unchanged.
    //
    // The residual is not cosmetic in this tree: the synthetic GAME_EVENT reaches ClientPacketListener.java:1799
    // and moves LevelLoadTracker from WaitingForServer to WaitingForPlayerChunk, so ClientPacketListener
    // :3045-3047 eventually nulls levelLoadTracker - and the already-ported <= 1.20.5 WIN_GAME branch at
    // ClientPacketListener.java:1739 then builds a LevelLoadingScreen around that null tracker, which NPEs in
    // LevelLoadingScreen#extractRenderState (:186/:200, both unconditional dereferences).
    private static void applyLevelChunksSentGameEvent(final ProtocolManager protocolManager) {
        final Protocol1_20_2To1_20_3 protocol = protocolManager.getProtocol(Protocol1_20_2To1_20_3.class);
        if (protocol == null) {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocol1_20_2To1_20_3 is not registered, the synthetic chunks-sent game event stays");
            return;
        }

        final PacketHandler worldDataTracker = protocol.getEntityRewriter().worldDataTrackerHandlerByKey();

        protocol.replaceClientbound(ClientboundPackets1_20_2.LOGIN, wrapper -> {
            wrapper.passthrough(Types.INT); // Entity id
            wrapper.passthrough(Types.BOOLEAN); // Hardcore
            wrapper.passthrough(Types.STRING_ARRAY); // World List
            wrapper.passthrough(Types.VAR_INT); // Max players
            wrapper.passthrough(Types.VAR_INT); // View distance
            wrapper.passthrough(Types.VAR_INT); // Simulation distance
            wrapper.passthrough(Types.BOOLEAN); // Reduced debug info
            wrapper.passthrough(Types.BOOLEAN); // Show death screen
            wrapper.passthrough(Types.BOOLEAN); // Limited crafting
            wrapper.passthrough(Types.STRING); // Dimension key
            wrapper.passthrough(Types.STRING); // World
            worldDataTracker.handle(wrapper);
        });

        protocol.replaceClientbound(ClientboundPackets1_20_2.RESPAWN, wrapper -> {
            wrapper.passthrough(Types.STRING); // Dimension
            wrapper.passthrough(Types.STRING); // World
            worldDataTracker.handle(wrapper);
        });

        // INITIALIZE_BORDER was registered with sendChunksSentGameEvent as its only handler, so the overwrite
        // leaves it a no-op.
        protocol.replaceClientbound(ClientboundPackets1_20_2.INITIALIZE_BORDER, wrapper -> {
        });
    }

}
