package malte0811.ferritecore.mixin.config;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

public class FerriteConfig {
    public static final Option NEIGHBOR_LOOKUP;
    public static final Option PROPERTY_MAP;
    public static final Option DEDUP_BLOCKSTATE_CACHE;
    public static final Option COMPACT_FAST_MAP;
    public static final Option THREADING_DETECTOR;
    public static final Option DATACOMPONENTS;

    static {
        ConfigBuilder builder = new ConfigBuilder();
        NEIGHBOR_LOOKUP = builder.createOption("replaceNeighborLookup", "Replace the blockstate neighbor table");
        PROPERTY_MAP = builder.createOption(
                "replacePropertyMap",
                "Do not store the properties of a state explicitly and read them" +
                        "from the replace neighbor table instead. Requires " + NEIGHBOR_LOOKUP.getName() + " to be enabled",
                NEIGHBOR_LOOKUP
        );
        DEDUP_BLOCKSTATE_CACHE = builder.createOption(
                "blockstateCacheDeduplication",
                "Deduplicate cached data for blockstates, most importantly collision and render shapes"
        );
        DATACOMPONENTS = builder.createOption(
                "dataComponentPatch",
                "Save memory overhead from empty data component maps/patched"
        );
        THREADING_DETECTOR = builder.createOptInOption(
                "useSmallThreadingDetector",
                "Replace objects used to detect multi-threaded access to chunks by a much smaller field. This option" +
                        " is disabled by default due to very rare and very hard-to-reproduce crashes, use at your own" +
                        " risk!"
        );
        COMPACT_FAST_MAP = builder.createOptInOption(
                "compactFastMap",
                "Use a slightly more compact, but also slightly slower representation for block states"
        );
        builder.finish();
    }

    public static class ConfigBuilder {
        private final List<Option> options = new ArrayList<>();

        public Option createOption(String name, String comment, Option... dependencies) {
            Option result = new Option(name, comment, true, dependencies);
            options.add(result);
            return result;
        }

        public Option createOptInOption(String name, String comment, Option... dependencies) {
            Option result = new Option(name, comment, false, dependencies);
            options.add(result);
            return result;
        }

        // MODIFIED for porting: upstream loaded the values from a loader-managed config file (Fabric/NeoForge
        // IPlatformConfigHooks) and let other mods override single options through their mod metadata. Neither exists
        // in this environment, so every option simply takes the default value it has upstream.
        private void finish() {
            Map<String, Boolean> defaults = new HashMap<>();
            for (Option option : this.options) {
                defaults.put(option.getName(), option.getDefaultValue());
            }
            Predicate<String> isEnabled = name -> Objects.requireNonNull(defaults.get(name));
            for (Option option : this.options) {
                option.set(isEnabled);
            }
        }
    }

    public static class Option {
        private final String name;
        private final String comment;
        private final boolean defaultValue;
        private final List<Option> dependencies;
        @Nullable
        private Boolean value;

        public Option(String name, String comment, boolean defaultValue, Option... dependencies) {
            this.name = name;
            this.comment = comment;
            this.defaultValue = defaultValue;
            this.dependencies = Arrays.asList(dependencies);
        }

        public void set(Predicate<String> isEnabled) {
            final boolean enabled = isEnabled.test(getName());
            if (enabled) {
                for (Option dep : dependencies) {
                    if (!isEnabled.test(dep.getName())) {
                        throw new IllegalStateException(
                                getName() + " is enabled in the FerriteCore config, but " + dep.getName()
                                        + " is not. This is not supported!"
                        );
                    }
                }
            }
            this.value = enabled;
        }

        public String getName() {
            return name;
        }

        public String getComment() {
            return comment;
        }

        public boolean isEnabled() {
            return Objects.requireNonNull(value);
        }

        public boolean getDefaultValue() {
            return defaultValue;
        }
    }
}
