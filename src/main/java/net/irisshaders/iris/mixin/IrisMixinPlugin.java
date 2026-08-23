package net.irisshaders.iris.mixin;

import com.google.common.base.Splitter;
import com.google.common.io.Files;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import net.irisshaders.iris.platform.IrisPlatformHelpers;

/**
 * MODIFIED for porting: upstream this class implements {@code IMixinConfigPlugin} for every one of Iris's mixin configs. Its
 * whole job is the decision in {@code shouldApplyMixin}: <em>none</em> of Iris's mixins are applied when the game is
 * configured to use the Vulkan backend, except the ones whose name contains {@code VKOnly}, which are applied only then. In
 * other words Iris is inactive under Vulkan apart from registering its shader-pack keybind.
 * <p>
 * There are no mixins here, so the plugin interface and its empty callbacks are gone, but the decision has to be made at run
 * time instead: the {@code usingVulkan} flag and the static initializer that computes it (by reading
 * {@code preferredGraphicsBackend} out of {@code options.txt}, before the game has parsed its options) are kept exactly as
 * they were, and {@link #isEnabled()} / {@link #isVulkanOnlyEnabled()} stand in for {@code shouldApplyMixin} at every place
 * a former mixin hooked into.
 */
public class IrisMixinPlugin {
    private static final Splitter OPTION_SPLITTER = Splitter.on(':').limit(2);

    public static boolean usingVulkan;

    static {
        BufferedReader reader = null;
        boolean check = true;
        try {
            reader = Files.newReader(IrisPlatformHelpers.getInstance().getGameDir().resolve("options.txt").toFile(), StandardCharsets.UTF_8);
        } catch (FileNotFoundException e) {
            usingVulkan = false;
            check = false;
        }

        if (check) {
            Map<String, String> options = new HashMap<>();

            try {
                reader.lines().forEach(line -> {
                    try {
                        Iterator<String> iterator = OPTION_SPLITTER.split(line).iterator();
                        options.put((String) iterator.next(), (String) iterator.next());
                    } catch (Exception var3) {
                    }
                });
            } catch (Throwable var6) {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Throwable var5) {
                        var6.addSuppressed(var5);
                    }
                }

                throw var6;
            }

            if (options.get("preferredGraphicsBackend") != null) {
                usingVulkan = options.get("preferredGraphicsBackend").toLowerCase(Locale.ROOT).contains("vulkan");
            } else {
                usingVulkan = false;
            }
        }
    }

    /**
     * MODIFIED for porting: was {@code shouldApplyMixin} for every mixin whose name does not contain {@code VKOnly}.
     */
    public static boolean isEnabled() {
        return !usingVulkan;
    }

    /**
     * MODIFIED for porting: was {@code shouldApplyMixin} for the {@code VKOnly*} mixins.
     */
    public static boolean isVulkanOnlyEnabled() {
        return usingVulkan;
    }
}
