package com.mentalfrostbyte.jello.selfcheck.engine.grim;

import com.github.retrooper.packetevents.util.Vector3d;
import com.mentalfrostbyte.jello.selfcheck.api.EngineContext;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Where the ported Grim sources hand SelfDetection what a server would have carried out. The {@code MODIFIED for
 * porting} sites in {@code ac.grim.grimac} call in here instead of kicking or teleporting the player.
 *
 * <p>This class is loaded by the connection's own isolating class loader, like Grim itself, so its one static field
 * is that connection's context and no other's.</p>
 */
public final class SigmaGrim {

    private static volatile @Nullable EngineContext context;

    private SigmaGrim() {
    }

    static void bind(final EngineContext engineContext) {
        context = engineContext;
    }

    static @Nullable EngineContext context() {
        return context;
    }

    /** {@code GrimPlayer.disconnect}: the server would disconnect the player now, for {@code reason}. */
    public static void wouldDisconnect(final String reason) {
        EngineContext current = context;
        if (current != null) {
            current.verdicts().disconnect(reason);
        }
    }

    /**
     * {@code SetbackTeleportUtil}: the server would teleport the player back ({@code resync}: resynchronise their
     * position) to {@code to}. Always returns true, so the caller stops before touching any state.
     */
    public static boolean wouldSetback(final boolean resync, final @Nullable Vector3d to) {
        EngineContext current = context;
        if (current != null) {
            String where = to == null ? "" : String.format(Locale.ROOT, " to %.2f, %.2f, %.2f", to.getX(), to.getY(), to.getZ());
            current.verdicts().setback((resync ? "position resync" : "setback") + where);
        }
        return true;
    }
}
