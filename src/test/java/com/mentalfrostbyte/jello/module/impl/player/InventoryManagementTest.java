package com.mentalfrostbyte.jello.module.impl.player;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.setting.InventoryLayoutSetting;
import com.mentalfrostbyte.jello.setting.ItemListSetting;
import com.mentalfrostbyte.jello.util.game.InventoryRole;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InventoryManagementTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(components -> components.apply());
    }

    private static ArrayList<ItemStack> bag() { return new ArrayList<>(Collections.nCopies(46, ItemStack.EMPTY)); }

    private static InventoryPlan.Policy policy(Map<Integer, String> rules) {
        return new InventoryPlan.Policy(rules, Set.of(), Set.of(), Map.of());
    }

    @Test void bothModulesRegisterAsPlayerModulesWithPersistentEditors() {
        var manager = new ModuleManager();
        manager.registerAll();
        assertEquals(ModuleCategory.PLAYER, manager.find("cheststeal").orElseThrow().getCategory());
        assertEquals(ModuleCategory.PLAYER, manager.find("invmanager").orElseThrow().getCategory());
        var layout = new InventoryLayoutSetting();
        layout.assign(9, "minecraft:diamond");
        layout.assign(45, "LOCK");
        layout.assign(44, "NONE");
        var saved = layout.toJson().getAsJsonObject();
        saved.addProperty("0", "minecraft:stone");
        saved.addProperty("10", "minecraft:unknown_item");
        var loaded = new InventoryLayoutSetting();
        assertTrue(loaded.fromJson(saved));
        assertEquals(layout.get(), loaded.get());
        var list = new ItemListSetting("Blacklist", "");
        assertTrue(list.fromJson(JsonParser.parseString("[\"minecraft:diamond\",\"minecraft:unknown_item\",\"minecraft:air\"]")));
        assertEquals(Set.of("minecraft:diamond"), list.get());
        for (var role : InventoryRole.values()) assertTrue(ItemListSetting.registeredItem(role.icon), role.icon);
    }

    @Test void exactRuleWinsOverBroadRuleAndLocksNeverMove() {
        var bag = bag();
        bag.set(9, new ItemStack(Items.GOLDEN_APPLE));
        bag.set(10, new ItemStack(Items.BREAD, 8));
        bag.set(11, new ItemStack(Items.BREAD, 64));
        var policy = policy(Map.of(36, "FOOD", 24, "minecraft:golden_apple", 11, "LOCK"));
        var first = InventoryPlan.next(bag, policy, true, true, true);
        assertEquals(new InventoryPlan.Move(InventoryPlan.Kind.SWAP, 9, 24), first);
        Collections.swap(bag, first.from(), first.to());
        var second = InventoryPlan.next(bag, policy, true, true, true);
        assertEquals(new InventoryPlan.Move(InventoryPlan.Kind.SWAP, 10, 36), second);
        Collections.swap(bag, second.from(), second.to());
        assertNull(InventoryPlan.next(bag, policy, true, true, true));
    }

    @Test void identicalAssignedStacksStayInPlaceAndUnassignedStacksMerge() {
        var bag = bag();
        bag.set(36, new ItemStack(Items.STONE, 4));
        bag.set(37, new ItemStack(Items.STONE, 42));
        bag.set(12, new ItemStack(Items.STONE, 10));
        var p = policy(Map.of(36, "BLOCK", 37, "BLOCK"));
        var move = InventoryPlan.next(bag, p, true, false, false);
        assertEquals(new InventoryPlan.Move(InventoryPlan.Kind.MERGE, 12, 36), move);
        bag.get(36).grow(10);
        bag.set(12, ItemStack.EMPTY);
        assertNull(InventoryPlan.next(bag, p, true, false, false));
    }

    @Test void lockedHotbarPreventsBundlePickupFromPackingAnotherAssignedStack() {
        var bag = bag();
        bag.set(10, new ItemStack(Items.BUNDLE));
        bag.set(9, new ItemStack(Items.DIAMOND));
        assertTrue(InventoryPlan.needsSwapBuffer(10, 9, bag.get(10), bag.get(9)));
        var rules = new java.util.HashMap<Integer, String>();
        rules.put(9, "minecraft:bundle");
        for (int i = 36; i <= 44; i++) rules.put(i, "LOCK");
        assertNull(InventoryPlan.next(bag, policy(rules), true, true, false));
        rules.remove(44);
        assertEquals(44, InventoryPlan.bufferHotbar(rules));
        assertEquals(new InventoryPlan.Move(InventoryPlan.Kind.SWAP, 10, 9), InventoryPlan.next(bag, policy(rules), true, true, false));
    }

    @Test void noOffhandMenuDoesNotConsumeCraftingOrArmorSlots() {
        var old = bag();
        old.removeLast();
        old.set(5, new ItemStack(Items.SHIELD));
        old.set(9, new ItemStack(Items.SHIELD));
        var p = policy(Map.of(45, "SHIELD"));
        assertNull(InventoryPlan.next(old, p, true, true, false));
        old.add(ItemStack.EMPTY);
        assertEquals(new InventoryPlan.Move(InventoryPlan.Kind.SWAP, 9, 45), InventoryPlan.next(old, p, true, true, false));
    }

    @Test void blacklistPrecedenceAndWholeStackQuotas() {
        var bag = bag();
        bag.set(9, new ItemStack(Items.DIAMOND));
        bag.set(10, new ItemStack(Items.DIRT));
        bag.set(11, new ItemStack(Items.SNOWBALL, 10));
        bag.set(12, new ItemStack(Items.SNOWBALL, 16));
        var p = new InventoryPlan.Policy(Map.of(), Set.of("minecraft:diamond"), Set.of("minecraft:diamond", "minecraft:dirt"),
                Map.of(InventoryRole.THROWABLE, 16));
        assertEquals(10, InventoryPlan.next(bag, p, false, true, false).from());
        bag.set(10, ItemStack.EMPTY);
        assertEquals(11, InventoryPlan.next(bag, p, false, true, false).from());
        bag.set(11, ItemStack.EMPTY);
        assertNull(InventoryPlan.next(bag, p, false, true, false));
        assertFalse(p.allowsLoot(new ItemStack(Items.EGG), bag));
        assertTrue(p.allowsLoot(new ItemStack(Items.DIAMOND), bag));
        assertFalse(ChestSteal.accepts("minecraft:diamond", true, p.keep(), p.trash()));
        assertFalse(ChestSteal.accepts("minecraft:stone", true, p.keep(), p.trash()));
    }

    @Test void realChestWaitsForItsOwnInitialSnapshotBeforeClosing() {
        var player = new net.minecraft.world.entity.player.Inventory(null, new net.minecraft.world.entity.EntityEquipment());
        var menu = net.minecraft.world.inventory.ChestMenu.threeRows(4, player);
        assertFalse(menu.hasReceivedInitialContents());
        var incoming = new ArrayList<>(Collections.nCopies(menu.slots.size(), ItemStack.EMPTY));
        incoming.set(26, new ItemStack(Items.DIAMOND, 4));
        menu.initializeContents(0, incoming, ItemStack.EMPTY);
        assertTrue(menu.hasReceivedInitialContents());
        assertEquals(4, menu.getSlot(26).getItem().getCount());
        var fresh = net.minecraft.world.inventory.ChestMenu.threeRows(4, player);
        assertFalse(fresh.hasReceivedInitialContents());
    }

    @Test void fullContainerStillMergesAndRespectsPlayerSlotIdsAndLocks() {
        var inv = new SimpleContainer(36);
        var hotbar = new Slot(inv, 0, 0, 0);
        var backpack = new Slot(inv, 9, 0, 0);
        var slots = List.of(backpack, hotbar);
        var stone = new ItemStack(Items.STONE, 8);
        assertSame(hotbar, ChestSteal.destination(stone, slots, Map.of(36, "minecraft:stone")));
        backpack.set(new ItemStack(Items.STONE, 60));
        assertSame(backpack, ChestSteal.destination(stone, slots, Map.of(36, "LOCK")));
        backpack.set(new ItemStack(Items.STONE, 64));
        assertNull(ChestSteal.destination(stone, slots, Map.of(36, "LOCK")));
        assertNull(ChestSteal.destination(new ItemStack(Items.DIAMOND), slots, Map.of(36, "BLOCK")));
        assertTrue(ChestSteal.storageTitle(Component.translatable("container.chestDouble")));
        assertFalse(ChestSteal.storageTitle(Component.literal("Shop")));
    }
}
