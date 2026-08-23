package net.caffeinemc.mods.sodium.client.services.vanilla;

import java.nio.file.Path;
import net.caffeinemc.mods.sodium.client.services.PlatformRuntimeInformation;
import net.minecraft.client.Minecraft;

/**
 * MODIFIED for porting: replaces the loader specific {@code FabricRuntimeInformation} /
 * {@code NeoForgeRuntimeInformation}. There is no mod loader in this project, so the game directory comes straight from
 * {@link Minecraft} and there is neither a mod list, an early loading screen, nor a refmap.
 */
public class VanillaRuntimeInformation implements PlatformRuntimeInformation {
    /**
     * MODIFIED for porting: the loader knew the game directory before the client was constructed, and sodium's pre-launch /
     * initialization code relies on that (it loads its options file). {@link net.minecraft.client.main.Main} therefore hands
     * the parsed game directory over before {@link Minecraft} exists.
     */
    private static Path gameDirectory;

    public static void setGameDirectory(final Path directory) {
        gameDirectory = directory;
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return false;
    }

    @Override
    public Path getGameDirectory() {
        if (gameDirectory != null) {
            return gameDirectory;
        }

        return Minecraft.getInstance().gameDirectory.toPath();
    }

    @Override
    public Path getConfigDirectory() {
        return this.getGameDirectory().resolve("config");
    }

    @Override
    public boolean platformHasEarlyLoadingScreen() {
        return false;
    }

    @Override
    public boolean platformUsesRefmap() {
        return false;
    }

    @Override
    public boolean isModInLoadingList(final String modId) {
        return false;
    }

    @Override
    public boolean usesBakedQuadColorMultiplication() {
        // Same as the Fabric implementation: vanilla does not multiply the vertex color by the baked quad color.
        return false;
    }
}
