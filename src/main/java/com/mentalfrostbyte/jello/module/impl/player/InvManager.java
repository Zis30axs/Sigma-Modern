package com.mentalfrostbyte.jello.module.impl.player;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.*;
import com.mentalfrostbyte.jello.util.game.InventoryRole;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class InvManager extends Module {
    private final InventoryLayoutSetting layout = register(new InventoryLayoutSetting());
    private final ItemListSetting whitelist = register(new ItemListSetting("Whitelist", "Always keep these items, including when also blacklisted."));
    private final ItemListSetting blacklist = register(new ItemListSetting("Blacklist", "Skip these when looting and discard them when cleanup is enabled."));
    private final BooleanSetting inventoryOnly = register(new BooleanSetting("Inventory Open", "Organize only while the survival inventory is visible.", true));
    private final BooleanSetting autoArmor = register(new BooleanSetting("Auto Armor", "Equip the highest-EHP armor pieces into the armor slots.", true));
    private final BooleanSetting merge = register(new BooleanSetting("Merge Stacks", "Consolidate compatible partial stacks.", true));
    private final BooleanSetting clean = register(new BooleanSetting("Cleanup", "Drop blacklisted stacks and apply enabled stock limits.", true));
    private final BooleanSetting duplicates = register(new BooleanSetting("Discard Spare Gear",
            "Discard lower-ranked or duplicate spare armor and gear after sorting. Named, whitelisted and unique Silk Touch/Fortune tools are kept.", false));
    private final BooleanSetting limits = register(new BooleanSetting("Stock Limits", "Limit looting and discard whole surplus stacks after sorting.", false));
    private final Map<InventoryRole, NumberSetting> caps = new EnumMap<>(InventoryRole.class);
    private final NumberSetting delay = register(new NumberSetting("Action Delay", "Ticks between inventory operations.", 2, 1, 20, 1));
    private int cooldown;

    public InvManager() {
        super(ModuleCategory.PLAYER, "InvManager", "Organizes the player inventory using slot assignments, item lists and optional stock limits.");
        cap(InventoryRole.BLOCK, "Blocks", 512, 2500);
        cap(InventoryRole.FOOD, "Food Points", 200, 2000);
        cap(InventoryRole.ARROW, "Arrows", 128, 2500);
        cap(InventoryRole.THROWABLE, "Throwables", 64, 600);
        cap(InventoryRole.WATER, "Water Buckets", 2, 16);
        cap(InventoryRole.LAVA, "Lava Buckets", 2, 16);
        cap(InventoryRole.MILK, "Milk Buckets", 2, 16);
        cap(InventoryRole.TOTEM, "Totems", 37, 37);
    }

    private void cap(InventoryRole role, String label, int start, int max) {
        var setting = register(new NumberSetting("Maximum " + label, "Stock limit for " + label.toLowerCase(java.util.Locale.ROOT) + ".", start, 0, max, 1));
        setting.visibleWhen(limits::get);
        caps.put(role, setting);
    }

    InventoryPlan.Policy policy() {
        var selected = new EnumMap<InventoryRole, Integer>(InventoryRole.class);
        if (limits.get()) caps.forEach((role, value) -> selected.put(role, value.getInt()));
        return new InventoryPlan.Policy(layout.get(), whitelist.get(), blacklist.get(), selected,
                InventoryRole.isLegacyCombat(), clean.get() && duplicates.get());
    }

    int disposableSlot(InventoryPlan.Policy policy) {
        if (!clean.get()) return -1;
        var slots = mc.player.inventoryMenu.slots.stream().map(Slot::getItem).toList();
        return InventoryPlan.disposableSlot(slots, policy, duplicates.get(), autoArmor.get());
    }

    @Override protected void onDisable() { cooldown = 0; }

    @EventTarget public void onTick(EventTick event) {
        if (!event.isPre()) return;
        var p = mc.player;
        if (p == null || mc.gameMode == null || !p.isAlive() || p.isSpectator() || p.hasInfiniteMaterials()
                || p.isUsingItem() || p.containerMenu != p.inventoryMenu || AutoPearl.isRescuing()
                || !(mc.gui.screen() instanceof InventoryScreen || mc.gui.screen() == null && !inventoryOnly.get())
                || !p.inventoryMenu.getCarried().isEmpty()) return;
        if (cooldown > 0) { cooldown--; return; }
        var menu = p.inventoryMenu;
        cooldown = delay.getInt() - 1;
        var action = InventoryPlan.next(menu.slots.stream().map(Slot::getItem).toList(),
                policy(), merge.get(), clean.get(), duplicates.get(), autoArmor.get());
        if (action == null) return;
        int from = action.from(), to = action.to();
        if (action.kind() == InventoryPlan.Kind.DROP) {
            click(menu, from, 1, ContainerInput.THROW);
        } else if (action.kind() == InventoryPlan.Kind.SWAP && to >= 5 && to <= 8
                && !menu.getSlot(to).hasItem() && from >= 9 && from <= 44) {
            click(menu, from, 0, ContainerInput.QUICK_MOVE);
        } else if (action.kind() == InventoryPlan.Kind.SWAP && to >= 36 && to <= 44) {
            click(menu, from, to - 36, ContainerInput.SWAP);
        } else if (action.kind() == InventoryPlan.Kind.SWAP && from >= 36 && from <= 44) {
            click(menu, to, from - 36, ContainerInput.SWAP);
        } else if (action.kind() == InventoryPlan.Kind.SWAP && InventoryPlan.needsSwapBuffer(from, to,
                menu.getSlot(from).getItem(), menu.getSlot(to).getItem())) {
            int button = InventoryPlan.bufferHotbar(layout.get()) - 36;
            if (button < 0) return;
            var source = menu.getSlot(from).getItem().copy();
            var held = menu.getSlot(36 + button);
            click(menu, from, button, ContainerInput.SWAP);
            if (p.containerMenu == menu && ItemStack.matches(held.getItem(), source)) {
                click(menu, to, button, ContainerInput.SWAP);
                if (p.containerMenu == menu) click(menu, from, button, ContainerInput.SWAP);
            }
        } else {
            // Normal pickup also works on versions with an offhand but without button-40 swaps.
            var source = menu.getSlot(from).getItem().copy();
            click(menu, from, 0, ContainerInput.PICKUP);
            if (p.containerMenu != menu || !ItemStack.matches(source, menu.getCarried())) return;
            click(menu, to, 0, ContainerInput.PICKUP);
            if (p.containerMenu == menu && !menu.getCarried().isEmpty() && !menu.getSlot(from).hasItem())
                click(menu, from, 0, ContainerInput.PICKUP);
        }
    }

    private static void click(InventoryMenu menu, int slot, int button, ContainerInput input) {
        mc.gameMode.handleContainerInput(menu.containerId, slot, button, input, mc.player);
    }
}
