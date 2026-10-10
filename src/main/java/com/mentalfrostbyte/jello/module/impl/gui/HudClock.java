package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;

/** Local computer time; the Modern renderer owns its appearance and movable HUD position. */
public final class HudClock extends Module {
    public enum Style {
        DIGITAL("Digital"), ANALOG("Analog");

        private final String label;

        Style(String label) { this.label = label; }

        @Override public String toString() { return this.label; }
    }

    private final EnumSetting<Style> style = this.register(new EnumSetting<>("Style",
            "Digital time or a clock face. Hold Alt to move the clock.", Style.DIGITAL));
    private final BooleanSetting twentyFourHour = this.register(new BooleanSetting("24 Hour",
            "Uses 00–23 hours instead of 12-hour time with AM or PM.", true));
    private final BooleanSetting seconds = this.register(new BooleanSetting("Seconds",
            "Shows seconds in the time and the second hand on the clock face.", true));
    private final BooleanSetting date = this.register(new BooleanSetting("Date",
            "Shows the computer's local date below the time.", true));
    private final NumberSetting scale = this.register(new NumberSetting("Scale",
            "Size of the clock.", 1.0F, 0.65F, 1.5F, 0.05F));
    private final NumberSetting background = this.register(new NumberSetting("Background",
            "Opacity of the clock's dark glass.", 0.8F, 0.0F, 1.0F, 0.05F));

    public HudClock() {
        super(ModuleCategory.INTERFACE, "Clock", "Shows your computer's local time and date.");
    }

    @Override public boolean isEnabledByDefault() { return true; }

    public Style getStyle() { return this.style.get(); }
    public boolean isTwentyFourHour() { return this.twentyFourHour.get(); }
    public boolean showsSeconds() { return this.seconds.get(); }
    public boolean showsDate() { return this.date.get(); }
    public float getScale() { return this.scale.get(); }
    public float getBackground() { return this.background.get(); }
}
