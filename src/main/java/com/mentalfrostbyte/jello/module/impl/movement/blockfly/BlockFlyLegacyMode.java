package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMove;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventSafeWalk;
import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.module.impl.movement.blockfly.BlockFlyPlacement.Target;
import com.mentalfrostbyte.jello.util.math.Rotations;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import com.mentalfrostbyte.jello.util.movement.MovementCorrection;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The common movement and post-movement placement flow of the original NCP, Smooth and Hypixel modes. */
abstract class BlockFlyLegacyMode extends BlockFlyMode {
    enum SpeedMode { NONE, JUMP, CONSTANT, AAC, CUBECRAFT, SLOW, SNEAK }

    record BlockCache(BlockPos anchor, Direction face) {
        BlockPos placedPos() { return this.anchor.relative(this.face); }
    }

    private static final int[][] CARDINAL_OFFSETS = {
            {0, 0, 0}, {-1, 0, 0}, {1, 0, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final int[][] CACHE_PATTERNS = {
            {1, 1, 1, 0}, {2, 1, 2, 0}, {3, 1, 3, 0}, {4, 1, 4, 0}, {0, -1, 0, 1}
    };

    private final BlockFly.Mode mode;
    private @Nullable Rotation rotation;
    private double baseY;
    private int offGroundTicks;
    private boolean descending;
    private int lastRotationTick;

    BlockFlyLegacyMode(final BlockFly parent, final BlockFly.Mode mode) {
        super(parent);
        this.mode = mode;
    }

    abstract SpeedMode speedMode();
    boolean keepRotations() { return true; }
    boolean downwards() { return false; }
    float extend() { return 0.0F; }
    float constantSpeed() { return 0.0F; }

    @Override
    public boolean usesSharedMovement() { return true; }

    @Override
    public void enable() {
        this.rotation = null;
        this.baseY = mc.player == null ? 0.0 : mc.player.getY();
        this.offGroundTicks = -1;
        this.descending = false;
        this.lastRotationTick = Integer.MIN_VALUE;
    }

    @Override
    public void disable() {
        this.parent.timer(1.0F);
        this.rotation = null;
        this.descending = false;
    }

    @Override
    public void input(final EventMovementInput event) {
        if (mc.gui.screen() != null || !this.parent.hasBlocks()) return;
        this.descending = this.downwards() && event.getInput().shift();
        if (this.descending) event.setShift(false);
        if (mc.player != null && this.speedMode() == SpeedMode.JUMP && mc.player.onGround()
                && event.isMoving() && !this.descending && !event.getInput().shift()) {
            event.setJump(true);
        }
    }

    @Override
    public void tick(final EventTick event) {
        if (!event.isPre()) return;
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || !this.parent.hasBlocks()) {
            this.descending = false;
            this.rotation = null;
            this.parent.clearRotation();
            this.parent.queuePlacement(null);
            this.parent.timer(1.0F);
            return;
        }
        this.descending = this.downwards() && mc.options.keyShift.isDown();
    }

    @Override
    public void motion(final EventMotion event) {
        if (!event.isPre()) return;
        this.parent.queuePlacement(null);
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || !this.parent.hasBlocks()) {
            this.rotation = null;
            this.parent.clearRotation();
            if (mc.player != null) {
                event.setYaw(mc.player.getYRot());
                event.setPitch(mc.player.getXRot());
            }
            return;
        }
        LocalPlayer player = mc.player;
        Vec3 eyes = new Vec3(event.getX(), event.getY() + player.getEyeHeight(), event.getZ());
        if (event.isOnGround()) this.baseY = event.getY();

        double x = event.getX();
        double y = event.getY();
        double z = event.getZ();
        if (this.extend() > 0.0F && isMoving(physicalInput()) && !player.horizontalCollision && !mc.options.keyJump.isDown()) {
            Vec3 extended = extendPosition(mc.level, event.getX(), event.getY(), event.getZ(),
                    movementYaw(player.getYRot(), physicalInput()), this.extend());
            x = extended.x;
            z = extended.z;
        }

        if (player.getDeltaMovement().y < 0.0 && player.fallDistance > 1.0F
                && BlockFlyPlacement.raycast(player, eyes, new Rotation(0.0F, 90.0F)) == null) {
            y += Math.max(player.getDeltaMovement().y * 2.0, -4.0);
        } else if (this.descending && this.mode != BlockFly.Mode.SMOOTH) {
            y -= 1.0;
        } else if ((this.speedMode() == SpeedMode.JUMP || this.speedMode() == SpeedMode.CUBECRAFT)
                && !mc.options.keyJump.isDown()) {
            y = this.baseY;
        }

        if (isReplaceable(mc.level, BlockPos.containing(event.getX(), event.getY() - 1.0, event.getZ()))) {
            x = event.getX();
            z = event.getZ();
        }

        BlockPos targetCell = BlockPos.containing(x, y - 1.0, z);
        Rotation previous = this.rotation == null ? new Rotation(event.getYaw(), event.getPitch()) : this.rotation;
        BlockFlyPlacement.Target target = null;
        Rotation desiredLook = null;

        if (isReplaceable(mc.level, targetCell)) {
            boolean excludeDown = this.mode != BlockFly.Mode.SMOOTH && !this.descending && this.downwards();
            BlockCache cache = findBlockCache(mc.level, targetCell, excludeDown, eyes, player.blockInteractionRange());
            if (cache != null) {
                desiredLook = rotationsToBlockFace(eyes, cache.anchor(), cache.face(), previous);
                if (this.mode == BlockFly.Mode.HYPIXEL) {
                    Vec3 faceCenter = Vec3.atCenterOf(cache.anchor()).add(
                            cache.face().getStepX() * 0.5, 0.0, cache.face().getStepZ() * 0.5);
                    if (cache.anchor().getY() - event.getY() < 0.0
                            && Math.hypot(event.getX() - faceCenter.x, event.getZ() - faceCenter.z) < 2.0) {
                        desiredLook = new Rotation(player.getYRot() + 1.0F, 90.0F);
                    }
                    BlockHitResult hit = new BlockHitResult(
                            randomFaceHitVec(cache.anchor(), cache.face()), cache.face(), cache.anchor(), false);
                    target = new BlockFlyPlacement.Target(cache.placedPos(), hit, desiredLook, eyes, false);
                } else if (this.mode == BlockFly.Mode.SMOOTH) {
                    target = resolveSmoothTarget(player, event, eyes, cache, previous);
                    if (target != null) {
                        desiredLook = target.rotation();
                    }
                } else {
                    BlockHitResult hit = new BlockHitResult(
                            randomFaceHitVec(cache.anchor(), cache.face()), cache.face(), cache.anchor(), false);
                    target = new BlockFlyPlacement.Target(cache.placedPos(), hit, desiredLook, eyes, false);
                }
            }
        }

        if (target != null) {
            this.parent.replenish();
            this.rotation = Rotations.continuous(previous, desiredLook);
            if (Math.abs(Mth.wrapDegrees(this.rotation.yaw() - previous.yaw())) > 0.01F
                    || Math.abs(this.rotation.pitch() - previous.pitch()) > 0.01F) {
                this.lastRotationTick = player.tickCount;
            }
            this.parent.aim(event, this.rotation, true, MovementCorrection.OFF);
            this.parent.queuePlacement(target);
        } else if (this.keepRotations() && this.rotation != null) {
            Rotation retained = this.mode == BlockFly.Mode.HYPIXEL
                    ? new Rotation(player.getYRot() + 1.0F, 90.0F) : this.rotation;
            this.parent.aim(event, retained, true, MovementCorrection.OFF);
        } else {
            this.parent.clearRotation();
        }
    }

    private @Nullable Target resolveSmoothTarget(final LocalPlayer player, final EventMotion event,
                                                 final Vec3 eyes, final BlockCache cache,
                                                 final Rotation previous) {
        Vec3 start = new Vec3(event.getX(), event.getY() - 0.8, event.getZ());
        float lookYaw = movementYaw(player.getYRot(), physicalInput()) - 270.0F;
        double angle = Math.toRadians(lookYaw);
        double endOffsetY = (mc.options.keyJump.isDown() || this.speedMode() == SpeedMode.JUMP) ? -0.6 : 0.0;
        Vec3 endDelta = new Vec3(-Math.sin(angle) * 2.3, endOffsetY, Math.cos(angle) * 2.3);
        BlockHitResult side = mc.level.clip(new ClipContext(
                start, start.add(endDelta), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (side.getType() != HitResult.Type.BLOCK) {
            Vec3 fallbackStart = new Vec3(player.xo, player.yo - 0.8, player.zo);
            side = mc.level.clip(new ClipContext(
                    fallbackStart, fallbackStart.add(endDelta), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        }
        if (side.getType() == HitResult.Type.BLOCK) {
            Rotation smooth = Rotations.continuous(previous, Rotations.towards(eyes, side.getLocation()));
            BlockFlyPlacement.Target rayTarget = BlockFlyPlacement.fromHit(player, eyes,
                    BlockFlyPlacement.raycast(player, eyes, smooth), smooth);
            if (rayTarget != null && !isFloorTopHit(rayTarget, event)) {
                return rayTarget;
            }
        }
        BlockFlyPlacement.Target fallback = BlockFlyPlacement.find(player, eyes, cache.placedPos(), previous);
        if (fallback != null && !isFloorTopHit(fallback, event)) {
            return fallback;
        }
        return null;
    }

    private static boolean isFloorTopHit(final BlockFlyPlacement.Target target, final EventMotion event) {
        return target.hit().getDirection() == Direction.UP
                && target.hit().getBlockPos().getY() <= event.getY() - 1.0
                && event.isOnGround();
    }

    @Override
    public void move(final EventMove event) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.screen() != null || event.getMoverType() != MoverType.SELF || !this.parent.hasBlocks()) return;
        if (this.speedMode() != SpeedMode.CUBECRAFT) this.parent.timer(1.0F);
        if (player.onGround()) {
            this.baseY = player.getY();
            this.offGroundTicks = 0;
        } else if (this.offGroundTicks >= 0) {
            this.offGroundTicks++;
        }
        switch (this.speedMode()) {
            case CONSTANT -> setSpeed(event, player, this.constantSpeed(), movementYaw(player.getYRot(), physicalInput()));
            case AAC -> {
                if (player.onGround() && this.lastRotationTick >= player.tickCount - 1) {
                    setSpeed(event, player, baseSpeed(player) * 0.82, movementYaw(player.getYRot(), physicalInput()));
                }
            }
            case SLOW -> scaleHorizontal(event, player.onGround() ? 0.75 : 0.93);
            case SNEAK -> scaleHorizontal(event, player.onGround() ? 0.65 : 0.85);
            case CUBECRAFT -> this.cubecraftMove(event, player);
            default -> this.parent.timer(1.0F);
        }
    }

    private void cubecraftMove(final EventMove event, final LocalPlayer player) {
        double speed = 0.2;
        if (mc.options.keyJump.isDown()) {
            this.parent.timer(1.0F);
        } else if (player.onGround()) {
            if (isMoving(physicalInput()) && !player.isShiftKeyDown() && !this.descending) event.setY(1.00000000000001);
        } else if (this.offGroundTicks == 1) {
            if (event.getY() <= 0.9) this.offGroundTicks = -1;
            else { event.setY(0.122); this.parent.timer(0.7F); speed = 2.4; }
        } else if (this.offGroundTicks == 2) {
            if (event.getY() > 0.05) this.offGroundTicks = -1;
            else { this.parent.timer(0.7F); speed = 0.28; }
        } else if (this.offGroundTicks == 3) {
            this.parent.timer(0.3F); speed = 2.4;
        } else if (this.offGroundTicks == 4) {
            this.parent.timer(1.0F); speed = 0.28;
        } else if (this.offGroundTicks == 6) {
            event.setY(-1.023456987345906);
        }
        if (player.fallDistance < 1.0F) {
            float yaw = Math.round(movementYaw(player.getYRot(), physicalInput()) / 90.0F) * 90.0F;
            setSpeed(event, player, speed, yaw);
        }
        player.setDeltaMovement(player.getDeltaMovement().x, event.getY(), player.getDeltaMovement().z);
    }

    @Override
    public void safeWalk(final EventSafeWalk event) {
        if (this.descending) event.setSafe(false);
        else if (this.speedMode() == SpeedMode.CUBECRAFT && mc.player != null && mc.level != null
                && !mc.player.getAbilities().flying && mc.player.fallDistance < 1.0F
                && mc.level.noCollision(mc.player, mc.player.getBoundingBox().expandTowards(0.0, -1.5, 0.0).deflate(0.05, 0.0, 0.05))) {
            event.setSafe(true);
        }
    }

    static boolean isMoving(final Input input) {
        return input.forward() != input.backward() || input.left() != input.right();
    }

    static Input physicalInput() {
        return new Input(mc.options.keyUp.isDown(), mc.options.keyDown.isDown(), mc.options.keyLeft.isDown(),
                mc.options.keyRight.isDown(), mc.options.keyJump.isDown(), mc.options.keyShift.isDown(), mc.options.keySprint.isDown());
    }

    static float movementYaw(final float cameraYaw, final Input input) {
        int forward = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
        int strafe = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
        return cameraYaw + (float) Math.toDegrees(Math.atan2(-strafe, forward));
    }

    private static double baseSpeed(final LocalPlayer player) {
        var effect = player.getEffect(MobEffects.SPEED);
        return 0.2873 * (effect == null ? 1.0 : 1.0 + 0.2 * (effect.getAmplifier() + 1));
    }

    private static void scaleHorizontal(final EventMove event, final double factor) {
        event.setX(event.getX() * factor);
        event.setZ(event.getZ() * factor);
    }

    private static void setSpeed(final EventMove event, final LocalPlayer player, final double speed, final float yaw) {
        double value = isMoving(physicalInput()) ? speed : 0.0;
        double angle = Math.toRadians(yaw);
        event.setX(-Math.sin(angle) * value);
        event.setZ(Math.cos(angle) * value);
    }

    static Vec3 extendPosition(final BlockGetter level, final double startX, final double playerY,
                               final double startZ, final float moveYaw, final float extend) {
        double maxSteps = Math.max(0.0, extend * 2.0);
        if (maxSteps <= 0.0) return new Vec3(startX, playerY, startZ);
        double angle = Math.toRadians(moveYaw);
        double stepX = -Math.sin(angle) * 0.45;
        double stepZ = Math.cos(angle) * 0.45;
        double curX = startX;
        double curZ = startZ;
        double step = 0.0;
        while (!isReplaceable(level, BlockPos.containing(curX, playerY - 1.0, curZ))) {
            step = Math.min(step + 1.0, maxSteps);
            curX = startX + stepX * step;
            curZ = startZ + stepZ * step;
            if (step >= maxSteps) break;
        }
        return new Vec3(curX, playerY, curZ);
    }

    static @Nullable BlockCache findBlockCache(final BlockGetter level, final BlockPos basePos,
                                               final boolean excludeDown, final Vec3 eyes, final double range) {
        double maxDistSq = Double.isFinite(range) && range > 0.0 ? Math.max(range * range, 20.25) : 20.25;
        for (int[] pattern : CACHE_PATTERNS) {
            boolean isOffset = pattern[3] != 0;
            for (int[] offset : CARDINAL_OFFSETS) {
                int ox = isOffset ? offset[0] + pattern[0] : offset[0] * pattern[0];
                int oy = isOffset ? offset[1] + pattern[1] : offset[1] * pattern[1];
                int oz = isOffset ? offset[2] + pattern[2] : offset[2] * pattern[2];
                BlockPos placed = basePos.offset(ox, oy, oz);
                if (!isReplaceable(level, placed)) continue;
                for (Direction face : Direction.values()) {
                    if (face == Direction.DOWN && excludeDown) continue;
                    BlockPos anchor = placed.relative(face.getOpposite());
                    if (!isValidAnchor(level, anchor)) continue;
                    Vec3 faceCenter = Vec3.atCenterOf(anchor).add(
                            face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                    if (eyes.distanceToSqr(faceCenter) <= maxDistSq) {
                        return new BlockCache(anchor.immutable(), face);
                    }
                }
            }
        }
        return null;
    }

    static Rotation rotationsToBlockFace(final Vec3 eyes, final BlockPos anchor, final Direction face,
                                         final Rotation previous) {
        double offsetX = face == Direction.EAST ? 0.49 : face == Direction.WEST ? -0.49 : 0.0;
        double offsetZ = face == Direction.SOUTH ? 0.49 : face == Direction.NORTH ? -0.49 : 0.0;
        double offsetY = face == Direction.UP ? 1.0 : 0.0;
        Vec3 target = new Vec3(anchor.getX() + 0.5 + offsetX, anchor.getY() - 0.02 + offsetY, anchor.getZ() + 0.5 + offsetZ);
        return Rotations.continuous(previous, Rotations.towards(eyes, target));
    }

    private static Vec3 randomFaceHitVec(final BlockPos anchor, final Direction face) {
        double x = anchor.getX() + 0.5 + face.getStepX() * 0.5;
        double y = anchor.getY() + 0.5 + face.getStepY() * 0.5;
        double z = anchor.getZ() + 0.5 + face.getStepZ() * 0.5;
        if (face == Direction.UP || face == Direction.DOWN) {
            x += randomOffset(0.2);
            z += randomOffset(0.2);
        } else {
            y += randomOffset(0.2);
            if (face == Direction.WEST || face == Direction.EAST) z += randomOffset(0.2);
            if (face == Direction.SOUTH || face == Direction.NORTH) x += randomOffset(0.2);
        }
        return new Vec3(x, y, z);
    }

    private static double randomOffset(final double bound) {
        return ThreadLocalRandom.current().nextDouble(-bound, bound);
    }

    static boolean isReplaceable(final BlockGetter level, final BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return false;
        if (level instanceof Level world && !world.hasChunkAt(pos)) return false;
        return level.getBlockState(pos).canBeReplaced();
    }

    static boolean isValidAnchor(final BlockGetter level, final BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return false;
        if (level instanceof Level world && !world.hasChunkAt(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return !state.canBeReplaced() && !state.getShape(level, pos).isEmpty();
    }
}
