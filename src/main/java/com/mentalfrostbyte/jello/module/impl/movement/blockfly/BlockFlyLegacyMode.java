package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMove;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventSafeWalk;
import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.util.math.Rotations;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import com.mentalfrostbyte.jello.util.movement.MovementCorrection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** The common movement curves of the original NCP, Smooth and Hypixel modes. */
abstract class BlockFlyLegacyMode extends BlockFlyMode {
    enum SpeedMode { NONE, JUMP, CONSTANT, AAC, CUBECRAFT, SLOW, SNEAK }

    private final BlockFly.Mode mode;
    private Rotation rotation;
    private Rotation reportRotation;
    private BlockFlyPlacement.Target plannedTarget;
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
        this.reportRotation = null;
        this.plannedTarget = null;
        this.baseY = mc.player == null ? 0.0 : mc.player.getY();
        this.offGroundTicks = -1;
        this.descending = false;
        this.lastRotationTick = Integer.MIN_VALUE;
    }

    @Override
    public void disable() {
        this.parent.timer(1.0F);
        this.rotation = null;
        this.reportRotation = null;
        this.plannedTarget = null;
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
        this.plannedTarget = null;
        this.reportRotation = null;
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || !this.parent.hasBlocks()) {
            this.descending = false;
            this.parent.clearRotation();
            this.parent.queuePlacement(null);
            this.parent.timer(1.0F);
            return;
        }
        LocalPlayer player = mc.player;
        this.descending = this.downwards() && mc.options.keyShift.isDown();
        // Choose before keyboard remapping and physics; the report later reuses this same look.
        this.plan(new EventMotion(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), player.onGround()));
    }

    @Override
    public void motion(final EventMotion event) {
        if (!event.isPre()) return;
        this.parent.queuePlacement(null);
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || !this.parent.hasBlocks()) {
            this.parent.clearRotation();
            if (mc.player != null) {
                event.setYaw(mc.player.getYRot());
                event.setPitch(mc.player.getXRot());
            }
            return;
        }
        if (this.reportRotation == null) return;
        this.parent.aim(event, this.reportRotation, true, MovementCorrection.SILENT);
        if (this.plannedTarget == null) return;
        Vec3 eyes = new Vec3(event.getX(), event.getY() + mc.player.getEyeHeight(), event.getZ());
        BlockFlyPlacement.Target current = BlockFlyPlacement.fromHit(mc.player, eyes,
                BlockFlyPlacement.raycast(mc.player, eyes, this.reportRotation), this.reportRotation);
        if (current != null && current.placedPos().equals(this.plannedTarget.placedPos())
                && current.hit().getBlockPos().equals(this.plannedTarget.hit().getBlockPos())
                && current.hit().getDirection() == this.plannedTarget.hit().getDirection()) {
            this.parent.queuePlacement(current);
        }
    }

    private void plan(final EventMotion event) {
        LocalPlayer player = mc.player;
        Vec3 eyes = new Vec3(event.getX(), event.getY() + player.getEyeHeight(), event.getZ());
        Rotation previous = this.rotation == null ? new Rotation(event.getYaw(), event.getPitch()) : this.rotation;
        double y = event.getY();
        double x = event.getX();
        double z = event.getZ();
        if (player.onGround()) this.baseY = player.getY();

        if (player.getDeltaMovement().y < 0.0 && player.fallDistance > 1.0F
                && BlockFlyPlacement.raycast(player, eyes, new Rotation(0.0F, 90.0F)) == null) {
            y += Math.max(player.getDeltaMovement().y * 2.0, -4.0);
        } else if (this.descending) {
            y -= 1.0;
        } else if ((this.speedMode() == SpeedMode.JUMP || this.speedMode() == SpeedMode.CUBECRAFT)
                && !mc.options.keyJump.isDown()) {
            y = this.baseY;
        }

        // The original Extend advances in 0.45-block steps only while standing on a support.
        if (this.mode == BlockFly.Mode.NCP && this.extend() > 0.0F && !player.horizontalCollision
                && !mc.options.keyJump.isDown() && !isReplaceable(BlockPos.containing(x, y - 1.0, z))) {
            double angle = Math.toRadians(movementYaw(player.getYRot(), physicalInput()));
            double distance = this.extend() * 0.9;
            if (isMoving(physicalInput())) {
                for (double step = Math.min(0.45, distance); step <= distance + 1.0E-6; step = Math.min(step + 0.45, distance)) {
                    x = event.getX() - Math.sin(angle) * step;
                    z = event.getZ() + Math.cos(angle) * step;
                    if (isReplaceable(BlockPos.containing(x, y - 1.0, z)) || step == distance) break;
                }
            }
        }

        BlockPos placed = BlockPos.containing(x, y - 1.0, z);
        if (!isReplaceable(placed) && isMoving(physicalInput())) {
            BlockPos nextFeet = BlockPos.containing(event.getX() + player.getDeltaMovement().x, y - 1.0,
                    event.getZ() + player.getDeltaMovement().z);
            if (isReplaceable(nextFeet)) placed = nextFeet;
        }
        BlockFlyPlacement.Target target = isReplaceable(placed)
                ? findOnLayer(player, eyes, placed, 1, previous) : null;

        if (target != null && this.mode == BlockFly.Mode.SMOOTH) {
            // Smooth follows the side of the current bridge from a feet-level horizontal ray.
            Vec3 start = new Vec3(event.getX(), event.getY() - 0.8, event.getZ());
            float yaw = movementYaw(player.getYRot(), physicalInput()) + 180.0F;
            Vec3 end = start.add(Vec3.directionFromRotation(0.0F, yaw).scale(2.3));
            BlockHitResult side = mc.level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (side.getType() == HitResult.Type.BLOCK) {
                Rotation smooth = Rotations.continuous(previous, Rotations.towards(eyes, side.getLocation()));
                BlockFlyPlacement.Target rayTarget = BlockFlyPlacement.fromHit(player, eyes,
                        BlockFlyPlacement.raycast(player, eyes, smooth), smooth);
                if (rayTarget != null && rayTarget.placedPos().getY() == placed.getY()) target = rayTarget;
            }
            if (target.hit().getDirection() == Direction.UP && player.onGround()
                    && target.hit().getBlockPos().getY() <= player.getY() - 1.0) target = null;
        }

        if (target != null && this.mode == BlockFly.Mode.HYPIXEL) {
            // Prefer the old straight-down Hypixel posture only when that ray really reaches this placement.
            Rotation downward = new Rotation(player.getYRot() + 1.0F, 90.0F);
            BlockFlyPlacement.Target aimed = BlockFlyPlacement.fromHit(player, eyes,
                    BlockFlyPlacement.raycast(player, eyes, downward), downward);
            if (aimed != null && aimed.placedPos().equals(target.placedPos())) target = aimed;
        }

        if (target != null) {
            this.parent.replenish();
            this.rotation = Rotations.continuous(previous, target.rotation());
            if (Math.abs(Mth.wrapDegrees(this.rotation.yaw() - previous.yaw())) > 0.01F
                    || Math.abs(this.rotation.pitch() - previous.pitch()) > 0.01F) this.lastRotationTick = player.tickCount;
            this.reportRotation = this.rotation;
            this.parent.requestRotation(this.reportRotation, true, MovementCorrection.SILENT);
            this.plannedTarget = target;
        } else if (this.keepRotations() && this.rotation != null) {
            Rotation retained = this.mode == BlockFly.Mode.HYPIXEL
                    ? new Rotation(player.getYRot() + 1.0F, 90.0F) : this.rotation;
            this.reportRotation = retained;
            this.parent.requestRotation(this.reportRotation, true, MovementCorrection.SILENT);
        } else {
            this.parent.clearRotation();
        }
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

    private static boolean isReplaceable(final BlockPos pos) {
        return mc.level != null && mc.level.hasChunkAt(pos) && mc.level.getBlockState(pos).canBeReplaced();
    }

    static BlockFlyPlacement.Target findOnLayer(final LocalPlayer player, final Vec3 eyes, final BlockPos base,
                                                final int radius, final Rotation previous) {
        for (BlockPos pos : BlockFlyPlacement.positionsAround(base, radius)) {
            if (pos.getY() != base.getY()) continue;
            BlockFlyPlacement.Target target = BlockFlyPlacement.find(player, eyes, pos, previous);
            if (target != null) return target;
        }
        return null;
    }
}
