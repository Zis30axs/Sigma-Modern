package com.mentalfrostbyte.jello.module;

/**
 * What a module is for. This is the module's own, canonical grouping - the one thing every interface can
 * fall back on. An interface is free to lay the categories out differently, or to group a module somewhere
 * else entirely, but that decision belongs to the interface and not here.
 */
public enum ModuleCategory {
    COMBAT("Combat"),
    MOVEMENT("Movement"),
    PLAYER("Player"),
    ITEM("Item"),
    WORLD("World"),
    RENDER("Render"),
    INTERFACE("Interface"),
    EXPLOIT("Exploit"),
    MISC("Misc");

    private final String displayName;

    ModuleCategory(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    @Override
    public String toString() {
        return this.displayName;
    }
}
