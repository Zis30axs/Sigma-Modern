package com.mentalfrostbyte.jello.module;

import com.mentalfrostbyte.jello.event.EnableAware;
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
public abstract class Module implements SettingHolder, MinecraftInstance, EnableAware {

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
     * Whether the module is on when the config has nothing to say about it: a fresh install, or a config
     * written before the module existed. Almost every module starts off. One that took over something the
     * client always did - the module list - starts on, so turning a feature into a module does not take it
     * away from anyone. The config's own on/off state, once there is one, always wins.
     */
    public boolean isEnabledByDefault() {
        return false;
    }

    /**
     * Switches the module on or off. This is the only way its state changes: it subscribes or unsubscribes
     * the module, runs {@link #onEnable()} or {@link #onDisable()}, and publishes an
     * {@link EventModuleToggle}. Setting the state it already has does nothing.
     *
     * <p>Both directions are transactional. A successful enable leaves the module {@link #isEnabled()},
     * with {@link #onEnable()} complete and exactly one subscription installed; a failed enable leaves it
     * not enabled and not subscribed, with any partial setup best-effort rolled back and the original
     * failure rethrown (a cleanup failure is attached to it as a suppressed exception rather than
     * replacing it). A disable always leaves the module unsubscribed and {@code enabled == false}, even
     * if {@link #onDisable()} itself throws - the transition already happened the moment the subscription
     * was removed, so the failure is a bug in that cleanup, not an incomplete toggle.</p>
     */
    public final void setEnabled(final boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }

        if (enabled) {
            this.enable();
        } else {
            this.disable();
        }
    }

    private void enable() {
        // Set before onEnable() so a subscription installed a moment later is never mistaken by
        // EventBus's disabled-subscriber guard (see EventBus#call) for one belonging to a module that
        // failed to start. onEnable() still runs before register(), so no event can reach this module
        // while it is mid-setup.
        this.enabled = true;
        boolean onEnableCompleted = false;
        try {
            this.onEnable();
            onEnableCompleted = true;
            EventBus.register(this);
        } catch (final RuntimeException failure) {
            this.enabled = false;
            EventBus.unregister(this); // no-op unless register() above already ran
            if (onEnableCompleted) {
                try {
                    this.onDisable();
                } catch (final RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }

        EventBus.call(new EventModuleToggle(this));
    }

    private void disable() {
        this.enabled = false;
        EventBus.unregister(this);
        try {
            this.onDisable();
        } finally {
            // The module is already off and unsubscribed by this point regardless of onDisable()'s
            // outcome, so listeners are told the truth even if onDisable() itself throws; the exception
            // still propagates past this finally block.
            EventBus.call(new EventModuleToggle(this));
        }
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
