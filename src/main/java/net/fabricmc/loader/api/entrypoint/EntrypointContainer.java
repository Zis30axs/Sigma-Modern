package net.fabricmc.loader.api.entrypoint;

import net.fabricmc.loader.api.ModContainer;

// MODIFIED for porting: embedded stand-in for fabric-loader
public interface EntrypointContainer<T> {
    T getEntrypoint();

    ModContainer getDeclaringMod();
}
