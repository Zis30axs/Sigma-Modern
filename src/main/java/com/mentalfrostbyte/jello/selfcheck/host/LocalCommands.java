package com.mentalfrostbyte.jello.selfcheck.host;

import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Chat lines the player types to the self-check, such as {@code .grim alerts}. Such a line is answered locally and
 * never sent to the server.
 *
 * <p>Only a root an engine registered - or {@code grim}, which is known even before a session exists - is taken;
 * any other line that happens to start with the prefix ({@code .lol}) is ordinary chat.</p>
 */
public final class LocalCommands {

    /** Roots taken even with no session running, so that they get an answer instead of going to the server. */
    static final Set<String> ALWAYS_KNOWN = Set.of("grim");

    private static final Pattern ROOT = Pattern.compile("[A-Za-z0-9_-]+");

    private LocalCommands() {
    }

    /**
     * Handles {@code message} if it is a self-check command and returns true; returns false for anything that should
     * go to the server as usual. {@code reply} receives a plain-text line when the command cannot be run.
     */
    public static boolean handle(final String message, final String prefix, final @Nullable SelfCheckSession session,
                                 final Consumer<String> reply) {
        if (prefix.isEmpty() || !message.startsWith(prefix)) {
            return false;
        }
        String rest = message.substring(prefix.length());
        int space = rest.indexOf(' ');
        String root = (space < 0 ? rest : rest.substring(0, space)).toLowerCase(Locale.ROOT);
        String arguments = space < 0 ? "" : rest.substring(space + 1).trim();
        if (!ROOT.matcher(root).matches()) {
            return false;
        }

        if (session != null && session.command(root, arguments)) {
            return true;
        }
        if (!ALWAYS_KNOWN.contains(root)) {
            return false;
        }
        reply.accept(session == null
                ? "SelfDetection is not running: it only checks multiplayer connections opened while the module is on."
                : "No self-check engine is answering " + prefix + root + " on this server.");
        return true;
    }
}
