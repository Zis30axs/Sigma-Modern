package com.mentalfrostbyte.jello.module.impl.render;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.NumberSetting;

/**
 * Shrinks the flames that fill the screen while you are on fire.
 *
 * <p>The overlay is two quads built from one sprite, and both knobs the 1.16 version wanted are literals in
 * the four lines that build them: the quad's height, and the alpha packed into its vertex colour. So this
 * module holds two numbers and the drawing code reads them - there is nothing to enable, nothing to cache
 * and nothing to invalidate, because the quads are rebuilt every frame anyway.</p>
 *
 * <p>The height is a fraction of the vanilla quad, anchored at its bottom edge. It does not go below a
 * quarter: past that the flame's own bottom edge rises into view as a straight horizontal cut.</p>
 */
public class LowFire extends Module {

    private final NumberSetting height = this.register(new NumberSetting(
            "Height", "Flame height, as a fraction of the vanilla overlay.", 0.4F, 0.25F, 1.0F, 0.05F));

    private final NumberSetting opacity = this.register(new NumberSetting(
            "Opacity", "How solid the flames are. The vanilla overlay is 0.9.", 0.9F, 0.1F, 1.0F, 0.05F));

    public LowFire() {
        super(ModuleCategory.RENDER, "LowFire", "Shrinks the fire overlay so you can see while burning");
    }

    public float getHeight() {
        return this.height.get();
    }

    public float getOpacity() {
        return this.opacity.get();
    }
}
