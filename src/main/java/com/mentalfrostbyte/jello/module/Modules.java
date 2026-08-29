package com.mentalfrostbyte.jello.module;

import com.mentalfrostbyte.Client;
import org.jspecify.annotations.Nullable;

/**
 * How the game asks a module whether it is on.
 *
 * <p>Some features are not something a module does to the game but something the game asks the module
 * about while it draws. Those sites call {@link #enabled(Class)} and get either the module or null:</p>
 *
 * <pre>{@code
 * // Sigma hook: ...
 * LowFire lowFire = Modules.enabled(LowFire.class);
 * if (lowFire != null) {
 *     height = lowFire.getHeight();
 * }
 * }</pre>
 *
 * <p>Going through here rather than reaching for the registry directly settles two things every such site
 * would otherwise have to remember. The registry hands back a module whether or not it is switched on, so a
 * caller that forgets to check would apply a disabled module's settings forever. And rendering starts
 * before the client does - the game draws its loading screen long before {@code Client.start()} runs - so a
 * query has to survive being asked too early.</p>
 */
public final class Modules {

    private Modules() {
    }

    /**
     * The single instance of {@code type} if the client is running and that module is switched on, and null
     * otherwise. A module class that was never registered is still a programming error and still throws.
     */
    public static <T extends Module> @Nullable T enabled(final Class<T> type) {
        Client client = Client.getInstance();
        if (!client.isStarted()) {
            return null;
        }

        T module = client.getModuleManager().get(type);
        return module.isEnabled() ? module : null;
    }
}
