package com.mentalfrostbyte.jello.module.impl.world;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.player.EventUpdate;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Automatically breaks beds, dragon eggs and cakes within reach (BedNuker / Fucker).
 *
 * <p>Supports two modes:</p>
 * <ul>
 *   <li><b>ROTATION</b>: aims smoothly at the target block with mouse-step GCD quantization, reports the
 *       facing silently on {@link EventMotion}, optionally applies {@link MovementCorrector}, and only mines
 *       once the reported look is aligned with the block.</li>
 *   <li><b>NORMAL</b>: mines the target block directly without altering player rotations.</li>
 * </ul>
 */
public class Fucker extends Module {

    public enum Mode {
        /** Silent server-side rotation with smooth turn speed and optional movement correction. */
        ROTATION,
        /** Direct block breaking without changing look angles. */
        NORMAL
    }

    private static final float AIM_TOLERANCE_DEG = 12.0F;
    private static final int ROTATION_PRIORITY = 80;

    private final EnumSetting<Mode> mode = this.register(new EnumSetting<>(
            "Mode", "ROTATION aims silently at the target block; NORMAL breaks without turning.", Mode.ROTATION));

    private final NumberSetting range = this.register(new NumberSetting(
            "Range", "Maximum reach in blocks from eye position to the target block.",
            4.5F, 1.5F, 6.0F, 0.1F));

    private final BooleanSetting beds = this.register(new BooleanSetting(
            "Beds", "Break beds within reach.", true));

    private final BooleanSetting dragonEgg = this.register(new BooleanSetting(
            "DragonEgg", "Break dragon eggs within reach.", true));

    private final BooleanSetting cake = this.register(new BooleanSetting(
            "Cake", "Break cakes within reach.", false));

    private final BooleanSetting throughWalls = this.register(new BooleanSetting(
            "ThroughWalls", "Allow breaking target blocks even when another block covers them.", true));

    private final BooleanSetting surroundings = this.register(new BooleanSetting(
            "Surroundings", "When ThroughWalls is off and a bed is covered, mine the blocking cover block first.", true));

    private final NumberSetting turnSpeed = this.register(new NumberSetting(
            "TurnSpeed", "Maximum degrees per tick in ROTATION mode.",
            90.0F, 15.0F, 180.0F, 5.0F));

    private final EnumSetting<MovementCorrection> moveFix = this.register(new EnumSetting<>(
            "MoveFix", "Movement correction while silently rotating towards the block.", MovementCorrection.SILENT));

    private final BooleanSetting swing = this.register(new BooleanSetting(
            "Swing", "Swing the main hand while breaking.", true));

    private @Nullable BlockPos targetPos;
    private Direction targetFace = Direction.UP;
    private @Nullable Rotation currentRotation;
    private boolean breaking;

    public Fucker() {
        super(ModuleCategory.WORLD, "Fucker", "Automatically breaks nearby beds, dragon eggs and cakes");
        this.surroundings.visibleWhen(() -> !this.throughWalls.get());
        this.turnSpeed.visibleWhen(() -> this.mode.is(Mode.ROTATION));
        this.moveFix.visibleWhen(() -> this.mode.is(Mode.ROTATION));
    }

    @Override
    protected void onDisable() {
        this.resetState(true);
    }

    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) {
        this.resetState(false);
    }

    @EventTarget
    public void onUpdate(final EventUpdate event) {
        if (!event.isPre() || mc.player == null || mc.level == null || mc.gameMode == null) {
            return;
        }

        BlockPos next = this.findBestTarget();
        if (next == null) {
            this.resetState(true);
            return;
        }

        if (!next.equals(this.targetPos)) {
            if (this.breaking) {
                mc.gameMode.stopDestroyBlock();
                this.breaking = false;
            }
            this.targetPos = next;
        }

        this.targetFace = this.bestFace(next);

        if (this.mode.is(Mode.NORMAL)) {
            this.releaseRotation();
            this.mineStep(next, this.targetFace);
        }
    }

    @EventTarget
    public void onMotion(final EventMotion event) {
        if (!this.mode.is(Mode.ROTATION) || mc.player == null || mc.level == null || mc.gameMode == null) {
            return;
        }

        if (this.targetPos == null) {
            this.releaseRotation();
            return;
        }

        if (event.isPre()) {
            Vec3 eye = mc.player.getEyePosition();
            Vec3 aimPoint = faceCenter(this.targetPos, this.targetFace);
            Rotation desired = Rotations.towards(eye, aimPoint);
            Rotation from = this.currentRotation != null
                    ? this.currentRotation
                    : new Rotation(event.getYaw(), event.getPitch());
            Rotation continuousDesired = Rotations.continuous(from, desired);
            float maxStep = this.turnSpeed.get();
            Rotation stepped = Rotations.limit(from, continuousDesired, maxStep, maxStep);
            double mouseStep = Rotations.mouseStep(mc.options.sensitivity().get());
            Rotation quantized = Rotations.quantize(from, stepped, mouseStep);

            this.currentRotation = quantized;
            event.setYaw(quantized.yaw());
            event.setPitch(quantized.pitch());
            event.forceRotation();

            MovementCorrector corrector = MovementCorrector.current();
            if (corrector != null) {
                corrector.request(this, quantized, this.moveFix.get(), ROTATION_PRIORITY);
            }
            return;
        }

        // POST: break after the rotated movement packet has been sent, once aimed within tolerance.
        if (this.currentRotation != null) {
            Vec3 eye = mc.player.getEyePosition();
            Rotation desired = Rotations.towards(eye, faceCenter(this.targetPos, this.targetFace));
            float yawDiff = Math.abs(Mth.wrapDegrees(desired.yaw() - this.currentRotation.yaw()));
            float pitchDiff = Math.abs(desired.pitch() - this.currentRotation.pitch());
            if (yawDiff <= AIM_TOLERANCE_DEG && pitchDiff <= AIM_TOLERANCE_DEG) {
                this.mineStep(this.targetPos, this.targetFace);
            }
        }
    }

    private void mineStep(final BlockPos pos, final Direction face) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            return;
        }
        if (mc.level.getBlockState(pos).isAir()) {
            this.breaking = false;
            this.targetPos = null;
            return;
        }

        if (!this.breaking) {
            if (mc.gameMode.startDestroyBlock(pos, face)) {
                this.breaking = true;
            }
        } else {
            if (!mc.gameMode.continueDestroyBlock(pos, face)) {
                this.breaking = false;
            }
        }

        if (this.swing.get()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private @Nullable BlockPos findBestTarget() {
        if (mc.player == null || mc.level == null) {
            return null;
        }

        Vec3 eye = mc.player.getEyePosition();
        double maxReach = this.range.get();
        double maxReachSq = maxReach * maxReach;
        int radius = (int) Math.ceil(maxReach);
        BlockPos origin = BlockPos.containing(eye);

        BlockPos closestObjective = null;
        double closestDistSq = Double.MAX_VALUE;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState state = mc.level.getBlockState(cursor);
                    if (!this.isObjective(state)) {
                        continue;
                    }

                    double distSq = eye.distanceToSqr(Vec3.atCenterOf(cursor));
                    if (distSq <= maxReachSq && distSq < closestDistSq) {
                        closestDistSq = distSq;
                        closestObjective = cursor.immutable();
                    }
                }
            }
        }

        if (closestObjective == null) {
            return null;
        }

        if (this.throughWalls.get()) {
            return closestObjective;
        }

        // Check line of sight to the objective; if blocked, optionally break the covering block first.
        BlockHitResult hit = mc.level.clip(new ClipContext(
                eye, Vec3.atCenterOf(closestObjective), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(closestObjective)) {
            return closestObjective;
        }

        if (this.surroundings.get()) {
            BlockPos cover = hit.getBlockPos();
            BlockState coverState = mc.level.getBlockState(cover);
            if (!coverState.isAir()
                    && coverState.getDestroySpeed(mc.level, cover) >= 0.0F
                    && eye.distanceToSqr(Vec3.atCenterOf(cover)) <= maxReachSq) {
                return cover;
            }
        }

        return null;
    }

    private boolean isObjective(final BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (this.beds.get() && state.is(BlockTags.BEDS)) {
            return true;
        }
        if (this.dragonEgg.get() && state.is(Blocks.DRAGON_EGG)) {
            return true;
        }
        return this.cake.get() && state.is(Blocks.CAKE);
    }

    private Direction bestFace(final BlockPos pos) {
        if (mc.player == null) {
            return Direction.UP;
        }
        Vec3 eye = mc.player.getEyePosition();
        Direction best = Direction.UP;
        double bestDistSq = Double.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            double distSq = eye.distanceToSqr(faceCenter(pos, dir));
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = dir;
            }
        }
        return best;
    }

    private static Vec3 faceCenter(final BlockPos pos, final Direction face) {
        return new Vec3(
                pos.getX() + 0.5 + face.getStepX() * 0.5,
                pos.getY() + 0.5 + face.getStepY() * 0.5,
                pos.getZ() + 0.5 + face.getStepZ() * 0.5);
    }

    private void resetState(final boolean stopMine) {
        if (stopMine && this.breaking && mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        this.breaking = false;
        this.targetPos = null;
        this.releaseRotation();
    }

    private void releaseRotation() {
        this.currentRotation = null;
        MovementCorrector corrector = MovementCorrector.current();
        if (corrector != null) {
            corrector.release(this);
        }
    }
}
