package com.mentalfrostbyte.jello.setting;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * One configurable value belonging to a {@link SettingHolder}.
 *
 * <p>Code that owns a setting keeps the object itself and reads it through {@link #get()}. The
 * {@linkplain #getName() name} exists for the framework - it is the key this setting is stored under and
 * the label the interface shows - and is not meant to be a lookup key in ordinary logic.</p>
 *
 * <p>The hierarchy is sealed, so a consumer that renders or edits settings can switch over the concrete
 * types exhaustively and the compiler will point out every place that needs to learn about a new one:</p>
 *
 * <pre>{@code
 * switch (setting) {
 *     case BooleanSetting flag -> renderToggle(flag);
 *     case NumberSetting number -> renderSlider(number);
 *     case EnumSetting<?> choice -> renderDropdown(choice);
 *     case ColorSetting color -> renderColorPicker(color);
 *     case TextSetting text -> renderTextBox(text);
 * }
 * }</pre>
 */
public sealed abstract class Setting<T> permits BooleanSetting, ColorSetting, EnumSetting, NumberSetting, TextSetting {

    private final String name;

    private final String description;

    private final T defaultValue;

    private T value;

    private BooleanSupplier visible = () -> true;

    private List<Runnable> listeners;

    protected Setting(final String name, final String description, final T defaultValue) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
        // Not run through sanitise: a subclass's own fields are still unassigned at this point, so its
        // idea of a valid range does not exist yet. Constructors narrow their default before calling up.
        this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
        this.value = this.defaultValue;
    }

    /** The key this setting is persisted under, and the label the interface shows. */
    public final String getName() {
        return this.name;
    }

    public final String getDescription() {
        return this.description;
    }

    public final T getDefaultValue() {
        return this.defaultValue;
    }

    public final T get() {
        return this.value;
    }

    /**
     * Assigns a new value, first passed through {@link #sanitise(Object)}. Listeners run only when the
     * value actually changed, compared by {@link Objects#equals} rather than by identity.
     */
    public final void set(final T value) {
        T sanitised = this.sanitise(Objects.requireNonNull(value, "value"));
        if (Objects.equals(this.value, sanitised)) {
            return;
        }

        this.value = sanitised;
        if (this.listeners != null) {
            this.listeners.forEach(Runnable::run);
        }
    }

    public final void reset() {
        this.set(this.defaultValue);
    }

    /**
     * Runs whenever the value changes, including while the config is being read. Handlers should stay
     * cheap and must not assume a world is loaded.
     */
    public final Setting<T> onChange(final Runnable listener) {
        if (this.listeners == null) {
            this.listeners = new ArrayList<>(1);
        }

        this.listeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /**
     * Hides this setting in the interface while the condition is false. It stays fully functional and is
     * still persisted - this only controls whether it is offered to the user.
     */
    public final Setting<T> visibleWhen(final BooleanSupplier condition) {
        this.visible = Objects.requireNonNull(condition, "condition");
        return this;
    }

    public final boolean isVisible() {
        return this.visible.getAsBoolean();
    }

    /**
     * Last stop before a value is stored, wherever it came from - a default, the config, the interface.
     * Subclasses that have a valid range narrow the value here instead of rejecting it.
     */
    protected T sanitise(final T value) {
        return value;
    }

    /** This setting's value, as it is written to the config. */
    public abstract JsonElement toJson();

    /**
     * Reads a value the config had for this setting. Returns {@code false} for content this setting
     * cannot make sense of, leaving the current value alone; the caller reports it with the context it
     * has - which holder and which setting - and carries on.
     */
    public final boolean fromJson(final JsonElement element) {
        Optional<T> parsed = this.parse(element);
        parsed.ifPresent(this::set);
        return parsed.isPresent();
    }

    protected abstract Optional<T> parse(JsonElement element);

    @Override
    public String toString() {
        return this.name + "=" + this.value;
    }
}
