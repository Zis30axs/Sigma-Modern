package net.fabricmc.loader.api.metadata;

import java.util.List;
import net.fabricmc.loader.api.Version;

// MODIFIED for porting: embedded stand-in for fabric-loader
public final class ModMetadata {
    private final String id;

    private ModMetadata(final String id) {
        this.id = id;
    }

    public static ModMetadata of(final String id) {
        return new ModMetadata(id);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return id;
    }

    public Version getVersion() {
        return () -> "1.0.0";
    }

    public List<Person> getAuthors() {
        return List.of();
    }
}
