package com.mentalfrostbyte.jello.module.impl.world;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;

/**
 * Shows you the weather you asked for instead of the weather the server sent.
 *
 * <p>The 1.16 version dropped the server's weather packets. That cannot be undone: the client never
 * interpolates weather itself, the ramps are the server's, and once its updates have been thrown away there
 * is no value left to go back to.</p>
 *
 * <p>So nothing is dropped here and nothing is written. The level keeps the server's own numbers, and only
 * the two getters that decide what is <em>drawn</em> answer differently - sky colour, fog, clouds, stars,
 * rain columns and splash particles all follow from those. Whether it is "really" raining, which is what
 * riptide, fire spread and mob behaviour ask, keeps reading the server's values, so the game stays in step
 * with the server even while the sky does not.</p>
 */
public class Weather extends Module {

    private final EnumSetting<Mode> mode = this.register(new EnumSetting<>(
            "Mode", "The weather to show.", Mode.CLEAR));

    private final NumberSetting intensity = this.register(new NumberSetting(
            "Intensity", "How heavy the forced weather looks.", 1.0F, 0.1F, 1.0F, 0.05F));

    public Weather() {
        super(ModuleCategory.WORLD, "Weather", "Overrides the weather you see, without lying to the server");
        this.intensity.visibleWhen(() -> !this.mode.is(Mode.CLEAR));
    }

    /** The rain level to draw with. */
    public float rainLevel() {
        return this.mode.is(Mode.CLEAR) ? 0.0F : this.intensity.get();
    }

    /** The thunder level to draw with. Thunder implies rain, exactly as it does in vanilla. */
    public float thunderLevel() {
        return this.mode.is(Mode.THUNDER) ? this.intensity.get() : 0.0F;
    }

    public enum Mode {
        CLEAR,
        RAIN,
        THUNDER
    }
}
