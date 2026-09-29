package com.mentalfrostbyte.jello.util.movement;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventJump;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventStrafe;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import java.util.Objects;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the local player's walking in step with a facing that is only reported to the server.
 *
 * <p>A module that reports a facing other than the camera's - {@code KillAura}'s silent rotation, a scaffold looking at
 * the block it places against - asks for a correction here, and the game applies it wherever it works movement out from
 * the facing: the keys' push and the sprint-jump boost, the direction keys themselves (for the modes that turn them),
 * and the elytra's and the swimmer's look. What each mode does is on {@link MovementCorrection}.</p>
 *
 * <h2>For a module</h2>
 * <pre>{@code
 * // Each tick, at the start of it (EventTick PRE), once the facing this tick reports is known:
 * MovementCorrector corrector = MovementCorrector.current();
 * if (corrector != null && reported != null) {
 *     corrector.request(this, reported, MovementCorrection.CLAUDE3);
 * }
 * // and in onDisable(), so a request never outlives its module:
 * corrector.release(this);
 * }</pre>
 * <p>A request lasts for the tick it was made in, and is gone at the end of it - so a module that stops asking stops
 * correcting, with nothing to remember to switch off. The facing must be the one the same tick's movement packet
 * reports: the server predicts each tick's movement from the facing that tick's packet carries. Only one request is
 * in force at a time: the highest {@code priority}, and the first made among equals.</p>
 *
 * <p>The mode follows LiquidBounce's {@code MovementCorrection}; see {@link MovementCorrection}.</p>
 */
public final class MovementCorrector {

    /** What a request gets when it names no priority. */
    public static final int DEFAULT_PRIORITY = 0;

    /**
     * How far past the edge of a direction the aim may drift before {@link MovementCorrection#CLAUDE3} lets go of it, in
     * degrees. Chosen by simulation (see {@code KeyRemapTest}): with an aim that circles and wobbles a few degrees a tick,
     * 6 keeps the keys from changing about half as often as {@code SILENT}'s rounding does, while the walk never strays
     * more than 28.5 degrees from the way it was meant (SILENT's 30) and strays 11.8 on average (SILENT's 12.5). Larger
     * values change the keys still less often but stray further; below 4 they change about a third more often.
     */
    static final float HYSTERESIS = 6.0F;

    /** A facing that has been asked for this tick. */
    public record Request(Object owner, Rotation look, MovementCorrection mode, int priority) {
        public Request {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(look, "look");
            Objects.requireNonNull(mode, "mode");
        }
    }

    private @Nullable Request active;
    /** CLAUDE3: the direction its keys made last tick, for its hysteresis; -1 for none. */
    private int direction = -1;

    // -Dsigma.debug.correctorStats: every 100 ticks, how much of them the corrector had something to do in, so an
    // experiment can tell that the facing it was to correct was there at all. Game thread.
    private static final boolean STATS = Boolean.getBoolean("sigma.debug.correctorStats");
    private int statTicks;
    private int statRequested;
    private int statMoving;
    private int statTurned;
    private double statYawDifference;

    /**
     * The corrector of the running client, or null before the client has started - the game draws and even ticks a
     * little before {@code Client.start()} runs, and a hook that asks then must find nothing rather than fail.
     */
    public static @Nullable MovementCorrector current() {
        Client client = Client.getInstance();
        return client.isStarted() ? client.getMovementCorrector() : null;
    }

    /** As {@link #request(Object, Rotation, MovementCorrection, int)} with the {@link #DEFAULT_PRIORITY}. */
    public void request(final Object owner, final Rotation look, final MovementCorrection mode) {
        this.request(owner, look, mode, DEFAULT_PRIORITY);
    }

    /**
     * Asks for the walking to be corrected to {@code look} for the rest of this tick. Game thread only.
     *
     * @param owner    who asks; the same object in {@link #release}
     * @param look     the facing this tick's movement packet reports (yaw as reported, not wrapped)
     * @param mode     how; {@link MovementCorrection#OFF} is a request for nothing to be corrected
     * @param priority the higher wins when two modules ask in the same tick; the first asker wins a tie
     */
    public void request(final Object owner, final Rotation look, final MovementCorrection mode, final int priority) {
        Request request = new Request(owner, look, mode, priority);
        if (this.active == null || priority > this.active.priority() || owner == this.active.owner()) {
            this.active = request;
        }
    }

    /** Withdraws {@code owner}'s request, if it has one in force - for when a module is switched off mid-tick. */
    public void release(final Object owner) {
        if (this.active != null && this.active.owner() == owner) {
            this.active = null;
        }
    }

    /** The request in force this tick, whatever its mode. */
    public @Nullable Request active() {
        return this.active;
    }

    /** The request in force this tick if it corrects anything. */
    private @Nullable Request correcting() {
        Request request = this.active;
        return request != null && request.mode().corrects() ? request : null;
    }

    // ------------------------------------------------------------------------------------------------ the game asks

    /** The end of the tick: what was asked for it is over. */
    @EventTarget
    public void onTick(final EventTick event) {
        if (event.isPost()) {
            this.active = null;
        }
    }

    /** The keys' push turns by the reported yaw. */
    @EventTarget
    public void onStrafe(final EventStrafe event) {
        Request request = this.correcting();
        if (request != null) {
            event.setYaw(request.look().yaw());
        }
    }

    /** So does the sprint-jump boost. */
    @EventTarget
    public void onJump(final EventJump event) {
        Request request = this.correcting();
        if (event.isPre() && request != null) {
            event.setYaw(request.look().yaw());
        }
    }

    /**
     * The direction keys the player's movement is to be worked out from this tick, given the ones they hold and the way
     * the camera faces: the same keys unless the mode in force turns them.
     */
    public Input correct(final Input keys, final float cameraYaw) {
        Input corrected = this.turn(keys, cameraYaw);
        if (STATS) {
            this.count(keys, corrected, cameraYaw);
        }
        return corrected;
    }

    private Input turn(final Input keys, final float cameraYaw) {
        Request request = this.correcting();
        if (request == null || !request.mode().turnsKeys()) {
            this.direction = -1;
            return keys;
        }

        float delta = cameraYaw - request.look().yaw();
        if (request.mode() == MovementCorrection.SILENT) {
            this.direction = -1;
            return KeyRemap.silent(keys, delta);
        }
        KeyRemap.Steered steered = KeyRemap.nearest(keys, delta, this.direction, HYSTERESIS);
        this.direction = steered.direction();
        return steered.keys();
    }

    private void count(final Input keys, final Input corrected, final float cameraYaw) {
        this.statTicks++;
        Request request = this.active;
        if (request != null) {
            this.statRequested++;
            boolean moving = keys.forward() != keys.backward() || keys.left() != keys.right();
            if (moving) {
                this.statMoving++;
                this.statYawDifference += Math.abs(Mth.wrapDegrees(cameraYaw - request.look().yaw()));
            }
        }
        if (!corrected.equals(keys)) {
            this.statTurned++;
        }
        if (this.statTicks >= 100) {
            Client.logger.info("Sigma debug: corrector, last {} ticks: {} with a request, {} of them moving (camera differs from the "
                    + "reported yaw by {} degrees on average), {} with the keys turned", this.statTicks, this.statRequested,
                    this.statMoving, this.statMoving == 0 ? 0 : Math.round(this.statYawDifference / this.statMoving),
                    this.statTurned);
            this.statTicks = this.statRequested = this.statMoving = this.statTurned = 0;
            this.statYawDifference = 0.0;
        }
    }

    /** The look the elytra and the swimmer are pushed along, or null to leave it to the entity's own. */
    public @Nullable Vec3 lookAngle() {
        Request request = this.correcting();
        return request == null ? null : Vec3.directionFromRotation(request.look().pitch(), request.look().yaw());
    }

    /** The pitch the elytra's lift is worked out from: the reported one under a correction, else {@code vanilla}. */
    public float xRot(final float vanilla) {
        Request request = this.correcting();
        return request == null ? vanilla : request.look().pitch();
    }
}
