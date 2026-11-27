package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.setting.ItemListSetting;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** A shared item picker: click a row, or carry it between the catalogue and whitelist. */
public final class ProjectileWhitelistScreen extends ModuleEditorScreen implements TextEntryScreen {
    private static final int ROW_H = 30;
    private static final int HEADING_H = 28;
    private final @Nullable Screen parent;
    private final ItemListSetting setting;
    private List<Entry> all = List.of(), entries = List.of(), selected = List.of();
    private java.util.Set<String> shownSelection;
    private EditBox search;
    private float catalogueScroll, selectedScroll;
    private @Nullable Entry pressed;
    private boolean pressedSelected, dragged;
    private double pressX, pressY;
    private boolean keyboardSelected;
    private int keyboardIndex = -1;

    private record Entry(String id, ItemStack stack, String name) {}
    record Box(int x, int y, int w, int h) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
        int viewTop() { return y + HEADING_H; }
        int viewHeight() { return Math.max(1, h - HEADING_H - 6); }
    }
    record Layout(Box catalogue, Box selected, Box done, int left, int top, int width, int footer) {}

    public ProjectileWhitelistScreen(final @Nullable Screen parent, final ItemListSetting setting) {
        super(Component.literal(Client.getInstance().getTranslations().text(setting.getName())));
        this.parent = parent;
        this.setting = Objects.requireNonNull(setting, "setting");
    }

    @Override protected void init() {
        // Only IDs from the code-owned catalogue can reach the item/block registry or resource renderer.
        this.all = this.setting.getAllowedIds().stream().map(id -> {
            var stack = InventoryItemIcons.get(id);
            String name = !stack.isEmpty() ? stack.getHoverName().getString()
                    : net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.Identifier.parse(id))
                            .map(holder -> holder.value().getName().getString()).orElse(id);
            return new Entry(id, stack, name);
        }).toList();
        this.shownSelection = null;
        Layout l = layout(this.width, this.height);
        String previous = this.search == null ? "" : this.search.getValue();
        this.search = this.addRenderableWidget(this.style.search(this.font, l.left(), l.top() + 17, l.width(), 18,
                Component.literal(ModuleScreenStyle.tr("search"))));
        this.search.setHint(Component.literal(ModuleScreenStyle.tr("search_hint")));
        this.search.setResponder(this::filter);
        this.search.setValue(previous);
        this.filter(previous);
        this.pressed = null;
        this.clampScroll();
    }

    static Layout layout(final int width, final int height) {
        int margin = Math.min(14, Math.max(4, width / 30));
        int w = Math.max(1, Math.min(660, width - 2 * margin));
        int x = Math.max(0, (width - w) / 2);
        int top = height >= 260 ? 28 : 9;
        int bodyY = top + 40;
        int footer = Math.max(bodyY + 60, height - 35);
        int bodyH = Math.max(1, footer - bodyY - 10);
        Box catalogue, selected;
        if (width >= 440) {
            int half = (w - 10) / 2;
            catalogue = new Box(x, bodyY, half, bodyH);
            selected = new Box(x + half + 10, bodyY, w - half - 10, bodyH);
        } else {
            int half = (bodyH - 8) / 2;
            catalogue = new Box(x, bodyY, w, Math.max(1, half));
            selected = new Box(x, bodyY + half + 8, w, Math.max(1, bodyH - half - 8));
        }
        return new Layout(catalogue, selected, new Box(x + w - 62, footer, 62, 22), x, top, w, footer);
    }

    private List<Entry> selectedEntries() {
        if (this.shownSelection != this.setting.get()) {
            this.selected = this.all.stream().filter(entry -> this.setting.contains(entry.id())).toList();
            this.shownSelection = this.setting.get();
        }
        return this.selected;
    }

    private void filter(String query) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        this.entries = q.isEmpty() ? this.all : this.all.stream().filter(e -> e.id().contains(q)
                || e.name().toLowerCase(Locale.ROOT).contains(q)).toList();
        this.catalogueScroll = 0;
        this.keyboardIndex = -1;
    }

    @Override public boolean isTypingText() { return this.search != null && this.search.isFocused(); }

    private void clampScroll() {
        Layout l = layout(this.width, this.height);
        this.catalogueScroll = clamp(this.catalogueScroll, this.entries.size(), l.catalogue());
        this.selectedScroll = clamp(this.selectedScroll, this.selectedEntries().size(), l.selected());
    }

    private static float clamp(float scroll, int size, Box box) {
        return Math.max(0, Math.min(scroll, Math.max(0, size * ROW_H - box.viewHeight())));
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        super.extractBackground(g, mx, my, partialTick);
        g.fill(0, 0, this.width, this.height, this.style == ModuleScreenStyle.MODERN ? 0xB00B1925 : 0x9A151515);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        this.clampScroll();
        Layout l = layout(this.width, this.height);
        style.label(g, getTitle().getString(), l.left(), l.top(), l.width(), 0xFFFFFFFF);
        super.extractRenderState(g, mx, my, partialTick);
        this.drawList(g, l.catalogue(), this.entries, false, mx, my);
        this.drawList(g, l.selected(), this.selectedEntries(), true, mx, my);
        String hint = ModuleScreenStyle.tr(this.width >= 500 ? "move_hint" : "move_hint_short");
        style.label(g, hint, l.left(), l.footer() + 6, l.width() - 72, 0xFFF1F1F1);
        Box done = l.done();
        style.button(g, done.x(), done.y(), done.w(), done.h(), ModuleScreenStyle.tr("done"), false, done.contains(mx, my), true);
        if (this.dragged && this.pressed != null) {
            g.nextStratum();
            int x = Math.max(0, Math.min(this.width - 130, mx + 9));
            int y = Math.max(0, Math.min(this.height - 27, my - 13));
            style.panel(g, x, y, Math.min(130, this.width), 26);
            g.item(this.pressed.stack(), x + 5, y + 5);
            style.label(g, this.pressed.name(), x + 25, y + 7, Math.min(98, this.width - x - 28), style.ink);
        }
    }

    private void drawList(GuiGraphicsExtractor g, Box box, List<Entry> list, boolean selected, int mx, int my) {
        boolean drop = this.dragged && this.pressed != null && box.contains(mx, my) && this.pressedSelected != selected;
        style.panel(g, box.x(), box.y(), box.w(), box.h());
        if (drop) g.outline(box.x(), box.y(), box.w(), box.h(), style.accent);
        String heading = ModuleScreenStyle.tr(selected ? "selected_list" : "catalogue", list.size());
        style.label(g, heading, box.x() + 10, box.y() + 8, box.w() - 20, style.ink);
        int scroll = Math.round(selected ? this.selectedScroll : this.catalogueScroll);
        g.enableScissor(box.x() + 3, box.viewTop(), box.x() + box.w() - 3, box.viewTop() + box.viewHeight());
        try {
            for (int i = scroll / ROW_H; i < list.size() && i * ROW_H < scroll + box.viewHeight(); i++) {
                Entry entry = list.get(i);
                int y = box.viewTop() + i * ROW_H - scroll;
                if (y + ROW_H <= box.viewTop() || y >= box.viewTop() + box.viewHeight()) continue;
                boolean over = box.contains(mx, my) && my >= box.viewTop() && my >= y && my < y + ROW_H;
                boolean focus = this.keyboardIndex == i && this.keyboardSelected == selected;
                boolean included = this.setting.contains(entry.id());
                style.control(g, box.x() + 5, y + 1, box.w() - 14, ROW_H - 3, included, over || focus);
                g.item(entry.stack(), box.x() + 10, y + 6);
                style.label(g, entry.name(), box.x() + 32, y + 9, box.w() - 69, style.ink);
                style.label(g, selected ? "−" : included ? "✓" : "+", box.x() + box.w() - 27, y + 9,
                        included ? style.accent : style.muted);
            }
            if (list.isEmpty()) {
                style.label(g, ModuleScreenStyle.tr(selected ? "drop_here" : "no_matches"), box.x() + 12,
                        box.viewTop() + 9, box.w() - 24, style.muted);
            }
        } finally {
            g.disableScissor();
        }
        int content = list.size() * ROW_H;
        if (content > box.viewHeight()) {
            int barH = Math.max(12, box.viewHeight() * box.viewHeight() / content);
            int barY = box.viewTop() + Math.round(scroll * (box.viewHeight() - barH) / (float) (content - box.viewHeight()));
            style.shape(g, box.x() + box.w() - 5, barY, 2, barH, 1, style.muted);
        }
    }

    private @Nullable Entry entryAt(Box box, List<Entry> list, float scroll, double mx, double my) {
        if (!box.contains(mx, my) || my < box.viewTop() || my >= box.viewTop() + box.viewHeight()) return null;
        int index = (int) ((my - box.viewTop() + Math.round(scroll)) / ROW_H);
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    @Override public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
        if (super.mouseClicked(e, twice)) return true;
        this.setFocused(null);
        if (e.button() != 0) return false;
        Layout l = layout(this.width, this.height);
        if (l.done().contains(e.x(), e.y())) { this.onClose(); return true; }
        this.keyboardIndex = -1;
        this.pressedSelected = l.selected().contains(e.x(), e.y());
        this.pressed = this.pressedSelected
                ? this.entryAt(l.selected(), this.selectedEntries(), this.selectedScroll, e.x(), e.y())
                : this.entryAt(l.catalogue(), this.entries, this.catalogueScroll, e.x(), e.y());
        this.pressX = e.x();
        this.pressY = e.y();
        this.dragged = false;
        return this.pressed != null;
    }

    @Override public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (e.button() != 0 || this.pressed == null) return false;
        if (Math.hypot(e.x() - this.pressX, e.y() - this.pressY) >= 4) this.dragged = true;
        return true;
    }

    @Override public boolean mouseReleased(MouseButtonEvent e) {
        if (e.button() != 0 || this.pressed == null) return false;
        Layout l = layout(this.width, this.height);
        if (this.dragged) {
            if (!this.pressedSelected && l.selected().contains(e.x(), e.y())) this.setting.add(this.pressed.id());
            else if (this.pressedSelected && l.catalogue().contains(e.x(), e.y())) this.setting.remove(this.pressed.id());
        } else {
            Box source = this.pressedSelected ? l.selected() : l.catalogue();
            List<Entry> list = this.pressedSelected ? this.selectedEntries() : this.entries;
            Entry released = this.entryAt(source, list, this.pressedSelected ? this.selectedScroll : this.catalogueScroll, e.x(), e.y());
            if (this.pressed.equals(released)) this.setting.toggle(this.pressed.id());
        }
        this.pressed = null;
        this.dragged = false;
        this.clampScroll();
        return true;
    }

    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        Layout l = layout(this.width, this.height);
        if (l.catalogue().contains(x, y)) this.catalogueScroll -= (float) sy * ROW_H;
        else if (l.selected().contains(x, y)) this.selectedScroll -= (float) sy * ROW_H;
        else return false;
        this.clampScroll();
        return true;
    }

    @Override public boolean keyPressed(KeyEvent e) {
        if (e.isEscape()) { this.onClose(); return true; }
        if (this.isTypingText() && e.key() != GLFW.GLFW_KEY_TAB) return super.keyPressed(e);
        if (e.key() == GLFW.GLFW_KEY_TAB) {
            this.setFocused(null);
            this.keyboardSelected = !this.keyboardSelected;
            this.keyboardIndex = 0;
        } else if (e.key() == GLFW.GLFW_KEY_UP || e.key() == GLFW.GLFW_KEY_DOWN) {
            this.keyboardIndex += e.key() == GLFW.GLFW_KEY_UP ? -1 : 1;
        } else if (e.key() == GLFW.GLFW_KEY_SPACE || e.key() == GLFW.GLFW_KEY_ENTER || e.key() == GLFW.GLFW_KEY_KP_ENTER) {
            List<Entry> list = this.keyboardSelected ? this.selectedEntries() : this.entries;
            if (this.keyboardIndex >= 0 && this.keyboardIndex < list.size()) this.setting.toggle(list.get(this.keyboardIndex).id());
            else return super.keyPressed(e);
        } else return super.keyPressed(e);
        List<Entry> list = this.keyboardSelected ? this.selectedEntries() : this.entries;
        this.keyboardIndex = list.isEmpty() ? -1 : Math.max(0, Math.min(list.size() - 1, this.keyboardIndex));
        Layout l = layout(this.width, this.height);
        Box box = this.keyboardSelected ? l.selected() : l.catalogue();
        float scroll = this.keyboardSelected ? this.selectedScroll : this.catalogueScroll;
        if (this.keyboardIndex >= 0) {
            int y = this.keyboardIndex * ROW_H;
            if (y < scroll) scroll = y;
            else if (y + ROW_H > scroll + box.viewHeight()) scroll = y + ROW_H - box.viewHeight();
        }
        if (this.keyboardSelected) this.selectedScroll = scroll;
        else this.catalogueScroll = scroll;
        this.clampScroll();
        return true;
    }

    @Override public void onClose() { this.minecraft.gui.setScreen(this.parent); }
    @Override public void removed() {
        this.pressed = null;
        Client.getInstance().saveConfig();
        super.removed();
    }
}
