package com.mentalfrostbyte.jello.anticheat.client;

import com.mentalfrostbyte.jello.anticheat.check.CheckSettings;
import com.mentalfrostbyte.jello.anticheat.observe.ObservedPlayers;
import com.mentalfrostbyte.jello.anticheat.observe.PlayerFlags;
import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;
import com.mentalfrostbyte.jello.module.impl.misc.ModuleAntiCheat;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Debug only ({@code -Dsigma.debug.openScreen=SUSPECTS|SUSPECTS_HUD}): puts a player who is not really there in
 * front of the local player and feeds the detector a made-up trajectory for them - a moment standing still, a
 * few reports claiming to be on the ground in mid-air, then a long run far too fast for a legal player.
 *
 * <p>Everything downstream is the real thing: the reports go through {@link ObservedPlayers} and the
 * {@link ClientWorldProbe} over the real level, so the checks, the chat alerts, the name tag and the suspect
 * list all fire exactly as they would for a real cheater. Only the packets are skipped, and the reports carry
 * made-up arrival times so the whole script plays at once.</p>
 *
 * <p>The spot is chosen with the same probe: open ground the checks will actually judge, not a tree top full of
 * vines, which they rightly refuse to.</p>
 */
public final class AntiCheatDemo {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long TICK = 50_000_000L;
    private static final int DEMO_ENTITY_ID = 0x7FFF0001;
    /** How far the fast run sweeps sideways, in blocks. */
    private static final double SWEEP = 1.8;

    private AntiCheatDemo() {
    }

    public static void seed(final ModuleAntiCheat module) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer me = mc.player;
        if (level == null || me == null) {
            return;
        }

        Vec3 spot = findOpenSpot(level, me);
        double x = spot.x;
        double y = spot.y;
        double z = spot.z;

        RemotePlayer body = new RemotePlayer(level, new GameProfile(UUID.randomUUID(), "Cheater_Demo"));
        // The server hands out entity ids; this one is made up, high enough not to meet a real one.
        body.setId(DEMO_ENTITY_ID);
        // Drawn three blocks in front of the camera so a capture shows the name tag; the trajectory below is judged
        // at the open spot, since the checks only ever see the positions they are fed.
        Vec3 look = me.getLookAngle();
        double length = Math.max(1.0E-3, Math.hypot(look.x, look.z));
        body.snapTo(me.getX() + look.x / length * 3.0, me.getY(), me.getZ() + look.z / length * 3.0, me.getYRot() + 180.0F, 0.0F);
        level.addEntity(body);

        ObservedPlayers players = module.observer().players();
        CheckSettings settings = module.observer().settings();
        long now = System.nanoTime();
        players.track(body.getId(), body.getUUID(), body.getGameProfile().name(), now - 60_000_000_000L);

        long t = now + TICK;
        int tick = 0;
        // Standing still: nothing to flag.
        for (; tick < 40; tick += 2) {
            report(players, body, x, y, z, true, 0, t + tick * TICK, settings);
        }

        // Claiming ground four blocks up.
        for (int i = 0; i < 8; i++, tick += 2) {
            report(players, body, x, y + 4.0, z, true, 0, t + tick * TICK, settings);
        }

        // Then back and forth at about 18 blocks a second, a long way past a sprint-hopper's ceiling.
        double lastX = x;
        for (int i = 0; i < 160; i++, tick += 2) {
            lastX = x + ((i % 4 < 2) ? 0.0 : SWEEP);
            report(players, body, lastX, y, z, true, PlayerFlags.SPRINTING, t + tick * TICK, settings);
        }

        TrackedPlayer tracked = players.get(body.getId());
        Sample last = tracked == null ? null : tracked.lastSample();
        LOGGER.info("Sigma debug: AntiCheat demo at ({}, {}, {}) fed {} samples; last {}; levels {}", x, y, z,
                tracked == null ? 0 : tracked.sampleCount(), last,
                tracked == null ? "-" : tracked.buffers().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().level()).toList());
    }

    private static void report(final ObservedPlayers players, final RemotePlayer body, final double x, final double y,
                               final double z, final boolean onGround, final int flags, final long nanos,
                               final CheckSettings settings) {
        players.onPosition(body.getId(), x, y, z, onGround,
                new ObservedPlayers.Snapshot(flags, 0.13, Sample.DEFAULT_JUMP_STRENGTH, Sample.DEFAULT_GRAVITY, false),
                nanos, settings);
    }

    /**
     * The nearest spot in front of the player where the checks can judge a standing player: level ground under
     * both ends of the sweep, nothing liquid or climbable around, open air four blocks up. Falls back to the spot
     * straight ahead if there is none (the demo then shows what refusing to judge looks like).
     */
    private static Vec3 findOpenSpot(final ClientLevel level, final LocalPlayer me) {
        ClientWorldProbe probe = new ClientWorldProbe();
        Vec3 look = me.getLookAngle();
        double length = Math.max(1.0E-3, Math.hypot(look.x, look.z));
        double fx = look.x / length;
        double fz = look.z / length;
        double rx = -fz;
        double rz = fx;

        Vec3 fallback = null;
        for (int distance = 4; distance <= 24; distance++) {
            for (int side = 0; side <= 16; side++) {
                int lateral = (side + 1) / 2 * (side % 2 == 0 ? 1 : -1);
                double x = Math.floor(me.getX() + fx * distance + rx * lateral) + 0.5;
                double z = Math.floor(me.getZ() + fz * distance + rz * lateral) + 0.5;
                double y = groundY(level, x, z, me.getY());
                if (fallback == null) {
                    fallback = new Vec3(x, y, z);
                }

                if (openAt(level, probe, x, y, z) && openAt(level, probe, x + SWEEP, y, z)
                        && probe.supported(x, y, z, 0.0, 0.01) && !probe.unjudgedEnvironment(x, y + 4.0, z)) {
                    return new Vec3(x, y, z);
                }
            }
        }

        return fallback;
    }

    private static boolean openAt(final ClientLevel level, final ClientWorldProbe probe, final double x, final double y, final double z) {
        return groundY(level, x, z, Double.NaN) == y && probe.chunkLoaded(x, z) && !probe.unjudgedEnvironment(x, y, z);
    }

    /** Where a player standing at this column would have their feet. */
    private static double groundY(final ClientLevel level, final double x, final double z, final double fallback) {
        int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
        return height > level.getMinY() ? height : fallback;
    }
}
