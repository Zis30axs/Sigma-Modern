package com.mentalfrostbyte.jello.module.impl.render;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import java.math.BigDecimal;

/** Player-label preferences and presentation-independent formatting. */
public final class NameTags extends Module {

    private final NumberSetting scale = this.register(new NumberSetting(
            "Scale", "Size of player name tags.", 1.0F, 0.65F, 1.5F, 0.05F));
    private final NumberSetting range = this.register(new NumberSetting(
            "Range", "Maximum tag distance, within vanilla's existing visibility limits.",
            64.0F, 8.0F, 128.0F, 8.0F));
    private final BooleanSetting avatar = this.register(new BooleanSetting(
            "Avatar", "Show a small skin icon beside the name.", false));
    private final BooleanSetting health = this.register(new BooleanSetting(
            "Health", "Show the player's health.", true));
    private final BooleanSetting healthBar = this.register(new BooleanSetting(
            "Health Bar", "Add a thin health bar below the name line.", false));
    private final BooleanSetting distance = this.register(new BooleanSetting(
            "Distance", "Show the distance to the player.", true));
    private final BooleanSetting ping = this.register(new BooleanSetting(
            "Ping", "Show the player's reported latency.", true));
    private final BooleanSetting score = this.register(new BooleanSetting(
            "Score", "Add the server's visible below-name score on a second line.", false));
    private final NumberSetting background = this.register(new NumberSetting(
            "Background", "Opacity of the tag background.", 0.82F, 0.25F, 1.0F, 0.05F));

    public NameTags() {
        super(ModuleCategory.RENDER, "NameTags", "Compact overhead player names with optional status details");
        this.healthBar.visibleWhen(this.health::get);
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    public float getScale() { return this.scale.get(); }
    public float getRange() { return this.range.get(); }
    public boolean showsAvatar() { return this.avatar.get(); }
    public boolean showsHealth() { return this.health.get(); }
    public boolean showsHealthBar() { return this.health.get() && this.healthBar.get(); }
    public boolean showsDistance() { return this.distance.get(); }
    public boolean showsPing() { return this.ping.get(); }
    public boolean showsScore() { return this.score.get(); }
    public float getBackground() { return this.background.get(); }

    /** Fraction of actual maximum health; unavailable or invalid values leave the bar empty. */
    public static float healthFraction(final float current, final float maximum) {
        if (!Float.isFinite(current) || !Float.isFinite(maximum) || maximum <= 0.0F) {
            return 0.0F;
        }
        return (float) Math.clamp((double) current / maximum, 0.0, 1.0);
    }

    /** Red at empty, amber at half, teal at full, with opaque interpolated colours between. */
    public static int healthColor(final float fraction) {
        float value = Float.isFinite(fraction) ? Math.clamp(fraction, 0.0F, 1.0F) : 0.0F;
        int from = value <= 0.5F ? 0xFFEF7F8E : 0xFFE8C982;
        int to = value <= 0.5F ? 0xFFE8C982 : 0xFF86E3C3;
        float progress = value <= 0.5F ? value * 2.0F : (value - 0.5F) * 2.0F;
        int red = interpolateChannel(from >>> 16 & 0xFF, to >>> 16 & 0xFF, progress);
        int green = interpolateChannel(from >>> 8 & 0xFF, to >>> 8 & 0xFF, progress);
        int blue = interpolateChannel(from & 0xFF, to & 0xFF, progress);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private static int interpolateChannel(final int from, final int to, final float progress) {
        return Math.round(from + (to - from) * progress);
    }

    /** Half-point health units; positive health never reads as zero, and extreme metadata stays within the label. */
    public static String formatHealth(final float health) {
        if (!Float.isFinite(health) || health < 0.0F) {
            return "—";
        }
        if (health > 9999.0F) {
            return "9999+";
        }
        double rounded = Math.ceil((double) health * 2.0) / 2.0;
        return BigDecimal.valueOf(rounded).stripTrailingZeros().toPlainString();
    }

    /** Whole metres; an invalid distance is not presented as zero. */
    public static String formatDistance(final double distance) {
        return !Double.isFinite(distance) || distance < 0.0 ? "—m" : Math.round(distance) + "m";
    }

    public static String formatPing(final int latency) {
        return latency < 0 ? "—ms" : latency > 9999 ? "9999+ms" : latency + "ms";
    }

    /**
     * Plain label text with ISO controls and legacy section-sign formatting removed. The limit counts
     * Unicode code points and includes the ellipsis, so a shortened label never exceeds its budget or
     * splits a surrogate pair. A dangling or unrecognised section sign is removed without eating text.
     */
    public static String boundedText(final String text, final int maxCodePoints) {
        if (text == null || text.isEmpty() || maxCodePoints <= 0) {
            return "";
        }
        StringBuilder clean = new StringBuilder(Math.min(text.length(), maxCodePoints));
        int count = 0;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == '§') {
                if (index < text.length() && isFormatCode(text.codePointAt(index))) {
                    index += Character.charCount(text.codePointAt(index));
                }
                continue;
            }
            if (Character.isISOControl(codePoint)) {
                continue;
            }
            if (count == maxCodePoints) {
                clean.setLength(clean.offsetByCodePoints(0, maxCodePoints - 1));
                return clean.append('…').toString();
            }
            clean.appendCodePoint(codePoint);
            count++;
        }
        return clean.toString();
    }

    private static boolean isFormatCode(final int codePoint) {
        return codePoint <= 0x7F && "0123456789abcdefklmnorx".indexOf(Character.toLowerCase(codePoint)) >= 0;
    }
}
