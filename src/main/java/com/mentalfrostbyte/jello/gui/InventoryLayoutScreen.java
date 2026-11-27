package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.setting.InventoryLayoutSetting;
import com.mentalfrostbyte.jello.setting.ItemListSetting;
import com.mentalfrostbyte.jello.util.game.InventoryRole;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Select a destination in the inventory grid, then a category or exact item in the palette. */
public final class InventoryLayoutScreen extends ModuleEditorScreen implements TextEntryScreen {
    private final @Nullable Screen parent;
    private final InventoryLayoutSetting layout;
    private final Map<String, Choice> choices = new HashMap<>();
    private final List<Choice> catalogue = new ArrayList<>();
    private final List<Cell> slots = new ArrayList<>(), palette = new ArrayList<>();
    private List<Choice> filtered = List.of();
    private EditBox search;
    private Button previous, next;
    private int selected = 36, page;
    private Geometry geometry;

    private record Choice(String key, String label, ItemStack icon, String searchText) { }
    record Geometry(int left, int top, int width, int step, int columns, int rows) {
        static Geometry of(int width, int height) {
            int w = Math.min(width - 12, 440);
            int step = height < 240 ? 18 : 22;
            int top = height < 240 ? 17 : 29;
            int columns = Math.max(1, w / step);
            int rows = Math.max(1, (height - 29 - (top + step * 4 + 33)) / step);
            return new Geometry((width - w) / 2, top, w, step, columns, rows);
        }
        int slotX(int slot) { return left + (slot == 45 ? 9 * step + 6 : (slot - 9) % 9 * step); }
        int slotY(int slot) { return top + (slot >= 36 ? 3 * step + 4 : (slot - 9) / 9 * step); }
        int searchY() { return top + step * 4 + 10; }
        int paletteY() { return searchY() + 23; }
    }

    public InventoryLayoutScreen(@Nullable Screen parent, InventoryLayoutSetting layout) {
        super(Component.literal(ModuleScreenStyle.tr("slot_layout")));
        this.parent = parent;
        this.layout = layout;
    }

    private void addChoice(String key, String id, @Nullable String label) {
        var icon = InventoryItemIcons.get(id);
        String name = label == null ? icon.getHoverName().getString() : Client.getInstance().getTranslations().text(label);
        var choice = new Choice(key, name, icon, (key + " " + name + (label == null ? "" : " " + label)).toLowerCase(Locale.ROOT));
        choices.put(key, choice);
        catalogue.add(choice);
    }

    @Override protected void init() {
        if (catalogue.isEmpty()) {
            for (var role : InventoryRole.values()) addChoice(role.name(), role.icon, role.label);
            for (String id : ItemListSetting.registeredItems()) addChoice(id, id, null);
        }
        geometry = Geometry.of(width, height);
        slots.clear();
        palette.clear();
        for (int slot = 9; slot <= 45; slot++) {
            int destination = slot;
            slots.add(addRenderableWidget(new Cell(geometry.slotX(slot), geometry.slotY(slot), geometry.step(),
                    () -> { selected = destination; refresh(); })));
        }
        String query = search == null ? "" : search.getValue();
        search = addRenderableWidget(style.search(font, geometry.left(), geometry.searchY(), geometry.width(), 18,
                Component.literal(ModuleScreenStyle.tr("search_slots"))));
        search.setHint(Component.literal(ModuleScreenStyle.tr("slot_hint")));
        for (int i = 0; i < geometry.rows() * geometry.columns(); i++) {
            int index = i;
            palette.add(addRenderableWidget(new Cell(geometry.left() + i % geometry.columns() * geometry.step(),
                    geometry.paletteY() + i / geometry.columns() * geometry.step(), geometry.step(), () -> {
                        layout.assign(selected, filtered.get(page * palette.size() + index).key());
                        refresh();
                    })));
        }
        int x = geometry.left(), y = height - 24;
        previous = button(x, y, 26, "<", () -> page(-1));
        next = button(x + 30, y, 26, ">", () -> page(1));
        button(x + geometry.width() - 56, y, 56, ModuleScreenStyle.tr("done"), this::onClose);
        search.setResponder(this::filter);
        search.setValue(query);
        filter(query);
    }

    private Button button(int x, int y, int w, String name, Runnable action) {
        return addRenderableWidget(style.button(x, y, w, 18, Component.literal(name), b -> action.run()));
    }

    private void filter(String query) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        filtered = q.isEmpty() ? catalogue : catalogue.stream().filter(c -> c.searchText().contains(q)).toList();
        page = 0;
        refresh();
    }

    private static String slotName(int i) {
        if (i == 45) return ModuleScreenStyle.tr("offhand");
        if (i >= 36) return ModuleScreenStyle.tr("hotbar_slot", i - 35);
        return ModuleScreenStyle.tr("backpack_slot", (i - 9) / 9 + 1, (i - 9) % 9 + 1);
    }

    private void refresh() {
        for (int i = 9; i <= 45; i++) {
            var cell = slots.get(i - 9);
            var rule = choices.get(layout.get().getOrDefault(i, "NONE"));
            ItemStack icon = rule.key().equals("NONE") ? ItemStack.EMPTY : rule.icon();
            String label = slotName(i) + " · " + rule.label();
            if (minecraft.player != null && i < minecraft.player.inventoryMenu.slots.size()) {
                var actual = minecraft.player.inventoryMenu.getSlot(i).getItem();
                if (!actual.isEmpty()) {
                    label += "\n" + ModuleScreenStyle.tr("holding", actual.getHoverName().getString(), actual.getCount());
                    if (icon.isEmpty()) icon = actual;
                }
            }
            cell.show(icon, label, i == selected, !rule.key().equals("NONE"));
        }
        for (int i = 0; i < palette.size(); i++) {
            int pos = page * palette.size() + i;
            var cell = palette.get(i);
            cell.visible = pos < filtered.size();
            cell.active = cell.visible;
            if (cell.visible) {
                var choice = filtered.get(pos);
                boolean role = InventoryRole.byKey(choice.key()) != null;
                String type = role ? ModuleScreenStyle.tr("slot_rule") : choice.key();
                cell.show(choice.icon(), choice.label() + "\n" + type,
                        choice.key().equals(layout.get().getOrDefault(selected, "NONE")), role);
            }
        }
        previous.active = page > 0;
        next.active = (page + 1) * palette.size() < filtered.size();
    }

    private void page(int delta) {
        page = Math.clamp(page + delta, 0, Math.max(0, (filtered.size() - 1) / palette.size()));
        refresh();
    }

    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (sy != 0 && y >= geometry.paletteY() && y < height - 24) { page(sy > 0 ? -1 : 1); return true; }
        return super.mouseScrolled(x, y, sx, sy);
    }

    private final class Cell extends Button {
        private ItemStack stack = ItemStack.EMPTY;
        private boolean selected, assigned;
        Cell(int x, int y, int step, Runnable press) {
            super(x, y, step - 1, step - 1, Component.empty(), b -> press.run(), DEFAULT_NARRATION);
        }
        void show(ItemStack stack, String label, boolean selected, boolean assigned) {
            this.stack = stack;
            this.selected = selected;
            this.assigned = assigned;
            var text = Component.literal(label);
            setMessage(text);
            setTooltip(Tooltip.create(text));
        }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float tick) {
            int x = getX(), y = getY(), w = getWidth();
            style.control(g, x, y, w, w, selected, isHoveredOrFocused());
            if (!stack.isEmpty()) g.item(stack, x + (w - 16) / 2, y + (w - 16) / 2);
            if (assigned) g.fill(x + 2, y + w - 2, x + w - 2, y + w - 1, style.accent);
        }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
        int panelTop = geometry.top() - 16;
        style.panel(g, geometry.left() - 4, panelTop, geometry.width() + 8, height - panelTop - 2);
        var active = choices.get(layout.get().getOrDefault(selected, "NONE"));
        String heading = slotName(selected) + "  ·  " + active.label();
        style.label(g, heading, geometry.left(), geometry.top() - 14, geometry.width(), style.ink);
        int labels = geometry.slotX(45) + geometry.step() + 9;
        if (labels + 68 <= geometry.left() + geometry.width()) {
            style.label(g, ModuleScreenStyle.tr("backpack"), labels, geometry.top() + 4, geometry.left() + geometry.width() - labels, style.muted);
            style.label(g, ModuleScreenStyle.tr("hotbar_offhand"), labels, geometry.slotY(45) + 4, geometry.left() + geometry.width() - labels, style.muted);
        }
        super.extractRenderState(g, mx, my, tick);
        int pages = Math.max(1, Math.ceilDiv(filtered.size(), palette.size()));
        style.label(g, filtered.isEmpty() ? ModuleScreenStyle.tr("no_matches") : (page + 1) + " / " + pages,
                geometry.left() + 64, height - 21, geometry.width() - 124, style.muted);
    }

    @Override public boolean isTypingText() { return search != null && search.isFocused(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public void removed() { Client.getInstance().saveConfig(); super.removed(); }
}
