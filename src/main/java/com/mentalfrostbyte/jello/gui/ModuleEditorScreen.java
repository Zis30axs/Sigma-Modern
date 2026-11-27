package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.jello.gui.modern.ModernBlurredBackdrop;
import com.mentalfrostbyte.jello.util.game.render.GuiVisuals;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The backdrop and presentation of a settings page opened over a ClickGUI. */
abstract class ModuleEditorScreen extends Screen implements ModernBlurredBackdrop {
    protected final ModuleScreenStyle style = ModuleScreenStyle.current();

    protected ModuleEditorScreen(Component title) {
        super(title);
    }

    @Override public final boolean usesStrongBackdropBlur() {
        return style == ModuleScreenStyle.MODERN || style == ModuleScreenStyle.JELLO;
    }

    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float tick) {
        if (style == ModuleScreenStyle.VANILLA) {
            super.extractBackground(g, mx, my, tick);
        } else {
            if (minecraft.level == null) {
                extractPanorama(g, tick);
                g.nextStratum();
            }
            GuiVisuals.blurBackground(g);
            minecraft.gui.hud.extractDeferredSubtitles();
        }
    }
}
