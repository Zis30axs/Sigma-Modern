package malte0811.ferritecore.util;

public class Constants {
    public static final String MODID = "ferritecore";

    // MODIFIED for porting: upstream also exposed PLATFORM_HOOKS (a reflectively loaded loader-specific
    // IPlatformHooks implementation used to look up obfuscated field names) and DISABLED_OVERRIDES_KEY (the mod
    // metadata key other mods used to disable single options). Both only exist to serve the Fabric/NeoForge runtime and
    // have no meaning in a direct source port, where the fields are accessed directly.
}
