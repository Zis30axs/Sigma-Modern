package com.mentalfrostbyte.jello.module.impl.render;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;

/**
 * Suppresses distracting screen overlays, distortion effects and gameplay fog without touching game state.
 *
 * <p>Hooks live directly at the rendering entry points ({@code GameRenderer}, {@code Hud},
 * {@code ScreenEffectRenderer} and {@code MobEffectFogEnvironment}), so potion timers, sounds and server
 * state remain untouched while the screen stays clear.</p>
 */
public class NoRender extends Module {

    private final BooleanSetting blindness = this.register(new BooleanSetting(
            "Blindness", "Removes the close fog added by the Blindness effect.", true));

    private final BooleanSetting darkness = this.register(new BooleanSetting(
            "Darkness", "Removes the pulsing fog added by the Darkness effect.", true));

    private final BooleanSetting nausea = this.register(new BooleanSetting(
            "Nausea", "Stops the screen warp and green overlay caused by Nausea.", true));

    private final BooleanSetting portal = this.register(new BooleanSetting(
            "Portal", "Stops the screen warp and purple overlay inside Nether portals.", true));

    private final BooleanSetting pumpkin = this.register(new BooleanSetting(
            "Pumpkin", "Hides helmet camera overlays such as carved pumpkins.", true));

    private final BooleanSetting powderSnow = this.register(new BooleanSetting(
            "PowderSnow", "Hides the frozen border overlay when standing in powder snow.", true));

    private final BooleanSetting vignette = this.register(new BooleanSetting(
            "Vignette", "Hides the darkened vignette around the screen edges.", false));

    private final BooleanSetting suffocation = this.register(new BooleanSetting(
            "Suffocation", "Hides the block texture drawn over the screen when your head is inside a block.", true));

    private final BooleanSetting underwater = this.register(new BooleanSetting(
            "Underwater", "Hides the underwater overlay texture while submerged.", false));

    private final BooleanSetting totem = this.register(new BooleanSetting(
            "Totem", "Hides the floating Totem of Undying pop animation.", true));

    public NoRender() {
        super(ModuleCategory.RENDER, "NoRender", "Hides distracting overlays, screen warps and effect fog");
    }

    public boolean hideBlindness() {
        return this.blindness.get();
    }

    public boolean hideDarkness() {
        return this.darkness.get();
    }

    public boolean hideNausea() {
        return this.nausea.get();
    }

    public boolean hidePortal() {
        return this.portal.get();
    }

    public boolean hidePumpkin() {
        return this.pumpkin.get();
    }

    public boolean hidePowderSnow() {
        return this.powderSnow.get();
    }

    public boolean hideVignette() {
        return this.vignette.get();
    }

    public boolean hideSuffocation() {
        return this.suffocation.get();
    }

    public boolean hideUnderwater() {
        return this.underwater.get();
    }

    public boolean hideTotem() {
        return this.totem.get();
    }
}
