package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;

/** Settings for Modern's server sidebar; drawing and Alt layout live in {@code gui.modern.ModernScoreboard}. */
public class Scoreboard extends Module {

    private final NumberSetting scale = this.register(new NumberSetting(
            "Scale", "Size of the Modern scoreboard. Shrinks further when needed to fit the screen.",
            1.0F, 0.65F, 1.5F, 0.05F));

    private final NumberSetting background = this.register(new NumberSetting(
            "Background", "Opacity of the Modern scoreboard's glass panel. 0 removes the tint and border.",
            192F / 255F, 0.0F, 1.0F, 0.05F));

    private final BooleanSetting blur = this.register(new BooleanSetting(
            "Blur", "Locally blurs the scenery behind the Modern scoreboard.", true));

    private final NumberSetting blurStrength = this.register(new NumberSetting(
            "Blur Strength", "Strength of the local background blur.", 0.8F, 0.0F, 1.0F, 0.05F));

    private final BooleanSetting showScores = this.register(new BooleanSetting(
            "Show Scores", "Shows the server's score column. Scores hidden by the server stay hidden.", true));

    private final BooleanSetting textShadow = this.register(new BooleanSetting(
            "Text Shadow", "Adds a shadow under the scoreboard text for contrast over the world.", true));

    public Scoreboard() {
        super(ModuleCategory.INTERFACE, "Scoreboard", "Shows the server sidebar on Modern glass; Alt + drag moves it");
        this.blurStrength.visibleWhen(this.blur::get);
    }

    /** Keep the previously built-in sidebar visible for configurations that predate this module. */
    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    public float getScale() {
        return this.scale.get();
    }

    public float getBackground() {
        return this.background.get();
    }

    public boolean hasBlur() {
        return this.blur.get();
    }

    public float getBlurStrength() {
        return this.blurStrength.get();
    }

    public boolean showsScores() {
        return this.showScores.get();
    }

    public boolean hasTextShadow() {
        return this.textShadow.get();
    }
}
