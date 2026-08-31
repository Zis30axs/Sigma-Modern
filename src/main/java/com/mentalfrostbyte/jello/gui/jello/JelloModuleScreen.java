package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.jello.module.ModuleManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

/** User-facing Jello ClickGUI without the temporary in-game mode selector. */
public final class JelloModuleScreen extends JelloClickGuiScreen {

    private static final int TOP_BAR = 0xFF0E2A3A;

    public JelloModuleScreen(final ModuleManager modules) {
        super(modules);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int left = Math.max(this.width - 170, this.width / 2);
        graphics.fill(left, 0, this.width, 26, TOP_BAR);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        int left = Math.max(this.width - 170, this.width / 2);
        if (event.x() >= left && event.y() >= 0 && event.y() < 26) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }
}
