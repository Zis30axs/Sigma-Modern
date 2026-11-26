package com.mentalfrostbyte.jello.module.impl.combat;

import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.network.EventReceivePacket;
import com.mentalfrostbyte.jello.event.impl.game.network.EventSendPacket;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.combat.crystalaura.CrystalDamage;
import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.module.impl.movement.blockfly.BlockFlyPlacement;
import com.mentalfrostbyte.jello.module.impl.player.AutoPearl;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.util.math.Rotations;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import com.mentalfrostbyte.jello.util.movement.MovementCorrection;
import com.mentalfrostbyte.jello.util.movement.MovementCorrector;
import com.mentalfrostbyte.jello.util.time.Timer;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Places and breaks end crystals for maximum damage to the nearest enemy, minimum to yourself.
 *
 * <p>Ported from LiquidBounce ({@code nextgen}, GPL-3.0, Copyright (c) 2015 - 2026 CCBlueX):
 * {@code features/module/modules/combat/crystalaura/} — {@code ModuleCrystalAura} with its
 * place/destroy submodules, {@code DamageProvider}, {@code PredictFeature},
 * {@code SubmoduleIdPredict}, {@code SubmoduleSetDead}, {@code SubmoduleBasePlace} and
 * {@code CrystalAuraTriggerer}. The damage math, rejection order, candidate scoring, switch
 * modes and trigger set follow upstream; the surrounding machinery is Sigma's own.</p>
 *
 * <p>Differences from upstream, all forced by Sigma having no Fabric loader or LBNG framework:</p>
 * <ul>
 *     <li>No {@code RotationManager} / rotation engine exists here: rotations are computed inline
 *     per tick like {@link KillAura} does ({@code Rotations.towards}, applied through
 *     {@link EventMotion}), and every attack is followed by a movement report via
 *     {@link AuraAttackCycle}, exactly as KillAura does. Upstream rotates first and acts in a
 *     callback; this port decides in {@link EventTick} and acts once the look has been reported
 *     (checked against {@code AuraAttackCycle.lastSent()}), at most one crystal action per tick.</li>
 *     <li>No off-thread trigger executor: place/break calculations run on the client thread in
 *     {@link EventTick}. Packet triggers only set flags that the next tick consumes.</li>
 *     <li>No {@code TargetTracker} / {@code PlayerSimulationCache} / {@code FriendManager}:
 *     targets come from the shared {@link Target} module, prediction is a linear extrapolation
 *     ({@code position + velocity * ticks}), and there is no friend list, so
 *     {@code MaxFriendDamage} has nothing to check against.</li>
 *     <li>No {@code BlockPlacer} for BasePlace: the obsidian is placed with the same
 *     {@code BlockFlyPlacement} geometry and {@code useItemOn} path as a crystal, and the
 *     movement-simulation / trap checks are reduced to a predicted-box intersection test.</li>
 *     <li>Damage is computed with the client-safe pieces of vanilla
 *     (see {@link CrystalDamage}): the raw formula, {@code ServerExplosion.getSeenPercent},
 *     {@code CombatRules}, and blast-protection levels summed by hand, because the vanilla
 *     enchantment path needs a {@code ServerLevel}.</li>
 *     <li>The speed debugger ({@code CrystalAuraSpeedDebugger}) is not ported: it only feeds a
 *     debug overlay. {@code SetDead} is ported because it is what makes fast place-break-place
 *     cycles possible — but virtually: the crystal stays in the world and only its id is skipped
 *     by the scans, because {@code Entity.unsetRemoved()} is protected here (see
 *     {@code deadCrystalIds}).</li>
 *     <li>There is no attack cooldown and no CPS cap upstream, so none is added; speed is bounded
 *     by the place/break delays (both default 0) and the one-action-per-tick rule above.</li>
 * </ul>
 */
public final class CrystalAura extends Module {

    /** How the target is chosen among those in range (players are always preferred first). */
    public enum Priority {
        DISTANCE,
        HEALTH
    }

    /** Whether to turn to the crystal, or act without turning (upstream's {@code RotationMode}). */
    public enum RotationMode {
        NORMAL,
        NONE
    }

    /** How the crystal slot is held while placing (upstream's {@code SwitchMode}). */
    public enum SwitchMode {
        SILENT,
        NORMAL,
        NONE
    }

    static final int CORRECTOR_PRIORITY = 5;
    private static final int MAX_CANDIDATE_DAMAGE_EVALUATIONS_PER_TICK = 48;
    private static final long PENDING_ACTION_TIMEOUT_MS = 500L;
    private static final double CRYSTAL_HEIGHT = 2.0;

    // ---------------------------------------------------------------------------- settings

    private final NumberSetting targetRange = this.register(new NumberSetting("Target Range",
            "How far away a target can be, in blocks.", 4.5F, 1.0F, 12.0F, 0.1F));
    private final EnumSetting<Priority> priority = this.register(new EnumSetting<>("Priority",
            "Which target to pick: the nearest, or the weakest. Players are always preferred.", Priority.HEALTH));
    private final NumberSetting hurtTime = this.register(new NumberSetting("Hurt Time",
            "Reject a target whose hurt time is above this.", 10.0F, 0.0F, 10.0F, 1.0F));

    private final EnumSetting<RotationMode> rotationMode = this.register(new EnumSetting<>("Rotation",
            "Normal turns to the crystal; None acts without turning.", RotationMode.NORMAL));
    private final BooleanSetting silent = this.register(new BooleanSetting("Silent",
            "Turns only what the server is told; your camera stays where you point it.", true));
    private final EnumSetting<KillAura.MovementCorrectorMode> movementCorrector = this.register(
            new EnumSetting<>("Movement Corrector",
                    "Silent look: how the walking is kept to the facing the server is told. MovCor follows the "
                            + "MovementCorrector module's Mode; Strict, Silent, Claude3 and Off keep their own mode.",
                    KillAura.MovementCorrectorMode.MOVCOR));

    private final NumberSetting placeDelay = this.register(new NumberSetting("Place Delay",
            "Minimum milliseconds between places.", 0.0F, 0.0F, 1000.0F, 1.0F));
    private final NumberSetting placeRange = this.register(new NumberSetting("Place Range",
            "How far away a crystal can be placed, in blocks.", 4.5F, 1.0F, 6.0F, 0.1F));
    private final NumberSetting placeWallsRange = this.register(new NumberSetting("Place Walls Range",
            "How far away a crystal can be placed through walls, in blocks.", 4.5F, 0.0F, 6.0F, 0.1F));
    private final BooleanSetting placeSwing = this.register(new BooleanSetting("Place Swing",
            "Swing the hand when placing a crystal.", true));
    private final EnumSetting<SwitchMode> switchMode = this.register(new EnumSetting<>("Switch",
            "Silent switches back after placing; Normal leaves the server on the crystal slot; "
                    + "None refuses to place unless the crystal is already held.", SwitchMode.SILENT));
    private final BooleanSetting onlyAbove = this.register(new BooleanSetting("Only Above",
            "Only place when the top face of the base block is the face being clicked.", false));
    private final BooleanSetting oldVersion = this.register(new BooleanSetting("1.12.2",
            "Legacy rules: the crystal needs two blocks of air above it.", false));

    private final NumberSetting breakDelay = this.register(new NumberSetting("Break Delay",
            "Minimum milliseconds between breaks.", 0.0F, 0.0F, 1000.0F, 1.0F));
    private final NumberSetting breakRange = this.register(new NumberSetting("Break Range",
            "How far away a crystal can be broken, in blocks.", 4.5F, 1.0F, 6.0F, 0.1F));
    private final NumberSetting breakWallsRange = this.register(new NumberSetting("Break Walls Range",
            "How far away a crystal can be broken through walls, in blocks.", 4.5F, 0.0F, 6.0F, 0.1F));
    private final BooleanSetting breakSwing = this.register(new BooleanSetting("Break Swing",
            "Swing the hand when breaking a crystal.", true));

    private final NumberSetting maxSelfDamage = this.register(new NumberSetting("Max Self Damage",
            "Reject a spot if it would hurt you more than this.", 2.0F, 0.0F, 10.0F, 0.1F));
    private final NumberSetting maxFriendDamage = this.register(new NumberSetting("Max Friend Damage",
            "Reject a spot if it would hurt a friend more than this. No friend list exists in Sigma, "
                    + "so this currently has nothing to check.", 1.0F, 0.0F, 10.0F, 0.1F));
    private final NumberSetting minEnemyDamage = this.register(new NumberSetting("Min Enemy Damage",
            "Reject a spot if it would hurt the target less than this.", 5.0F, 0.0F, 10.0F, 0.1F));
    private final BooleanSetting antiSuicide = this.register(new BooleanSetting("Anti Suicide",
            "Reject a spot if it would kill you (damage at or above your health + absorption).", true));
    private final BooleanSetting efficient = this.register(new BooleanSetting("Efficient",
            "Reject a spot unless it hurts the target strictly more than it hurts you.", true));
    private final BooleanSetting terrain = this.register(new BooleanSetting("Terrain",
            "Ignore blocks the explosion would destroy when working out exposure.", true));

    private final BooleanSetting selfPredict = this.register(new BooleanSetting("Self Predict",
            "Also score a spot by where you will be in a few ticks.", true));
    private final BooleanSetting targetPredict = this.register(new BooleanSetting("Target Predict",
            "Also score a spot by where the target will be in a few ticks.", true));
    private final NumberSetting placeTicks = this.register(new NumberSetting("Place Ticks",
            "How many ticks ahead to predict for placing.", 2.0F, 0.0F, 20.0F, 1.0F));
    private final NumberSetting breakTicks = this.register(new NumberSetting("Break Ticks",
            "How many ticks ahead to predict for breaking.", 1.0F, 0.0F, 20.0F, 1.0F));
    private final NumberSetting basePlaceTicks = this.register(new NumberSetting("Base Place Ticks",
            "How many ticks ahead to predict for base-place spots.", 4.0F, 0.0F, 20.0F, 1.0F));
    private final EnumSetting<CrystalDamage.CalcMode> calcMode = this.register(new EnumSetting<>("Calc Mode",
            "Both combines the current and predicted damage; Predict Only uses the predicted damage.",
            CrystalDamage.CalcMode.BOTH));
    private final EnumSetting<CrystalDamage.Logic> logic = this.register(new EnumSetting<>("Logic",
            "Both: And needs the current and predicted damage to pass; Or needs either to pass.",
            CrystalDamage.Logic.AND));
    private final BooleanSetting checkIntersect = this.register(new BooleanSetting("Check Intersect",
            "Reject a spot if a predicted player box would block the crystal space.", true));

    private final BooleanSetting idPredict = this.register(new BooleanSetting("ID Predict",
            "Attack the crystal's expected entity ID right after placing, before its spawn packet arrives.",
            false));
    private final NumberSetting idPredictOffset = this.register(new NumberSetting("ID Predict Offset",
            "Highest entity-ID offset to pre-attack.", 2.0F, 1.0F, 100.0F, 1.0F));
    private final BooleanSetting idPredictSwingAlways = this.register(new BooleanSetting("ID Predict Swing Always",
            "Swing before every predicted attack instead of once.", false));
    private final BooleanSetting idPredictRotateBack = this.register(new BooleanSetting("ID Predict Rotate Back",
            "Send a rotation packet back to the old look after a predicted attack.", false));

    private final BooleanSetting setDead = this.register(new BooleanSetting("Set Dead",
            "Remove a broken crystal from the world immediately, restoring it if the server does not "
                    + "confirm within Confirm Time. This is what makes fast place-break-place cycles work.",
            true));
    private final NumberSetting confirmTime = this.register(new NumberSetting("Confirm Time",
            "Milliseconds to wait for the server to confirm a break before restoring the crystal.",
            150.0F, 10.0F, 2000.0F, 10.0F));

    private final BooleanSetting basePlace = this.register(new BooleanSetting("Base Place",
            "Build an obsidian base under the target when that spot is clearly better than any ready spot.",
            true));
    private final NumberSetting basePlaceCalcDelay = this.register(new NumberSetting("Base Place Calc Delay",
            "Minimum milliseconds between base-place recalculations.", 120.0F, 0.0F, 1000.0F, 10.0F));
    private final NumberSetting basePlaceTimeout = this.register(new NumberSetting("Base Place Timeout",
            "Give up on the current base-place target after this long.", 240.0F, 0.0F, 5000.0F, 10.0F));
    private final NumberSetting minAdvantage = this.register(new NumberSetting("Min Advantage",
            "Damage advantage, in HP, a base-place spot must have over a ready spot to be preferred.",
            2.0F, 0.1F, 10.0F, 0.1F));
    private final BooleanSetting platformOnly = this.register(new BooleanSetting("Platform Only",
            "Only place the base directly below the enemy, one layer lower.", false));
    private final BooleanSetting onlyAboveSelf = this.register(new BooleanSetting("Only Above Self",
            "Only place a base at or above your own Y level.", false));

    private final BooleanSetting tickTrigger = this.register(new BooleanSetting("Tick Trigger",
            "Run place and break every tick. Only tick is the most legit trigger upstream.", true));
    private final BooleanSetting blockChangeTrigger = this.register(new BooleanSetting("Block Change Trigger",
            "Re-place when a block nearby turns to air.", true));
    private final BooleanSetting crystalSpawnTrigger = this.register(new BooleanSetting("Crystal Spawn Trigger",
            "Re-break as soon as a crystal spawns.", true));
    private final BooleanSetting crystalDestroyTrigger = this.register(new BooleanSetting("Crystal Destroy Trigger",
            "Re-place as soon as a crystal is removed.", true));
    private final BooleanSetting explodeSoundTrigger = this.register(new BooleanSetting("Explode Sound Trigger",
            "Re-place when an explosion sound plays.", true));
    private final BooleanSetting entityMoveTrigger = this.register(new BooleanSetting("Entity Move Trigger",
            "Re-run place and break when an entity teleports nearby.", true));
    private final BooleanSetting selfMoveTrigger = this.register(new BooleanSetting("Self Move Trigger",
            "Re-run place and break on your own movement packets.", false));
    private final BooleanSetting notWhileUsingItem = this.register(new BooleanSetting("Not While Using Item",
            "Pause while you are using an item.", false));

    // -------------------------------------------------------------------------------- state

    private enum PendingAction {
        NONE,
        PLACE_CRYSTAL,
        PLACE_BASE,
        BREAK
    }

    private @Nullable LivingEntity target;
    private @Nullable Rotation rotation;
    private final AuraAttackCycle attackCycle = new AuraAttackCycle();
    private final Timer placeTimer = new Timer();
    private final Timer breakTimer = new Timer();
    private final Timer basePlaceTimer = new Timer();

    private PendingAction pendingAction = PendingAction.NONE;
    private @Nullable Rotation pendingRotation;
    private long pendingSinceMs;
    /** Base block of the pending crystal place, or the pending obsidian base position. */
    private @Nullable BlockPos pendingBase;
    /** The hit to click for the pending place, computed from the planned rotation. */
    private @Nullable BlockHitResult pendingHit;
    /** The crystal the pending break is aimed at. */
    private @Nullable EndCrystal pendingCrystal;

    /** Base-place target currently being built, with the time it was chosen (milliseconds). */
    private @Nullable BlockPos basePlaceTarget;
    private long basePlaceTargetSinceMs;

    /**
     * SetDead bookkeeping: crystal ids the module treats as destroyed. LBNG physically removes
     * the crystal from the client world and restores it with {@code Entity.unsetRemoved()}; that
     * method is protected here, so Sigma instead keeps the entity in the world and only pretends
     * it is dead: the break and place scans skip these ids, and an id is forgotten once the
     * server confirms the removal or {@link #confirmTime} elapses. The only cost is cosmetic —
     * the crystal keeps rendering for up to {@code ConfirmTime}.
     */
    private final Map<Integer, Long> deadCrystalIds = new HashMap<>();
    /** IDs the server has confirmed destroyed (from remove-entities packets or explode sounds). */
    private final Set<Integer> confirmedIds = ConcurrentHashMap.newKeySet();
    /** IDs this module has attacked (breaks and ID-predictions), for SetDead confirmation. */
    private final Set<Integer> attackedIds = ConcurrentHashMap.newKeySet();
    /** Highest entity ID seen, for ID prediction. Written on the Netty thread, read on the game thread. */
    private final AtomicInteger highestId = new AtomicInteger();

    /** Packet triggers only set these; the next tick consumes them. Written on the Netty thread. */
    private volatile boolean placeRequested;
    private volatile boolean destroyRequested;

    /** Current best place / break, for debugging and for the next tick's sticky choice. */
    private @Nullable BlockPos currentPlaceBase;
    private @Nullable EndCrystal currentBreakTarget;

    public CrystalAura() {
        super(ModuleCategory.COMBAT, "CrystalAura",
                "Places and breaks end crystals for maximum damage to the nearest enemy.");
        this.silent.visibleWhen(() -> !this.rotationMode.is(RotationMode.NONE));
        this.movementCorrector.visibleWhen(() -> !this.rotationMode.is(RotationMode.NONE) && this.silent.get());
        this.logic.visibleWhen(() -> this.calcMode.is(CrystalDamage.CalcMode.BOTH));
        this.idPredictOffset.visibleWhen(this.idPredict::get);
        this.idPredictSwingAlways.visibleWhen(this.idPredict::get);
        this.idPredictRotateBack.visibleWhen(this.idPredict::get);
        this.confirmTime.visibleWhen(this.setDead::get);
        this.basePlaceCalcDelay.visibleWhen(this.basePlace::get);
        this.basePlaceTimeout.visibleWhen(this.basePlace::get);
        this.minAdvantage.visibleWhen(this.basePlace::get);
        this.platformOnly.visibleWhen(this.basePlace::get);
        this.onlyAboveSelf.visibleWhen(this.basePlace::get);
    }

    // ------------------------------------------------------------------------------ lifecycle

    @Override
    protected void onEnable() {
        this.clearState();
        LocalPlayer player = mc.player;
        this.attackCycle.reclaim(player == null ? null : new Rotation(player.getYRot(), player.getXRot()));
        this.placeTimer.reset();
        this.breakTimer.reset();
        this.basePlaceTimer.reset();
    }

    @Override
    protected void onDisable() {
        this.releaseCombat();
        this.clearState();
    }

    private void releaseCombat() {
        LocalPlayer player = mc.player;
        this.rotation = null;
        this.target = null;
        this.pendingAction = PendingAction.NONE;
        this.pendingRotation = null;
        this.pendingBase = null;
        this.pendingHit = null;
        this.pendingCrystal = null;
        MovementCorrector corrector = MovementCorrector.current();
        MovementCorrection drainMode = this.silent.get() && corrector != null
                ? this.movementCorrector.get().resolve(corrector) : null;
        if (corrector != null) {
            corrector.release(this);
        }
        if (player != null && mc.level != null && player.connection.hasClientLoaded()) {
            this.attackCycle.drainOnBus(drainMode);
        } else {
            this.attackCycle.reset(null);
        }
    }

    private void clearState() {
        this.deadCrystalIds.clear();
        this.confirmedIds.clear();
        this.attackedIds.clear();
        this.placeRequested = false;
        this.destroyRequested = false;
        this.basePlaceTarget = null;
        this.currentPlaceBase = null;
        this.currentBreakTarget = null;
        this.pendingAction = PendingAction.NONE;
        this.pendingRotation = null;
        this.pendingBase = null;
        this.pendingHit = null;
        this.pendingCrystal = null;
    }

    /** The silent look other modules must preserve, or null to use the player's own look. */
    public @Nullable Rotation getSilentRotation() {
        return this.silent.get() && !AutoPearl.isRescuing() ? this.rotation : null;
    }

    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) {
        this.clearState();
        this.attackCycle.reset(null);
    }

    // ----------------------------------------------------------------------------------- tick

    @EventTarget
    public void onTick(final EventTick event) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        MultiPlayerGameMode gameMode = mc.gameMode;
        if (!event.isPre()) {
            return;
        }
        if (AutoPearl.isRescuing()) {
            this.releaseCombat();
            return;
        }
        if (player == null || level == null || gameMode == null || !player.isAlive()) {
            return;
        }
        if (!this.rotationMode.is(RotationMode.NONE) && higherPriorityRotationActive()) {
            this.rotation = null;
            this.pendingAction = PendingAction.NONE;
            this.pendingRotation = null;
            return;
        }
        Rotation pendingAttackLook = this.attackCycle.unreportedAttack();
        if (pendingAttackLook != null) {
            this.rotation = pendingAttackLook;
            if (!this.silent.get()) {
                player.setYRot(pendingAttackLook.yaw());
                player.setXRot(pendingAttackLook.pitch());
            }
            this.correctMovement();
            return;
        }
        this.maintainSetDead();
        if (this.notWhileUsingItem.get() && player.isUsingItem()) {
            this.rotation = null;
            return;
        }
        if (mc.gui.screen() != null || player.isSpectator()) {
            this.target = null;
            this.rotation = null;
            return;
        }

        Vec3 eye = player.getEyePosition();
        this.target = this.pickTarget(player, level, eye);
        if (this.target == null) {
            this.rotation = null;
            this.pendingAction = PendingAction.NONE;
            return;
        }

        // A decided action runs once its look has been reported to the server. While it is
        // pending, no new decision may replace it.
        if (this.pendingAction != PendingAction.NONE) {
            if (this.rotationMode.is(RotationMode.NONE) || this.pendingRotation == null
                    || this.pendingRotation.equals(this.attackCycle.lastSent())
                    || System.nanoTime() / 1_000_000L - this.pendingSinceMs > PENDING_ACTION_TIMEOUT_MS) {
                this.executePending(player, level, gameMode, eye);
            } else {
                this.rotation = this.pendingRotation;
                if (!this.silent.get()) {
                    player.setYRot(this.pendingRotation.yaw());
                    player.setXRot(this.pendingRotation.pitch());
                }
                this.correctMovement();
            }
            return;
        }

        boolean run = this.tickTrigger.get() || this.placeRequested || this.destroyRequested;
        if (!run) {
            this.rotation = null;
            return;
        }
        boolean wantPlace = this.placeRequested;
        boolean wantDestroy = this.destroyRequested;
        this.placeRequested = false;
        this.destroyRequested = false;

        // Break first, like upstream's destroy-then-place tick; at most one crystal action a tick.
        if ((this.tickTrigger.get() || wantDestroy) && this.breakTimer.hasElapsed(this.breakDelay.getInt())) {
            EndCrystal crystal = this.pickBreakTarget(player, level, eye);
            this.currentBreakTarget = crystal;
            if (crystal != null) {
                Rotation look = this.aimAtCrystal(eye, crystal);
                this.pendingAction = PendingAction.BREAK;
                this.pendingCrystal = crystal;
                this.pendingBase = null;
                this.pendingHit = null;
                this.pendingSinceMs = System.nanoTime() / 1_000_000L;
                this.pendingRotation = this.applyRotation(player, look);
                if (this.rotationMode.is(RotationMode.NONE)) {
                    this.executePending(player, level, gameMode, eye);
                }
                return;
            }
        }
        if ((this.tickTrigger.get() || wantPlace) && this.placeTimer.hasElapsed(this.placeDelay.getInt())) {
            PlaceChoice choice = this.pickPlaceTarget(player, level, eye);
            if (choice != null) {
                this.currentPlaceBase = choice.base();
                this.pendingAction = choice.basePlace() ? PendingAction.PLACE_BASE : PendingAction.PLACE_CRYSTAL;
                this.pendingBase = choice.base();
                this.pendingHit = choice.hit();
                this.pendingCrystal = null;
                this.pendingSinceMs = System.nanoTime() / 1_000_000L;
                this.pendingRotation = this.applyRotation(player, choice.rotation());
                if (this.rotationMode.is(RotationMode.NONE)) {
                    this.executePending(player, level, gameMode, eye);
                }
                return;
            }
            this.currentPlaceBase = null;
        }
        this.rotation = null;
    }

    private static boolean higherPriorityRotationActive() {
        BlockFly scaffold = Modules.enabled(BlockFly.class);
        if (scaffold != null && scaffold.isActivelyWorking()) {
            return true;
        }
        KillAura aura = Modules.enabled(KillAura.class);
        return aura != null && aura.getSilentRotation() != null;
    }

    private Rotation applyRotation(final LocalPlayer player, final Rotation look) {
        if (this.rotationMode.is(RotationMode.NONE)) {
            this.rotation = null;
            return look;
        }
        Rotation from = this.attackCycle.lastSent() != null
                ? this.attackCycle.lastSent() : new Rotation(player.getYRot(), player.getXRot());
        this.rotation = Rotations.continuous(from, look);
        if (!this.silent.get()) {
            player.setYRot(this.rotation.yaw());
            player.setXRot(this.rotation.pitch());
        }
        this.correctMovement();
        return this.rotation;
    }

    private void correctMovement() {
        MovementCorrector corrector = MovementCorrector.current();
        if (corrector != null && this.rotation != null && this.silent.get()) {
            corrector.request(this, this.rotation, this.movementCorrector.get().resolve(corrector), CORRECTOR_PRIORITY);
        }
    }

    @EventTarget
    public void onMotion(final EventMotion event) {
        if (AutoPearl.isRescuing()) {
            return;
        }
        MovementCorrector corrector = MovementCorrector.current();
        if (corrector != null && corrector.active() != null && corrector.active().priority() > CORRECTOR_PRIORITY) {
            return;
        }
        this.attackCycle.motion(event, this.rotation, this.silent.get());
    }

    /**
     * Since 1.19 the use-item packet carries the look it was aimed with; with a silent look that
     * must be the reported one, not the camera's. Every movement packet that carries a look is
     * noted as what the server now holds.
     */
    @EventTarget(EventPriority.LOWEST)
    public void onSend(final EventSendPacket event) {
        if (!mc.isSameThread() || event.isCancelled()) {
            return;
        }
        Rotation reported = this.rotation;
        if (reported != null && this.silent.get()
                && event.getPacket() instanceof ServerboundUseItemPacket use) {
            event.setPacket(new ServerboundUseItemPacket(use.getHand(), use.getSequence(),
                    reported.yaw(), reported.pitch()));
        } else if (event.getPacket() instanceof ServerboundMovePlayerPacket move) {
            this.attackCycle.reported(move);
            if (this.selfMoveTrigger.get()) {
                this.destroyRequested = true;
                this.placeRequested = true;
            }
        }
    }

    // ------------------------------------------------------------------------------- triggers

    /**
     * Packet triggers only record flags and IDs here: this runs on the Netty thread, so it must
     * never touch the level or the player.
     */
    @EventTarget
    public void onReceive(final EventReceivePacket event) {
        if (event.getPacket() instanceof ClientboundAddEntityPacket add) {
            this.highestId.accumulateAndGet(add.getId(), Math::max);
            if (add.getType() == EntityTypes.END_CRYSTAL) {
                if (this.crystalSpawnTrigger.get()) {
                    this.destroyRequested = true;
                }
                if (this.setDead.get()) {
                    this.placeRequested = true;
                }
            }
        } else if (event.getPacket() instanceof ClientboundRemoveEntitiesPacket remove) {
            boolean anyAttacked = false;
            for (int id : remove.getEntityIds()) {
                if (this.attackedIds.contains(id)) {
                    this.confirmedIds.add(id);
                    anyAttacked = true;
                }
            }
            if (anyAttacked && this.crystalDestroyTrigger.get()) {
                this.placeRequested = true;
            }
        } else if (event.getPacket() instanceof ClientboundSoundEntityPacket sound) {
            if (SoundEvents.GENERIC_EXPLODE.equals(sound.getSound())) {
                if (this.attackedIds.contains(sound.getId())) {
                    this.confirmedIds.add(sound.getId());
                }
                if (this.explodeSoundTrigger.get()) {
                    this.placeRequested = true;
                }
            }
        } else if (event.getPacket() instanceof ClientboundTeleportEntityPacket) {
            if (this.entityMoveTrigger.get()) {
                this.destroyRequested = true;
                this.placeRequested = true;
            }
        } else if (event.getPacket() instanceof ClientboundBlockUpdatePacket block) {
            if (this.blockChangeTrigger.get() && block.getBlockState().isAir()) {
                this.placeRequested = true;
            }
        }
    }

    // ---------------------------------------------------------------------------------- targets

    private @Nullable LivingEntity pickTarget(final LocalPlayer player, final ClientLevel level, final Vec3 eye) {
        double reach = this.targetRange.get();
        if (this.target != null && this.validTarget(player, this.target)
                && distanceToBox(eye, this.target) <= reach) {
            return this.target;
        }
        Comparator<LivingEntity> order = switch (this.priority.get()) {
            case DISTANCE -> Comparator.comparingDouble(entity -> distanceToBox(eye, entity));
            case HEALTH -> Comparator.comparingDouble(LivingEntity::getHealth);
        };
        // Players first (upstream's TYPE priority), then the chosen order.
        Comparator<LivingEntity> full = Comparator.comparingInt((LivingEntity e) -> e instanceof Player ? 0 : 1)
                .thenComparing(order);
        LivingEntity best = null;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living && this.validTarget(player, living)
                    && distanceToBox(eye, living) <= reach
                    && (best == null || full.compare(living, best) < 0)) {
                best = living;
            }
            if (entity != null) {
                this.highestId.accumulateAndGet(entity.getId(), Math::max);
            }
        }
        return best;
    }

    private boolean validTarget(final LocalPlayer player, final LivingEntity entity) {
        if (entity == player || !entity.isAlive() || entity.isRemoved() || entity.isSpectator()
                || entity.hurtTime > this.hurtTime.getInt()) {
            return false;
        }
        Target targets = Target.current();
        return targets == null ? entity instanceof Player : targets.accepts(player, entity);
    }

    private static double distanceToBox(final Vec3 eye, final Entity entity) {
        return Math.sqrt(entity.getBoundingBox().distanceToSqr(eye));
    }

    private Rotation aimAtCrystal(final Vec3 eye, final EndCrystal crystal) {
        AABB box = crystal.getBoundingBox();
        return Rotations.towards(eye, Rotations.nearestPoint(eye, box, 0.15));
    }

    // ----------------------------------------------------------------------------------- break

    private @Nullable EndCrystal pickBreakTarget(final LocalPlayer player, final ClientLevel level, final Vec3 eye) {
        LivingEntity target = this.target;
        if (target == null) {
            return null;
        }
        double maxRange = Math.max(this.breakRange.get(), this.breakWallsRange.get());
        CrystalDamage.DamageOptions options = this.breakOptions();
        EndCrystal best = null;
        CrystalDamage.DamagePair bestPair = null;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal crystal)) {
                continue;
            }
            // SetDead: a crystal the module already attacked is treated as gone.
            if (this.deadCrystalIds.containsKey(crystal.getId())) {
                continue;
            }
            if (!level.getWorldBorder().isWithinBounds(crystal.blockPosition())) {
                continue;
            }
            double distance = distanceToBox(eye, crystal);
            if (distance > maxRange) {
                continue;
            }
            boolean visible = this.hasLineOfSight(level, player, eye, crystal.getBoundingBox().getCenter());
            if (distance > (visible ? this.breakRange.get() : this.breakWallsRange.get())) {
                continue;
            }
            CrystalDamage.DamagePair pair = CrystalDamage.evaluate(crystal.position(), target, player, level,
                    List.of(), options);
            if (pair == null) {
                continue;
            }
            if (bestPair == null || pair.enemyDamage() > bestPair.enemyDamage()
                    || pair.enemyDamage() == bestPair.enemyDamage()
                    && pair.selfDamage() < bestPair.selfDamage()) {
                best = crystal;
                bestPair = pair;
            }
        }
        return best;
    }

    private boolean hasLineOfSight(final ClientLevel level, final LocalPlayer player, final Vec3 eye, final Vec3 point) {
        HitResult hit = level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS;
    }

    // ----------------------------------------------------------------------------------- place

    private record PlaceChoice(BlockPos base, BlockHitResult hit, Rotation rotation, boolean basePlace) {
    }

    private @Nullable PlaceChoice pickPlaceTarget(final LocalPlayer player, final ClientLevel level, final Vec3 eye) {
        LivingEntity target = this.target;
        if (target == null) {
            return null;
        }
        double maxRange = Math.max(this.placeRange.get(), this.placeWallsRange.get());
        CrystalDamage.DamageOptions placeOptions = this.placeOptions(false);
        CrystalDamage.DamageOptions baseOptions = this.placeOptions(true);

        List<BlockPos> bases = new ArrayList<>();
        BlockPos feet = player.blockPosition();
        int radius = (int) Math.ceil(maxRange);
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos base = feet.offset(x, y, z);
                    if (!level.isInWorldBounds(base) || !level.getWorldBorder().isWithinBounds(base)) {
                        continue;
                    }
                    if (Vec3.atCenterOf(base).distanceToSqr(eye) > maxRange * maxRange) {
                        continue;
                    }
                    bases.add(base);
                }
            }
        }
        bases.sort(Comparator.comparingDouble(base -> Vec3.atCenterOf(base).distanceToSqr(eye)));

        PlaceCandidate bestReady = null;
        PlaceCandidate bestBase = null;
        int evaluations = 0;
        for (BlockPos base : bases) {
            if (evaluations >= MAX_CANDIDATE_DAMAGE_EVALUATIONS_PER_TICK) {
                break;
            }
            BlockState state = level.getBlockState(base);
            boolean ready = state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
            boolean baseEligible = !ready && this.basePlaceEligible(player, level, base, target);
            if (!ready && !baseEligible) {
                continue;
            }
            if (!this.aboveIsFree(level, base)) {
                continue;
            }
            if (base.getY() + 1.0 >= target.getBoundingBox().maxY) {
                continue;
            }
            AABB crystalSpace = CrystalDamage.crystalBox(base);
            if (this.spaceBlockedByEntities(level, crystalSpace)) {
                continue;
            }
            if (this.checkIntersect.get() && this.predictedBoxBlocks(player, target, crystalSpace,
                    ready ? this.placeTicks.getInt() : this.basePlaceTicks.getInt())) {
                continue;
            }
            Vec3 center = CrystalDamage.crystalCenter(base);
            double distance = Math.sqrt(center.distanceToSqr(eye));
            boolean visible = this.hasLineOfSight(level, player, eye, center);
            if (distance > (visible ? this.placeRange.get() : this.placeWallsRange.get())) {
                continue;
            }
            CrystalDamage.DamagePair pair = CrystalDamage.evaluate(center, target, player, level, List.of(),
                    ready ? placeOptions : baseOptions);
            evaluations++;
            if (pair == null) {
                continue;
            }
            PlaceCandidate candidate = new PlaceCandidate(base, pair, center.distanceToSqr(eye));
            if (ready) {
                if (bestReady == null || candidate.betterThan(bestReady)) {
                    bestReady = candidate;
                }
            } else if (bestBase == null || candidate.betterThan(bestBase)) {
                bestBase = candidate;
            }
        }

        PlaceCandidate chosen = bestReady;
        boolean needsBase = false;
        if (bestBase != null && this.basePlaceTimer.hasElapsed(this.basePlaceCalcDelay.getInt())) {
            if (bestReady == null || bestBase.pair().enemyDamage() - bestReady.pair().enemyDamage()
                    > this.minAdvantage.get()) {
                chosen = bestBase;
                needsBase = true;
                this.basePlaceTimer.reset();
            }
        }
        if (chosen == null) {
            return null;
        }
        if (needsBase) {
            if (this.basePlaceTarget == null || !this.basePlaceTarget.equals(chosen.base())) {
                this.basePlaceTarget = chosen.base();
                this.basePlaceTargetSinceMs = System.nanoTime() / 1_000_000L;
            }
            if (System.nanoTime() / 1_000_000L - this.basePlaceTargetSinceMs > this.basePlaceTimeout.getInt()) {
                this.basePlaceTarget = null;
                return null;
            }
            return this.choiceForBase(player, level, eye, chosen.base());
        }
        this.basePlaceTarget = null;
        return this.choiceForCrystal(player, level, eye, chosen.base());
    }

    private record PlaceCandidate(BlockPos base, CrystalDamage.DamagePair pair, double distanceSq) {
        boolean betterThan(final PlaceCandidate other) {
            return CrystalDamage.compareCandidates(this.pair.enemyDamage(), this.pair.selfDamage(),
                    this.distanceSq, other.pair.enemyDamage(), other.pair.selfDamage(), other.distanceSq) < 0;
        }
    }

    private @Nullable PlaceChoice choiceForCrystal(final LocalPlayer player, final ClientLevel level,
                                                   final Vec3 eye, final BlockPos base) {
        BlockFlyPlacement.Target found = BlockFlyPlacement.find(player, eye, base.above(), this.rotation);
        if (found == null) {
            return null;
        }
        BlockHitResult hit = found.hit();
        if (!hit.getBlockPos().equals(base)) {
            return null;
        }
        if (this.onlyAbove.get() && hit.getDirection() != Direction.UP) {
            return null;
        }
        return new PlaceChoice(base, hit, found.rotation(), false);
    }

    private @Nullable PlaceChoice choiceForBase(final LocalPlayer player, final ClientLevel level,
                                                final Vec3 eye, final BlockPos base) {
        BlockFlyPlacement.Target found = BlockFlyPlacement.find(player, eye, base, this.rotation);
        if (found == null) {
            return null;
        }
        return new PlaceChoice(base, found.hit(), found.rotation(), true);
    }

    private boolean aboveIsFree(final ClientLevel level, final BlockPos base) {
        if (!level.isEmptyBlock(base.above())) {
            return false;
        }
        return !this.oldVersion.get() || level.isEmptyBlock(base.above(2));
    }

    private boolean spaceBlockedByEntities(final ClientLevel level, final AABB space) {
        for (Entity entity : level.getEntities((Entity) null, space, entity -> true)) {
            if (!(entity instanceof EndCrystal)) {
                return true;
            }
        }
        return false;
    }

    private boolean predictedBoxBlocks(final LocalPlayer player, final LivingEntity target, final AABB crystalSpace,
                                       final int ticks) {
        if (this.selfPredict.get()) {
            AABB predicted = CrystalDamage.predictedBox(player.getBoundingBox(),
                    CrystalDamage.predictPosition(player.position(), player.getDeltaMovement(), ticks));
            if (CrystalDamage.willBeBlocked(crystalSpace, predicted)) {
                return true;
            }
        }
        if (this.targetPredict.get() && target instanceof Player) {
            AABB predicted = CrystalDamage.predictedBox(target.getBoundingBox(),
                    CrystalDamage.predictPosition(target.position(), target.getDeltaMovement(), ticks));
            return CrystalDamage.willBeBlocked(crystalSpace, predicted);
        }
        return false;
    }

    // -------------------------------------------------------------------------------- base place

    private boolean basePlaceEligible(final LocalPlayer player, final ClientLevel level, final BlockPos base,
                                      final LivingEntity target) {
        if (!this.basePlace.get()) {
            return false;
        }
        if (this.onlyAboveSelf.get() && base.getY() < player.blockPosition().getY()) {
            return false;
        }
        int targetFeet = (int) Math.floor(target.getY());
        int maxY = this.platformOnly.get() ? targetFeet - 1 : targetFeet;
        if (base.getY() < maxY - 3 || base.getY() > maxY) {
            return false;
        }
        BlockState state = level.getBlockState(base);
        if (!state.canBeReplaced()) {
            return false;
        }
        double maxRange = Math.max(this.placeRange.get(), this.placeWallsRange.get());
        if (Vec3.atCenterOf(base).distanceToSqr(player.getEyePosition()) + 1.0 > maxRange * maxRange) {
            return false;
        }
        if (!level.getEntities((Entity) null, new AABB(base), entity -> true).isEmpty()) {
            return false;
        }
        // Simplified movement simulation: the player must not run into the new block in the next
        // few ticks (upstream simulates the full movement; this only checks the linear prediction).
        AABB blockBox = new AABB(base);
        AABB predicted = CrystalDamage.predictedBox(player.getBoundingBox(),
                CrystalDamage.predictPosition(player.position(), player.getDeltaMovement(), 3));
        if (blockBox.intersects(predicted)) {
            return false;
        }
        return base.getY() != (int) Math.floor(predicted.minY);
    }

    // ----------------------------------------------------------------------------- execution

    private void executePending(final LocalPlayer player, final ClientLevel level, final MultiPlayerGameMode gameMode,
                                final Vec3 eye) {
        PendingAction action = this.pendingAction;
        Rotation look = this.pendingRotation != null
                ? this.pendingRotation : new Rotation(player.getYRot(), player.getXRot());
        boolean rotated = !this.rotationMode.is(RotationMode.NONE) && this.pendingRotation != null;
        this.pendingAction = PendingAction.NONE;
        this.pendingRotation = null;
        this.rotation = rotated ? look : null;
        if (rotated) {
            this.correctMovement();
        }
        switch (action) {
            case BREAK -> this.executeBreak(player, gameMode, look, rotated);
            case PLACE_CRYSTAL -> this.executePlace(player, level, gameMode, eye, look, false);
            case PLACE_BASE -> this.executePlace(player, level, gameMode, eye, look, true);
            case NONE -> {
            }
        }
    }

    private void executeBreak(final LocalPlayer player, final MultiPlayerGameMode gameMode,
                              final Rotation look, final boolean rotated) {
        EndCrystal crystal = this.pendingCrystal;
        this.pendingCrystal = null;
        if (crystal == null || crystal.isRemoved() || !crystal.isAlive()) {
            this.rotation = null;
            return;
        }
        gameMode.attack(player, crystal);
        if (this.breakSwing.get()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        this.attackCycle.attacked(look, rotated && this.silent.get());
        this.attackedIds.add(crystal.getId());
        if (this.setDead.get()) {
            this.removeCrystalClientSide(crystal);
        }
        this.breakTimer.reset();
    }

    private void executePlace(final LocalPlayer player, final ClientLevel level, final MultiPlayerGameMode gameMode,
                              final Vec3 eye, final Rotation look, final boolean basePlace) {
        BlockPos base = this.pendingBase;
        BlockHitResult plannedHit = this.pendingHit;
        this.pendingBase = null;
        this.pendingHit = null;
        if (base == null || plannedHit == null) {
            return;
        }
        // Re-raytrace from the look the server was actually told, like BlockFly does.
        Rotation sent = this.attackCycle.lastSent() != null ? this.attackCycle.lastSent() : look;
        BlockHitResult hit = BlockFlyPlacement.raycast(player, eye, sent);
        if (hit == null || !hit.getBlockPos().equals(base)) {
            return;
        }
        if (!basePlace && this.onlyAbove.get() && hit.getDirection() != Direction.UP) {
            return;
        }
        if (basePlace) {
            if (!this.canPlaceBase(level, base)) {
                return;
            }
        } else if (!this.canPlaceCrystal(level, base)) {
            return;
        }

        int slot = basePlace ? this.findBaseSlot(player) : this.findCrystalSlot(player);
        InteractionHand hand;
        if (slot == -2) {
            hand = InteractionHand.OFF_HAND;
        } else if (slot >= 0) {
            hand = InteractionHand.MAIN_HAND;
        } else {
            return;
        }
        if (this.switchMode.is(SwitchMode.NONE) && hand == InteractionHand.MAIN_HAND
                && player.getInventory().getSelectedSlot() != slot) {
            return;
        }

        var inventory = player.getInventory();
        int previous = inventory.getSelectedSlot();
        boolean interacted = false;
        float cameraYaw = player.getYRot();
        float cameraPitch = player.getXRot();
        try {
            if (hand == InteractionHand.MAIN_HAND && slot != previous) {
                inventory.setSelectedSlot(slot);
                gameMode.syncCarriedItem();
            }
            // Block prediction must use the reported facing.
            player.setYRot(sent.yaw());
            player.setXRot(sent.pitch());
            boolean success = gameMode.useItemOn(player, hand, hit).consumesAction();
            interacted = true;
            if (success && this.placeSwing.get()) {
                player.swing(hand);
            }
            if (success) {
                this.placeTimer.reset();
                if (!basePlace && this.idPredict.get()) {
                    this.runIdPredict(player, level, base, look);
                }
                if (basePlace) {
                    this.basePlaceTarget = null;
                }
            }
        } finally {
            player.setYRot(cameraYaw);
            player.setXRot(cameraPitch);
            if (hand == InteractionHand.MAIN_HAND) {
                inventory.setSelectedSlot(previous);
                if (this.switchMode.is(SwitchMode.SILENT) || interacted) {
                    gameMode.syncCarriedItem();
                }
            }
        }
    }

    /** The {@code EndCrystalItem.useOn} checks, without placing anything. */
    private boolean canPlaceCrystal(final ClientLevel level, final BlockPos base) {
        BlockState state = level.getBlockState(base);
        if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) {
            return false;
        }
        if (!level.isEmptyBlock(base.above())) {
            return false;
        }
        return level.getEntities((Entity) null, CrystalDamage.crystalBox(base), entity -> true).isEmpty();
    }

    private boolean canPlaceBase(final ClientLevel level, final BlockPos base) {
        return level.getBlockState(base).canBeReplaced()
                && level.getEntities((Entity) null, new AABB(base), entity -> true).isEmpty();
    }

    /** Crystal slot: selected slot first, then the nearest hotbar slot, then the offhand.
     * Returns -2 for the offhand, -1 when no crystal is held. */
    private int findCrystalSlot(final LocalPlayer player) {
        var inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        if (inventory.getItem(selected).is(Items.END_CRYSTAL)) {
            return selected;
        }
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            if (inventory.getItem(i).is(Items.END_CRYSTAL)) {
                int distance = Math.abs(i - selected);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = i;
                }
            }
        }
        if (best >= 0) {
            return best;
        }
        return player.getOffhandItem().is(Items.END_CRYSTAL) ? -2 : -1;
    }

    /** Obsidian first, else bedrock; same search order as {@link #findCrystalSlot}. */
    private int findBaseSlot(final LocalPlayer player) {
        var inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        for (int pass = 0; pass < 2; pass++) {
            var wanted = pass == 0 ? Items.OBSIDIAN : Items.BEDROCK;
            if (inventory.getItem(selected).is(wanted)) {
                return selected;
            }
            int best = -1;
            int bestDistance = Integer.MAX_VALUE;
            for (int i = 0; i < 9; i++) {
                if (inventory.getItem(i).is(wanted)) {
                    int distance = Math.abs(i - selected);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = i;
                    }
                }
            }
            if (best >= 0) {
                return best;
            }
            if (player.getOffhandItem().is(wanted)) {
                return -2;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------------------ ID predict

    private void runIdPredict(final LocalPlayer player, final ClientLevel level, final BlockPos base,
                              final Rotation look) {
        AABB future = CrystalDamage.crystalBox(base);
        Vec3 eye = player.getEyePosition();
        double reach = Math.max(this.breakRange.get(), this.breakWallsRange.get());
        boolean strictTickEnd = ProtocolTranslator.getTargetVersion().newerThanOrEqualTo(ProtocolVersion.v1_21_2);
        Rotation aim = strictTickEnd ? look : Rotations.towards(eye, Rotations.nearestPoint(eye, future, 0.15));
        if (Rotations.hit(eye, aim, future, reach).isEmpty()) {
            return;
        }
        if (mc.getConnection() == null) {
            return;
        }
        if (!strictTickEnd) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(player.getX(), player.getY(), player.getZ(),
                    aim.yaw(), aim.pitch(), player.onGround(), player.horizontalCollision));
        }
        boolean swung = false;
        int maxOffset = this.idPredictOffset.getInt();
        for (int offset = 1; offset <= maxOffset; offset++) {
            int id = this.highestId.get() + offset;
            Entity existing = level.getEntity(id);
            if (existing != null && !(existing instanceof EndCrystal)) {
                continue;
            }
            if (this.idPredictSwingAlways.get() || !swung) {
                player.swing(InteractionHand.MAIN_HAND);
                swung = true;
            }
            mc.getConnection().send(new ServerboundAttackPacket(id));
            this.attackedIds.add(id);
        }
        this.breakTimer.reset();
        if (strictTickEnd) {
            this.attackCycle.attacked(look, !this.rotationMode.is(RotationMode.NONE) && this.silent.get());
        } else if (this.idPredictRotateBack.get()) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(player.getX(), player.getY(),
                    player.getZ(), look.yaw(), look.pitch(), player.onGround(), player.horizontalCollision));
        }
    }

    // --------------------------------------------------------------------------------- SetDead

    private void removeCrystalClientSide(final EndCrystal crystal) {
        this.deadCrystalIds.put(crystal.getId(), System.nanoTime() / 1_000_000L);
    }

    private void maintainSetDead() {
        if (this.deadCrystalIds.isEmpty()) {
            return;
        }
        long nowMs = System.nanoTime() / 1_000_000L;
        var iterator = this.deadCrystalIds.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            int id = entry.getKey();
            if (this.confirmedIds.remove(id)) {
                this.attackedIds.remove(id);
                iterator.remove();
                continue;
            }
            // Unconfirmed past ConfirmTime: the crystal is still alive server-side, so stop
            // pretending it is dead and let the module attack it again. The entity was never
            // removed from the world, so there is nothing to restore.
            if (nowMs - entry.getValue() >= this.confirmTime.getInt()) {
                iterator.remove();
            }
        }
    }

    // ----------------------------------------------------------------------------------- options

    private CrystalDamage.DamageOptions placeOptions(final boolean basePlace) {
        int ticks = basePlace ? this.basePlaceTicks.getInt() : this.placeTicks.getInt();
        return new CrystalDamage.DamageOptions(
                this.minEnemyDamage.get(), this.maxSelfDamage.get(), this.maxFriendDamage.get(),
                this.antiSuicide.get(), this.efficient.get(), this.terrain.get(),
                this.selfPredict.get(), this.targetPredict.get(),
                ticks, ticks,
                this.calcMode.get(), this.logic.get());
    }

    private CrystalDamage.DamageOptions breakOptions() {
        int ticks = this.breakTicks.getInt();
        return new CrystalDamage.DamageOptions(
                this.minEnemyDamage.get(), this.maxSelfDamage.get(), this.maxFriendDamage.get(),
                this.antiSuicide.get(), this.efficient.get(), this.terrain.get(),
                this.selfPredict.get(), this.targetPredict.get(),
                ticks, ticks,
                this.calcMode.get(), this.logic.get());
    }
}
