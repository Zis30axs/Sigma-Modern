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
import com.viaversion.viafabricplus.protocoltranslator.impl.ViaFabricPlusMappingDataLoader;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.data.FullMappings;
import com.viaversion.viaversion.api.minecraft.HolderSet;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.data.version.StructuredDataKeys1_20_5;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.data.ToolProperties;
import com.viaversion.viaversion.api.minecraft.item.data.ToolRule;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.exception.InformativeException;
import com.viaversion.viaversion.libs.fastutil.ints.Int2IntMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2IntOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.IntOpenHashSet;
import com.viaversion.viaversion.libs.fastutil.ints.IntSet;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.protocols.v1_11_1to1_12.Protocol1_11_1To1_12;
import com.viaversion.viaversion.protocols.v1_20_2to1_20_3.packet.ClientboundPackets1_20_3;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.Protocol1_20_3To1_20_5;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.data.MaxStackSize1_20_3;
import com.viaversion.viaversion.protocols.v1_9_1to1_9_3.packet.ClientboundPackets1_9_3;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.raphimc.vialegacy.api.LegacyProtocolVersion;
import org.jetbrains.annotations.Nullable;

/**
 * MODIFIED for porting: replaces two ViaFabricPlus mixins whose {@code @Mixin} target is a ViaVersion class, which
 * Sigma-Modern cannot edit and cannot inline into a Minecraft source file.
 *
 * <p>{@code features/item/data_fix/MixinBlockItemPacketRewriter1_20_5} emulates legacy item behaviour purely
 * through 1.20.5 data components, which older servers do not have: per-target {@code TOOL} properties (mining
 * speeds, suitable-for block sets, damage per block), {@code MAX_DAMAGE} for b1.8.1 armour, and the b1.7.3 food fix
 * ({@code MAX_STACK_SIZE} 1 plus an empty {@code FOOD}). Without it every pre-1.20.5 target mines at modern speeds,
 * damages tools by modern amounts and shows modern durability.
 *
 * <p>{@code features/recipe/MixinEntityPacketRewriter1_12} stops ViaVersion from wiping the client's recipe book on
 * every join to a {@code <= 1.11.1} server.
 *
 * <p>See {@link ViaFabricPlusProtocolPatches} for the mapping-loader timing rule this package follows;
 * {@link #apply()} joins the two protocols' futures itself, because that barrier is private there.
 */
public final class LegacyItemAndRecipePatches {

    // was VFP features/item/data_fix/MixinBlockItemPacketRewriter1_20_5#viaFabricPlus$foodItems_b1_7_3 (@Unique).
    // The nine b1.7.3 food identifiers, hardcoded upstream as well.
    private static final String[] FOOD_IDENTIFIERS_B1_7_3 = {
        "minecraft:apple",
        "minecraft:mushroom_stew",
        "minecraft:bread",
        "minecraft:porkchop",
        "minecraft:cooked_porkchop",
        "minecraft:golden_apple",
        "minecraft:cod",
        "minecraft:cooked_cod",
        "minecraft:cookie"
    };

    // The three tables are keyed by 1.20.5 item id rather than by identifier, because the appended handlers below
    // see items whose id ViaVersion has already mapped to the 1.20.5 side - see #fixItem. Each key is the identifier
    // put through the protocol's OWN item mappings (#mappedItemId), which is upstream's key space exactly: upstream
    // matches on the unmapped identifier of the item, and this matches on the id that identifier is mapped to.
    //
    // Values are the unmapped id of the same item, which the oversized-stack replay in #fixItem needs.
    private static final Int2IntMap FOOD_ITEMS_B1_7_3 = new Int2IntOpenHashMap();

    // was ...#viaFabricPlus$armorMaxDamage_b1_8_1 (@Unique), read from armor-damages-b1.8.1.json.
    private static final Int2IntMap ARMOR_MAX_DAMAGE_B1_8_1 = new Int2IntOpenHashMap();

    // was ...#viaFabricPlus$toolDataChanges (@Unique), read from item-tool-components.json. Insertion ordered,
    // because #fixItem takes the first listed version whose gate matches and that has a row for the item.
    private static final Map<ProtocolVersion, Int2ObjectMap<ToolProperties>> TOOL_DATA_CHANGES = new LinkedHashMap<>();

    // Only a guard against a server sending a container component that nests into itself. Upstream cannot loop,
    // because it builds nested items depth-first out of a finite NBT tag.
    private static final int MAX_NESTED_ITEM_DEPTH = 8;

    private LegacyItemAndRecipePatches() {
    }

    public static void apply() {
        final ProtocolManager protocolManager = Via.getManager().getProtocolManager();
        awaitMappings(protocolManager, Protocol1_20_3To1_20_5.class, Protocol1_11_1To1_12.class);

        // Neither half may throw out of here: ProtocolTranslator#init calls ViaFabricPlusProtocolPatches#apply() -
        // and through it this method - inside a CompletableFuture.runAsync that logs nothing, so an escaping
        // exception would silently skip the AUTO_DETECT registration and ViaFabricPlusProtocol#initialize() that
        // follow it, plus every patch wired after this one. #loadItemMappings in particular parses two JSON files
        // and throws IllegalStateException on an unknown version key. Same guard shape as
        // ClassicCpeExtensionPatches#apply. Giving up leaves the unpatched behaviour, since the tables are filled
        // before the first handler is appended.
        try {
            applyItemDataFix(protocolManager);
        } catch (final Throwable t) {
            ViaFabricPlusImpl.INSTANCE.getLogger()
                .error("Failed to install the legacy item data fixes, legacy items keep their modern tool, durability and food behaviour", t);
        }

        try {
            applyRecipeReset(protocolManager);
        } catch (final Throwable t) {
            ViaFabricPlusImpl.INSTANCE.getLogger()
                .error("Failed to install the recipe reset patch, joining a <= 1.11.1 server will clear the recipe book", t);
        }
    }

    // was VFP features/item/data_fix/MixinBlockItemPacketRewriter1_20_5#loadItemMappings (@Inject <init> RETURN)
    // and #appendItemDataFixComponents (@Inject appendItemDataFixComponents RETURN). Applies to every server the
    // 1.20.3 -> 1.20.5 protocol sits in front of, i.e. every target <= 1.20.4, with the per-fix gates in #fixItem.
    //
    // Table loading: upstream fills the food and armour tables in the rewriter's constructor and defers the tool
    // table to LoadingCycle.POST_VIAVERSION_LOAD so that all protocols' mappings exist. All three are built here
    // instead, because apply() runs right after that cycle is invoked AND after the mapping-loader barrier - the
    // block and item id lookups they need are only valid then.
    //
    // Component attaching: appendItemDataFixComponents is private on a jar class and has no provider, so the
    // components are attached one level out, at the packets that carry the items. Every caller of
    // handleItemToClient writes the item it returns straight into the wrapper, so an appended handler reads back
    // the very object the injection point saw, and it does so before the wrapper is serialised and before the next
    // protocol in the chain sees it - which is what makes this equivalent to injecting at that method's RETURN.
    // Nothing in between touches the four components: BlockItemPacketRewriter1_20_5 is a plain ItemRewriter, not a
    // StructuredItemRewriter, so it runs no Rewritable pass of its own, and all that is left of handleItemToClient
    // after the injection point is the item id mapping plus ViaVersion's oversized-stack branch, which #fixItem
    // replays. The block ids inside a TOOL component are still rewritten by the protocols above this one, exactly
    // as they are upstream.
    private static void applyItemDataFix(final ProtocolManager protocolManager) {
        final Protocol1_20_3To1_20_5 protocol = protocolManager.getProtocol(Protocol1_20_3To1_20_5.class);
        if (protocol == null) {
            ViaFabricPlusImpl.INSTANCE.getLogger()
                .warn("Protocol1_20_3To1_20_5 is not registered, legacy items keep their modern tool, durability and food behaviour");
            return;
        }

        final FullMappings itemMappings = protocol.getMappingData().getFullItemMappings();
        if (itemMappings == null) {
            ViaFabricPlusImpl.INSTANCE.getLogger()
                .warn("Protocol1_20_3To1_20_5 has no full item mappings, legacy item data fixes will not be applied");
            return;
        }

        loadItemMappings(protocol, itemMappings);

        // Every clientbound packet of this protocol whose handler leaves a converted item in the wrapper's packet
        // values: CONTAINER_SET_SLOT and CONTAINER_SET_CONTENT (shared item registrations), SET_EQUIPMENT
        // (EntityPacketRewriter1_20_5), and MERCHANT_OFFERS, UPDATE_ADVANCEMENTS and UPDATE_RECIPES
        // (BlockItemPacketRewriter1_20_5's own handlers, the last through RecipeRewriter1_20_5).
        //
        // Not covered, because the item is nested inside another value rather than being one: the item particle in
        // LEVEL_PARTICLES, the item entity data in SET_ENTITY_DATA, the items inside block entity tags in
        // LEVEL_CHUNK_WITH_LIGHT / BLOCK_ENTITY_DATA, and the items ComponentRewriter1_20_5 builds out of a
        // show_item hover event. None of the four components is observable there - the client derives mining speed
        // and tool damage from the held item and draws durability and food tooltips for inventory, equipment, trade
        // and recipe items, not for dropped items, item frames, world block entities or chat hovers (and the two
        // components that do change a tooltip only apply to <= b1.8.1 targets, which have no hover events at all).
        // Items nested in a CONTAINER, BUNDLE_CONTENTS or CHARGED_PROJECTILES component of a covered item are
        // reached, since those are exactly the nested items upstream fixes through itemFromTag.
        final PacketHandler fixer = wrapper -> fixItems(protocol, wrapper);
        protocol.appendClientbound(ClientboundPackets1_20_3.CONTAINER_SET_SLOT, fixer);
        protocol.appendClientbound(ClientboundPackets1_20_3.CONTAINER_SET_CONTENT, fixer);
        protocol.appendClientbound(ClientboundPackets1_20_3.SET_EQUIPMENT, fixer);
        protocol.appendClientbound(ClientboundPackets1_20_3.MERCHANT_OFFERS, fixer);
        protocol.appendClientbound(ClientboundPackets1_20_3.UPDATE_ADVANCEMENTS, fixer);
        protocol.appendClientbound(ClientboundPackets1_20_3.UPDATE_RECIPES, fixer);
    }

    // The item count differs per packet - one slot, a whole container, an equipment / trade / recipe list - so every
    // value of an item type is walked instead of using fixed indices. wrapper.is(type, index) matches on type
    // identity, and these are exactly the types BlockItemPacketRewriter1_20_5 writes converted items with:
    // mappedItemType() and mappedItemArrayType() are VersionedTypes.V1_20_5.item and .itemArray, and MERCHANT_OFFERS
    // additionally writes itemCost and optionalItemCost. PacketWrapper#is is deprecated but neither replaced nor
    // changed in behaviour - it is the only public way to ask whether an n-th value of a type exists, and the
    // alternative, deriving each count from the packet structure again, is exactly the fragility this avoids.
    //
    // is/get both rescan the wrapper's value list from the front, so the walk is O(matches * values). That only
    // matters for UPDATE_RECIPES, whose ~1100 vanilla recipes make it a few hundred ms once per join, on Via's
    // netty thread rather than the render thread.
    @SuppressWarnings("deprecation")
    private static void fixItems(final Protocol1_20_3To1_20_5 protocol, final PacketWrapper wrapper) throws InformativeException {
        final ProtocolVersion serverVersion = wrapper.user().getProtocolInfo().serverProtocolVersion();
        for (int i = 0; wrapper.is(VersionedTypes.V1_20_5.item, i); i++) {
            fixItem(protocol, serverVersion, wrapper.get(VersionedTypes.V1_20_5.item, i), 0);
        }
        for (int i = 0; wrapper.is(VersionedTypes.V1_20_5.itemCost, i); i++) {
            fixItem(protocol, serverVersion, wrapper.get(VersionedTypes.V1_20_5.itemCost, i), 0);
        }
        for (int i = 0; wrapper.is(VersionedTypes.V1_20_5.optionalItemCost, i); i++) {
            fixItem(protocol, serverVersion, wrapper.get(VersionedTypes.V1_20_5.optionalItemCost, i), 0);
        }
        for (int i = 0; wrapper.is(VersionedTypes.V1_20_5.itemArray, i); i++) {
            fixItemArray(protocol, serverVersion, wrapper.get(VersionedTypes.V1_20_5.itemArray, i), 0);
        }
    }

    private static void fixItemArray(final Protocol1_20_3To1_20_5 protocol, final ProtocolVersion serverVersion,
                                     final @Nullable Item[] items, final int depth) {
        if (items == null) {
            return;
        }
        for (final Item item : items) {
            fixItem(protocol, serverVersion, item, depth);
        }
    }

    // Verbatim body of MixinBlockItemPacketRewriter1_20_5#appendItemDataFixComponents, including every gate
    // comparison, the order of the three fixes and the break-on-first-hit of the tool loop.
    private static void fixItem(final Protocol1_20_3To1_20_5 protocol, final ProtocolVersion serverVersion,
                                final @Nullable Item item, final int depth) {
        if (item == null) {
            return;
        }

        final StructuredDataContainer data = item.dataContainer();
        // A no-op for every item ViaVersion converted here - toStructuredItem sets the same lookup - but it also
        // makes set() safe on the placeholder items handleNonEmptyItemToClient creates for non-nullable slots,
        // which never reach upstream's injection point and carry no lookup of their own.
        data.setIdLookup(protocol, true);
        final int identifier = item.identifier();

        // Fix durability tooltip displaying wrong
        if (serverVersion.olderThanOrEqualTo(LegacyProtocolVersion.b1_8tob1_8_1)) {
            if (ARMOR_MAX_DAMAGE_B1_8_1.containsKey(identifier)) {
                data.set(StructuredDataKey.MAX_DAMAGE, ARMOR_MAX_DAMAGE_B1_8_1.get(identifier));
            }
        }

        // Fix item desyncs
        if (serverVersion.olderThanOrEqualTo(LegacyProtocolVersion.b1_7tob1_7_3)) {
            if (FOOD_ITEMS_B1_7_3.containsKey(identifier)) {
                data.set(StructuredDataKey.MAX_STACK_SIZE, 1);
                data.setEmpty(StructuredDataKey.FOOD1_20_5);

                // Upstream sets MAX_STACK_SIZE before ViaVersion's own oversized-stack branch, which then wins for a
                // stack larger than the 1.20.3 maximum; here the food fix runs after it, so that branch is replayed
                // to keep the same result - with the unmapped id ViaVersion passed, which is what the table stores.
                // Only reachable with ViaVersion's non-default handle-invalid-item-count.
                if (Via.getConfig().handleInvalidItemCount()
                    && item.amount() > MaxStackSize1_20_3.getMaxStackSize(FOOD_ITEMS_B1_7_3.get(identifier))) {
                    data.set(StructuredDataKey.MAX_STACK_SIZE, item.amount());
                }
            }
        }

        // Tool data changes include mining speeds as well as suitable blocks and damage values
        for (final Map.Entry<ProtocolVersion, Int2ObjectMap<ToolProperties>> entry : TOOL_DATA_CHANGES.entrySet()) {
            if (serverVersion.olderThanOrEqualTo(entry.getKey())) {
                final ToolProperties toolProperties = entry.getValue().get(identifier);
                if (toolProperties != null) {
                    data.set(StructuredDataKey.TOOL1_20_5, toolProperties);
                    break;
                }
            }
        }

        // Items nested in a component went through handleItemToClient as well (BlockItemPacketRewriter1_20_5's
        // itemFromTag), so they carry the same fixes upstream.
        if (depth >= MAX_NESTED_ITEM_DEPTH) {
            return;
        }
        final StructuredDataKeys1_20_5 keys = VersionedTypes.V1_20_5.structuredDataKeys();
        fixItemArray(protocol, serverVersion, data.get(keys.container), depth + 1);
        fixItemArray(protocol, serverVersion, data.get(keys.bundleContents), depth + 1);
        fixItemArray(protocol, serverVersion, data.get(keys.chargedProjectiles), depth + 1);
    }

    // was VFP features/item/data_fix/MixinBlockItemPacketRewriter1_20_5#loadItemMappings, item side. Verbatim
    // upstream logic, with the identifier keys resolved to 1.20.5 item ids by #mappedItemId. An identifier that this
    // ViaVersion does not know does not resolve and is skipped instead of being stored, so unknown rows cannot
    // collide on one key.
    private static void loadItemMappings(final Protocol1_20_3To1_20_5 protocol, final FullMappings itemMappings) {
        for (final String identifier : FOOD_IDENTIFIERS_B1_7_3) {
            final int unmappedId = itemMappings.id(identifier);
            final int id = unmappedId != -1 ? itemMappings.getNewId(unmappedId) : -1;
            if (id != -1) {
                FOOD_ITEMS_B1_7_3.put(id, unmappedId);
            }
        }

        final JsonObject armorMaxDamages = ViaFabricPlusMappingDataLoader.INSTANCE.loadData("armor-damages-b1.8.1.json");
        for (final Map.Entry<String, JsonElement> entry : armorMaxDamages.entrySet()) {
            final int id = mappedItemId(itemMappings, entry.getKey());
            if (id != -1) {
                ARMOR_MAX_DAMAGE_B1_8_1.put(id, entry.getValue().getAsInt());
            }
        }

        final JsonObject itemToolComponents = ViaFabricPlusMappingDataLoader.INSTANCE.loadData("item-tool-components.json");
        for (final Map.Entry<String, JsonElement> entry : itemToolComponents.entrySet()) {
            final ProtocolVersion version = ProtocolVersion.getClosest(entry.getKey());
            if (version == null) {
                throw new IllegalStateException("Unknown protocol version: " + entry.getKey());
            }
            final Int2ObjectMap<ToolProperties> toolProperties = new Int2ObjectOpenHashMap<>();
            final JsonArray toolComponents = entry.getValue().getAsJsonArray();
            for (final JsonElement toolComponent : toolComponents) {
                final JsonObject toolComponentObject = toolComponent.getAsJsonObject();
                final String item = toolComponentObject.get("item").getAsString();
                final float defaultMiningSpeed = toolComponentObject.get("default_mining_speed").getAsFloat();
                final int damagePerBlock = toolComponentObject.get("damage_per_block").getAsInt();
                final int[] suitableFor = blockJsonArrayToIds(protocol, version, toolComponentObject.getAsJsonArray("suitable_for"));
                final List<ToolRule> toolRules = new ArrayList<>();
                final JsonArray miningSpeeds = toolComponentObject.getAsJsonArray("mining_speeds");
                for (final JsonElement miningSpeed : miningSpeeds) {
                    final JsonObject miningSpeedObject = miningSpeed.getAsJsonObject();
                    final int[] blocks = blockJsonArrayToIds(protocol, version, miningSpeedObject.getAsJsonArray("blocks"));
                    final float speed = miningSpeedObject.get("speed").getAsFloat();
                    toolRules.add(new ToolRule(HolderSet.of(blocks), speed, null));
                }
                if (suitableFor.length > 0) {
                    toolRules.add(new ToolRule(HolderSet.of(suitableFor), null, true));
                }
                final int itemId = mappedItemId(itemMappings, item);
                if (itemId != -1) {
                    toolProperties.put(itemId, new ToolProperties(toolRules.toArray(new ToolRule[0]), defaultMiningSpeed, damagePerBlock));
                }
            }
            TOOL_DATA_CHANGES.put(version, toolProperties);
        }
    }

    // The 1.20.5-side id a 1.20.3 item with this identifier ends up with, resolved the way
    // BlockItemPacketRewriter1_20_5#toMappedItemId itself does it: the identifier's own unmapped id, put through the
    // protocol's item mappings. -1 when either step does not resolve. Deliberately not FullMappings#mappedId, which
    // looks the name up in the MAPPED registry and so would only agree with upstream's unmapped-identifier match
    // while the name is unchanged between the two versions.
    private static int mappedItemId(final FullMappings itemMappings, final String identifier) {
        final int unmappedId = itemMappings.id(identifier);
        return unmappedId != -1 ? itemMappings.getNewId(unmappedId) : -1;
    }

    // was VFP features/item/data_fix/MixinBlockItemPacketRewriter1_20_5#viaFabricPlus$blockJsonArrayToIds (@Unique).
    // Converts block identifiers as well as materials (prefixed with #) to block ids. Verbatim, including the
    // unmapped-side MappingData1_20_5#blockId that upstream uses and the array order dependence of the '-' removals.
    private static int[] blockJsonArrayToIds(final Protocol1_20_3To1_20_5 protocol, final ProtocolVersion protocolVersion, final JsonArray jsonArray) {
        final IntSet ids = new IntOpenHashSet();
        for (final JsonElement element : jsonArray) {
            final String name = element.getAsString();
            if (name.startsWith("#")) { // Material name
                final String material = name.substring(1);
                for (final Map.Entry<String, Map<ProtocolVersion, String>> entry : ViaFabricPlusMappingDataLoader.BLOCK_MATERIALS.entrySet()) {
                    for (final Map.Entry<ProtocolVersion, String> materialEntry : entry.getValue().entrySet()) {
                        if (protocolVersion.olderThanOrEqualTo(materialEntry.getKey()) && materialEntry.getValue().equals(material)) {
                            ids.add(protocol.getMappingData().blockId(entry.getKey()));
                            break;
                        }
                    }
                }
            } else if (name.startsWith("-")) { // Block name
                ids.remove(protocol.getMappingData().blockId(name.substring(1)));
            } else { // Block name
                ids.add(protocol.getMappingData().blockId(name));
            }
        }
        return ids.toIntArray();
    }

    // was VFP features/recipe/MixinEntityPacketRewriter1_12#dontClearRecipes (@Redirect no-oping the
    // ProtocolVersion#newerThanOrEqualTo test in EntityPacketRewriter1_12$1#lambda$register$1, the anonymous
    // PacketHandlers registered for ClientboundPackets1_9_3.LOGIN). That test is against the CLIENT version, so a
    // 26.2 client always passes it and every join to a <= 1.11.1 server - the only targets Protocol1_11_1To1_12 is in
    // the chain for - gets an empty UPDATE_RECIPES scheduled at it, which wipes ClientPacketListener's recipe
    // container and with it the recipe book. Forcing the test to false means the packet is never created, so the
    // handler is re-registered without that block; the empty scheduled packet cannot be intercepted later, because
    // scheduleSend(Protocol1_12_2To1_13.class) starts the pipeline after that protocol.
    //
    // The rest is ViaVersion's own LOGIN handler (EntityPacketRewriter1_12.java:40-59) reproduced exactly: three
    // mapped fields and the client world environment update. The entity rewriter is this protocol's only LOGIN
    // registration - the shared LOGIN registration only exists from 1.20.5 on - so replacing drops nothing else.
    private static void applyRecipeReset(final ProtocolManager protocolManager) {
        final Protocol1_11_1To1_12 protocol = protocolManager.getProtocol(Protocol1_11_1To1_12.class);
        if (protocol == null || !protocol.hasRegisteredClientbound(ClientboundPackets1_9_3.LOGIN)) {
            ViaFabricPlusImpl.INSTANCE.getLogger()
                .warn("Protocol1_11_1To1_12 has no LOGIN handler, joining a <= 1.11.1 server will clear the recipe book");
            return;
        }

        protocol.replaceClientbound(ClientboundPackets1_9_3.LOGIN, wrapper -> {
            wrapper.passthrough(Types.INT); // Entity id
            wrapper.passthrough(Types.UNSIGNED_BYTE); // Game mode
            final int dimensionId = wrapper.passthrough(Types.INT); // Dimension

            wrapper.user().storables(protocol).clientWorld().setEnvironment(dimensionId);
        });
    }

    // Same barrier as ViaFabricPlusProtocolPatches#awaitMappings, which is private there. Blocks until each
    // protocol's registerPackets() has run, so the append and replace calls above cannot race it.
    // getMappingLoaderFuture returns null once the mappings are loaded, which is the "already done" case.
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
