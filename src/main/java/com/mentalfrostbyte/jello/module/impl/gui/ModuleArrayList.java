package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.ColorSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.setting.Setting;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;
import org.jspecify.annotations.Nullable;

/**
 * The list of switched-on modules down the side of the game screen, as a module of its own: a switch to hide it,
 * and the choices of what it lists and how it looks.
 *
 * <p>The list used to be drawn unconditionally by SigmaModern's HUD. That drawing now lives in
 * {@code gui.modern.ModernArrayList}, which asks {@code Modules.enabled} for this module every frame; this class
 * only holds the settings and decides which modules are listed and in what order, the same split as
 * {@code ModernChat}. It {@linkplain #isEnabledByDefault() starts on}, and switched on untouched it draws what the
 * old list did: every other module that is on, by name, in the top-right corner.</p>
 */
public class ModuleArrayList extends Module {

    /** The size the rest of SigmaModern's HUD text is drawn at. */
    public static final float DEFAULT_FONT_SIZE = 11.0F;

    /** The corner the list hangs from. Bottom-left is left out: the chat lives there. */
    public enum Corner {
        TOP_RIGHT,
        TOP_LEFT,
        BOTTOM_RIGHT;

        public boolean isRight() {
            return this != TOP_LEFT;
        }

        public boolean isBottom() {
            return this == BOTTOM_RIGHT;
        }
    }

    public enum Order {
        /** By name, the way the list always was. */
        ALPHABETICAL,
        /** Widest line first, so the list steps in from the corner. */
        LENGTH
    }

    public enum ColorMode {
        /** Every line in {@code Color}. */
        STATIC,
        /** {@code Color}, with a slow wave of brightness running down the list. */
        WAVE,
        /** Pastel hues cycling down the list; {@code Color} is not used. */
        RAINBOW
    }

    private final EnumSetting<Corner> position = this.register(new EnumSetting<>(
            "Position", "Which corner of the screen the list hangs from.", Corner.TOP_RIGHT));

    private final EnumSetting<Order> sort = this.register(new EnumSetting<>(
            "Sort", "ALPHABETICAL by name, or LENGTH for the widest line first.", Order.ALPHABETICAL));

    private final BooleanSetting suffix = this.register(new BooleanSetting(
            "Suffix", "Shows each module's mode after its name in a dimmer color, e.g. Speed Legit Hop.", false));

    private final BooleanSetting hideVisuals = this.register(new BooleanSetting(
            "Hide Visuals", "Leaves out Render and Interface modules, whose effect is on screen anyway.", false));

    private final NumberSetting fontSize = this.register(new NumberSetting(
            "Font Size", "Text size in pixels; the rest of the HUD is 11.", DEFAULT_FONT_SIZE, 8.0F, 16.0F, 0.5F));

    private final NumberSetting spacing = this.register(new NumberSetting(
            "Spacing", "Extra pixels between lines.", 0.0F, 0.0F, 6.0F, 1.0F));

    private final NumberSetting background = this.register(new NumberSetting(
            "Background", "How solid the dark glass behind each line is. 0 draws none.", 0.0F, 0.0F, 1.0F, 0.05F));

    private final BooleanSetting accentBar = this.register(new BooleanSetting(
            "Accent Bar", "A thin bar in each line's color along the list's outer edge.", false));

    private final EnumSetting<ColorMode> colorMode = this.register(new EnumSetting<>(
            "Color Mode", "STATIC, WAVE (a brightness wave down the list) or RAINBOW.", ColorMode.STATIC));

    private final ColorSetting color = this.register(new ColorSetting(
            "Color", "The text color for STATIC and WAVE.", 0xFFEAF6FF));

    private final BooleanSetting textShadow = this.register(new BooleanSetting(
            "Text Shadow", "A drop shadow under the text, so it reads over bright scenery.", true));

    private final BooleanSetting animations = this.register(new BooleanSetting(
            "Animations", "Slides lines in and out as modules are switched, and eases the rest into place.", true));

    public ModuleArrayList() {
        super(ModuleCategory.INTERFACE, "ArrayList", "Lists the modules that are switched on down the side of the screen");
        this.color.visibleWhen(() -> !this.colorMode.is(ColorMode.RAINBOW));
    }

    /** The list was always on screen before it was a module; it stays that way for configs that predate it. */
    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    /**
     * The modules to list, in order: every switched-on module but this one (a list that is showing is on, so naming
     * itself says nothing), minus Render and Interface modules under {@code Hide Visuals}. {@code width} measures a
     * module's whole line, suffix included, for {@link Order#LENGTH}; ties fall back to the name.
     */
    public List<Module> listed(final Collection<Module> modules, final ToIntFunction<Module> width) {
        Comparator<Module> byName = Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER);
        Comparator<Module> order = this.sort.is(Order.LENGTH)
                ? Comparator.comparingInt(width).reversed().thenComparing(byName)
                : byName;
        return modules.stream()
                .filter(module -> module != this && module.isEnabled() && !this.hidden(module))
                .sorted(order)
                .toList();
    }

    private boolean hidden(final Module module) {
        return this.hideVisuals.get()
                && (module.getCategory() == ModuleCategory.RENDER || module.getCategory() == ModuleCategory.INTERFACE);
    }

    /**
     * What follows a module's name under {@code Suffix}: its first choice setting's value, worded for reading
     * ({@code LEGIT_HOP} becomes "Legit Hop"). Null with {@code Suffix} off or for a module without such a setting.
     */
    public @Nullable String suffixOf(final Module module) {
        if (!this.suffix.get()) {
            return null;
        }

        for (Setting<?> setting : module.settings()) {
            if (setting instanceof EnumSetting<?> choice) {
                return EnumSetting.label(choice.get());
            }
        }

        return null;
    }

    public Corner getPosition() {
        return this.position.get();
    }

    public float getFontSize() {
        return this.fontSize.get();
    }

    public int getSpacing() {
        return this.spacing.getInt();
    }

    public float getBackground() {
        return this.background.get();
    }

    public boolean hasAccentBar() {
        return this.accentBar.get();
    }

    public ColorMode getColorMode() {
        return this.colorMode.get();
    }

    public int getColor() {
        return this.color.get();
    }

    public boolean hasTextShadow() {
        return this.textShadow.get();
    }

    public boolean isAnimated() {
        return this.animations.get();
    }
}
