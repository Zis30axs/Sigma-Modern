package net.fabricmc.loader.api;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

// MODIFIED for porting: embedded stand-in for fabric-loader (no mod loader in this environment)
public final class FabricLoader {
    private static final FabricLoader INSTANCE = new FabricLoader();
    private final List<ModContainer> mods = new ArrayList<>();

    private FabricLoader() {
        for (final String id : new String[] {"minecraft", "viafabricplus", "sodium", "iris", "sodium-extra", "lithium", "ferrite-core"}) {
            mods.add(new ModContainer(id));
        }
    }

    public static FabricLoader getInstance() {
        return INSTANCE;
    }

    public Path getConfigDir() {
        return Paths.get("run", "config");
    }

    public boolean isModLoaded(final String id) {
        return getModContainer(id).isPresent();
    }

    public Optional<ModContainer> getModContainer(final String id) {
        return mods.stream().filter(m -> m.getMetadata().getId().equals(id)).findFirst();
    }

    public Collection<ModContainer> getAllMods() {
        return List.copyOf(mods);
    }

    public <T> List<EntrypointContainer<T>> getEntrypointContainers(final String key, final Class<T> type) {
        return java.util.Collections.emptyList();
    }
}
