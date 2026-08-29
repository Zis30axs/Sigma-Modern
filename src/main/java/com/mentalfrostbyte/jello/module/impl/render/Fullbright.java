package com.mentalfrostbyte.jello.module.impl.render;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;

/**
 * Lets you see in the dark.
 *
 * <p>The 1.16 client did this by writing {@code gamma = 999} into the game's own brightness option. That is
 * not merely blocked on this version, it is destructive: brightness is validated to {@code 0..1}, and an
 * out-of-range write is replaced by the <em>default</em>, so the trick would quietly throw away whatever
 * brightness the user had chosen and leave it thrown away.</p>
 *
 * <p>So this module never touches the option. The lightmap - the little 16x16 texture every lit surface in
 * the world samples - is rebuilt from the options once per tick, and this overrides the rebuilt result. That
 * also means there is nothing to put back: switch the module off and the next tick's lightmap is the
 * vanilla one again.</p>
 */
public class Fullbright extends Module {

    private final EnumSetting<Mode> mode = this.register(new EnumSetting<>(
            "Mode", "How the light is faked.", Mode.AMBIENT));

    private final NumberSetting strength = this.register(new NumberSetting(
            "Strength", "How far past the game's own maximum brightness to push.", 8.0F, 1.0F, 16.0F, 0.5F));

    private final BooleanSetting hideVignette = this.register(new BooleanSetting(
            "Hide Vignette", "Also fade out the dark edges of the screen, which the game darkens from world"
            + " light rather than from the lightmap.", true));

    public Fullbright() {
        super(ModuleCategory.RENDER, "Fullbright", "Makes everything visible in the dark");
        this.strength.visibleWhen(() -> this.mode.is(Mode.BRIGHTNESS));
    }

    /**
     * Rewrites the finished lightmap state. Called once per tick from the extractor, with every vanilla
     * field already filled in.
     */
    public void apply(final LightmapRenderState renderState) {
        switch (this.mode.get()) {
            // Exact and cheap: everything after the ambient term only adds light, and the shader clamps, so
            // a white ambient colour lands on full brightness whatever else is going on.
            case AMBIENT -> renderState.ambientColor = LightmapRenderStateExtractor.WHITE;
            // The classic look. The shader extrapolates past its own gamma curve, which brightens by a
            // factor - so it lifts dim light a long way but cannot rescue a pitch-black texel.
            case BRIGHTNESS -> renderState.brightness = this.strength.get();
            // What the potion looks like, because it is the potion's own value.
            case NIGHT_VISION -> renderState.nightVisionEffectIntensity = 1.0F;
        }
    }

    /** Whether the vignette should be faded out along with the darkness it is drawn for. */
    public boolean hidesVignette() {
        return this.hideVignette.get();
    }

    public enum Mode {
        AMBIENT,
        BRIGHTNESS,
        NIGHT_VISION
    }
}
