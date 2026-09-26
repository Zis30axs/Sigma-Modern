package ac.grim.grimac.platform.api;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

@AllArgsConstructor
public enum Platform {

    FABRIC("fabric"),
    BUKKIT("bukkit"),
    FOLIA("folia"),
    // MODIFIED for porting: Grim embedded in the Sigma client by SelfDetection (com.mentalfrostbyte.jello.selfcheck).
    SIGMA("sigma");

    @Getter private final String name;

    public static @Nullable Platform getByName(String name) {
        for (Platform platform : values()) {
            if (platform.getName().equalsIgnoreCase(name)) return platform;
        }
        return null;
    }

}
