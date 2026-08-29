package com.mentalfrostbyte.jello.module;

import com.mentalfrostbyte.jello.event.EventBus;
import com.mentalfrostbyte.jello.event.impl.client.EventModuleToggle;
import com.mentalfrostbyte.jello.setting.Setting;
import com.mentalfrostbyte.jello.setting.SettingHolder;
import com.mentalfrostbyte.jello.util.game.MinecraftInstance;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One feature the user can switch on, with its own settings.
 *
 * <p>A module declares its settings as fields and reads them directly, which is what keeps the code
 * readable and the types checked:</p>
 *
 * <pre>{@code
 * public class Example extends Module {
 *     private final BooleanSetting loud = register(new BooleanSetting("Loud", "Shout instead", false));
 *
 *     public Example() {
 *         super(ModuleCategory.MISC, "Example", "Does something");
 *     }
 *
 *     @EventTarget
 *     public void onTick(EventTick event) {
 *         if (this.loud.get()) { ... }
 *     }
 * }
 * }</pre>
 *
 * <p>While a module is enabled it is subscribed to the {@link EventBus}, and while it is disabled it is
 * not - so an {@code @EventTarget} method only ever runs when the module is actually on, and no listener
 * has to check its own state first.</p>
 *
 * <p>What deliberately is <em>not</em> here: anything about how a module is drawn, named on screen or
 * announced. Toggling publishes an {@link EventModuleToggle} and whatever cares - the module list, a
 * sound, a notification - subscribes to that.</p>
 */
public abstract class Module implements SettingHolder, MinecraftInstance {

    private final String name;

    private final ModuleCategory category;

    private final String description;

    private final Map<String, Setting<?>> settings = new LinkedHashMap<>();

    private boolean enabled;

    private Keybind keybind = Keybind.UNBOUND;

    protected Module(final ModuleCategory category, final String name, final String description) {
        this.category = Objects.requireNonNull(category, "category");
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
    }

    /**
     * Takes ownership of a setting and hands it straight back, so it can be declared and kept in one line.
     * Two settings on the same module may not share a name - that name is what the config stores them
     * under, so a clash would silently lose one of them.
     */
    protected final <S extends Setting<?>> S register(final S setting) {
        Setting<?> existing = this.settings.putIfAbsent(setting.getName(), setting);
        if (existing != null) {
            throw new IllegalArgumentException(this.name + " already has a setting named '" + setting.getName() + "'");
        }

        return setting;
    }

    /** The module's name: shown to the user, and the key it is stored under. */
    public final String getName() {
        return this.name;
    }

    public final ModuleCategory getCategory() {
        return this.category;
    }

    public final String getDescription() {
        return this.description;
    }

    public final boolean isEnabled() {
        return this.enabled;
    }

    /**
     * Switches the module on or off. This is the only way its state changes: it subscribes or unsubscribes
     * the module, runs {@link #onEnable()} or {@link #onDisable()}, and publishes an
     * {@link EventModuleToggle}. Setting the state it already has does nothing.
     */
    public final void setEnabled(final boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }

        this.enabled = enabled;
        // Subscribed only while fully on: enable runs onEnable first so a packet event arriving from the
        // network thread cannot reach a listener mid-setup, and disable unsubscribes before tearing down.
        if (enabled) {
            this.onEnable();
            EventBus.register(this);
        } else {
            EventBus.unregister(this);
            this.onDisable();
        }

        EventBus.call(new EventModuleToggle(this));
    }

    public final void toggle() {
        this.setEnabled(!this.enabled);
    }

    /**
     * Runs when the module is switched on. It may be called with no world loaded - the config is read
     * during startup - so it must not assume {@code mc.level} or {@code mc.player} exist.
     */
    protected void onEnable() {
    }

    /** Runs when the module is switched off. Undo anything {@link #onEnable()} did to the game here. */
    protected void onDisable() {
    }

    public final Keybind getKeybind() {
        return this.keybind;
    }

    public final void setKeybind(final Keybind keybind) {
        this.keybind = Objects.requireNonNull(keybind, "keybind");
    }

    @Override
    public final Collection<Setting<?>> settings() {
        return Collections.unmodifiableCollection(this.settings.values());
    }

    @Override
    public final Optional<Setting<?>> setting(final String name) {
        return Optional.ofNullable(this.settings.get(name));
    }

    @Override
    public String toString() {
        return this.name;
    }
}
