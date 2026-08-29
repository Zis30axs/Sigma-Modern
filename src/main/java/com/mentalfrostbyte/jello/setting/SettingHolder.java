package com.mentalfrostbyte.jello.setting;

import java.util.Collection;
import java.util.Optional;

/**
 * Something that owns a group of settings - today a module, later whatever else needs to expose values
 * to the config and the interface.
 *
 * <p>This is the boundary the framework works through. The config reads and writes settings, and the
 * interface lists them, without knowing what kind of thing is holding them, and without assuming that a
 * holder's settings are all there is.</p>
 */
public interface SettingHolder {

    /** Every setting this holder owns, in the order it declared them. */
    Collection<Setting<?>> settings();

    /**
     * Looks a setting up by its persisted name. This exists for the framework - the config, the
     * interface, commands - and not for ordinary logic, which should hold the setting object instead.
     */
    Optional<Setting<?>> setting(String name);
}
