package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.action.EventClick;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventSafeWalk;
import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.module.impl.movement.blockfly.BlockFlyPlacement.Target;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.util.math.Rotations;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import com.mentalfrostbyte.jello.util.movement.MovementCorrection;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** HeyPixel's shared jump, Eagle, snap and held-item presentation policy, plus Grim 3-tick SprintScaffold. */
abstract class BlockFlyCustomBase extends BlockFlyMode {
    public enum BridgeMode { NORMAL, GRIM_SPRINT, TELLY_BRIDGE, KEEP_Y }

    private static final int[] GRIM_YAW_STEPS = {-2, 1, -1, 2, 3};
    private static final Set<Block> EXTRA_EXCLUDED = Set.of(Blocks.SNOW, Blocks.COAL_ORE, Blocks.DIAMOND_ORE,
            Blocks.EMERALD_ORE, Blocks.GOLD_ORE, Blocks.IRON_ORE, Blocks.LAPIS_ORE, Blocks.REDSTONE_TORCH, Blocks.FLOWER_POT);
    protected final EnumSetting<BridgeMode> bridgeMode;
    protected final BooleanSetting eagle;
    protected final BooleanSetting sneak;
    protected final BooleanSetting snap;
    protected final NumberSetting grimSprintWindow;
    protected final BooleanSetting hideSnap;
    protected final BooleanSetting renderItemSpoof;
    protected Rotation rotation;
    protected Rotation correctRotation;
    protected boolean hasAnchor;
    protected boolean tellyGroundFrame;
    protected int baseY;
    protected int offGroundTicks;
    protected boolean active;
    protected boolean wantsJump;
    private int previousSlot = -1;
    private int ownedSlot = -1;
    private int sneakTicks;
    private boolean forceSneak;
    private boolean queued;
    private @Nullable Target queuedTarget;
    private @Nullable LocalPlayer sessionPlayer;

    private @Nullable Rotation grimStoredAim;
    private @Nullable Rotation grimLockedForward;
    private int grimSprintTicks;
    private int grimAimStepIndex;
    private boolean grimAimingThisTick;
    private boolean grimLastSentWasAim;
    private boolean grimNeedsFlushTick;
    private volatile boolean grimTeleportPending;

    BlockFlyCustomBase(final BlockFly parent, final BlockFly.Mode mode, final String prefix) {
        super(parent);
        this.bridgeMode = parent.modeSetting(mode, new EnumSetting<>(prefix + " Mode", "Bridge style.", BridgeMode.NORMAL));
        this.eagle = parent.modeSetting(mode, new BooleanSetting(prefix + " Eagle", "Sneak on block edges in Normal mode.", true), () -> this.bridgeMode.is(BridgeMode.NORMAL));
        this.sneak = parent.modeSetting(mode, new BooleanSetting(prefix + " Sneak", "Pulse sneak on ticks 18 through 20 of each cycle.", true), () -> !this.bridgeMode.is(BridgeMode.GRIM_SPRINT));
        this.snap = parent.modeSetting(mode, new BooleanSetting(prefix + " Snap", "Snap to a placement face in Normal mode.", true), () -> this.bridgeMode.is(BridgeMode.NORMAL));
        this.grimSprintWindow = parent.modeSetting(
                mode,
                new NumberSetting(
                        prefix + " Grim Sprint Window",
                        "Forward sprint ticks per aim snap (3 for Grim 3-tick RotationPlace window; 0 = lock Pos-only).",
                        3, 0, 20, 1),
                () -> this.bridgeMode.is(BridgeMode.GRIM_SPRINT));
        this.hideSnap = parent.modeSetting(
                mode,
                new BooleanSetting(prefix + " Hide Snap Rotation", "Keep snap corrections out of the camera.", true),
                () -> (this.bridgeMode.is(BridgeMode.NORMAL) && this.snap.get()) || this.bridgeMode.is(BridgeMode.GRIM_SPRINT));
        this.renderItemSpoof = parent.modeSetting(mode, new BooleanSetting(prefix + " Render Item Spoof", "Show the original held item while scaffolding.", true));
    }

    protected abstract boolean activationHeld();
    protected abstract boolean updatePlan(LocalPlayer player, Vec3 eyes);
    protected abstract Rotation computeDesiredRotation(LocalPlayer player, Vec3 eyes);
    protected abstract boolean hitsSnapAnchor(LocalPlayer player, Vec3 eyes, Rotation look);
    protected abstract @Nullable Target resolveMotionPlacement(
            LocalPlayer player, EventMotion event, Vec3 eyes, Rotation currentLook);
    protected abstract float turnSpeed();
    protected boolean grimRotation() { return false; }
    protected boolean rejectUseName() { return false; }
    protected boolean eagleSafeWalk() { return false; }

    protected @Nullable BlockPos currentAnchorPos() {
        return null;
    }

    protected boolean updatePlanForGrimSprint(final LocalPlayer player, final Vec3 eyes,
                                              final @Nullable Rotation storedAim) {
        return this.updatePlan(player, eyes);
    }

    protected @Nullable Target resolveGrimSprintPlacement(final LocalPlayer player, final EventMotion event,
                                                          final Vec3 eyes, final Rotation storedAim) {
        return this.resolveMotionPlacement(player, event, eyes, storedAim);
    }

    @Override
    public boolean usesSharedMovement() { return this.active && this.activationHeld(); }

    private void resetGrimSprintState() {
        this.grimStoredAim = null;
        this.grimLockedForward = null;
        this.grimSprintTicks = 0;
        this.grimAimingThisTick = false;
        this.grimLastSentWasAim = false;
        this.grimNeedsFlushTick = false;
        this.grimTeleportPending = false;
    }

    @Override
    public void enable() {
        this.sessionPlayer = mc.player;
        this.previousSlot = mc.player == null ? -1 : mc.player.getInventory().getSelectedSlot();
        this.ownedSlot = -1;
        this.rotation = mc.player == null ? new Rotation(0.0F, 0.0F)
                : new Rotation(mc.player.getYRot() - 180.0F, mc.player.getXRot());
        this.correctRotation = this.rotation;
        this.baseY = Integer.MIN_VALUE;
        this.offGroundTicks = 0;
        this.sneakTicks = 0;
        this.active = false;
        this.hasAnchor = false;
        this.tellyGroundFrame = false;
        this.forceSneak = false;
        this.wantsJump = false;
        this.queued = false;
        this.queuedTarget = null;
        this.resetGrimSprintState();
    }

    @Override
    public void disable() {
        this.restoreOwnedSlot();
        this.previousSlot = this.ownedSlot = -1;
        this.sessionPlayer = null;
        this.releaseInput();
        this.parent.clearRotation();
    }

    private void restoreOwnedSlot() {
        if (mc.player == this.sessionPlayer && mc.player != null && this.previousSlot >= 0
                && this.ownedSlot >= 0 && mc.player.getInventory().getSelectedSlot() == this.ownedSlot) {
            mc.player.getInventory().setSelectedSlot(this.previousSlot);
            if (mc.gameMode != null && mc.getConnection() != null) {
                if (mc.isInPreMovementTick()) {
                    mc.gameMode.syncCarriedItem();
                } else {
                    mc.gameMode.markCarriedItemDirty();
                }
            }
        }
        this.ownedSlot = -1;
    }

    protected void releaseInput() {
        this.restoreOwnedSlot();
        // Only EventMovementInput is modified, so physical and toggle key states never need unwinding.
        this.active = this.hasAnchor = this.tellyGroundFrame = this.forceSneak = this.wantsJump = this.queued = false;
        this.sneakTicks = 0;
        this.baseY = Integer.MIN_VALUE;
        this.queuedTarget = null;
        this.resetGrimSprintState();
        if (mc.player != null) this.rotation = new Rotation(mc.player.getYRot(), mc.player.getXRot());
    }

    @Override
    public void receivePacket(final com.mentalfrostbyte.jello.event.impl.game.network.EventReceivePacket event) {
        if (event.getPacket() instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket) {
            this.grimTeleportPending = true;
        }
    }

    @Override
    public void sendPacket(final com.mentalfrostbyte.jello.event.impl.game.network.EventSendPacket event) {
        if (!this.bridgeMode.is(BridgeMode.GRIM_SPRINT) || event.isCancelled()) return;
        if (event.getPacket() instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket move) {
            if (this.grimAimingThisTick && move.hasRotation()) {
                this.grimLastSentWasAim = true;
                this.grimNeedsFlushTick = false;
                this.grimSprintTicks = 0;
            } else if (!this.grimAimingThisTick) {
                if (!move.hasRotation()) {
                    this.grimNeedsFlushTick = false;
                    this.grimSprintTicks++;
                } else if (this.grimLastSentWasAim) {
                    this.grimLastSentWasAim = false;
                    this.grimNeedsFlushTick = false;
                    this.grimSprintTicks = 1;
                }
            }
        }
    }

    @Override
    public void tick(final EventTick event) {
        if (!event.isPre()) return;
        if (mc.player != this.sessionPlayer) this.enable();
        if (this.grimTeleportPending) this.resetGrimSprintState();
        this.queued = false;
        this.queuedTarget = null;
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || !this.activationHeld()
                || !mc.player.isAlive() || mc.player.isSpectator()) {
            this.releaseInput();
            this.parent.clearRotation();
            return;
        }
        LocalPlayer player = mc.player;
        if (!this.active) this.previousSlot = player.getInventory().getSelectedSlot();
        this.active = this.selectBlock(player);
        if (!this.active) {
            this.releaseInput();
            this.parent.clearRotation();
            return;
        }
        boolean moving = BlockFlyLegacyMode.isMoving(BlockFlyLegacyMode.physicalInput());
        boolean holdingJump = mc.options.keyJump.isDown();
        if (player.onGround()) this.offGroundTicks = 0;
        else this.offGroundTicks++;
        int belowY = Mth.floor(player.getY()) - 1;
        boolean flatMode = this.bridgeMode.is(BridgeMode.NORMAL) || this.bridgeMode.is(BridgeMode.GRIM_SPRINT);
        if (this.baseY == Integer.MIN_VALUE || this.baseY > belowY || player.onGround() || !moving
                || holdingJump || flatMode) this.baseY = belowY;

        if (this.bridgeMode.is(BridgeMode.GRIM_SPRINT)) {
            this.tickGrimSprint(player, moving);
            return;
        }

        this.resetGrimSprintState();
        this.hasAnchor = this.updatePlan(player, player.getEyePosition());
        this.forceSneak = false;
        if (this.sneak.get()) {
            this.sneakTicks++;
            if (this.sneakTicks >= 21) this.sneakTicks = 0;
            this.forceSneak = this.sneakTicks >= 18;
            if (this.sneakTicks == 18) player.setSprinting(false);
        } else this.sneakTicks = 0;
        this.wantsJump = !flatMode && moving;
        if (this.bridgeMode.is(BridgeMode.NORMAL) && this.eagle.get()) {
            this.forceSneak |= player.onGround() && this.atEdge(player);
        }
        if (this.hasAnchor) {
            this.correctRotation = this.computeDesiredRotation(player, player.getEyePosition());
            this.rotation = this.turnTowards(this.correctRotation);
        }
        if (this.bridgeMode.is(BridgeMode.TELLY_BRIDGE) && this.offGroundTicks < 1 && moving) {
            this.tellyGroundFrame = true;
            float forwardYaw = Rotations.continuous(this.rotation.yaw(), player.getYRot());
            this.rotation = new Rotation(
                    Rotations.limit(this.rotation, new Rotation(forwardYaw, this.rotation.pitch()), 180.0F, 180.0F).yaw(),
                    this.rotation.pitch());
            this.parent.requestRotation(this.rotation, true, MovementCorrection.SILENT);
            return;
        }
        this.tellyGroundFrame = false;
        if (this.hasAnchor) {
            if (this.bridgeMode.is(BridgeMode.NORMAL) && this.snap.get() && !holdingJump
                    && !this.hitsSnapAnchor(player, player.getEyePosition(), this.rotation)) {
                float forwardYaw = Rotations.continuous(this.rotation.yaw(), player.getYRot());
                this.rotation = new Rotation(forwardYaw + jitter(0.25F), this.rotation.pitch());
            }
            this.parent.requestRotation(this.rotation, true, MovementCorrection.SILENT);
        } else {
            this.parent.clearRotation();
        }
    }

    private void tickGrimSprint(final LocalPlayer player, final boolean moving) {
        this.forceSneak = false;
        this.wantsJump = false;
        this.sneakTicks = 0;
        this.tellyGroundFrame = false;

        Vec3 eyes = player.getEyePosition();
        this.hasAnchor = this.updatePlanForGrimSprint(player, eyes, this.grimStoredAim);
        Vec3 ahead = player.position().add(player.getDeltaMovement().scale(2.0));
        boolean airAhead = player.level().getBlockState(BlockPos.containing(ahead.x, this.baseY, ahead.z)).canBeReplaced()
                || player.level().getBlockState(BlockPos.containing(player.getX(), this.baseY, player.getZ())).canBeReplaced();
        if (!this.hasAnchor && !airAhead && this.grimStoredAim == null && !this.grimNeedsFlushTick) {
            this.grimAimingThisTick = false;
            this.parent.clearRotation();
            return;
        }

        float moveYaw = moving
                ? BlockFlyLegacyMode.movementYaw(player.getYRot(), BlockFlyLegacyMode.physicalInput())
                : player.getYRot();
        int maxWindow = Math.max(0, this.grimSprintWindow.getInt());
        BlockPos anchor = this.currentAnchorPos();
        boolean storedHitsAnchor = this.grimStoredAim != null
                && (anchor == null || grimRayHitsSupport(eyes, this.grimStoredAim, anchor, player.blockInteractionRange()));
        boolean windowExpired = maxWindow > 0 && this.grimSprintTicks >= maxWindow && (this.hasAnchor || airAhead);
        boolean directionChanged = this.grimLockedForward != null && moving
                && Math.abs(Mth.wrapDegrees(moveYaw - this.grimLockedForward.yaw())) > 20.0F;
        boolean mustHoldForward = this.grimNeedsFlushTick || this.parent.hasDeferredPlacement();
        boolean shouldAim = !mustHoldForward
                && (this.grimStoredAim == null || !storedHitsAnchor || windowExpired || directionChanged);

        if (shouldAim) {
            this.grimAimingThisTick = true;
            this.rotation = this.computeGrimAim(player, eyes, moveYaw, anchor);
            this.grimStoredAim = this.rotation;
            this.grimLockedForward = null;
            this.grimSprintTicks = 0;
        } else {
            this.grimAimingThisTick = false;
            if (this.grimLockedForward == null) {
                float pitch = this.grimStoredAim != null ? this.grimStoredAim.pitch() : 78.0F;
                this.grimLockedForward = this.fixed(new Rotation(moveYaw, pitch));
            }
            this.rotation = this.grimLockedForward;
        }
        this.parent.requestRotation(this.rotation, true, MovementCorrection.SILENT);
    }

    private Rotation computeGrimAim(final LocalPlayer player, final Vec3 eyes,
                                    final float moveYaw, final @Nullable BlockPos anchor) {
        float step = Math.max(0.05F, (float) Rotations.mouseStep(mc.options.sensitivity().get()));
        int stepMult = GRIM_YAW_STEPS[Math.floorMod(this.grimAimStepIndex++, GRIM_YAW_STEPS.length)];
        float rawYaw = moveYaw - 180.0F + stepMult * step;
        float rawPitch = Mth.clamp(78.0F + ((stepMult & 1) == 0 ? step : 0.0F), -90.0F, 90.0F);
        Rotation backAim = this.fixed(new Rotation(rawYaw, rawPitch));
        if (anchor == null || grimRayHitsSupport(eyes, backAim, anchor, player.blockInteractionRange())) {
            return backAim;
        }
        Rotation desired = this.computeDesiredRotation(player, eyes);
        Rotation nudged = this.fixed(new Rotation(desired.yaw() + stepMult * step, desired.pitch()));
        if (grimRayHitsSupport(eyes, nudged, anchor, player.blockInteractionRange())) {
            return nudged;
        }
        return this.fixed(desired);
    }

    /**
     * Exact geometric match to Grim's {@code RotationPlace.didRayTraceHit}: checks whether {@code aim}
     * intersects {@code SimpleCollisionBox(supportPos)} (deflated slightly against trig-table rounding).
     */
    static boolean grimRayHitsSupport(final Vec3 eyes, final Rotation aim, final BlockPos supportPos, final double range) {
        if (eyes == null || aim == null || supportPos == null || !Double.isFinite(range) || range <= 0.0
                || !Float.isFinite(aim.yaw()) || !Float.isFinite(aim.pitch())) {
            return false;
        }
        AABB fullBox = new AABB(supportPos);
        if (fullBox.contains(eyes)) {
            return true;
        }
        AABB safeBox = fullBox.deflate(0.002);
        Vec3 end = eyes.add(Vec3.directionFromRotation(aim.pitch(), aim.yaw()).scale(range));
        return safeBox.clip(eyes, end).isPresent();
    }

    static int grimYawStepOffset(final int cycleIndex) {
        return GRIM_YAW_STEPS[Math.floorMod(cycleIndex, GRIM_YAW_STEPS.length)];
    }

    @Override
    public void safeWalk(final EventSafeWalk event) {
        if (this.active && this.activationHeld() && this.eagleSafeWalk()
                && this.bridgeMode.is(BridgeMode.NORMAL) && this.eagle.get()
                && mc.player != null && mc.player.onGround() && this.atEdge(mc.player)) {
            event.setSafe(true);
        }
    }

    @Override
    public void input(final EventMovementInput event) {
        if (!this.active || !this.activationHeld() || mc.gui.screen() != null) return;
        if (this.bridgeMode.is(BridgeMode.GRIM_SPRINT)) {
            if (!this.grimAimingThisTick && event.isMoving() && !event.getInput().shift()) {
                event.setSprint(true);
                if (mc.player != null && !mc.player.isUsingItem() && mc.player.getFoodData().getFoodLevel() > 6) {
                    mc.player.setSprinting(true);
                }
            }
            return;
        }
        if (this.forceSneak) event.setShift(true);
        if (!this.bridgeMode.is(BridgeMode.NORMAL) && event.isMoving()) event.setJump(true);
        if (this.forceSneak) event.setSprint(false);
    }

    @Override
    public void motion(final EventMotion event) {
        if (!this.active || !this.activationHeld() || mc.player == null || mc.level == null || mc.gui.screen() != null) {
            this.parent.queuePlacement(null);
            this.parent.clearRotation();
            if (event.isPre() && mc.player != null) {
                event.setYaw(mc.player.getYRot());
                event.setPitch(mc.player.getXRot());
            }
            return;
        }
        if (this.bridgeMode.is(BridgeMode.GRIM_SPRINT)) {
            this.motionGrimSprint(event);
            return;
        }
        if (!event.isPre()) {
            // Explicit 1.17 rotation mode restores the camera direction only on pre-1.21.2 protocols;
            // 1.21.2+ forbids post-movement placements and extra rotation packets before CLIENT_TICK_END.
            if (this.queued && this.grimRotation()
                    && ProtocolTranslator.getTargetVersion().olderThan(ProtocolVersion.v1_21_2)) {
                this.parent.queuePlacement(null);
                this.parent.place(this.queuedTarget);
                mc.player.sendRotation(mc.player.getYRot(), mc.player.getXRot());
            }
            return;
        }
        if (!this.hasAnchor && !this.tellyGroundFrame) return;
        LocalPlayer player = mc.player;
        Vec3 eyes = new Vec3(event.getX(), event.getY() + player.getEyeHeight(), event.getZ());
        this.parent.aim(event, this.rotation, true, MovementCorrection.SILENT);
        if (this.grimRotation()) event.forceRotation();
        if (this.tellyGroundFrame || !this.hasAnchor) return;

        BlockFlyPlacement.Target current = this.resolveMotionPlacement(player, event, eyes, this.rotation);
        if (current == null || !this.isValidStack(player.getMainHandItem())) return;
        if (current.rotation().pitch() != this.rotation.pitch()
                || current.rotation().yaw() != this.rotation.yaw()) {
            this.rotation = new Rotation(this.rotation.yaw(), current.rotation().pitch());
            this.parent.aim(event, this.rotation, true, MovementCorrection.SILENT);
            if (this.grimRotation()) event.forceRotation();
        }
        this.parent.queuePlacement(current);
        this.queued = true;
        this.queuedTarget = current;
    }

    private void motionGrimSprint(final EventMotion event) {
        if (!event.isPre()) {
            if (this.grimNeedsFlushTick) {
                this.parent.queuePlacement(null);
            }
            return;
        }
        LocalPlayer player = mc.player;
        Vec3 eyes = new Vec3(event.getX(), event.getY() + player.getEyeHeight(), event.getZ());
        if (this.grimAimingThisTick) {
            boolean moving = BlockFlyLegacyMode.isMoving(BlockFlyLegacyMode.physicalInput());
            float moveYaw = moving
                    ? BlockFlyLegacyMode.movementYaw(player.getYRot(), BlockFlyLegacyMode.physicalInput())
                    : player.getYRot();
            this.hasAnchor = this.updatePlanForGrimSprint(player, eyes, null);
            this.rotation = this.computeGrimAim(player, eyes, moveYaw, this.currentAnchorPos());
            this.grimStoredAim = this.rotation;
            this.parent.aim(event, this.rotation, true, MovementCorrection.SILENT);

            Target current = this.resolveGrimSprintPlacement(player, event, eyes, this.rotation);
            if (current != null && current.hit().getDirection() != Direction.DOWN
                    && grimRayHitsSupport(eyes, this.rotation, current.hit().getBlockPos(), player.blockInteractionRange())
                    && this.isValidStack(player.getMainHandItem())) {
                this.parent.queuePlacement(current);
            }
            return;
        }

        if (this.grimLockedForward == null) {
            return;
        }
        this.parent.aim(event, this.rotation, true, MovementCorrection.SILENT);
        int maxWindow = Math.max(0, this.grimSprintWindow.getInt());
        if (this.grimStoredAim == null || (maxWindow > 0 && this.grimSprintTicks >= maxWindow)) {
            return;
        }
        this.hasAnchor = this.updatePlanForGrimSprint(player, eyes, this.grimStoredAim);
        Target sprintTarget = this.resolveGrimSprintPlacement(player, event, eyes, this.grimStoredAim);
        if (sprintTarget == null || sprintTarget.hit().getDirection() == Direction.DOWN) {
            return;
        }
        if (!grimRayHitsSupport(eyes, this.grimStoredAim, sprintTarget.hit().getBlockPos(), player.blockInteractionRange())) {
            return;
        }
        if (!this.isValidStack(player.getMainHandItem())) {
            return;
        }
        Target nonStrict = sprintTarget.strictRaycast()
                ? new Target(sprintTarget.placedPos(), sprintTarget.hit(), sprintTarget.rotation(), eyes, false)
                : sprintTarget;
        this.parent.queuePlacement(nonStrict, () -> this.grimNeedsFlushTick = true);
    }

    @Override
    public void click(final EventClick event) {
        if (event.getButton() == EventClick.Button.RIGHT && this.active && this.activationHeld()) event.cancel();
    }

    @Override
    public ItemStack renderedItem(final InteractionHand hand, final ItemStack original) {
        if (hand == InteractionHand.MAIN_HAND && this.renderItemSpoof.get() && this.active
                && mc.player == this.sessionPlayer && mc.player != null && this.previousSlot >= 0) {
            return mc.player.getInventory().getItem(this.previousSlot);
        }
        return original;
    }

    /** A render-only look; it must never be written back to LocalPlayer's camera angles. */
    @Override
    public Rotation cameraRotation() {
        boolean hasLook = this.hasAnchor || (this.bridgeMode.is(BridgeMode.GRIM_SPRINT) && this.grimStoredAim != null);
        return this.active && hasLook && !this.hideSnap.get() ? this.rotation : null;
    }

    protected final Rotation turnTowards(final Rotation desired) {
        Rotation continuous = Rotations.continuous(this.rotation, desired);
        if (this.bridgeMode.is(BridgeMode.NORMAL) && this.snap.get()) return continuous;
        return new Rotation(Rotations.limit(this.rotation, continuous, this.turnSpeed(), 180.0F).yaw(), continuous.pitch());
    }

    protected final boolean hitsTarget(final LocalPlayer player, final Vec3 eyes, final Rotation look,
                                       final BlockFlyPlacement.Target expected) {
        BlockHitResult hit = BlockFlyPlacement.raycast(player, eyes, look);
        return hit != null && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(expected.hit().getBlockPos())
                && hit.getDirection() == expected.hit().getDirection();
    }

    protected final Rotation fixed(final Rotation desired) {
        return Rotations.quantize(this.rotation, Rotations.continuous(this.rotation, desired),
                Rotations.mouseStep(mc.options.sensitivity().get()));
    }

    private boolean selectBlock(final LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (this.isValidStack(player.getInventory().getItem(slot))) {
                if (player.getInventory().getSelectedSlot() != slot) {
                    player.getInventory().setSelectedSlot(slot);
                    if (mc.isInPreMovementTick() && mc.gameMode != null && mc.getConnection() != null) {
                        mc.gameMode.syncCarriedItem();
                    }
                }
                this.ownedSlot = slot;
                return true;
            }
        }
        return false;
    }

    protected boolean isValidStack(final ItemStack stack) {
        if (!BlockFlyPlacement.isPlaceable(stack) || stack.getCount() <= 1
                || !(stack.getItem() instanceof BlockItem item) || item.getBlock() instanceof SlabBlock
                || EXTRA_EXCLUDED.contains(item.getBlock())) return false;
        String name = stack.getHoverName().getString();
        return !name.contains("Click") && !name.contains("Right") && !name.contains("Teleport")
                && (!this.rejectUseName() || !name.contains("Use"))
                && !name.contains("点击") && !name.contains("使用") && !name.contains("传送") && !name.contains("再来");
    }

    private boolean atEdge(final LocalPlayer player) {
        AABB box = player.getBoundingBox();
        double inset = Math.min(0.3, Math.min(box.getXsize(), box.getZsize()) / 2.0 - 0.001);
        return mc.level.noCollision(player, box.deflate(inset, 0.0, inset).move(0.0, -0.5, 0.0));
    }

    protected static float jitter(final float amount) {
        return (float) ThreadLocalRandom.current().nextDouble(-amount, amount);
    }

    /** Naven's six-layer diamond, deterministic and de-duplicated at the symmetry axes. */
    protected static Set<BlockPos> pyramid(final BlockPos base) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        positions.add(base);
        for (int distance = 1; distance <= 6; distance++) {
            positions.add(base.below(distance));
            for (int x = 0; x <= distance; x++) {
                for (int z = 0; z <= distance - x; z++) {
                    if (x == 0 && z == 0) continue;
                    int y = distance - x - z;
                    positions.add(base.offset(x, -y, z));
                    positions.add(base.offset(-x, -y, z));
                    positions.add(base.offset(x, -y, -z));
                    positions.add(base.offset(-x, -y, -z));
                }
            }
        }
        return positions;
    }
}
