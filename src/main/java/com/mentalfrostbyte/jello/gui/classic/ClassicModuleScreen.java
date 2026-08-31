package com.mentalfrostbyte.jello.gui.classic;

import com.mentalfrostbyte.jello.module.ModuleManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

/** User-facing Classic ClickGUI without the temporary in-game mode selector. */
public final class ClassicModuleScreen extends ClassicClickGuiScreen {

    private static final int BACKGROUND = 0xB0101010;

    public ClassicModuleScreen(final ModuleManager modules) {
        super(modules);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int left = Math.max(this.width - 170, this.width / 2);
        graphics.fill(left, 0, this.width, 22, BACKGROUND);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        int left = Math.max(this.width - 170, this.width / 2);
        if (event.x() >= left && event.y() >= 0 && event.y() < 22) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }
}
