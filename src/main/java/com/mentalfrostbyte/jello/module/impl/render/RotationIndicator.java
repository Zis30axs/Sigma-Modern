package com.mentalfrostbyte.jello.module.impl.render;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.ColorSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;

/** Crosshair display policy; the shared movement report supplies the look independently of any aiming module. */
public final class RotationIndicator extends Module {
    public enum Direction {
        HEAD("Head"), BODY("Body"), BOTH("Head + Body");

        private final String label;
        Direction(String label) { this.label = label; }
        @Override public String toString() { return this.label; }
    }

    private final EnumSetting<Direction> direction = this.register(new EnumSetting<>(
            "Direction", "Head follows the last movement report. Body estimates the model's horizontal facing.", Direction.HEAD));
    private final NumberSetting length = this.register(new NumberSetting(
            "Length", "Maximum line length from the crosshair. A dashed line points behind the camera.", 72F, 24F, 160F, 4F));
    private final NumberSetting thickness = this.register(new NumberSetting(
            "Thickness", "Width of the direction lines.", 1.5F, 0.75F, 3F, 0.25F));
    private final ColorSetting headColor = this.register(new ColorSetting(
            "Head Color", "Color of the reported head direction.", 0xFF8FDDF2));
    private final ColorSetting bodyColor = this.register(new ColorSetting(
            "Body Color", "Color of the estimated body direction.", 0xFFFFCC80));

    public RotationIndicator() {
        super(ModuleCategory.RENDER, "RotationIndicator", "Points from the crosshair toward the reported head or body direction.");
        this.headColor.visibleWhen(this::showsHead);
        this.bodyColor.visibleWhen(this::showsBody);
    }

    public boolean showsHead() { return this.direction.get() != Direction.BODY; }
    public boolean showsBody() { return this.direction.get() != Direction.HEAD; }
    public float getLength() { return this.length.get(); }
    public float getThickness() { return this.thickness.get(); }
    public int getHeadColor() { return this.headColor.get() | 0xFF000000; }
    public int getBodyColor() { return this.bodyColor.get() | 0xFF000000; }
}
