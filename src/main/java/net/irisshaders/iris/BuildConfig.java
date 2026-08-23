package net.irisshaders.iris;

/**
 * MODIFIED for porting: upstream generates this class with the {@code com.github.gmazzo.buildconfig} Gradle plugin (see
 * {@code common/build.gradle.kts}). This project has no such generator, so the class is written out with exactly the values
 * the plugin was configured to emit for the {@code main} source set.
 */
public final class BuildConfig {
    public static final boolean IS_SHARED_BETA = false;

    public static final boolean ACTIVATE_RENDERDOC = false;

    public static final String BETA_TAG = "";

    public static final int BETA_VERSION = 0;

    private BuildConfig() {
    }
}
