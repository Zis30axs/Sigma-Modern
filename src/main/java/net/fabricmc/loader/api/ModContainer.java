package net.fabricmc.loader.api;

import net.fabricmc.loader.api.metadata.ModMetadata;

// MODIFIED for porting: embedded stand-in for fabric-loader
public final class ModContainer {
    private final ModMetadata metadata;

    public ModContainer(final String id) {
        this.metadata = ModMetadata.of(id);
    }

    public ModMetadata getMetadata() {
        return metadata;
    }
}
