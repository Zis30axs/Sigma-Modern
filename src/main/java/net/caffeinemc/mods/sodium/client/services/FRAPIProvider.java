package net.caffeinemc.mods.sodium.client.services;

public interface FRAPIProvider {
    // MODIFIED for porting: the ServiceLoader indirection is gone. The Fabric Rendering API is not present in this
    // project, so this is the no-op implementation the upstream loadOr() call falls back to.
    FRAPIProvider INSTANCE = () -> {};

    static FRAPIProvider getInstance() {
        return INSTANCE;
    }

    /**
     * Registers the FRAPI provider. This should only be called once, and should be called during mod initialization.
     */
    void register();
}
