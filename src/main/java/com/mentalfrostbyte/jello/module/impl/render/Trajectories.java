package com.mentalfrostbyte.jello.module.impl.render;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.ColorSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;

/** Display preferences for the local player's held projectile preview. */
public final class Trajectories extends Module {
    public enum Aim {
        PLAYER("Player"), REPORTED("Reported");
        private final String label;
        Aim(String label) { this.label = label; }
        @Override public String toString() { return this.label; }
    }

    private final EnumSetting<Aim> aim = this.register(new EnumSetting<>(
            "Aim", "Player previews your use-item direction; Reported uses the last movement rotation for silent aiming.", Aim.PLAYER));
    private final NumberSetting range = this.register(new NumberSetting(
            "Range", "Maximum length of the preview in blocks, within loaded terrain.", 128F, 32F, 256F, 16F));
    private final NumberSetting thickness = this.register(new NumberSetting(
            "Thickness", "Width of the trajectory line.", 1.5F, 0.75F, 3F, 0.25F));
    private final BooleanSetting hitMarker = this.register(new BooleanSetting(
            "Hit Marker", "Marks the first predicted block or entity collision.", true));
    private final ColorSetting color = this.register(new ColorSetting(
            "Color", "Color of the predicted flight path.", 0xFF8FDDF2));
    private final ColorSetting hitColor = this.register(new ColorSetting(
            "Hit Color", "Color of an entity hit.", 0xFFFFB56B));

    public Trajectories() {
        super(ModuleCategory.RENDER, "Trajectories", "Shows the expected flight path of the projectile held in your hand.");
    }

    public boolean usesReportedAim() { return this.aim.get() == Aim.REPORTED; }
    public float getRange() { return this.range.get(); }
    public float getThickness() { return this.thickness.get(); }
    public boolean showsHitMarker() { return this.hitMarker.get(); }
    public int getColor() { return this.color.get() | 0xFF000000; }
    public int getHitColor() { return this.hitColor.get() | 0xFF000000; }
}
