package com.mentalfrostbyte.jello.gui.mainmenu;

import com.mentalfrostbyte.jello.gui.ModeSelectScreen;
import com.mentalfrostbyte.jello.util.client.render.theme.ClientColors;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/** Vanilla title screen plus the two historical No Addons Sigma affordances. */
public final class NoAddonsMainMenuScreen extends TitleScreen {

    private static final int LIGHT = ClientColors.LIGHT_GREYISH_BLUE.getColor();

    @Override
    protected void init() {
        super.init();

        // Old NoAddonHolder appended a full-width Switch button one row below vanilla's
        // language/options/quit cluster. Preserve the interaction while using the 26.2 widget stack.
        int switchY = this.height / 4 + 48 + 120;
        this.addRenderableWidget(
            Button.builder(Component.literal("Switch"), button -> this.minecraft.gui.setScreen(new ModeSelectScreen(this)))
                .bounds(this.width / 2 - 100, switchY, 200, 20)
                .build()
        );
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // Faithful to old NoAddonHolder: label starts after the vanilla version string and PROD is
        // rendered at half scale as a tiny suffix rather than as a modern badge.
        int y = this.height - 10;
        int tint = withAlpha(LIGHT, 102);
        graphics.text(this.font, "No Addons - SIGMA", 87, y, tint, false);

        graphics.pose().pushMatrix();
        graphics.pose().translate(184.0F, (float)y);
        graphics.pose().scale(0.5F, 0.5F);
        graphics.text(this.font, "PROD", 0, 0, tint, false);
        graphics.pose().popMatrix();
    }

    private static int withAlpha(final int color, final int alpha) {
        return Math.max(0, Math.min(255, alpha)) << 24 | color & 0x00FFFFFF;
    }
}
