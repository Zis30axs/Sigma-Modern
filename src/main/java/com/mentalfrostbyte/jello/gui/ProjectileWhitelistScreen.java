package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.modern.ModernBlurredBackdrop;
import com.mentalfrostbyte.jello.gui.modern.ModernStyle;
import com.mentalfrostbyte.jello.gui.modern.ModernTypography;
import com.mentalfrostbyte.jello.setting.ItemListSetting;
import com.mentalfrostbyte.jello.util.game.render.GuiVisuals;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** A shared item picker: click a row, or carry it between the catalogue and whitelist. */
public final class ProjectileWhitelistScreen extends Screen implements ModernBlurredBackdrop {
    private static final int ROW_H = 30;
    private static final int HEADING_H = 28;
    private final @Nullable Screen parent;
    private final ItemListSetting setting;
    private List<Entry> entries = List.of();
    private float catalogueScroll, selectedScroll;
    private @Nullable Entry pressed;
    private boolean pressedSelected, dragged;
    private double pressX, pressY;
    private boolean keyboardSelected;
    private int keyboardIndex = -1;

    private record Entry(String id, ItemStack stack) {}
    record Box(int x, int y, int w, int h) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
        int viewTop() { return y + HEADING_H; }
        int viewHeight() { return Math.max(1, h - HEADING_H - 6); }
    }
    record Layout(Box catalogue, Box selected, Box done, int left, int top, int width, int footer) {}

    public ProjectileWhitelistScreen(final @Nullable Screen parent, final ItemListSetting setting) {
        super(Component.literal("Projectile whitelist"));
        this.parent = parent;
        this.setting = Objects.requireNonNull(setting, "setting");
    }

    @Override protected void init() {
        // Only IDs from the code-owned catalogue can reach the item registry or resource renderer.
        this.entries = this.setting.getAllowedIds().stream().map(id -> new Entry(id,
                BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).map(ItemStack::new).orElse(ItemStack.EMPTY))).toList();
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
        return this.entries.stream().filter(entry -> this.setting.contains(entry.id())).toList();
    }

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
        if (this.minecraft.level == null) {
            this.extractPanorama(g, partialTick);
            g.nextStratum();
        }
        GuiVisuals.blurBackground(g);
        g.fill(0, 0, this.width, this.height, 0xB00B1925);
        this.minecraft.gui.hud.extractDeferredSubtitles();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        this.clampScroll();
        Layout l = layout(this.width, this.height);
        ModernTypography.draw(g, "Projectile whitelist", l.left(), l.top(), 1.3F, ModernStyle.TEXT, false);
        ModernTypography.draw(g, ModernTypography.fit("Choose which hotbar items may be used automatically.", l.width()),
                l.left(), l.top() + 19, ModernStyle.MUTED, false);
        this.drawList(g, l.catalogue(), this.entries, false, mx, my);
        this.drawList(g, l.selected(), this.selectedEntries(), true, mx, my);
        String hint = this.width >= 500 ? "Click to add/remove. Drag between lists." : "Click or drag to select";
        ModernTypography.draw(g, ModernTypography.fit(hint, l.width() - 72), l.left(), l.footer() + 7, ModernStyle.MUTED, false);
        ModernStyle.rounded(g, l.done().x(), l.done().y(), l.done().w(), l.done().h(), 6,
                l.done().contains(mx, my) ? 0xFF7DD3FF : 0xFF4EAFE2);
        ModernTypography.draw(g, "Done", l.done().x() + 16, l.done().y() + 7, ModernStyle.INK, false);
        if (this.dragged && this.pressed != null) {
            g.nextStratum();
            int x = Math.max(0, Math.min(this.width - 130, mx + 9));
            int y = Math.max(0, Math.min(this.height - 27, my - 13));
            ModernStyle.rounded(g, x, y, Math.min(130, this.width), 26, 6, 0xF0E8F3FA);
            g.item(this.pressed.stack(), x + 5, y + 5);
            ModernTypography.draw(g, ModernTypography.fit(this.name(this.pressed), 98), x + 25, y + 9, ModernStyle.INK, false);
        }
    }

    private String name(Entry entry) {
        return entry.stack().isEmpty() ? entry.id() : entry.stack().getHoverName().getString();
    }

    private void drawList(GuiGraphicsExtractor g, Box box, List<Entry> list, boolean selected, int mx, int my) {
        boolean drop = this.dragged && this.pressed != null && box.contains(mx, my) && this.pressedSelected != selected;
        ModernStyle.rounded(g, box.x(), box.y(), box.w(), box.h(), 9, drop ? 0xFAE1F5FF : ModernStyle.PANEL_SURFACE);
        String heading = selected ? "Whitelist  ·  " + list.size() : "Projectiles  ·  " + list.size();
        ModernTypography.draw(g, ModernTypography.fit(heading, box.w() - 20), box.x() + 10, box.y() + 10, ModernStyle.INK, false);
        int scroll = Math.round(selected ? this.selectedScroll : this.catalogueScroll);
        g.enableScissor(box.x() + 3, box.viewTop(), box.x() + box.w() - 3, box.viewTop() + box.viewHeight());
        try {
            for (int i = 0; i < list.size(); i++) {
                Entry entry = list.get(i);
                int y = box.viewTop() + i * ROW_H - scroll;
                if (y + ROW_H <= box.viewTop() || y >= box.viewTop() + box.viewHeight()) continue;
                boolean over = box.contains(mx, my) && my >= box.viewTop() && my >= y && my < y + ROW_H;
                boolean focus = this.keyboardIndex == i && this.keyboardSelected == selected;
                boolean included = this.setting.contains(entry.id());
                ModernStyle.rounded(g, box.x() + 5, y + 1, box.w() - 14, ROW_H - 3, 5,
                        over || focus ? 0x453BAEE6 : included ? 0x242E9BD6 : 0x18FFFFFF);
                g.item(entry.stack(), box.x() + 10, y + 6);
                ModernTypography.draw(g, ModernTypography.fit(this.name(entry), Math.max(1, box.w() - 69)),
                        box.x() + 32, y + 10, ModernStyle.INK, false);
                ModernTypography.draw(g, selected ? "−" : included ? "✓" : "+", box.x() + box.w() - 27, y + 10,
                        included ? ModernStyle.BLUE : ModernStyle.INK_MUTED, false);
            }
            if (list.isEmpty()) {
                ModernTypography.draw(g, ModernTypography.fit("Drop items here", box.w() - 24), box.x() + 12,
                        box.viewTop() + 10, ModernStyle.INK_MUTED, false);
            }
        } finally {
            g.disableScissor();
        }
        int content = list.size() * ROW_H;
        if (content > box.viewHeight()) {
            int barH = Math.max(12, box.viewHeight() * box.viewHeight() / content);
            int barY = box.viewTop() + Math.round(scroll * (box.viewHeight() - barH) / (float) (content - box.viewHeight()));
            ModernStyle.rounded(g, box.x() + box.w() - 5, barY, 2, barH, 1, 0x8057798D);
        }
    }

    private @Nullable Entry entryAt(Box box, List<Entry> list, float scroll, double mx, double my) {
        if (!box.contains(mx, my) || my < box.viewTop() || my >= box.viewTop() + box.viewHeight()) return null;
        int index = (int) ((my - box.viewTop() + Math.round(scroll)) / ROW_H);
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    @Override public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
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
        if (e.key() == GLFW.GLFW_KEY_TAB) {
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
