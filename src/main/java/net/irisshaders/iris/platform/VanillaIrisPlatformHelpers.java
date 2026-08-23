package net.irisshaders.iris.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * MODIFIED for porting: replaces the loader specific {@code IrisFabricHelpers} / {@code IrisForgeHelpers}. There is no mod
 * loader in this project, so:
 * <ul>
 *   <li>{@link #isModLoaded(String)} is always false. Iris only ever asks about {@code distanthorizons},
 *       {@code continuity}, {@code monocle} and {@code fabric-resource-loader-v0}; none of them can be installed here (there
 *       is nothing to install them with), so the answer is the same one a Fabric install without those mods would give.</li>
 *   <li>{@link #getVersion()} is a constant instead of being read from the mod metadata.</li>
 *   <li>{@link #getGameDir()} / {@link #getConfigDir()} are handed over by {@link net.minecraft.client.main.Main} before the
 *       client is constructed, because Iris reads them from {@code Iris#onEarlyInitialize} - which runs inside the
 *       {@code Minecraft} constructor - exactly like the loader provided them.</li>
 *   <li>{@link #compareVersions(String, String)} used Fabric Loader's {@code SemanticVersion}; it is implemented here
 *       directly. It is only used by {@link net.irisshaders.iris.UpdateChecker} to compare Iris's own version against the
 *       version advertised by the update server, both of which are plain semantic versions.</li>
 *   <li>{@link #registerKeyBinding(KeyMapping)} used Fabric API's {@code KeyMappingHelper}, which appends the mapping to
 *       {@link net.minecraft.client.Options#keyMappings}. The mappings are collected here and {@code Options#keyMappings}
 *       appends {@link #extraKeyMappings()} to its own array - Iris registers its keybinds before {@code new Options(...)}
 *       runs, so they are all present by then.</li>
 *   <li>{@link #useELS()} and {@link #getBlockAppearance} keep the Fabric implementation's answers ({@code false} and the
 *       state unchanged); the NeoForge implementation is the one that answers differently.</li>
 * </ul>
 */
public class VanillaIrisPlatformHelpers implements IrisPlatformHelpers {
    /**
     * The version of Iris that was ported. Upstream reads this from the mod metadata, which does not exist here.
     */
    public static final String IRIS_VERSION = "1.11.2+mc26.2";

    private static final List<KeyMapping> EXTRA_KEY_MAPPINGS = new ArrayList<>();

    private static Path gameDir = Path.of(".");

    /**
     * Called by {@link net.minecraft.client.main.Main} with the parsed game directory, before Iris initializes.
     */
    public static void setGameDir(final Path directory) {
        gameDir = directory;
    }

    /**
     * The key mappings Iris registered, for {@link net.minecraft.client.Options} to append to its own array.
     */
    public static KeyMapping[] extraKeyMappings() {
        return EXTRA_KEY_MAPPINGS.toArray(KeyMapping[]::new);
    }

    @Override
    public boolean isModLoaded(final String modId) {
        return false;
    }

    @Override
    public String getVersion() {
        return IRIS_VERSION;
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return false;
    }

    @Override
    public Path getGameDir() {
        return gameDir;
    }

    @Override
    public Path getConfigDir() {
        return gameDir.resolve("config");
    }

    @Override
    public int compareVersions(final String currentVersion, final String semanticVersion) throws Exception {
        try {
            return compareSemanticVersions(currentVersion, semanticVersion);
        } catch (RuntimeException e) {
            throw new Exception(e);
        }
    }

    /**
     * Compares two semantic versions the way Fabric Loader's {@code SemanticVersion} does for the shapes Iris uses: the
     * dot-separated numeric components are compared first (a missing component counts as zero), then a version with a
     * pre-release suffix sorts before the same version without one, and finally the pre-release suffixes are compared
     * component by component (numeric components numerically and with lower precedence than non-numeric ones). Build
     * metadata is ignored, as the specification requires.
     */
    private static int compareSemanticVersions(final String left, final String right) {
        String leftCore = stripBuildMetadata(left);
        String rightCore = stripBuildMetadata(right);
        String leftPreRelease = preRelease(leftCore);
        String rightPreRelease = preRelease(rightCore);
        String[] leftComponents = core(leftCore).split("\\.");
        String[] rightComponents = core(rightCore).split("\\.");

        for (int i = 0; i < Math.max(leftComponents.length, rightComponents.length); i++) {
            int comparison = Integer.compare(component(leftComponents, i), component(rightComponents, i));
            if (comparison != 0) {
                return comparison;
            }
        }

        if (leftPreRelease.isEmpty() || rightPreRelease.isEmpty()) {
            // A version without a pre-release suffix is greater than the same version with one.
            return Integer.compare(leftPreRelease.isEmpty() ? 1 : 0, rightPreRelease.isEmpty() ? 1 : 0);
        }

        String[] leftIdentifiers = leftPreRelease.split("\\.");
        String[] rightIdentifiers = rightPreRelease.split("\\.");

        for (int i = 0; i < Math.min(leftIdentifiers.length, rightIdentifiers.length); i++) {
            String leftIdentifier = leftIdentifiers[i];
            String rightIdentifier = rightIdentifiers[i];
            boolean leftNumeric = isNumeric(leftIdentifier);
            boolean rightNumeric = isNumeric(rightIdentifier);
            int comparison;

            if (leftNumeric && rightNumeric) {
                comparison = Integer.compare(Integer.parseInt(leftIdentifier), Integer.parseInt(rightIdentifier));
            } else if (leftNumeric != rightNumeric) {
                // Numeric identifiers always have lower precedence than non-numeric ones.
                comparison = leftNumeric ? -1 : 1;
            } else {
                comparison = leftIdentifier.compareTo(rightIdentifier);
            }

            if (comparison != 0) {
                return comparison;
            }
        }

        return Integer.compare(leftIdentifiers.length, rightIdentifiers.length);
    }

    private static String stripBuildMetadata(final String version) {
        int plus = version.indexOf('+');
        return plus == -1 ? version : version.substring(0, plus);
    }

    private static String core(final String version) {
        int dash = version.indexOf('-');
        return dash == -1 ? version : version.substring(0, dash);
    }

    private static String preRelease(final String version) {
        int dash = version.indexOf('-');
        return dash == -1 ? "" : version.substring(dash + 1);
    }

    private static int component(final String[] components, final int index) {
        if (index >= components.length) {
            return 0;
        }

        String component = components[index];
        if (!isNumeric(component)) {
            throw new NumberFormatException("Not a semantic version component: " + component);
        }

        return Integer.parseInt(component);
    }

    private static boolean isNumeric(final String value) {
        if (value.isEmpty()) {
            return false;
        }

        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                return false;
            }
        }

        return true;
    }

    @Override
    public KeyMapping registerKeyBinding(final KeyMapping keyMapping) {
        EXTRA_KEY_MAPPINGS.add(keyMapping);
        return keyMapping;
    }

    @Override
    public boolean useELS() {
        return false;
    }

    @Override
    public BlockState getBlockAppearance(
        final BlockAndTintGetter level, final BlockState state, final Direction cullFace, final BlockPos pos
    ) {
        return state;
    }
}
