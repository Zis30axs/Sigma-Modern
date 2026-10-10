package com.mentalfrostbyte.jello.module.impl.render;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.ColorSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;

/** Preferences for Modern's projected outlines of loaded players. */
public final class Esp extends Module {
    public enum Style {
        BOX("Box"), CORNERS("Corners");
        private final String label;
        Style(String label) { this.label = label; }
        @Override public String toString() { return this.label; }
    }

    /** Frame-local visibility facts, without retaining an entity or a world. */
    public record Candidate(boolean localOrCamera, boolean sameWorld, boolean alive,
                            boolean removed, boolean spectator, boolean invisible, double distanceSquared) {}

    private final EnumSetting<Style> style = this.register(new EnumSetting<>(
            "Style", "Full outlines or light corner brackets.", Style.CORNERS));
    private final BooleanSetting healthBar = this.register(new BooleanSetting(
            "Health Bar", "Show the player's current health beside the outline.", true));
    private final BooleanSetting distance = this.register(new BooleanSetting(
            "Distance", "Show the distance below the player.", false));
    private final BooleanSetting healthColor = this.register(new BooleanSetting(
            "Health Color", "Color the outline according to the player's health.", false));
    private final ColorSetting color = this.register(new ColorSetting(
            "Color", "Color of the player outlines.", 0xFF8FDDF2));
    private final NumberSetting range = this.register(new NumberSetting(
            "Range", "Maximum distance to a loaded player.", 96F, 8F, 256F, 8F));
    private final BooleanSetting invisible = this.register(new BooleanSetting(
            "Invisibles", "Include invisible players already loaded by the client.", false));

    public Esp() {
        super(ModuleCategory.RENDER, "ESP", "Modern player outlines visible through walls.");
        this.color.visibleWhen(() -> !this.healthColor.get());
    }

    @Override public boolean isEnabledByDefault() { return false; }
    public Style getStyle() { return this.style.get(); }
    public boolean showsHealthBar() { return this.healthBar.get(); }
    public boolean showsDistance() { return this.distance.get(); }
    public int outlineColor(float healthFraction) {
        return this.healthColor.get() ? NameTags.healthColor(healthFraction) : this.color.get() | 0xFF000000;
    }

    public boolean accepts(Candidate player) {
        return accepts(player, this.range.get(), this.invisible.get());
    }

    /** The filter intentionally does not depend on line of sight or vanilla name-tag visibility. */
    public static boolean accepts(Candidate player, float range, boolean includeInvisible) {
        return !player.localOrCamera && player.sameWorld && player.alive && !player.removed
                && !player.spectator && (!player.invisible || includeInvisible)
                && Float.isFinite(range) && range > 0F && Double.isFinite(player.distanceSquared)
                && player.distanceSquared >= 0.0 && player.distanceSquared <= (double) range * range;
    }
}
