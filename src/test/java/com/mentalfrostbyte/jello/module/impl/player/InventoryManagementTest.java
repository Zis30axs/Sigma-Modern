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
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InventoryManagementTest {
    private static HolderLookup.RegistryLookup<Enchantment> enchantments;

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup).forEach(components -> components.apply());
        enchantments = lookup.lookupOrThrow(Registries.ENCHANTMENT);
    }

    private static ArrayList<ItemStack> bag() { return new ArrayList<>(Collections.nCopies(46, ItemStack.EMPTY)); }

    private static InventoryPlan.Policy policy(Map<Integer, String> rules) {
        return new InventoryPlan.Policy(rules, Set.of(), Set.of(), Map.of());
    }

    private static InventoryPlan.Policy policy(Map<Integer, String> rules, boolean legacyCombat, boolean discardSpareGear) {
        return new InventoryPlan.Policy(rules, Set.of(), Set.of(), Map.of(), legacyCombat, discardSpareGear);
    }

    private static ItemStack enchanted(Item item, ResourceKey<Enchantment> key, int level) {
        ItemStack stack = new ItemStack(item);
        stack.enchant(enchantments.getOrThrow(key), level);
        return stack;
    }

    private static ItemStack enchant(ItemStack stack, ResourceKey<Enchantment> key, int level) {
        stack.enchant(enchantments.getOrThrow(key), level);
        return stack;
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

    @Test void diamondProtectionOneBeatsChainmailProtectionThreeAcrossVersionsAndContexts() {
        Item[][] pairs = {
            { Items.CHAINMAIL_HELMET, Items.DIAMOND_HELMET },
            { Items.CHAINMAIL_CHESTPLATE, Items.DIAMOND_CHESTPLATE },
            { Items.CHAINMAIL_LEGGINGS, Items.DIAMOND_LEGGINGS },
            { Items.CHAINMAIL_BOOTS, Items.DIAMOND_BOOTS }
        };
        int[] armorSlots = { 5, 6, 7, 8 };

        for (boolean legacyCombat : new boolean[] { true, false }) {
            for (int idx = 0; idx < pairs.length; idx++) {
                int armorSlot = armorSlots[idx];
                ItemStack chainProt3 = enchanted(pairs[idx][0], Enchantments.PROTECTION, 3);
                ItemStack diamondProt1 = enchanted(pairs[idx][1], Enchantments.PROTECTION, 1);

                // 1) Naked / early-game looting: Chainmail Prot 3 already worn, Diamond Prot 1 in backpack.
                var naked = bag();
                naked.set(armorSlot, chainProt3.copy());
                naked.set(12, diamondProt1.copy());
                var p = policy(Map.of(), legacyCombat, true);

                var equip = InventoryPlan.next(naked, p, true, true, true, true);
                assertEquals(new InventoryPlan.Move(InventoryPlan.Kind.SWAP, 12, armorSlot), equip,
                        "Expected Diamond Prot 1 to replace Chainmail Prot 3 at slot " + armorSlot + " (legacy=" + legacyCombat + ")");
                Collections.swap(naked, equip.from(), equip.to());

                var drop = InventoryPlan.next(naked, p, true, true, true, true);
                assertEquals(new InventoryPlan.Move(InventoryPlan.Kind.DROP, 12, -1), drop,
                        "Expected spare Chainmail Prot 3 to be discarded after equipping Diamond Prot 1 (legacy=" + legacyCombat + ")");
                naked.set(12, ItemStack.EMPTY);
                assertNull(InventoryPlan.next(naked, p, true, true, true, true));

                // 2) Full Netherite Prot 4 worn in the other 3 armor slots.
                var geared = bag();
                geared.set(5, enchanted(Items.NETHERITE_HELMET, Enchantments.PROTECTION, 4));
                geared.set(6, enchanted(Items.NETHERITE_CHESTPLATE, Enchantments.PROTECTION, 4));
                geared.set(7, enchanted(Items.NETHERITE_LEGGINGS, Enchantments.PROTECTION, 4));
                geared.set(8, enchanted(Items.NETHERITE_BOOTS, Enchantments.PROTECTION, 4));
                geared.set(armorSlot, chainProt3.copy());
                geared.set(15, diamondProt1.copy());

                var gearedSwap = InventoryPlan.next(geared, p, true, true, true, true);
                assertEquals(new InventoryPlan.Move(InventoryPlan.Kind.SWAP, 15, armorSlot), gearedSwap);
            }
        }
    }

    @Test void bindingCurseAndElytraAreHandledSafely() {
        var p = policy(Map.of(), false, true);

        // Never equip Curse of Binding armor into an empty slot, and discard it when cleanup runs.
        var cursedBag = bag();
        cursedBag.set(10, enchanted(Items.DIAMOND_CHESTPLATE, Enchantments.BINDING_CURSE, 1));
        assertEquals(new InventoryPlan.Move(InventoryPlan.Kind.DROP, 10, -1),
                InventoryPlan.next(cursedBag, p, true, true, true, true));

        // Never try to unequip worn Curse of Binding armor; still keep #1 replacement in backpack and drop worse spares.
        var stuckWorn = bag();
        stuckWorn.set(5, enchanted(Items.LEATHER_HELMET, Enchantments.BINDING_CURSE, 1));
        stuckWorn.set(10, enchanted(Items.DIAMOND_HELMET, Enchantments.PROTECTION, 2));
        stuckWorn.set(11, enchanted(Items.CHAINMAIL_HELMET, Enchantments.PROTECTION, 3));
        assertEquals(new InventoryPlan.Move(InventoryPlan.Kind.DROP, 11, -1),
                InventoryPlan.next(stuckWorn, p, true, true, true, true));
        stuckWorn.set(11, ItemStack.EMPTY);
        assertNull(InventoryPlan.next(stuckWorn, p, true, true, true, true));

        // Never overwrite an equipped Elytra; keep #1 backup chestplate and discard inferior chestplates.
        var flying = bag();
        flying.set(6, new ItemStack(Items.ELYTRA));
        flying.set(10, enchanted(Items.DIAMOND_CHESTPLATE, Enchantments.PROTECTION, 1));
        flying.set(11, enchanted(Items.CHAINMAIL_CHESTPLATE, Enchantments.PROTECTION, 3));
        assertEquals(new InventoryPlan.Move(InventoryPlan.Kind.DROP, 11, -1),
                InventoryPlan.next(flying, p, true, true, true, true));
        flying.set(11, ItemStack.EMPTY);
        assertNull(InventoryPlan.next(flying, p, true, true, true, true));
    }

    @Test void versionAwareWeaponScoringHandlesCooldownAndLegacySharpness() {
        ItemStack stoneAxe = new ItemStack(Items.STONE_AXE);
        ItemStack diamondAxe = new ItemStack(Items.DIAMOND_AXE);
        ItemStack sharp2DiamondSword = enchanted(Items.DIAMOND_SWORD, Enchantments.SHARPNESS, 2);

        // Modern 1.9+: DPS makes Sharpness II Diamond Sword beat Stone Axe despite Stone Axe's 9 raw damage.
        assertTrue(InventoryRole.score("WEAPON", sharp2DiamondSword, null, false)
                > InventoryRole.score("WEAPON", stoneAxe, null, false));

        var bag = bag();
        bag.set(10, stoneAxe);
        bag.set(11, diamondAxe);
        bag.set(12, sharp2DiamondSword);
        var p = policy(Map.of(36, "WEAPON", 39, "AXE"), false, true);

        // Specific AXE rule (priority 1) claims diamondAxe first, then broad WEAPON rule (priority 2) claims sharp2DiamondSword over stoneAxe.
        var first = InventoryPlan.next(bag, p, true, true, true, false);
        assertEquals(new InventoryPlan.Move(InventoryPlan.Kind.SWAP, 11, 39), first);
        Collections.swap(bag, first.from(), first.to());

        var second = InventoryPlan.next(bag, p, true, true, true, false);
        assertEquals(new InventoryPlan.Move(InventoryPlan.Kind.SWAP, 12, 36), second);
        Collections.swap(bag, second.from(), second.to());

        // Stone Axe is now redundant to both WEAPON and AXE and gets dropped.
        var third = InventoryPlan.next(bag, p, true, true, true, false);
        assertEquals(new InventoryPlan.Move(InventoryPlan.Kind.DROP, 10, -1), third);

        // Legacy 1.8: Sharpness II Iron Sword (7 + 2.5 = 9.5) beats unenchanted Diamond Sword (8.0).
        ItemStack sharp2IronSword = enchanted(Items.IRON_SWORD, Enchantments.SHARPNESS, 2);
        ItemStack plainDiamondSword = new ItemStack(Items.DIAMOND_SWORD);
        assertTrue(InventoryRole.score("SWORD", sharp2IronSword, null, true)
                > InventoryRole.score("SWORD", plainDiamondSword, null, true));
    }

    @Test void toolScoringRejectsFragileGoldAndPreservesDistinctSilkTouchAndFortune() {
        ItemStack goldPick = new ItemStack(Items.GOLDEN_PICKAXE);
        ItemStack ironPick = new ItemStack(Items.IRON_PICKAXE);
        assertTrue(InventoryRole.score("PICKAXE", ironPick) > InventoryRole.score("PICKAXE", goldPick));

        ItemStack fortuneBest = enchant(enchanted(Items.DIAMOND_PICKAXE, Enchantments.EFFICIENCY, 5), Enchantments.FORTUNE, 3);
        ItemStack silkBest = enchant(enchanted(Items.DIAMOND_PICKAXE, Enchantments.EFFICIENCY, 4), Enchantments.SILK_TOUCH, 1);
        ItemStack silkWorse = enchant(enchanted(Items.DIAMOND_PICKAXE, Enchantments.EFFICIENCY, 1), Enchantments.SILK_TOUCH, 1);

        var bag = bag();
        bag.set(38, fortuneBest);
        bag.set(10, silkBest);
        bag.set(11, silkWorse);
        bag.set(12, ironPick);
        var p = policy(Map.of(38, "PICKAXE"), false, true);

        // silkBest (slot 10) is preserved for Silk Touch; silkWorse (slot 11) and ironPick (slot 12) are dropped.
        assertEquals(new InventoryPlan.Move(InventoryPlan.Kind.DROP, 11, -1),
                InventoryPlan.next(bag, p, true, true, true, false));
        bag.set(11, ItemStack.EMPTY);
        assertEquals(new InventoryPlan.Move(InventoryPlan.Kind.DROP, 12, -1),
                InventoryPlan.next(bag, p, true, true, true, false));
        bag.set(12, ItemStack.EMPTY);
        assertNull(InventoryPlan.next(bag, p, true, true, true, false));
    }

    @Test void chestLootFilterAndMidChestDisposableSlotRespectArmorAndUnsortedKeepers() {
        var p = policy(Map.of(36, "WEAPON"), false, true);
        List<ItemStack> owned = List.of(enchanted(Items.DIAMOND_CHESTPLATE, Enchantments.PROTECTION, 1));

        assertFalse(p.allowsLoot(enchanted(Items.CHAINMAIL_CHESTPLATE, Enchantments.PROTECTION, 3), owned));
        assertFalse(p.allowsLoot(enchanted(Items.DIAMOND_CHESTPLATE, Enchantments.BINDING_CURSE, 1), owned));
        assertTrue(p.allowsLoot(enchanted(Items.DIAMOND_CHESTPLATE, Enchantments.PROTECTION, 2), owned));

        // Even while the best sword is still unsorted at slot 20, disposableSlot protects slot 20 and drops worse sword at 15.
        var unsorted = bag();
        unsorted.set(15, new ItemStack(Items.WOODEN_SWORD));
        unsorted.set(20, new ItemStack(Items.DIAMOND_SWORD));
        assertEquals(15, InventoryPlan.disposableSlot(unsorted, p, true, true));
    }
}
