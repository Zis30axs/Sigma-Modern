package com.mentalfrostbyte.jello.module.impl.player;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.setting.*;
import com.mentalfrostbyte.jello.util.game.InventoryRole;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

public final class ChestSteal extends Module {
    enum MoveMode { QUICK_MOVE, PICKUP }
    enum Order { NEAREST, INDEX, REVERSE, RANDOM }
    private final ItemListSetting whitelist = register(new ItemListSetting("Whitelist", "Items allowed when Whitelist Only is enabled."));
    private final ItemListSetting blacklist = register(new ItemListSetting("Blacklist", "Never take these items, even if whitelisted."));
    private final BooleanSetting only = register(new BooleanSetting("Whitelist Only", "Take only items in the whitelist.", false));
    private final BooleanSetting respect = register(new BooleanSetting("Use InvManager", "Respect an enabled InvManager's blacklist and stock limits.", true));
    private final ChestAura aura = new ChestAura();
    private final BooleanSetting autoOpen = register(new BooleanSetting("Chest Aura", "Open nearby visible storage containers once.", false));
    private final NumberSetting range = register(new NumberSetting("Open Range", "Reach for opening visible containers.", 3, 1, 6, 0.25F));
    private final BooleanSetting preview = register(new BooleanSetting("Compact View", "Show the live chest contents in a compact panel while looting.", false));
    private final BooleanSetting named = register(new BooleanSetting("Check Title", "Only loot containers with a standard storage title. Skip custom server menus.", true));
    private final BooleanSetting close = register(new BooleanSetting("Auto Close", "Close the container when no more eligible items fit.", true));
    private final BooleanSetting shulkers = register(new BooleanSetting("Shulker Boxes", "Also loot opened shulker boxes.", true));
    private final BooleanSetting makeRoom = register(new BooleanSetting("Make Room", "When full, allow enabled InvManager cleanup to discard one unwanted stack for eligible loot.", false));
    private final EnumSetting<MoveMode> moveMode = register(new EnumSetting<>("Move Mode", "Quick move or pick up and place into a destination slot.", MoveMode.QUICK_MOVE));
    private final EnumSetting<Order> order = register(new EnumSetting<>("Order", "Choose the next container slot.", Order.NEAREST));
    private final NumberSetting delay = register(new NumberSetting("Click Delay", "Ticks between transfers.", 2, 1, 20, 1));
    private @Nullable AbstractContainerMenu opened;
    private int cooldown, last;

    public ChestSteal() {
        super(ModuleCategory.PLAYER, "ChestSteal", "Transfers filtered items from opened storage containers into the player inventory.");
        range.visibleWhen(autoOpen::get);
    }

    @Override protected void onDisable() { opened = null; aura.reset(); }
    @EventTarget public void onWorld(EventLoadWorld event) { opened = null; aura.reset(); }

    public static boolean compact(AbstractContainerScreen<?> screen) {
        var module = Modules.enabled(ChestSteal.class);
        return module != null && module.preview.get() && module.supported(screen.getMenu())
                && (!module.named.get() || storageTitle(screen.getTitle()));
    }

    private boolean supported(AbstractContainerMenu menu) {
        return menu instanceof ChestMenu || shulkers.get() && menu instanceof ShulkerBoxMenu;
    }

    static boolean accepts(String id, boolean only, Set<String> whitelist, Set<String> blacklist) {
        return !blacklist.contains(id) && (!only || whitelist.contains(id));
    }

    @EventTarget public void onTick(EventTick event) {
        if (!event.isPre()) return;
        var p = mc.player;
        if (p == null || mc.gameMode == null || !p.isAlive() || p.isSpectator() || AutoPearl.isRescuing()) return;
        if (mc.gui.screen() == null && p.containerMenu == p.inventoryMenu && autoOpen.get())
            aura.tick(mc, range.get(), shulkers.get());
        if (!(mc.gui.screen() instanceof MenuAccess<?> screen) || screen.getMenu() != p.containerMenu || !supported(p.containerMenu)) {
            opened = null;
            return;
        }
        var menu = p.containerMenu;
        if (menu != opened) {
            if (autoOpen.get()) aura.opened(mc);
            opened = menu;
            cooldown = delay.getInt() - 1;
            last = 0;
        }
        if (!menu.hasReceivedInitialContents() || !menu.getCarried().isEmpty()
                || named.get() && !storageTitle(mc.gui.screen().getTitle())) return;
        if (cooldown > 0) { cooldown--; return; }

        var storage = menu.slots.stream().filter(s -> s.container == p.getInventory()).toList();
        var manager = respect.get() ? Modules.enabled(InvManager.class) : null;
        var policy = manager == null ? null : manager.policy();
        var owned = new ArrayList<ItemStack>();
        if (policy != null) {
            storage.forEach(s -> owned.add(s.getItem()));
            owned.add(p.getOffhandItem());
            owned.add(p.getItemBySlot(EquipmentSlot.HEAD));
            owned.add(p.getItemBySlot(EquipmentSlot.CHEST));
            owned.add(p.getItemBySlot(EquipmentSlot.LEGS));
            owned.add(p.getItemBySlot(EquipmentSlot.FEET));
        }
        boolean eligible = false;
        var choices = new ArrayList<Slot>();
        var destinations = new HashMap<Slot, Slot>();
        for (Slot slot : menu.slots) {
            var stack = slot.getItem();
            if (slot.container == p.getInventory() || stack.isEmpty() || !slot.mayPickup(p)) continue;
            if (!accepts(InventoryRole.id(stack), only.get(), whitelist.get(), blacklist.get())) continue;
            if (policy != null && !policy.allowsLoot(stack, owned)) continue;
            eligible = true;
            Slot destination = destination(stack, storage, policy == null ? Map.of() : policy.layout());
            if (destination != null) {
                choices.add(slot);
                destinations.put(slot, destination);
            }
        }
        if (choices.isEmpty()) {
            if (eligible && makeRoom.get() && manager != null && policy != null) {
                int discard = manager.disposableSlot(policy);
                for (Slot slot : storage) {
                    if (menuSlot(slot) == discard && slot.mayPickup(p)) {
                        mc.gameMode.handleContainerInput(menu.containerId, slot.index, 1, ContainerInput.THROW, p);
                        cooldown = delay.getInt() - 1;
                        return;
                    }
                }
            }
            if (close.get()) p.closeContainer();
            return;
        }
        var selected = select(choices, order.get(), last);
        if (policy == null && moveMode.is(MoveMode.QUICK_MOVE)) {
            mc.gameMode.handleContainerInput(menu.containerId, selected.index, 0, ContainerInput.QUICK_MOVE, p);
        } else {
            Slot dest = destinations.get(selected);
            if (moveMode.is(MoveMode.QUICK_MOVE) && !dest.hasItem() && dest.getContainerSlot() < 9) {
                mc.gameMode.handleContainerInput(menu.containerId, selected.index, dest.getContainerSlot(), ContainerInput.SWAP, p);
            } else {
                ItemStack before = selected.getItem().copy();
                mc.gameMode.handleContainerInput(menu.containerId, selected.index, 0, ContainerInput.PICKUP, p);
                if (p.containerMenu == menu && ItemStack.matches(menu.getCarried(), before)) {
                    mc.gameMode.handleContainerInput(menu.containerId, dest.index, 0, ContainerInput.PICKUP, p);
                    if (p.containerMenu == menu && !menu.getCarried().isEmpty() && !selected.hasItem())
                        mc.gameMode.handleContainerInput(menu.containerId, selected.index, 0, ContainerInput.PICKUP, p);
                }
            }
        }
        last = selected.index;
        cooldown = delay.getInt() - 1;
    }

    private static final Set<String> STORAGE_TITLES = Set.of("container.chest", "container.chestDouble",
            "container.enderchest", "container.barrel", "container.shulkerBox", "entity.minecraft.chest_minecart",
            "entity.minecraft.chest_boat", "entity.minecraft.acacia_chest_boat", "entity.minecraft.bamboo_chest_raft",
            "entity.minecraft.birch_chest_boat", "entity.minecraft.cherry_chest_boat", "entity.minecraft.dark_oak_chest_boat",
            "entity.minecraft.jungle_chest_boat", "entity.minecraft.mangrove_chest_boat", "entity.minecraft.oak_chest_boat",
            "entity.minecraft.pale_oak_chest_boat", "entity.minecraft.poplar_chest_boat", "entity.minecraft.spruce_chest_boat");

    static boolean storageTitle(Component title) {
        if (title.getContents() instanceof TranslatableContents translated)
            return STORAGE_TITLES.contains(translated.getKey());
        String text = title.getString();
        return STORAGE_TITLES.stream().anyMatch(key -> Component.translatable(key).getString().equals(text));
    }

    private static int menuSlot(Slot slot) {
        int index = slot.getContainerSlot();
        return index < 9 ? 36 + index : index;
    }

    /** Fill a matching rule first, then merge, then use ordinary free space. Preserve booked and locked slots. */
    static @Nullable Slot destination(ItemStack stack, List<Slot> storage, Map<Integer, String> layout) {
        Slot best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Slot slot : storage) {
            if (!slot.mayPlace(stack)) continue;
            String rule = layout.get(menuSlot(slot));
            if (rule != null && InventoryRole.score(rule, stack) < 0) continue;
            var other = slot.getItem();
            if (!other.isEmpty() && (!ItemStack.isSameItemSameComponents(other, stack)
                    || other.getCount() >= slot.getMaxStackSize(stack))) continue;
            int rank = (rule == null ? 4 : InventoryRole.byKey(rule) == null ? 0 : 2) + (other.isEmpty() ? 1 : 0);
            if (rank < bestRank) { best = slot; bestRank = rank; }
        }
        return best;
    }

    static Slot select(List<Slot> choices, Order order, int last) {
        return switch (order) {
            case INDEX -> choices.getFirst();
            case REVERSE -> choices.getLast();
            case RANDOM -> choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
            case NEAREST -> choices.stream().min(java.util.Comparator.comparingInt(s -> {
                int dx = s.index % 9 - last % 9, dy = s.index / 9 - last / 9;
                return dx * dx + dy * dy;
            })).orElseThrow();
        };
    }
}
