package com.mentalfrostbyte.jello.module.impl.combat;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventRunLoop;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.util.math.Rotations;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Saves up time near an enemy and spends it to land the first hit.
 *
 * <p>While an enemy is near but out of reach, the game is slowed down slightly, which banks timer
 * balance: every tick that runs slower than real time leaves the client behind the server. Once the
 * balance pays for a hit, it is spent — either by speeding the game up ({@link Mode#TIMER}) or by
 * running extra ticks at once ({@link Mode#HOLD_FIRST} holds ticks back first, {@link Mode#BURST_FIRST}
 * bursts first and holds back the ticks the balance did not cover after). It works best together with
 * KillAura, which does the actual clicking.</p>
 *
 * <p>Ported from LiquidBounce ({@code nextgen}, {@code features/module/modules/combat}: {@code ModuleTickBase}
 * and {@code utils/client/Timer}), GPL-3.0, Copyright (c) 2015 - 2026 CCBlueX. Differences from upstream,
 * all forced by what Sigma has or has not:</p>
 * <ul>
 *     <li>Upstream's global {@code Timer} (priority speed requests, hold/burst in the frame's tick loop,
 *     balance) is replaced by two small hooks in Minecraft's own tick loop: {@code getTickTargetMillis}
 *     multiplies in this module's requested speed (the BlockFly precedent), and {@code runTick} asks
 *     {@link #adjustTicksToDo(int)} for holds and bursts. The balance lives in this module and is accounted
 *     per frame from the ticks that actually ran.</li>
 *     <li>Upstream simulates the local player with {@code SimulatedPlayer} (full movement simulation with
 *     input and collision) to plan boosts. Sigma has no player simulation, so future positions are linear
 *     extrapolation with gravity and a flat-ground assumption; hit checks use the enemy's current box, as
 *     upstream does, and only the knockback follow-through extrapolates the enemy.</li>
 *     <li>Upstream's {@code Requires} is a multi-choice; here it is four separate booleans (default:
 *     KillAura only).</li>
 *     <li>Upstream reads the enemy's attack cooldown and a server-side hurt-time correction from its combat
 *     tracking; Sigma has no such data, so the enemy-cooldown branch of Advantage is dropped and hurt time
 *     is read as-is.</li>
 *     <li>Upstream sums knockback resistance from the enemy's armor items; here the synced
 *     {@code KNOCKBACK_RESISTANCE} attribute is used instead.</li>
 *     <li>Upstream re-plans while holding (a packet handler releases the hold early once the balance pays);
 *     here a hold runs its full course and the module re-plans from the current state afterwards.</li>
 *     <li>Upstream's Visuals (path line, balance ring, afterimage) are not ported; they need LBNG's render
 *     pipeline.</li>
 *     <li>KillAura's click schedule and its own minimum cooldown are not readable here, so
 *     {@code killAuraClicksAt} approximates with the attack cooldown being full.</li>
 * </ul>
 */
public class TickBase extends Module {

    /** How the saved time is spent. */
    public enum Mode {
        /** Speeds the game up until the hit lands. */
        TIMER,
        /** Holds the game back, then bursts once the balance pays for it. */
        HOLD_FIRST,
        /** Bursts right away, then holds back what the balance did not cover. */
        BURST_FIRST;

        @Override
        public String toString() {
            return switch (this) {
                case TIMER -> "Timer";
                case HOLD_FIRST -> "HoldFirst";
                case BURST_FIRST -> "BurstFirst";
            };
        }
    }

    /** Where a burst mode is in its sequence; Timer mode does not use this. */
    private enum BurstState {
        IDLE,
        HOLDING,
        DELAY,
        BURST_WAIT,
        HOLD_AFTER,
        COOLDOWN
    }

    /** One tick of a simulated path: feet position, velocity, and whether the step lands or falls. */
    record Step(Vec3 pos, Vec3 velocity, boolean onGround, double fallDistance) {

        Vec3 eyes(final float eyeHeight) {
            return this.pos.add(0.0, eyeHeight, 0.0);
        }

        boolean falling() {
            return !this.onGround && this.fallDistance > 0.0;
        }
    }

    /** A hit on an entity, landing on a point. */
    private record Hit(Entity entity, Vec3 point) {
    }

    /** The tick a planned boost lands its hit on (-1: no hit; 0: already in reach), and the hit if it pays off. */
    private record BoostPlan(int ticks, @Nullable Hit hit) {
    }

    private final EnumSetting<Mode> mode = this.register(new EnumSetting<>("Mode",
            "How the saved time is spent: Timer speeds the game up, HoldFirst holds back then bursts, "
                    + "BurstFirst bursts right away and holds back the rest after.",
            Mode.TIMER));

    private final BooleanSetting requireKillAura = this.register(new BooleanSetting("Require KillAura",
            "Only spend balance while KillAura is running with a target.", true));
    private final BooleanSetting requireLookingAt = this.register(new BooleanSetting("Require Looking At",
            "Only spend balance while an enemy is under the crosshair.", false));
    private final BooleanSetting requireClick = this.register(new BooleanSetting("Require Click",
            "Only spend balance while the attack key is held.", false));
    private final BooleanSetting requireOnGround = this.register(new BooleanSetting("Require On Ground",
            "Only spend balance while standing on the ground.", false));

    private final NumberSetting chance = this.register(new NumberSetting("Chance",
            "Percent chance to take an opportunity to hit earlier, rolled once per opportunity.", 100.0F, 0.0F, 100.0F, 0.5F));
    private final NumberSetting cooldownMin = this.register(new NumberSetting("Cooldown Min",
            "Shortest wait between spends, in ticks.", 0.0F, 0.0F, 100.0F, 1.0F));
    private final NumberSetting cooldownMax = this.register(new NumberSetting("Cooldown Max",
            "Longest wait between spends, in ticks.", 0.0F, 0.0F, 100.0F, 1.0F));
    private final NumberSetting attackCooldown = this.register(new NumberSetting("Attack Cooldown",
            "How full the attack cooldown has to be when the hit lands, where the server has one.", 1.0F, 0.0F, 1.0F, 0.05F));

    private final NumberSetting maxBalance = this.register(new NumberSetting("Max Balance",
            "Most ticks of balance charging saves up.", 3.0F, 0.0F, 40.0F, 1.0F));
    private final NumberSetting chargeRange = this.register(new NumberSetting("Charge Range",
            "How close an enemy has to be, in blocks, before charging starts saving up time.", 50.0F, 0.0F, 500.0F, 1.0F));
    private final NumberSetting chargeSpeed = this.register(new NumberSetting("Charge Speed",
            "Game speed while charging. Below 1 banks balance; 1 never charges.", 0.9F, 0.1F, 1.0F, 0.05F));
    private final BooleanSetting chargeWhileUsingItem = this.register(new BooleanSetting("Charge While Using Item",
            "Also charge while eating, drawing a bow, or otherwise using an item.", false));

    private final NumberSetting range = this.register(new NumberSetting("Range",
            "How far away a hit can land, in blocks. KillAura's own range still decides its clicks.", 3.0F, 1.0F, 6.0F, 0.05F));
    private final BooleanSetting throughWalls = this.register(new BooleanSetting("Through Walls",
            "Plan hits through blocks as well as in the open.", false));

    private final NumberSetting boostSpeed = this.register(new NumberSetting("Boost Speed",
            "Timer mode: how much faster the game runs while boosting.", 5.0F, 1.0F, 10.0F, 0.5F));
    private final NumberSetting maxTicks = this.register(new NumberSetting("Max Ticks",
            "HoldFirst/BurstFirst: most ticks a burst runs at once.", 4.0F, 1.0F, 20.0F, 1.0F));
    private final NumberSetting delayMin = this.register(new NumberSetting("Delay Min",
            "HoldFirst/BurstFirst: shortest normal ticks between holding back and bursting.", 0.0F, 0.0F, 20.0F, 1.0F));
    private final NumberSetting delayMax = this.register(new NumberSetting("Delay Max",
            "HoldFirst/BurstFirst: longest normal ticks between holding back and bursting.", 0.0F, 0.0F, 20.0F, 1.0F));

    private final BooleanSetting advantage = this.register(new BooleanSetting("Advantage",
            "Only spend when the hit wins the exchange: the enemy cannot hit back within Knockback Ticks.", true));
    private final NumberSetting enemyRange = this.register(new NumberSetting("Enemy Range",
            "Advantage: how far away the enemy has to be pushed to be out of its reach.", 3.0F, 0.0F, 8.0F, 0.1F));
    private final NumberSetting knockbackTicks = this.register(new NumberSetting("Knockback Ticks",
            "Advantage: ticks after the hit in which the enemy must not be able to hit back.", 2.0F, 0.0F, 10.0F, 1.0F));
    private final NumberSetting knockbackMultiplier = this.register(new NumberSetting("Knockback Multiplier",
            "Advantage: for servers whose knockback differs from vanilla.", 1.0F, 0.0F, 3.0F, 0.1F));
    private final BooleanSetting skipInvulnerable = this.register(new BooleanSetting("Skip Invulnerable",
            "Advantage: do not spend on a hit that lands while the enemy is still invulnerable.", true));
    private final BooleanSetting criticals = this.register(new BooleanSetting("Criticals",
            "Advantage: a critical hit also counts as winning the exchange.", true));

    /** Ticks the client is behind the server; charging builds it, spending uses it. Game thread only. */
    private float balance;
    /** Game speed this module last asked for; read by Minecraft's tick loop. Game thread only. */
    private float requestedSpeed = 1.0F;
    /** Ticks the tick loop should hold back; set by the burst state machine. Game thread only. */
    private int holdRequested;
    /** Extra ticks the tick loop should run; set by the burst state machine. Game thread only. */
    private int burstRequested;
    /** Burst ticks that actually ran, for the hold-after math. Game thread only. */
    private int lastBurstRan;
    /** Frame the current burst was scheduled in; the burst runs in the next frame. Game thread only. */
    private long burstScheduledFrame = -1;
    /** Frames seen; advanced once per rendered frame. Game thread only. */
    private long frameId;
    /** Burst-mode sequence state. Game thread only. */
    private BurstState burstState = BurstState.IDLE;
    /** Ticks left in the DELAY or COOLDOWN state. Game thread only. */
    private int waitRemaining;
    /** Timer mode: ticks left before another boost may start. Game thread only. */
    private int cooldownTicks;
    /** Timer mode: a boost is running. Game thread only. */
    private boolean boosting;
    /** Whether the current chance to hit earlier was taken; rolled once per opportunity. */
    private @Nullable Boolean chanceRolled;
    /** Burst mode: normal ticks before the burst of the plan in progress. */
    private int pendingWalk;
    /** Burst mode: landing tick of the plan in progress. */
    private int pendingTicks;

    public TickBase() {
        super(ModuleCategory.COMBAT, "TickBase", "Saves up time near an enemy and spends it to land the first hit.");
        this.mode.onChange(this::resetState);
        this.boostSpeed.visibleWhen(() -> this.mode.is(Mode.TIMER));
        this.maxTicks.visibleWhen(() -> !this.mode.is(Mode.TIMER));
        this.delayMin.visibleWhen(() -> !this.mode.is(Mode.TIMER));
        this.delayMax.visibleWhen(() -> !this.mode.is(Mode.TIMER));
        this.enemyRange.visibleWhen(this.advantage::get);
        this.knockbackTicks.visibleWhen(this.advantage::get);
        this.knockbackMultiplier.visibleWhen(this.advantage::get);
        this.skipInvulnerable.visibleWhen(this.advantage::get);
        this.criticals.visibleWhen(this.advantage::get);
    }

    @Override
    protected void onEnable() {
        this.resetState();
    }

    @Override
    protected void onDisable() {
        this.resetState();
    }

    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) {
        this.resetState();
    }

    @EventTarget
    public void onRunLoop(final EventRunLoop event) {
        this.frameId++;
    }

    /**
     * Game speed this module wants right now; 1 when it has nothing to say. Read by
     * {@code Minecraft.getTickTargetMillis} once per frame.
     */
    public float timerMultiplier() {
        return this.requestedSpeed;
    }

    /**
     * Applies a frame's holds and bursts to the tick count the tick loop computed, and keeps the balance.
     * Called from {@code Minecraft.runTick} once per frame, game thread only. Returns the ticks the frame
     * should run.
     */
    public static int adjustTicksToDo(final int ticksToDo) {
        TickBase tickBase = Modules.enabled(TickBase.class);
        return tickBase == null ? ticksToDo : tickBase.adjustTicks(ticksToDo);
    }

    @EventTarget
    public void onTick(final EventTick event) {
        if (!event.isPre()) {
            return;
        }
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || !player.isAlive() || player.isSpectator() || mc.isPaused()) {
            this.requestedSpeed = 1.0F;
            return;
        }
        if (mc.gui.screen() != null) {
            this.requestedSpeed = 1.0F;
            return;
        }
        this.requestedSpeed = clampSpeed(this.tickSpeed(player, level));
    }

    private void resetState() {
        this.balance = 0.0F;
        this.requestedSpeed = 1.0F;
        this.holdRequested = 0;
        this.burstRequested = 0;
        this.lastBurstRan = 0;
        this.burstScheduledFrame = -1;
        this.burstState = BurstState.IDLE;
        this.waitRemaining = 0;
        this.cooldownTicks = 0;
        this.boosting = false;
        this.chanceRolled = null;
        this.pendingWalk = 0;
        this.pendingTicks = 0;
    }

    private static float clampSpeed(final float speed) {
        return Float.isFinite(speed) ? Mth.clamp(speed, 0.05F, 10.0F) : 1.0F;
    }

    // -------------------------------------------------------------------------------------------------- tick loop

    private int adjustTicks(final int ticksToDo) {
        if (mc.level == null || mc.player == null || mc.isPaused()) {
            return ticksToDo;
        }
        float speed = this.requestedSpeed;
        int held = Math.min(this.holdRequested, ticksToDo);
        this.holdRequested -= held;
        int runNormal = Math.min(Math.max(0, ticksToDo - held), 10);
        int burst = Math.min(this.burstRequested, Math.max(0, 10 - runNormal));
        this.burstRequested -= burst;
        this.lastBurstRan += burst;
        this.balance += balanceAfterFrame(ticksToDo, speed, held, burst);
        return runNormal + burst;
    }

    private float tickSpeed(final LocalPlayer player, final ClientLevel level) {
        if (this.mode.is(Mode.TIMER)) {
            return this.timerSpeed(player, level);
        }
        return this.burstSpeed(player, level);
    }

    /** Timer mode: boost when the balance pays for a hit, charge otherwise. */
    private float timerSpeed(final LocalPlayer player, final ClientLevel level) {
        if (this.cooldownTicks > 0) {
            this.cooldownTicks--;
        }
        BoostPlan plan = this.planBoost(player, level);
        if (plan.ticks() > 0 && plan.hit() != null && this.cooldownTicks <= 0 && this.rollChance()) {
            this.boosting = true;
            return this.boostSpeed.get();
        }
        if (this.boosting) {
            this.boosting = false;
            this.cooldownTicks = this.randomCooldown();
        }
        if (plan.ticks() <= 0) {
            this.chanceRolled = null;
        }
        if (plan.ticks() == 0) {
            // Already in reach: back to the normal speed right away.
            return 1.0F;
        }
        Float charging = this.chargingSpeed(player, level);
        return charging == null ? 1.0F : charging;
    }

    /** Burst modes: the HoldFirst / BurstFirst sequence as a state machine across ticks. */
    private float burstSpeed(final LocalPlayer player, final ClientLevel level) {
        boolean holdFirst = this.mode.is(Mode.HOLD_FIRST);
        switch (this.burstState) {
            case IDLE -> {
                int walk = holdFirst ? this.randomDelay() + 2 : 1;
                Integer ticks = this.planBurst(player, level, walk);
                if (ticks == null) {
                    this.chanceRolled = null;
                    return this.chargingOrOne(player, level);
                }
                if (!this.rollChance()) {
                    return this.chargingOrOne(player, level);
                }
                this.chanceRolled = null;
                this.pendingWalk = walk;
                this.pendingTicks = ticks;
                if (holdFirst) {
                    this.holdRequested = this.maxTicks.getInt();
                    this.burstState = BurstState.HOLDING;
                } else {
                    this.startBurst(ticks - walk);
                    this.burstState = BurstState.BURST_WAIT;
                }
                return 1.0F;
            }
            case HOLDING -> {
                if (this.holdRequested > 0) {
                    return 1.0F;
                }
                // The hold ran its course; re-plan from the current state.
                int walk = this.pendingWalk;
                Integer ticks = this.planBurst(player, level, walk);
                if (ticks == null) {
                    this.chanceRolled = null;
                    return this.toCooldown(player, level);
                }
                int burst = ticks - walk;
                if (burst > this.balance) {
                    this.chanceRolled = null;
                    return this.toCooldown(player, level);
                }
                this.pendingTicks = ticks;
                this.waitRemaining = this.randomDelay();
                this.burstState = BurstState.DELAY;
                return 1.0F;
            }
            case DELAY -> {
                if (this.waitRemaining > 0) {
                    this.waitRemaining--;
                    return 1.0F;
                }
                this.startBurst(this.pendingTicks - this.pendingWalk);
                this.burstState = BurstState.BURST_WAIT;
                return 1.0F;
            }
            case BURST_WAIT -> {
                if (this.frameId > this.burstScheduledFrame) {
                    if (holdFirst) {
                        this.burstState = BurstState.COOLDOWN;
                        this.waitRemaining = this.randomCooldown();
                    } else {
                        int charged = (int) Mth.clamp(this.balance, 0.0F, this.maxBalance.get());
                        this.holdRequested = Math.max(0, this.lastBurstRan - charged);
                        this.burstState = BurstState.HOLD_AFTER;
                    }
                }
                return 1.0F;
            }
            case HOLD_AFTER -> {
                if (this.holdRequested > 0) {
                    return 1.0F;
                }
                this.burstState = BurstState.COOLDOWN;
                this.waitRemaining = this.randomCooldown();
                return 1.0F;
            }
            case COOLDOWN -> {
                if (this.waitRemaining > 0) {
                    this.waitRemaining--;
                } else {
                    this.burstState = BurstState.IDLE;
                }
                return this.chargingOrOne(player, level);
            }
        }
        // Unreachable today (every BurstState is covered), but javac does not treat an
        // exhaustive switch statement as always returning, so a fallback is required.
        return this.chargingOrOne(player, level);
    }

    private float toCooldown(final LocalPlayer player, final ClientLevel level) {
        this.burstState = BurstState.COOLDOWN;
        this.waitRemaining = this.randomCooldown();
        return this.chargingOrOne(player, level);
    }

    private void startBurst(final int ticks) {
        this.burstRequested = Math.max(0, ticks);
        this.burstScheduledFrame = this.frameId;
        this.lastBurstRan = 0;
    }

    private float chargingOrOne(final LocalPlayer player, final ClientLevel level) {
        Float charging = this.chargingSpeed(player, level);
        return charging == null ? 1.0F : charging;
    }

    /**
     * Charges while an enemy is near but out of reach. Plain speed request, since clamping the balance
     * would drop what BurstFirst still has to pay back.
     */
    private @Nullable Float chargingSpeed(final LocalPlayer player, final ClientLevel level) {
        if (this.chargeSpeed.get() >= 1.0F || this.balance >= this.maxBalance.get()) {
            return null;
        }
        if (!this.chargeWhileUsingItem.get() && player.isUsingItem()) {
            return null;
        }
        if (this.findEnemy(player, level, this.chargeRange.get()) == null) {
            return null;
        }
        if (this.hitFrom(player, level, player.getEyePosition()) != null) {
            return null;
        }
        return this.chargeSpeed.get();
    }

    // --------------------------------------------------------------------------------------------------- planning

    /**
     * The snapshots of the boost the balance pays for, up to the hit landing on the last, and that hit.
     * Only the first hit found is considered, as upstream.
     */
    private BoostPlan planBoost(final LocalPlayer player, final ClientLevel level) {
        int boost = boostTicks(this.balance, this.maxBalance.getInt(), this.boostSpeed.get());
        int knockbackTicks = this.knockbackTicks.getInt();
        List<Step> simulated = simulate(player.position(), player.getDeltaMovement(), player.onGround(),
                player.fallDistance, boost + 1 + knockbackTicks);
        List<Step> path = simulated.subList(0, boost + 1);
        for (int i = 0; i < path.size(); i++) {
            Hit hit = this.hitFrom(player, level, path.get(i).eyes(player.getEyeHeight()));
            if (hit == null) {
                continue;
            }
            int landingDelay = (int) Math.ceil((double) i / this.boostSpeed.get());
            boolean ok = this.requirementsMet(player, level, hit.point())
                    && this.cooldownReady(player, landingDelay)
                    && this.paysOff(player, hit, simulated, i, landingDelay);
            return new BoostPlan(i, ok ? hit : null);
        }
        return new BoostPlan(-1, null);
    }

    /**
     * The tick of the simulated path to land the hit on after {@code walk} normal ticks, or null when no
     * hit lands and pays off, or one lands by just walking without a critical hit to wait for.
     */
    private @Nullable Integer planBurst(final LocalPlayer player, final ClientLevel level, final int walk) {
        int maxTicks = this.maxTicks.getInt();
        int knockbackTicks = this.knockbackTicks.getInt();
        List<Step> simulated = simulate(player.position(), player.getDeltaMovement(), player.onGround(),
                player.fallDistance, walk + maxTicks + 1 + knockbackTicks);
        List<Step> path = simulated.subList(0, walk + maxTicks + 1);
        List<@Nullable Hit> hits = new ArrayList<>(path.size());
        for (Step step : path) {
            hits.add(this.hitFrom(player, level, step.eyes(player.getEyeHeight())));
        }
        Integer ticks = this.ticksToHit(player, path, hits, walk, simulated);
        if (ticks == null) {
            return null;
        }
        Hit hit = hits.get(ticks);
        return hit != null && this.requirementsMet(player, level, hit.point()) ? ticks : null;
    }

    /**
     * The tick of {@code path} to land the hit on, preferring a critical one, with {@code walk} normal
     * ticks before the burst. {@code hits} are the hits from each tick of {@code path}, {@code simulated}
     * the player simulated on past it.
     */
    private @Nullable Integer ticksToHit(final LocalPlayer player, final List<Step> path,
                                         final List<@Nullable Hit> hits, final int walk,
                                         final List<Step> simulated) {
        int first = -1;
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i) != null) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return null;
        }
        List<Integer> landing = new ArrayList<>();
        for (int tick = 0; tick < path.size(); tick++) {
            Hit hit = hits.get(tick);
            if (hit == null || tick <= walk) {
                continue;
            }
            int delay = landingDelay(this.mode.is(Mode.HOLD_FIRST), tick, walk, this.balance);
            if (!this.killAuraClicksAt(player, tick - 1) || !this.cooldownReady(player, delay)) {
                continue;
            }
            if (this.paysOff(player, hit, simulated, tick, delay)) {
                landing.add(tick);
            }
        }
        if (this.prefersCritical()) {
            for (int tick : landing) {
                if (path.get(tick).falling()) {
                    return tick;
                }
            }
        }
        if (first <= walk) {
            return null;
        }
        return landing.isEmpty() ? null : landing.get(0);
    }

    // ------------------------------------------------------------------------------------------------------ hits

    /**
     * Where a hit from the given eyes lands: on KillAura's target, otherwise on an enemy under the
     * crosshair. Null when it misses. Uses the enemy's current box, as upstream does.
     */
    private @Nullable Hit hitFrom(final LocalPlayer player, final ClientLevel level, final Vec3 eyes) {
        KillAura killAura = Modules.enabled(KillAura.class);
        LivingEntity target = killAura == null ? null : killAura.getTarget();
        double range = this.range.get();
        if (target != null && target.isAlive()) {
            Vec3 point = nearestPoint(eyes, target.getBoundingBox());
            if (eyes.distanceToSqr(point) <= range * range && this.hasLineOfSight(player, level, eyes, point)) {
                return new Hit(target, point);
            }
            return null;
        }
        return this.enemyUnderCrosshair(player, level, eyes, range);
    }

    /** Like {@code ProjectileUtil.getEntityHitResult}, against the boxes as they are now. */
    private @Nullable Hit enemyUnderCrosshair(final LocalPlayer player, final ClientLevel level,
                                              final Vec3 eyes, final double range) {
        Rotation look = new Rotation(player.getYRot(), player.getXRot());
        Hit best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !this.isEnemy(player, living)) {
                continue;
            }
            AABB box = living.getBoundingBox().inflate(living.getPickRadius());
            Optional<Vec3> hit = Rotations.hit(eyes, look, box, range);
            if (hit.isEmpty()) {
                continue;
            }
            double dist = eyes.distanceToSqr(hit.get());
            if (dist < bestDist && this.hasLineOfSight(player, level, eyes, hit.get())) {
                bestDist = dist;
                best = new Hit(living, hit.get());
            }
        }
        return best;
    }

    private boolean hasLineOfSight(final LocalPlayer player, final ClientLevel level,
                                   final Vec3 from, final Vec3 to) {
        if (this.throughWalls.get()) {
            return true;
        }
        HitResult wall = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return wall.getType() == HitResult.Type.MISS;
    }

    private static Vec3 nearestPoint(final Vec3 point, final AABB box) {
        return new Vec3(
                Mth.clamp(point.x, box.minX, box.maxX),
                Mth.clamp(point.y, box.minY, box.maxY),
                Mth.clamp(point.z, box.minZ, box.maxZ));
    }

    private @Nullable LivingEntity findEnemy(final LocalPlayer player, final ClientLevel level, final double range) {
        Vec3 eye = player.getEyePosition();
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living && this.isEnemy(player, living)) {
                double dist = living.getBoundingBox().distanceToSqr(eye);
                if (dist <= range * range && dist < bestDist) {
                    best = living;
                    bestDist = dist;
                }
            }
        }
        return best;
    }

    private boolean isEnemy(final LocalPlayer player, final LivingEntity entity) {
        Target targets = Target.current();
        return targets != null && targets.accepts(player, entity);
    }

    // ---------------------------------------------------------------------------------------------- requirements

    private boolean requirementsMet(final LocalPlayer player, final ClientLevel level, final Vec3 hitPoint) {
        if (this.requireKillAura.get()) {
            KillAura killAura = Modules.enabled(KillAura.class);
            if (killAura == null || killAura.getTarget() == null) {
                return false;
            }
        }
        if (this.requireLookingAt.get()) {
            double range = player.getEyePosition().distanceTo(hitPoint) + 1.0;
            if (this.enemyUnderCrosshair(player, level, player.getEyePosition(), range) == null) {
                return false;
            }
        }
        if (this.requireClick.get() && !mc.options.keyAttack.isDown()) {
            return false;
        }
        if (this.requireOnGround.get() && !player.onGround()) {
            return false;
        }
        return true;
    }

    /** Whether KillAura clicks the given ticks from now, if it is the one attacking. */
    private boolean killAuraClicksAt(final LocalPlayer player, final int ticks) {
        KillAura killAura = Modules.enabled(KillAura.class);
        if (killAura == null || killAura.getTarget() == null) {
            return true;
        }
        // KillAura's click schedule is not readable here; the attack cooldown is the binding gate on
        // modern servers, and legacy combat has no cooldown to wait for.
        return !this.hasCooldown() || player.getAttackStrengthScale(ticks) >= 1.0F;
    }

    /**
     * Whether the server has the attack cooldown full enough for a hit landing {@code landingDelay}
     * ticks of real time from now.
     */
    private boolean cooldownReady(final LocalPlayer player, final int landingDelay) {
        if (!this.hasCooldown()) {
            return true;
        }
        return player.getAttackStrengthScale(landingDelay) >= this.attackCooldown.get();
    }

    private boolean hasCooldown() {
        return !legacyCombat();
    }

    private static boolean legacyCombat() {
        return ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8);
    }

    private boolean prefersCritical() {
        return Modules.enabled(Criticals.class) != null || Modules.enabled(KillAura.class) != null;
    }

    /** Whether {@code chance} lets us take the current chance to hit earlier, rolled once until it is gone. */
    private boolean rollChance() {
        if (this.chanceRolled != null) {
            return this.chanceRolled;
        }
        boolean roll = ThreadLocalRandom.current().nextFloat() * 100.0F < this.chance.get();
        this.chanceRolled = roll;
        return roll;
    }

    private int randomCooldown() {
        return randomBetween(this.cooldownMin.getInt(), this.cooldownMax.getInt());
    }

    private int randomDelay() {
        return randomBetween(this.delayMin.getInt(), this.delayMax.getInt());
    }

    private static int randomBetween(final int low, final int high) {
        int min = Math.min(low, high);
        int max = Math.max(low, high);
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    // --------------------------------------------------------------------------------------------------- advantage

    /**
     * Whether {@code hit} pays off when it lands with the player at {@code landing} of {@code path},
     * {@code landingDelay} ticks of real time from now. The hit wins when the enemy cannot hit back
     * within Knockback Ticks, or when it is critical.
     */
    private boolean paysOff(final LocalPlayer player, final Hit hit, final List<Step> path,
                            final int landing, final int landingDelay) {
        if (!this.advantage.get() || !(hit.entity() instanceof LivingEntity enemy)) {
            return true;
        }
        if (this.skipInvulnerable.get() && enemy.hurtTime > landingDelay + this.pingTicks(player)) {
            return false;
        }
        boolean modern = this.hasCooldown();
        float strength = modern ? player.getAttackStrengthScale(landingDelay) : 1.0F;
        boolean critical = modern && this.criticals.get() && this.isCritical(path.get(landing), strength, player);
        boolean sprintHit = player.isSprinting() && strength > 0.9F;
        double range = this.rangeAfterKnockback(player, enemy, path, landing, landingDelay, sprintHit);
        // Upstream also wins when the enemy's own cooldown is not full; Sigma has no data on the enemy's
        // attack cooldown, so that branch is dropped.
        return range > this.enemyRange.get() || critical;
    }

    /**
     * How far {@code enemy} is from us {@code knockbackTicks} ticks after the knockback of our hit, which
     * lands with the player at {@code landing} of {@code path}, {@code landingDelay} ticks of real time
     * from now.
     */
    private double rangeAfterKnockback(final LocalPlayer player, final LivingEntity enemy, final List<Step> path,
                                       final int landing, final int landingDelay, final boolean sprintHit) {
        int knockbackTicks = this.knockbackTicks.getInt();
        List<Step> enemyPath = simulate(enemy.position(), enemy.getDeltaMovement(), enemy.onGround(),
                enemy.fallDistance, landingDelay + 1);
        Vec3 feet = enemyPath.get(Math.min(landingDelay, enemyPath.size() - 1)).pos();
        Vec3 before = enemy instanceof Player
                ? Vec3.ZERO
                : enemyPath.get(Math.min(landingDelay + 1, enemyPath.size() - 1)).pos().subtract(feet);
        Vec3 motion = this.knockback(enemy, path.get(landing).pos(), feet, before, sprintHit);
        for (int i = 0; i < knockbackTicks; i++) {
            feet = feet.add(motion);
            // Knockback lifts the enemy off the ground, where only the air slows it down.
            motion = motion.scale(0.91);
        }
        int index = Math.min(landing + knockbackTicks, path.size() - 1);
        AABB box = boxAt(player, path.get(index).pos());
        return Math.sqrt(box.distanceToSqr(feet.add(0.0, enemy.getEyeHeight(), 0.0)));
    }

    /** Whether the hit is critical: falling, a full enough cooldown, and not sprinting (unless Criticals runs). */
    private boolean isCritical(final Step landing, final float strength, final LocalPlayer player) {
        if (!landing.falling() || strength <= 0.9F) {
            return false;
        }
        boolean criticalsOn = Modules.enabled(Criticals.class) != null;
        return (criticalsOn || Modules.enabled(KillAura.class) != null)
                && (!player.isSprinting() || criticalsOn);
    }

    /**
     * The horizontal motion of {@code enemy} at {@code at} after our hit from {@code from}, with the server
     * having {@code motion} of it before.
     */
    private Vec3 knockback(final LivingEntity enemy, final Vec3 from, final Vec3 at,
                           final Vec3 motion, final boolean sprintHit) {
        Vec3 away = new Vec3(at.x - from.x, 0.0, at.z - from.z).normalize();
        // The synced attribute is used; upstream sums the same resistance off the armor items instead.
        double resistance = Mth.clamp(enemy.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0.0, 1.0);
        double strength = this.knockbackMultiplier.get() * (1.0 - resistance);
        return knockbackImpulse(motion, away, strength, sprintHit);
    }

    private static AABB boxAt(final LocalPlayer player, final Vec3 pos) {
        return player.getBoundingBox().move(pos.subtract(player.position()));
    }

    private int pingTicks(final LocalPlayer player) {
        if (mc.getConnection() == null) {
            return 0;
        }
        PlayerInfo info = mc.getConnection().getPlayerInfo(player.getUUID());
        return info == null ? 0 : info.getLatency() / 50;
    }

    // -------------------------------------------------------------------------------------------------- simulation

    /**
     * Where the player is in the coming ticks, starting with now. Linear extrapolation: horizontal speed
     * is sustained on the ground (input holds it) and drags in the air, gravity pulls down, and the feet
     * stop at the ground they started on. No collision, so this is a flat-ground approximation — the
     * substitute for upstream's full movement simulation, which Sigma has no equivalent of.
     */
    private static List<Step> simulate(final Vec3 startPos, final Vec3 startVelocity,
                                       final boolean startOnGround, final double startFallDistance, final int ticks) {
        List<Step> steps = new ArrayList<>(ticks + 1);
        Step current = new Step(startPos, startVelocity, startOnGround, startFallDistance);
        steps.add(current);
        double groundY = startOnGround ? startPos.y : Double.NEGATIVE_INFINITY;
        for (int i = 0; i < ticks; i++) {
            current = stepPhysics(current, groundY);
            steps.add(current);
        }
        return steps;
    }

    /** One tick of the extrapolation: gravity, drag, and the flat ground the step started on. */
    static Step stepPhysics(final Step current, final double groundY) {
        double vx = current.velocity().x;
        double vy = current.velocity().y;
        double vz = current.velocity().z;
        boolean onGround = current.onGround();
        double fallDistance = current.fallDistance();
        if (onGround) {
            if (vy > 0.0) {
                // Jumping: leave the ground this tick.
                onGround = false;
                vy -= 0.08;
                vx *= 0.91;
                vz *= 0.91;
            } else {
                vy = 0.0;
                fallDistance = 0.0;
            }
        } else {
            vy -= 0.08;
            if (vy < -3.92) {
                vy = -3.92;
            }
            if (vy < 0.0) {
                fallDistance += -vy;
            }
            vx *= 0.91;
            vz *= 0.91;
        }
        Vec3 pos = current.pos().add(vx, vy, vz);
        if (!onGround && pos.y <= groundY) {
            pos = new Vec3(pos.x, groundY, pos.z);
            vy = 0.0;
            onGround = true;
            fallDistance = 0.0;
        }
        return new Step(pos, new Vec3(vx, vy, vz), onGround, fallDistance);
    }

    /** Ticks of real time until the hit on {@code tick} of the path lands. */
    static int landingDelay(final boolean holdFirst, final int tick, final int walk, final float balance) {
        return holdFirst ? walk + Math.max(0, tick - walk - (int) balance) : walk;
    }

    /** Extra ticks the balance can pay for right now. */
    static int spendableTicks(final float balance, final int maxBalance) {
        return (int) Mth.clamp(balance, 0.0F, (float) maxBalance);
    }

    /**
     * Ticks the balance lets run at {@code boostSpeed}. Each takes 1 / speed ticks of real time and spends
     * the rest of a tick of balance.
     */
    static int boostTicks(final float balance, final int maxBalance, final float boostSpeed) {
        if (boostSpeed <= 1.0F) {
            return 0;
        }
        return Math.min(100, (int) (spendableTicks(balance, maxBalance) / (1.0F - 1.0F / boostSpeed)));
    }

    /**
     * Balance change of one frame: the real time its ticks account for, minus the game ticks that ran.
     * Normal ticks run at {@code speed}, held ticks account for their real time, burst ticks for none;
     * ticks the vanilla 10-per-frame cap drops never ran and change nothing.
     */
    static float balanceAfterFrame(final int ticksToDo, final float speed, final int held, final int burst) {
        int runNormal = Math.min(Math.max(0, ticksToDo - held), 10);
        int runBurst = Math.min(burst, Math.max(0, 10 - runNormal));
        return runNormal * (1.0F / speed - 1.0F) + held / speed - runBurst;
    }

    /** The knockback impulse of a hit, before it is integrated over the knockback ticks. */
    static Vec3 knockbackImpulse(final Vec3 motion, final Vec3 away, final double strength, final boolean sprintHit) {
        Vec3 knocked = new Vec3(motion.x, 0.0, motion.z).scale(0.5).add(away.scale(0.4 * strength));
        return sprintHit ? knocked.scale(0.5).add(away.scale(0.5 * strength)) : knocked;
    }
}
