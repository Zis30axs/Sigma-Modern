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

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.viafabricplus.ViaFabricPlusImpl;
import com.viaversion.viafabricplus.protocoltranslator.impl.ViaFabricPlusMappingDataLoader;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.protocols.v1_8to1_9.Protocol1_8To1_9;
import com.viaversion.viaversion.protocols.v1_8to1_9.packet.ClientboundPackets1_8;
import com.viaversion.viaversion.protocols.v1_8to1_9.packet.ServerboundPackets1_9;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * MODIFIED for porting: replaces ViaFabricPlus' {@code features/item/attack_damage/MixinItemPacketRewriter1_9},
 * whose {@code @Mixin} target is ViaVersion's {@code ItemPacketRewriter1_9} - the 1.8 -> 1.9 item rewriter - and
 * which therefore cannot be inlined into a Minecraft source file. That mixin gives every 1.8 tool and weapon its
 * 1.8 {@code AttributeModifiers} NBT on the way to the client, and an empty list to 1.8 armour, so a
 * {@code <= 1.8} server's items carry 1.8 damage numbers instead of the modern client's defaults. It takes both
 * off again on the way back to the server. The tooltip half of the same feature is already inlined
 * ({@code ItemStack#addAttributeTooltips} and {@code ItemAttributeModifiers.Display.Default#apply}) and is what
 * reads this NBT; armour points come from {@code ArmorHudEmulation1_8}, which pushes them as a
 * {@code generic.armor} attribute instead of leaving them on the items - which is why the empty list on armour is
 * upstream's intent and not a hole in its data.
 *
 * <p>ViaVersion has no setter for a protocol's item rewriter, and the rewriter's own packet handlers close over
 * {@code this}, so replacing or subclassing it could not reach the inventory packets anyway. What is reachable is
 * the packet level: {@code handleItemToClient} mutates the {@link Item} in place and every caller ignores its
 * return value, so appending a handler to the packet that read the item lands on the same object at the same
 * point in the pipeline - the item is serialised only when the wrapper is written, after every handler has run.
 *
 * <p>Covered call sites (viaversion-common 5.12.0-SNAPSHOT sources), i.e. all six places this protocol calls
 * {@code handleItemToClient} or {@code handleItemToServer} on an item that can reach the table:
 * {@code ItemPacketRewriter1_9} CONTAINER_SET_SLOT (:133) and CONTAINER_SET_CONTENT (:173) and
 * {@code EntityPacketRewriter1_9} SET_EQUIPPED_ITEM (:189) clientbound; the entity-data ITEM case of
 * {@code EntityPacketRewriter1_9#handleEntityData} (:457), reached from SET_ENTITY_DATA and from the two
 * spawn packets that carry entity data, ADD_MOB and ADD_PLAYER; plus SET_CREATIVE_MODE_SLOT (:255) and
 * CONTAINER_CLICK (:299) serverbound, which are the protocol's only two {@code handleItemToServer} call
 * sites. The seventh call site, the potion item {@code SpawnPacketRewriter1_9} builds inside a
 * {@code wrapper.create(...)} it sends itself (:112), provably cannot produce this data: it is
 * {@code new DataItem(373, ...)} and item-identifiers-1.8.json only holds ids 256-317, so both upstream's map
 * lookup and the array below miss it. That packet is also unreachable through the public API, because a
 * packet created and sent from Protocol1_8To1_9 does not re-enter that protocol's own handlers.
 *
 * <p>See {@link ViaFabricPlusProtocolPatches} for the mapping-loader timing rule this package follows;
 * {@link #apply()} records why this particular protocol needs no barrier.
 */
public final class ItemAttackDamagePatches {

    private ItemAttackDamagePatches() {
    }

    public static void apply() {
        // No mapping-loader barrier is needed here, unlike the patches in ViaFabricPlusProtocolPatches:
        // Protocol1_8To1_9 overrides neither getMappingData() nor hasMappingDataToLoad(), so the latter is false
        // and ProtocolManagerImpl.java:261-263 runs initialize() - and with it registerPackets() - on the
        // registering thread, inside ViaManagerImpl.initAndLoad. apply() is called after that returns, so the
        // handlers the appends below attach to are already there.
        final ProtocolManager protocolManager = Via.getManager().getProtocolManager();

        final Protocol1_8To1_9 protocol = protocolManager.getProtocol(Protocol1_8To1_9.class);
        if (protocol == null) {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocol1_8To1_9 is not registered, 1.8 items will keep their modern attribute values");
            return;
        }

        final CompoundTag[][] attributeModifiersByItemId = loadAttributeModifiers();
        if (attributeModifiersByItemId == null) {
            return;
        }

        // The marker tag name has to match upstream's byte for byte, because the serverbound half looks it up
        // again. Upstream built it with the rewriter's own ItemRewriter#nbtTagName("attributeFix")
        // ("VV|" + protocol class simple name + "|attributeFix"), so it is taken from that same rewriter here.
        final String attributeFixTagName = protocol.getItemRewriter().nbtTagName("attributeFix");

        // was VFP features/item/attack_damage/MixinItemPacketRewriter1_9#addAttributeFixData
        // (@Inject handleItemToClient RETURN). Applies to every target that goes through Protocol1_8To1_9, i.e.
        // 1.8 and older including the ViaLegacy chain - upstream has no further version gate.
        // Appending is equivalent to injecting at that method's RETURN: ViaVersion's handler chain calls
        // handleItemToClient and discards the returned item (ItemPacketRewriter1_9.java:133 and :173,
        // EntityPacketRewriter1_9.java:189), and an appended handler runs after the whole chain - including the
        // brewing-stand patch, which only reshuffles slots and reuses the same Item objects - on the same
        // instance still held in the wrapper's packet values.
        protocol.appendClientbound(ClientboundPackets1_8.CONTAINER_SET_SLOT,
            wrapper -> addAttributeFixData(wrapper.get(Types.ITEM1_8, 0), attributeModifiersByItemId, attributeFixTagName));
        protocol.appendClientbound(ClientboundPackets1_8.CONTAINER_SET_CONTENT, wrapper -> {
            for (final Item item : wrapper.get(Types.ITEM1_8_SHORT_ARRAY, 0)) {
                addAttributeFixData(item, attributeModifiersByItemId, attributeFixTagName);
            }
        });
        protocol.appendClientbound(ClientboundPackets1_8.SET_EQUIPPED_ITEM,
            wrapper -> addAttributeFixData(wrapper.get(Types.ITEM1_8, 0), attributeModifiersByItemId, attributeFixTagName));

        // Same hook, entity-data half: the ITEM branch of EntityPacketRewriter1_9#handleEntityData
        // (EntityPacketRewriter1_9.java:457) calls the very same handleItemToClient - discarding its return
        // value like the other callers - so upstream's RETURN inject fires there too. The three 1.8 packets
        // that run that filter are SET_ENTITY_DATA (EntityPacketRewriter1_9.java:217) and the two spawn
        // packets that carry entity data, ADD_MOB (SpawnPacketRewriter1_9.java:207) and ADD_PLAYER (:285);
        // all three map the list with map(Types.ENTITY_DATA_LIST1_8, Types.ENTITY_DATA_LIST1_9), which stores
        // it under the mapped type, and the per-entry 1.8 -> 1.9 conversion happens in that filter, before an
        // appended handler runs - so one handler covers all three and sees the converted entries. Only an
        // ITEM-typed entry ever holds an Item, and EntityDataIndex1_9 has exactly three of those - ITEM_ITEM,
        // FIREWORK_ROCKET_INFO and ITEM_FRAME_ITEM - so the instanceof is an exact stand-in for the filter's
        // `case ITEM` without importing the type table.
        final PacketHandler entityDataItemHandler = wrapper -> {
            for (final EntityData entityData : wrapper.get(Types.ENTITY_DATA_LIST1_9, 0)) {
                if (entityData.getValue() instanceof Item item) {
                    addAttributeFixData(item, attributeModifiersByItemId, attributeFixTagName);
                }
            }
        };
        protocol.appendClientbound(ClientboundPackets1_8.SET_ENTITY_DATA, entityDataItemHandler);
        protocol.appendClientbound(ClientboundPackets1_8.ADD_MOB, entityDataItemHandler);
        protocol.appendClientbound(ClientboundPackets1_8.ADD_PLAYER, entityDataItemHandler);

        // was VFP features/item/attack_damage/MixinItemPacketRewriter1_9#removeAttributeFixData
        // (@Inject handleItemToServer RETURN). Both handleItemToServer call sites are packet handlers
        // (ItemPacketRewriter1_9.java:255 SET_CREATIVE_MODE_SLOT and :299 CONTAINER_CLICK), so the serverbound
        // half is complete. Running after ViaVersion's own toServer conversion rather than before it is what the
        // RETURN inject did, and it matters: that conversion drops a tag it has emptied itself
        // (tag.isEmpty() -> setTag(null)) and has to still see the injected tags while it runs.
        protocol.appendServerbound(ServerboundPackets1_9.SET_CREATIVE_MODE_SLOT,
            wrapper -> removeAttributeFixData(wrapper.get(Types.ITEM1_8, 0), attributeFixTagName));
        protocol.appendServerbound(ServerboundPackets1_9.CONTAINER_CLICK,
            wrapper -> removeAttributeFixData(wrapper.get(Types.ITEM1_8, 0), attributeFixTagName));
    }

    // was VFP features/item/attack_damage/MixinItemPacketRewriter1_9#addAttributeFixData, second half: the item
    // side of that hook, kept in one place so the clientbound appends above stay readable. Verbatim upstream
    // logic - marker tag first, then the modifier list only when the item has none of its own, and the two
    // RemoveTag / RemoveAttributeModifiers flags that tell the serverbound half how much to undo.
    private static void addAttributeFixData(
        final @Nullable Item item,
        final CompoundTag[][] attributeModifiersByItemId,
        final String attributeFixTagName
    ) {
        if (item == null) {
            return;
        }

        final int identifier = item.identifier();
        if (identifier < 0 || identifier >= attributeModifiersByItemId.length) {
            return;
        }
        final CompoundTag[] attributes = attributeModifiersByItemId[identifier];
        if (attributes == null) {
            return;
        }

        final CompoundTag attributeFixTag = new CompoundTag();
        CompoundTag tag = item.tag();
        if (tag == null) {
            tag = new CompoundTag();
            item.setTag(tag);
            attributeFixTag.putBoolean("RemoveTag", true);
        }
        tag.put(attributeFixTagName, attributeFixTag);

        if (tag.getListTag("AttributeModifiers", CompoundTag.class) == null) {
            final ListTag<CompoundTag> attributeModifiersTag = new ListTag<>(CompoundTag.class);
            for (final CompoundTag attribute : attributes) {
                attributeModifiersTag.add(attribute.copy());
            }
            tag.put("AttributeModifiers", attributeModifiersTag);
            attributeFixTag.putBoolean("RemoveAttributeModifiers", true);
        }
    }

    // was VFP features/item/attack_damage/MixinItemPacketRewriter1_9#removeAttributeFixData, item side.
    // Verbatim upstream logic, including removeUnchecked - an item whose marker tag is not a compound is left to
    // fail the same way it would upstream instead of being silently tolerated.
    private static void removeAttributeFixData(final @Nullable Item item, final String attributeFixTagName) {
        if (item == null) {
            return;
        }
        final CompoundTag tag = item.tag();
        if (tag == null) {
            return;
        }
        final CompoundTag attributeFixTag = tag.removeUnchecked(attributeFixTagName);
        if (attributeFixTag == null) {
            return;
        }

        if (attributeFixTag.contains("RemoveAttributeModifiers")) {
            tag.remove("AttributeModifiers");
        }
        if (attributeFixTag.contains("RemoveTag")) {
            item.setTag(null);
        }
    }

    // was VFP features/item/attack_damage/MixinItemPacketRewriter1_9#loadAdditionalData (@Inject <init> RETURN)
    // together with the two @Unique fields it fills: viaFabricPlus$itemIdentifiers, 1.8 item id -> identifier,
    // and viaFabricPlus$itemAttributes, identifier -> attribute name -> slot plus 1.8 ModifierData. Loading here
    // is the same single load as upstream's: Protocol1_8To1_9 creates exactly one ItemPacketRewriter1_9
    // (Protocol1_8To1_9.java:62, a final field initialised inline), and apply() runs once, after it.
    //
    // The two tables are collapsed into one array indexed by the 1.8 item id, holding each item's
    // AttributeModifiers entries as finished NBT. The identifier map was only ever a step towards the attribute
    // table, and each ModifierData was only ever read back out into these seven tags, so nothing observable
    // changes. An id with no attribute entry keeps a null slot - upstream's
    // "identifier == null || !containsKey(identifier)" case - and an id whose entry is an empty object keeps a
    // zero-length array, which is upstream's empty attribute map: 25 of the 45 entries are that - the 20 armour
    // pieces and the 5 hoes - and they still have to get the marker tag and an empty AttributeModifiers list.
    private static @Nullable CompoundTag[][] loadAttributeModifiers() {
        final JsonObject itemIdentifiers = ViaFabricPlusMappingDataLoader.INSTANCE.loadData("item-identifiers-1.8.json");
        final JsonObject itemAttributes = ViaFabricPlusMappingDataLoader.INSTANCE.loadData("item-attributes-1.8.json");
        if (itemIdentifiers == null || itemAttributes == null) {
            ViaFabricPlusImpl.INSTANCE.getLogger()
                .error("item-identifiers-1.8.json or item-attributes-1.8.json is missing, 1.8 items will keep their modern attribute values");
            return null;
        }

        int highestItemId = 0;
        for (final Map.Entry<String, JsonElement> identifierEntry : itemIdentifiers.entrySet()) {
            highestItemId = Math.max(highestItemId, identifierEntry.getValue().getAsInt());
        }

        final CompoundTag[][] attributeModifiers = new CompoundTag[highestItemId + 1][];
        for (final Map.Entry<String, JsonElement> identifierEntry : itemIdentifiers.entrySet()) {
            final JsonElement attributes = itemAttributes.get(identifierEntry.getKey());
            if (attributes == null) {
                continue;
            }

            final List<CompoundTag> modifiers = new ArrayList<>();
            for (final Map.Entry<String, JsonElement> attributeEntry : attributes.getAsJsonObject().entrySet()) {
                final JsonObject attributeData = attributeEntry.getValue().getAsJsonObject();
                final UUID uuid = UUID.fromString(attributeData.get("id").getAsString());

                final CompoundTag modifier = new CompoundTag();
                modifier.putString("AttributeName", attributeEntry.getKey());
                modifier.putString("Name", attributeData.get("name").getAsString());
                modifier.putDouble("Amount", attributeData.get("amount").getAsDouble());
                modifier.putInt("Operation", attributeData.get("operation").getAsInt());
                modifier.putLong("UUIDMost", uuid.getMostSignificantBits());
                modifier.putLong("UUIDLeast", uuid.getLeastSignificantBits());
                modifier.putString("Slot", attributeData.get("slot").getAsString());
                modifiers.add(modifier);
            }
            attributeModifiers[identifierEntry.getValue().getAsInt()] = modifiers.toArray(new CompoundTag[0]);
        }
        return attributeModifiers;
    }

}
