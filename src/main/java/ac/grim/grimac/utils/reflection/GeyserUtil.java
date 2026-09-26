package ac.grim.grimac.utils.reflection;

import lombok.experimental.UtilityClass;

import java.util.UUID;

@UtilityClass
public class GeyserUtil {
    // MODIFIED for porting: SelfDetection judges the local Java Edition player only (Bedrock targets are not
    // supported), and Geyser/Floodgate are server plugins that do not exist in the client. Upstream checked
    // FloodgateApi/Geyser.api() when their classes were present; here the answer is always "not Bedrock", which also
    // drops the compile-time dependency on the Geyser and Floodgate APIs.
    public static boolean isBedrockPlayer(UUID uuid) {
        return false;
    }
}
