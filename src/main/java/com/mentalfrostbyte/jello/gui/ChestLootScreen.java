package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.jello.module.impl.player.ChestSteal;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Compact read-only chest view over the world, backed by the same server menu as the normal screen. */
public final class ChestLootScreen extends Screen implements MenuAccess<AbstractContainerMenu> {
    private final AbstractContainerScreen<?> original;
    private boolean returning;
    private ModuleScreenStyle style;

    public ChestLootScreen(AbstractContainerScreen<?> original) {
        super(original.getTitle());
        this.original = original;
    }

    @Override public AbstractContainerMenu getMenu() { return original.getMenu(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float tick) {
        minecraft.gui.hud.extractDeferredSubtitles();
    }

    @Override protected void init() {
        style = ModuleScreenStyle.current();
        addRenderableWidget(style.button(width - 166, 25 + rows() * 18, 158, 18,
                Component.literal(ModuleScreenStyle.tr("full_inventory")), b -> full()));
    }

    private int rows() { return (getMenu().slots.size() - 36) / 9; }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
        int x = width - 172;
        style.panel(g, x, 5, 168, 42 + rows() * 18);
        style.label(g, title.getString(), x + 6, 8, 156, style.ink);
        for (int i = 0; i < rows() * 9; i++) {
            int left = x + 4 + (i % 9) * 18, top = 23 + (i / 9) * 18;
            boolean hovered = mx >= left && mx < left + 17 && my >= top && my < top + 17;
            style.control(g, left, top, 17, 17, false, hovered);
            var stack = getMenu().getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            g.item(stack, left, top);
            g.itemDecorations(font, stack, left, top);
            if (hovered) g.setTooltipForNextFrame(font, stack, mx, my);
        }
        super.extractRenderState(g, mx, my, tick);
    }

    private void full() {
        returning = true;
        minecraft.gui.setScreen(original);
    }
    @Override public void tick() {
        if (minecraft.player == null || minecraft.player.containerMenu != getMenu()) minecraft.gui.setScreen(null);
        else if (!minecraft.player.isAlive()) onClose();
        else if (!ChestSteal.compact(original) || !getMenu().getCarried().isEmpty()) full();
    }
    @Override public boolean keyPressed(KeyEvent e) {
        if (minecraft.options.keyInventory.matches(e)) { onClose(); return true; }
        return super.keyPressed(e);
    }
    @Override public void onClose() { if (minecraft.player != null) minecraft.player.closeContainer(); }
    @Override public void removed() { if (!returning && minecraft.player != null) getMenu().removed(minecraft.player); }
}
