package ac.grim.grimac.utils.viaversion;

import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.reflection.ReflectionUtils;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ViaVersionUtil {
    // MODIFIED for porting: upstream asked whether the ViaVersion *plugin* runs on the server. In the client the
    // class is always there - it is ViaFabricPlus's own ViaVersion, translating for the client - and Grim must not
    // treat it as the server's: SelfDetection judges the wire in the server's protocol with client version = server
    // version, so there is never a Via between the two. Always "not installed".
    public static final boolean isAvailable = false;

    static {
        if (!isAvailable && ReflectionUtils.hasClass("us.myles.ViaVersion.api.Via")) {
            LogUtil.error("Using unsupported ViaVersion 4.0 API, update ViaVersion to 5.0");
        }
    }
}
