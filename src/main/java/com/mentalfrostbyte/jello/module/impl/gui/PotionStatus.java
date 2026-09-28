package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * The player's status effects as a list on SigmaModern's HUD, in place of vanilla's row of icons in the top-right
 * corner: each effect with its own line-drawn icon, its name and level and the time it has left.
 *
 * <p>The drawing is {@code gui.modern.ModernPotionStatus}'s, the same split as {@link ModuleArrayList}: this class
 * holds the settings and decides which effects are shown and in what order. While it is on and the client is in
 * SigmaModern, vanilla's icons are not drawn (a Sigma hook in {@code Hud.extractEffects}); the list sits above the
 * ArrayList, on whichever side that hangs from, and the ArrayList starts below it. Switched off, vanilla's icons come
 * back and the ArrayList moves below them instead.</p>
 */
public class PotionStatus extends Module {

    /** Vanilla starts blinking an effect's icon when this many ticks are left (ten seconds). */
    public static final int EXPIRING_TICKS = 200;

    public enum Layout {
        /** A line per effect: icon, name and level, time left, and a bar for how much of it is left. */
        LIST,
        /**
         * Lines in the ArrayList's own style (its text size, spacing, background and shadow): name, time and the icon
         * at the screen edge, so the effects read as the top of the list.
         */
        INLINE
    }

    public enum Order {
        /** Good effects first, then neutral, then bad, as vanilla's two rows; soonest to run out first within each. */
        KIND,
        /** Soonest to run out first; endless ones last. */
        DURATION,
        /** By name. */
        NAME
    }

    /** What the ordering and filtering need to know about one active effect. */
    public record Entry(String name, MobEffectCategory category, int ticks, boolean infinite, boolean ambient, boolean showIcon) {}

    private final EnumSetting<Layout> layout = this.register(new EnumSetting<>(
            "Layout", "LIST: a glass card per effect with a duration bar. INLINE: lines styled like the ArrayList's.", Layout.LIST));

    private final EnumSetting<Order> sort = this.register(new EnumSetting<>(
            "Sort", "KIND: good effects first, as vanilla. DURATION: soonest to run out first. NAME: alphabetical.", Order.KIND));

    private final BooleanSetting showLevel = this.register(new BooleanSetting(
            "Show Level", "Adds the level after the name, as Speed II; level I is left out.", true));

    private final BooleanSetting showTime = this.register(new BooleanSetting(
            "Show Time", "Shows how long each effect has left.", true));

    private final BooleanSetting durationBar = this.register(new BooleanSetting(
            "Duration Bar", "A thin bar under each line that runs down with the effect.", true));

    private final BooleanSetting coloredIcons = this.register(new BooleanSetting(
            "Colored Icons", "Draws each icon in its effect's color, lightened to read on dark glass.", true));

    private final NumberSetting background = this.register(new NumberSetting(
            "Background", "How solid the dark glass behind the effects is. 0 draws none.", 0.6F, 0.0F, 1.0F, 0.05F));

    private final BooleanSetting blinkExpiring = this.register(new BooleanSetting(
            "Blink Expiring", "Pulses an effect in its last ten seconds, as vanilla's icons do.", true));

    private final BooleanSetting hideAmbient = this.register(new BooleanSetting(
            "Hide Ambient", "Leaves out effects from a beacon or a conduit, which keep coming back while in range.", false));

    public PotionStatus() {
        super(ModuleCategory.INTERFACE, "PotionStatus", "Lists your status effects above the ArrayList, in place of vanilla's icons");
        this.durationBar.visibleWhen(() -> this.layout.is(Layout.LIST));
        this.background.visibleWhen(() -> this.layout.is(Layout.LIST));
    }

    /** Vanilla always shows its effect icons; replacing them with nothing would lose information. */
    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    /**
     * The effects to show, in order: those vanilla would give an icon ({@code showIcon}), minus ambient ones under
     * {@code Hide Ambient}. Ties fall back to the name, so the order never flickers between equal effects.
     */
    public <T> List<T> listed(final Collection<T> effects, final Function<T, Entry> entry) {
        Comparator<Entry> byName = Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER);
        Comparator<Entry> bySoonest = Comparator.comparing(Entry::infinite).thenComparingInt(Entry::ticks);
        Comparator<Entry> order = switch (this.sort.get()) {
            case KIND -> Comparator.comparingInt((Entry e) -> kindRank(e.category())).thenComparing(bySoonest).thenComparing(byName);
            case DURATION -> bySoonest.thenComparing(byName);
            case NAME -> byName.thenComparing(bySoonest);
        };
        return effects.stream()
                .filter(effect -> this.shows(entry.apply(effect)))
                .sorted(Comparator.comparing(entry, order))
                .toList();
    }

    private boolean shows(final Entry entry) {
        return entry.showIcon() && !(this.hideAmbient.get() && entry.ambient());
    }

    private static int kindRank(final MobEffectCategory category) {
        return switch (category) {
            case BENEFICIAL -> 0;
            case NEUTRAL -> 1;
            case HARMFUL -> 2;
        };
    }

    /** The level after an effect's name: nothing for level I, Roman numerals to X, then plain digits. */
    public static String level(final int amplifier) {
        if (amplifier <= 0) {
            return "";
        }
        int level = amplifier + 1;
        if (level > 10) {
            return Integer.toString(level);
        }
        return new String[]{"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"}[level];
    }

    /**
     * How visible an effect with {@code ticks} left is drawn: fully until its last ten seconds, then pulsing, quicker
     * and deeper as it runs out, as vanilla's icons blink. Never quite invisible, so it can still be read. {@code ticks}
     * may be fractional (the frame's partial tick), so the pulse is smooth.
     */
    public static float blink(final float ticks, final boolean infinite) {
        if (infinite || ticks > EXPIRING_TICKS) {
            return 1.0F;
        }
        float used = 1.0F - Math.max(ticks, 0.0F) / EXPIRING_TICKS;
        float depth = 0.15F + 0.35F * used;
        float wave = (float) Math.cos(ticks * Math.PI / (6.0F - 3.0F * used));
        return 1.0F - depth * (0.5F - 0.5F * wave);
    }

    public Layout getLayout() {
        return this.layout.get();
    }

    public boolean showsLevel() {
        return this.showLevel.get();
    }

    public boolean showsTime() {
        return this.showTime.get();
    }

    public boolean hasDurationBar() {
        return this.durationBar.get();
    }

    public boolean hasColoredIcons() {
        return this.coloredIcons.get();
    }

    public float getBackground() {
        return this.background.get();
    }

    public boolean blinksExpiring() {
        return this.blinkExpiring.get();
    }
}
