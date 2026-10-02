package com.mentalfrostbyte.jello.module.impl.combat;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.network.EventSendPacket;
import com.mentalfrostbyte.jello.event.impl.player.EventStopUsingItem;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.util.math.Rotations;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import com.mentalfrostbyte.jello.util.movement.MovementCorrection;
import com.mentalfrostbyte.jello.util.movement.MovementCorrector;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Attacks the nearest enemy in reach, turning to face it.
 *
 * <p>Written for this client, not ported: it hangs off the client's own events - the tick, the movement report
 * ({@link EventMotion}) and the outgoing packets - and attacks through the same {@code MultiPlayerGameMode.attack} a click goes through, so {@code EventAttack}
 * listeners such as Criticals and SuperKnockback work with it.</p>
 *
 * <p>Each tick, at its start, it picks a target, works out this tick's look direction, and - if that look meets the
 * target within reach and an attack is due - attacks, all before the tick's movement report. That is the order a
 * vanilla click keeps: the attack goes out first and the look it was aimed with follows in the same tick's movement
 * packet. Which entities are eligible comes from the shared {@link Target} module. With {@code Silent} the look is only
 * reported to the server; the camera stays with the player, and the walking is kept in step with the reported look by
 * the {@link MovementCorrector} (the {@code Movement Corrector} setting).</p>
 *
 * <p>The rotation and AutoBlock modes span both sides of the line an anticheat draws, for {@code SelfDetection} to be
 * tested with; which of them GrimAC flags, and why, is in {@code SELFCHECK_PORTING.md}. {@code Claude1}, {@code Claude2}
 * and {@code Claude3} (a {@link MovementCorrection}) are experimental modes named after their author for now.</p>
 */
public class KillAura extends Module {

    /** How the target is chosen among those in reach. */
    public enum Priority {
        DISTANCE,
        ANGLE,
        HEALTH
    }

    /** When an attack is due: Auto always obeys the click rate and, on 1.9+, also waits for a full cooldown. */
    public enum Timing {
        AUTO,
        COOLDOWN,
        CPS
    }

    public enum RotationMode {
        /** Never turns; attacks what the player happens to be looking at, or - with Ray Trace off - anything in reach. */
        NONE,
        /** Faces the target's centre at once, keeping the yaw continuous. */
        SNAP,
        /** Faces the target's centre at once with atan2's yaw as it comes, wrapped to -180..180. */
        WRAPPED,
        /** Turns towards the target's centre by at most {@code Turn Speed} a tick. */
        SMOOTH,
        /** Experimental: eases towards the nearest point of the target in whole mouse steps. */
        CLAUDE1
    }

    public enum AutoBlock {
        NONE,
        /** Keeps the block up and attacks through it. */
        HOLD,
        /** Lowers the block, attacks and raises it again, all in one tick. */
        SAME_TICK,
        /** Experimental: lowers the block one tick, attacks and raises it the next, as a block-hitting player does. */
        CLAUDE2
    }

    public enum MovementCorrectorMode {
        /** Follows the currently selected Mode on the MovementCorrector module. */
        MOVCOR,
        OFF,
        STRICT,
        SILENT,
        CLAUDE3;

        MovementCorrection resolve(final MovementCorrector corrector) {
            return this == MOVCOR ? corrector.mode() : MovementCorrection.valueOf(this.name());
        }

        @Override
        public String toString() {
            return this == MOVCOR ? "MovCor" : this.name();
        }
    }

    private final NumberSetting range = this.register(new NumberSetting("Range",
            "How far away a target can be hit, in blocks. Vanilla's reach is 3.", 3.0F, 1.0F, 6.0F, 0.05F));

    private final NumberSetting aimRange = this.register(new NumberSetting("Aim Range",
            "How far away it starts turning to a target, in blocks.", 4.0F, 1.0F, 8.0F, 0.1F));

    private final EnumSetting<Priority> priority = this.register(new EnumSetting<>("Priority",
            "Which target to pick: the nearest, the one closest to where you look, or the weakest.", Priority.DISTANCE));

    private final EnumSetting<Timing> timing = this.register(new EnumSetting<>("Timing",
            "Auto always obeys Min/Max CPS; on 1.9+ it also waits for the attack cooldown.", Timing.AUTO));

    private final NumberSetting minCps = this.register(new NumberSetting("Min CPS",
            "The slowest click rate, when hitting by click rate.", 8.0F, 1.0F, 20.0F, 1.0F));

    private final NumberSetting maxCps = this.register(new NumberSetting("Max CPS",
            "The fastest click rate, when hitting by click rate.", 12.0F, 1.0F, 20.0F, 1.0F));

    private final EnumSetting<RotationMode> rotationMode = this.register(new EnumSetting<>("Rotation",
            "How it turns to the target. Claude1 is experimental.", RotationMode.SNAP));

    private final NumberSetting turnSpeed = this.register(new NumberSetting("Turn Speed",
            "Smooth: the most it turns in a tick, in degrees.", 40.0F, 1.0F, 180.0F, 1.0F));

    private final BooleanSetting silent = this.register(new BooleanSetting("Silent",
            "Turns only what the server is told; your camera stays where you point it.", true));

    private final EnumSetting<MovementCorrectorMode> movementCorrector = this.register(new EnumSetting<>("Movement Corrector",
            "Silent look: how the walking is kept to the facing the server is told. MovCor follows the MovementCorrector "
                    + "module's Mode; Strict, Silent, Claude3 and Off keep their old per-aura behaviour.",
            MovementCorrectorMode.STRICT));

    private final BooleanSetting rayTrace = this.register(new BooleanSetting("Ray Trace",
            "Only hits when the look direction actually meets the target.", true));

    private final BooleanSetting throughWalls = this.register(new BooleanSetting("Through Walls",
            "Ray Trace: also hits through blocks.", false));

    private final EnumSetting<AutoBlock> autoBlock = this.register(new EnumSetting<>("AutoBlock",
            "Blocks with a sword (1.8) or an off-hand shield between hits. Claude2 is experimental.", AutoBlock.NONE));

    // Game thread only.
    private @Nullable LivingEntity target;
    /** The look reported this tick, or null to leave the report to vanilla. */
    private @Nullable Rotation rotation;
    /**
     * The look the server holds: the last one any movement packet carried, a teleport confirmation's included - the
     * client's own record of what it reported misses those.
     */
    private @Nullable Rotation lastSent;
    private float clickBudget;
    private float cps;
    private boolean blockingByAura;
    /** Claude2: the block was lowered last tick for an attack this tick. */
    private boolean pendingAttack;

    public KillAura() {
        super(ModuleCategory.COMBAT, "KillAura", "Attacks the nearest enemy in reach, turning to face it.");
        this.minCps.visibleWhen(() -> !this.timing.is(Timing.COOLDOWN));
        this.maxCps.visibleWhen(() -> !this.timing.is(Timing.COOLDOWN));
        this.turnSpeed.visibleWhen(() -> this.rotationMode.is(RotationMode.SMOOTH));
        this.silent.visibleWhen(() -> !this.rotationMode.is(RotationMode.NONE));
        this.movementCorrector.visibleWhen(() -> !this.rotationMode.is(RotationMode.NONE) && this.silent.get());
        this.throughWalls.visibleWhen(this.rayTrace::get);
    }

    @Override
    protected void onEnable() {
        LocalPlayer player = mc.player;
        this.lastSent = player == null ? null : new Rotation(player.getYRot(), player.getXRot());
        this.rotation = null;
        this.target = null;
        this.clickBudget = 0.0F;
        this.cps = 0.0F;
        this.blockingByAura = false;
        this.pendingAttack = false;
    }

    @Override
    protected void onDisable() {
        LocalPlayer player = mc.player;
        if (player != null && this.blockingByAura && mc.gameMode != null) {
            mc.gameMode.releaseUsingItem(player);
        }
        this.blockingByAura = false;
        this.pendingAttack = false;
        this.rotation = null;
        this.target = null;
        MovementCorrector corrector = MovementCorrector.current();
        if (corrector != null) {
            corrector.release(this);
        }
    }

    public @Nullable LivingEntity getTarget() {
        return this.target;
    }

    @EventTarget
    public void onTick(final EventTick event) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        MultiPlayerGameMode gameMode = mc.gameMode;
        if (!event.isPre() || player == null || level == null || gameMode == null) {
            return;
        }
        Rotation from = this.lastSent != null ? this.lastSent : new Rotation(player.getYRot(), player.getXRot());
        Vec3 eye = player.getEyePosition();
        this.target = mc.gui.screen() == null && !player.isSpectator() ? this.pickTarget(player, level, eye) : null;

        if (this.target == null) {
            this.rotation = this.returning(from, new Rotation(player.getYRot(), player.getXRot()));
            this.correctMovement();
            this.lowerBlock(player, gameMode);
            this.pendingAttack = false;
            this.clickBudget = 0.0F;
            return;
        }

        this.rotation = this.step(from, this.aimAt(eye, this.target));
        if (this.rotation != null && !this.silent.get()) {
            player.setYRot(this.rotation.yaw());
            player.setXRot(this.rotation.pitch());
        }
        this.correctMovement();
        Rotation look = this.rotation != null ? this.rotation : new Rotation(player.getYRot(), player.getXRot());
        Optional<Vec3> hit = this.reach(player, level, eye, look, this.target);
        boolean due = this.tickClick(player);
        this.fight(player, gameMode, this.target, hit, due);
    }

    /**
     * A block the aura raised stays up until the aura lowers it, as if the player held the use key: without this the
     * game would lower it again in the same tick, and the server would see it flicker instead of stay.
     */
    @EventTarget
    public void onStopUsing(final EventStopUsingItem event) {
        if (this.blockingByAura && this.target != null) {
            event.cancel();
        }
    }

    /**
     * A silent look is only reported, so the server predicts the walking from it while the client works it out from the
     * camera. Asked for each tick the look is reported, for that tick: the corrector brings the two together.
     */
    private void correctMovement() {
        MovementCorrector corrector = MovementCorrector.current();
        if (corrector != null && this.rotation != null && this.silent.get()) {
            corrector.request(this, this.rotation, this.movementCorrector.get().resolve(corrector));
        }
    }

    @EventTarget
    public void onMotion(final EventMotion event) {
        Rotation reported = this.rotation;
        if (event.isPre() && reported != null && this.silent.get()) {
            event.setYaw(reported.yaw());
            event.setPitch(reported.pitch());
            // After a teleport the server holds the confirmation's look, while the client still remembers the one it
            // reported before; a look that happens to match that stale record would otherwise not be sent at all.
            if (!reported.equals(this.lastSent)) {
                event.forceRotation();
            }
        }
    }

    /**
     * Since 1.19 the use-item packet carries the look it was aimed with, and the server holds it to the tick's movement
     * report; with a silent look that must be the reported one, not the camera's. Every movement packet that carries a
     * look is noted as what the server now holds.
     */
    @EventTarget
    public void onSend(final EventSendPacket event) {
        if (!mc.isSameThread()) {
            return;
        }
        Rotation reported = this.rotation;
        if (reported != null && this.silent.get() && event.getPacket() instanceof ServerboundUseItemPacket use) {
            event.setPacket(new ServerboundUseItemPacket(use.getHand(), use.getSequence(), reported.yaw(), reported.pitch()));
        } else if (event.getPacket() instanceof ServerboundMovePlayerPacket move && move.hasRotation()) {
            this.lastSent = new Rotation(move.getYRot(0.0F), move.getXRot(0.0F));
        }
    }

    // ------------------------------------------------------------------------------------------------ targets

    private @Nullable LivingEntity pickTarget(final LocalPlayer player, final ClientLevel level, final Vec3 eye) {
        double reach = this.aimRange.get();
        // Stay on the current target while it is still a target; switching every tick makes the look jump about.
        if (this.target != null && this.valid(player, this.target) && distance(eye, this.target) <= reach) {
            return this.target;
        }
        Comparator<LivingEntity> order = switch (this.priority.get()) {
            case DISTANCE -> Comparator.comparingDouble(entity -> distance(eye, entity));
            case ANGLE -> Comparator.comparingDouble(entity -> Math.abs(
                    Rotations.continuous(player.getYRot(), Rotations.towards(eye, entity.getBoundingBox().getCenter()).yaw()) - player.getYRot()));
            case HEALTH -> Comparator.comparingDouble(LivingEntity::getHealth);
        };
        LivingEntity best = null;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living && this.valid(player, living) && distance(eye, living) <= reach
                    && (best == null || order.compare(living, best) < 0)) {
                best = living;
            }
        }
        return best;
    }

    private boolean valid(final LocalPlayer player, final LivingEntity entity) {
        Target targets = Target.current();
        return targets != null && targets.accepts(player, entity);
    }

    private static double distance(final Vec3 eye, final Entity entity) {
        return Math.sqrt(entity.getBoundingBox().distanceToSqr(eye));
    }

    // ---------------------------------------------------------------------------------------------- rotations

    private Rotation aimAt(final Vec3 eye, final LivingEntity entity) {
        AABB box = entity.getBoundingBox();
        Vec3 point = this.rotationMode.is(RotationMode.CLAUDE1) ? Rotations.nearestPoint(eye, box, 0.15) : box.getCenter();
        return Rotations.towards(eye, point);
    }

    /** This tick's look, stepping from what the server was last told; null when this mode does not turn. */
    private @Nullable Rotation step(final Rotation from, final Rotation wanted) {
        return switch (this.rotationMode.get()) {
            case NONE -> null;
            case WRAPPED -> wanted;
            case SNAP -> Rotations.continuous(from, wanted);
            case SMOOTH -> Rotations.limit(from, Rotations.continuous(from, wanted), this.turnSpeed.get(), this.turnSpeed.get());
            case CLAUDE1 -> claude1(from, Rotations.continuous(from, wanted), Rotations.mouseStep(mc.options.sensitivity().get()));
        };
    }

    /**
     * With no target, the gradual modes turn back to the player's own look the way they turned away, and hand the
     * report back to vanilla once they are there; the others hand it back at once.
     */
    private @Nullable Rotation returning(final Rotation from, final Rotation own) {
        if (this.rotation == null || !this.silent.get()) {
            return null;
        }
        Rotation back = Rotations.continuous(from, own);
        Rotation next = switch (this.rotationMode.get()) {
            case SMOOTH -> Rotations.limit(from, back, this.turnSpeed.get(), this.turnSpeed.get());
            case CLAUDE1 -> claude1(from, back, Rotations.mouseStep(mc.options.sensitivity().get()));
            default -> null;
        };
        if (next == null || Math.abs(next.yaw() - back.yaw()) < 1.0F && Math.abs(next.pitch() - back.pitch()) < 1.0F) {
            return null;
        }
        return next;
    }

    /**
     * Claude1's step: covers 60% of the turn left each tick - at least 3 degrees, at most 55 sideways and 30 up or down -
     * so it starts fast and settles, then rounds the turn to whole mouse steps as a real mouse would have made it.
     */
    static Rotation claude1(final Rotation from, final Rotation to, final double mouseStep) {
        float yaw = ease(to.yaw() - from.yaw(), 55.0F);
        float pitch = ease(to.pitch() - from.pitch(), 30.0F);
        return Rotations.quantize(from, new Rotation(from.yaw() + yaw, from.pitch() + pitch), mouseStep);
    }

    static float ease(final float left, final float max) {
        float abs = Math.abs(left);
        float move = Math.min(abs, Math.max(3.0F, abs * 0.6F));
        return Math.copySign(Math.min(move, max), left);
    }

    // ------------------------------------------------------------------------------------------------ hitting

    /** Where {@code look} from the eye meets the target within Range, or anywhere in Range with Ray Trace off. */
    private Optional<Vec3> reach(final LocalPlayer player, final ClientLevel level, final Vec3 eye, final Rotation look,
                                 final LivingEntity entity) {
        double reach = this.range.get();
        AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
        if (!this.rayTrace.get()) {
            return distance(eye, entity) <= reach ? Optional.of(box.getCenter()) : Optional.empty();
        }
        Optional<Vec3> hit = Rotations.hit(eye, look, box, reach);
        if (hit.isEmpty() || this.throughWalls.get()) {
            return hit;
        }
        HitResult wall = level.clip(new ClipContext(eye, hit.get(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return wall.getType() == HitResult.Type.MISS ? hit : Optional.empty();
    }

    /** Whether an attack is due this tick; a click rate only builds up while there is a target. */
    private boolean tickClick(final LocalPlayer player) {
        Timing selected = this.timing.get();
        boolean cooldownReady = player.getAttackStrengthScale(0.5F) >= 1.0F;
        if (selected == Timing.COOLDOWN) {
            return cooldownReady;
        }

        if (this.cps <= 0.0F) {
            this.rollCps();
        }
        this.clickBudget = Math.min(1.0F, this.clickBudget + this.cps / 20.0F);
        boolean cpsReady = this.clickBudget >= 1.0F;
        return timingDue(selected, legacyCombat(), cooldownReady, cpsReady);
    }

    /**
     * AUTO keeps the CPS limiter on every protocol. Modern combat adds the vanilla cooldown as a second gate;
     * legacy combat has no attack cooldown, so AUTO is the same timing gate as CPS there.
     */
    static boolean timingDue(final Timing timing, final boolean legacy, final boolean cooldownReady, final boolean cpsReady) {
        return switch (timing) {
            case COOLDOWN -> cooldownReady;
            case CPS -> cpsReady;
            case AUTO -> cpsReady && (legacy || cooldownReady);
        };
    }

    private void rollCps() {
        float low = Math.min(this.minCps.get(), this.maxCps.get());
        float high = Math.max(this.minCps.get(), this.maxCps.get());
        this.cps = low + ThreadLocalRandom.current().nextFloat() * (high - low);
    }

    /** 1.8 and older: no cooldown, and the swing goes out before the attack. */
    private static boolean legacyCombat() {
        return ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8);
    }

    private void fight(final LocalPlayer player, final MultiPlayerGameMode gameMode, final LivingEntity entity,
                       final Optional<Vec3> hit, final boolean due) {
        InteractionHand hand = blockingHand(player);
        AutoBlock mode = hand == null ? AutoBlock.NONE : this.autoBlock.get();
        boolean blocking = hand != null && player.isUsingItem() && player.getUsedItemHand() == hand;
        if (player.isUsingItem() && !blocking) {
            // Eating, drawing a bow...: a click does nothing then, so neither does the aura.
            return;
        }
        boolean attack = hit.isPresent() && due;
        switch (mode) {
            case NONE -> {
                if (attack) {
                    this.attack(player, gameMode, entity);
                }
            }
            case HOLD -> {
                if (attack) {
                    this.attack(player, gameMode, entity);
                }
                if (!blocking) {
                    this.raiseBlock(player, gameMode, hand);
                }
            }
            case SAME_TICK -> {
                if (attack) {
                    if (blocking) {
                        this.lowerBlock(player, gameMode);
                    }
                    this.attack(player, gameMode, entity);
                    this.raiseBlock(player, gameMode, hand);
                } else if (!blocking) {
                    this.raiseBlock(player, gameMode, hand);
                }
            }
            case CLAUDE2 -> {
                if (this.pendingAttack) {
                    // Lowered last tick: hit now if it still reaches, then block again the way a right click would.
                    this.pendingAttack = false;
                    if (hit.isPresent()) {
                        this.attack(player, gameMode, entity);
                    }
                    this.raiseBlockByClick(player, gameMode, entity, hit);
                } else if (attack && blocking) {
                    // A player has to let go of the block before a click hits; the hit comes next tick.
                    this.lowerBlock(player, gameMode);
                    this.pendingAttack = true;
                } else if (attack) {
                    this.attack(player, gameMode, entity);
                    this.raiseBlockByClick(player, gameMode, entity, hit);
                } else if (!blocking) {
                    this.raiseBlockByClick(player, gameMode, entity, hit);
                }
            }
        }
    }

    /** A click on the target, the way {@code Minecraft.startAttack} makes one, swing included. */
    private void attack(final LocalPlayer player, final MultiPlayerGameMode gameMode, final LivingEntity entity) {
        boolean legacy = legacyCombat();
        if (legacy) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        gameMode.attack(player, entity);
        if (!legacy) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        this.clickBudget = Math.max(0.0F, this.clickBudget - 1.0F);
        this.rollCps();
    }

    /** The hand that holds something to block with: a sword on 1.8 (ViaFabricPlus lets it block), else a shield. */
    static @Nullable InteractionHand blockingHand(final LocalPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).getUseAnimation() == ItemUseAnimation.BLOCK) {
                return hand;
            }
        }
        return null;
    }

    /**
     * Raises the block straight away with a use-item packet. Whether it went up is read off the player, not the result:
     * on 1.8 a sword's use reports PASS (the stack did not change) although the block is up.
     */
    private void raiseBlock(final LocalPlayer player, final MultiPlayerGameMode gameMode, final InteractionHand hand) {
        gameMode.useItem(player, hand);
        this.blockingByAura = player.isUsingItem();
    }

    /**
     * Raises the block the way a right click on the target does ({@code Minecraft.startUseItem}): for each hand, first
     * an interaction with the entity under the crosshair, and only if that does nothing, the item.
     */
    private void raiseBlockByClick(final LocalPlayer player, final MultiPlayerGameMode gameMode, final LivingEntity entity,
                                   final Optional<Vec3> hit) {
        if (player.isUsingItem()) {
            return;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);
            if (hit.isPresent() && player.isWithinEntityInteractionRange(entity, 0.0)
                    && gameMode.interact(player, entity, new EntityHitResult(entity, hit.get()), hand) instanceof InteractionResult.Success) {
                return;
            }
            if (!held.isEmpty() && gameMode.useItem(player, hand) instanceof InteractionResult.Success) {
                break;
            }
        }
        this.blockingByAura = player.isUsingItem();
    }

    private void lowerBlock(final LocalPlayer player, final MultiPlayerGameMode gameMode) {
        if (this.blockingByAura && player.isUsingItem()) {
            gameMode.releaseUsingItem(player);
        }
        this.blockingByAura = false;
    }
}
