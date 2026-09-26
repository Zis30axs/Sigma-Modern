package com.mentalfrostbyte.jello.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Optional;

/**
 * A choice from a fixed set, backed by an enum.
 *
 * <p>An enum instead of a list of strings: the owner compares against constants the compiler knows about,
 * adding or removing a choice is a compile error at every place that handles it, and the constant's name
 * is a stable key to persist. A choice that disappeared from the enum reads back as unrecognised, and the
 * setting keeps its default.</p>
 */
public final class EnumSetting<E extends Enum<E>> extends Setting<E> {

    private final List<E> options;

    public EnumSetting(final String name, final String description, final E defaultValue) {
        super(name, description, defaultValue);
        this.options = List.of(defaultValue.getDeclaringClass().getEnumConstants());
    }

    /** Every choice, in the order the enum declares them. */
    public List<E> getOptions() {
        return this.options;
    }

    public boolean is(final E option) {
        return this.get() == option;
    }

    /** The choice after the current one, wrapping around - what clicking a cycling control does. */
    public void cycle() {
        this.set(this.options.get((this.options.indexOf(this.get()) + 1) % this.options.size()));
    }

    /** The current choice's place in {@link #getOptions()}. */
    public int index() {
        return this.options.indexOf(this.get());
    }

    /** Picks the choice at {@code index} in {@link #getOptions()}, clamped to the list. */
    public void setIndex(final int index) {
        this.set(this.options.get(Math.max(0, Math.min(this.options.size() - 1, index))));
    }

    /** Moves {@code delta} choices along, stopping at either end - what a scroll wheel over the control does. */
    public void step(final int delta) {
        this.setIndex(this.index() + delta);
    }

    /**
     * A choice as a person would write it: the constant's own {@code toString()} when the enum gives one, otherwise
     * its name in words - {@code LEGIT_HOP} as "Legit Hop". For showing only; the name is what is stored.
     */
    public static String label(final Enum<?> option) {
        String shown = option.toString();
        if (!shown.equals(option.name())) {
            return shown;
        }

        StringBuilder out = new StringBuilder(shown.length());
        for (String word : shown.split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(word.charAt(0)).append(word.substring(1).toLowerCase(java.util.Locale.ROOT));
        }

        return out.toString();
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(this.get().name());
    }

    @Override
    protected Optional<E> parse(final JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return Optional.empty();
        }

        String name = element.getAsString();
        return this.options.stream().filter(option -> option.name().equals(name)).findFirst();
    }
}
