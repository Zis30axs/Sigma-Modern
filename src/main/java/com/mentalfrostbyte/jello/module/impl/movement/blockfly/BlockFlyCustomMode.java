package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.module.impl.movement.blockfly.BlockFlyPlacement.Target;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.util.math.Rotations;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** SigmaClient's Custom: solid-anchor tracking, back-facing pitch search, Eagle SafeWalk, and gradual telly turns. */
public final class BlockFlyCustomMode extends BlockFlyCustomBase {
    private final BooleanSetting advanced;
    private final BooleanSetting grim;
    private final BooleanSetting duplicate;
    private @Nullable BlockPos anchorPos;

    public BlockFlyCustomMode(final BlockFly parent) {
        super(parent, BlockFly.Mode.CUSTOM, "Custom");
        this.advanced = parent.modeSetting(BlockFly.Mode.CUSTOM, new BooleanSetting("Custom Advanced Block Search", "Search neighboring faces when the backward ray misses.", true));
        this.grim = parent.modeSetting(BlockFly.Mode.CUSTOM, new BooleanSetting("Custom Grim1.17Rot", "Send a placement rotation followed by a camera rotation report.", false));
        this.duplicate = parent.modeSetting(BlockFly.Mode.CUSTOM, new BooleanSetting("Custom DuplicateRotPlace", "Vary the turn step and tower aim slightly.", true));
    }

    @Override protected boolean activationHeld() { return true; }
    @Override protected boolean grimRotation() { return this.grim.get(); }
    @Override protected boolean rejectUseName() { return true; }
    @Override protected boolean eagleSafeWalk() { return true; }
    @Override protected float turnSpeed() { return this.duplicate.get() ? 75.0F - (jitter(3.0F) + 3.0F) : 75.0F; }

    @Override
    public void enable() {
        super.enable();
        this.anchorPos = null;
    }

    @Override
    protected void releaseInput() {
        super.releaseInput();
        this.anchorPos = null;
    }

    @Override
    protected boolean updatePlan(final LocalPlayer player, final Vec3 eyes) {
        this.anchorPos = this.findAnchorPos(player, player.getX(), player.getY(), player.getZ());
        return this.anchorPos != null;
    }

    @Override
    protected Rotation computeDesiredRotation(final LocalPlayer player, final Vec3 eyes) {
        float rotationYaw = player.getYRot() - 180.0F;
        if (this.isTower(player, player.getX(), player.getY(), player.getZ())) {
            float jitter = this.duplicate.get() ? jitter(0.25F) : 0.0F;
            return new Rotation(rotationYaw + jitter, 90.0F);
        }
        float realYaw = this.advanced.get()
                ? BlockFlyLegacyMode.movementYaw(player.getYRot(), BlockFlyLegacyMode.physicalInput())
                : player.getYRot();
        float yaw = realYaw - 180.0F + jitter(0.25F);
        Rotation backwards = new Rotation(yaw, 82.0F);
        boolean buildSoon = this.shouldBuild(player, player.getX(), player.getY(), player.getZ())
                || this.shouldBuild(player,
                        player.getX() + player.getDeltaMovement().x,
                        player.getY(),
                        player.getZ() + player.getDeltaMovement().z);
        if (buildSoon && this.anchorPos != null) {
            BlockHitResult initialHit = BlockFlyPlacement.raycast(player, eyes, backwards);
            if (this.isCustomAimHitValid(player, initialHit)) {
                return backwards;
            }
            Rotation bestPitch = this.findValidPitchRotation(player, eyes, yaw,
                    Math.max(-90.0F, this.rotation.pitch() - 30.0F),
                    Math.min(90.0F, this.rotation.pitch() + 20.0F), 0.3F, null, false);
            if (bestPitch != null) {
                return bestPitch;
            }
            if (this.advanced.get()) {
                Rotation optimal = this.findOptimalRotation(player, eyes, yaw);
                if (optimal != null) {
                    return optimal;
                }
            }
        }
        return backwards;
    }

    @Override
    protected boolean hitsSnapAnchor(final LocalPlayer player, final Vec3 eyes, final Rotation look) {
        if (this.anchorPos == null) return false;
        BlockHitResult hit = BlockFlyPlacement.raycast(player, eyes, look);
        return hit != null && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(this.anchorPos)
                && hit.getDirection() != Direction.UP;
    }

    @Override
    protected @Nullable Target resolveMotionPlacement(final LocalPlayer player, final EventMotion event,
                                                      final Vec3 eyes, final Rotation currentLook) {
        this.anchorPos = this.findAnchorPos(player, event.getX(), event.getY(), event.getZ());
        if (this.anchorPos == null) return null;
        boolean towering = this.isTower(player, event.getX(), event.getY(), event.getZ());
        if (!towering && !this.shouldBuild(player, event.getX(), event.getY(), event.getZ())) return null;

        Rotation usedLook = currentLook;
        BlockHitResult hit = BlockFlyPlacement.raycast(player, eyes, usedLook);
        if (!this.isPlacementHitAllowed(player, event, hit, towering)) {
            Rotation refined = this.findValidPitchRotation(player, eyes, currentLook.yaw(),
                    towering ? 75.0F : Math.max(-90.0F, currentLook.pitch() - 30.0F),
                    90.0F, 0.3F, event, towering);
            if (refined == null) return null;
            usedLook = new Rotation(currentLook.yaw(), refined.pitch());
            hit = BlockFlyPlacement.raycast(player, eyes, usedLook);
            if (!this.isPlacementHitAllowed(player, event, hit, towering)) return null;
        }
        return BlockFlyPlacement.fromHit(player, eyes, hit, usedLook);
    }

    private @Nullable Rotation findValidPitchRotation(final LocalPlayer player, final Vec3 eyes, final float yaw,
                                                      final float minPitch, final float maxPitch, final float step,
                                                      final @Nullable EventMotion placementEvent,
                                                      final boolean towering) {
        Rotation best = null;
        float bestDelta = Float.POSITIVE_INFINITY;
        for (float pitch = minPitch; pitch <= maxPitch; pitch += step) {
            Rotation trial = this.fixed(new Rotation(yaw, pitch));
            Rotation fixedPitchTrial = new Rotation(yaw, Mth.clamp(trial.pitch(), -90.0F, 90.0F));
            BlockHitResult hit = BlockFlyPlacement.raycast(player, eyes, fixedPitchTrial);
            boolean valid = placementEvent == null
                    ? this.isCustomAimHitValid(player, hit)
                    : this.isPlacementHitAllowed(player, placementEvent, hit, towering);
            if (valid) {
                float delta = Math.abs(fixedPitchTrial.pitch() - this.rotation.pitch());
                if (delta < bestDelta) {
                    best = fixedPitchTrial;
                    bestDelta = delta;
                }
            }
        }
        return best;
    }

    private @Nullable Rotation findOptimalRotation(final LocalPlayer player, final Vec3 eyes, final float baseYaw) {
        if (this.anchorPos == null) return null;
        for (Direction face : Direction.Plane.HORIZONTAL) {
            BlockPos placed = this.anchorPos.relative(face);
            if (!BlockFlyLegacyMode.isReplaceable(mc.level, placed)) continue;
            BlockFlyPlacement.Target direct = BlockFlyPlacement.find(player, eyes, placed, this.rotation);
            if (direct != null && direct.hit().getBlockPos().equals(this.anchorPos)
                    && direct.hit().getDirection() == face) {
                return this.fixed(direct.rotation());
            }
        }
        float currentPitch = this.rotation.pitch();
        for (float yawOffset = 0.0F; yawOffset < 180.0F; yawOffset += 2.0F) {
            for (float pitchOffset = 0.0F; pitchOffset < 25.0F; pitchOffset += 1.5F) {
                for (int sign = 0; sign < 2; sign++) {
                    float pitch = Mth.clamp(currentPitch - pitchOffset * (sign == 0 ? 1.0F : -1.0F), -90.0F, 90.0F);
                    for (float candidateYaw : new float[]{baseYaw + yawOffset, baseYaw - yawOffset}) {
                        Rotation trial = this.fixed(new Rotation(candidateYaw, pitch));
                        if (this.isCustomAimHitValid(player, BlockFlyPlacement.raycast(player, eyes, trial))) {
                            return trial;
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean isCustomAimHitValid(final LocalPlayer player, final @Nullable BlockHitResult hit) {
        if (hit == null || hit.getType() != HitResult.Type.BLOCK || hit.isInside() || this.anchorPos == null) {
            return false;
        }
        Direction face = hit.getDirection();
        if (face == Direction.DOWN || face == Direction.UP) return false;
        BlockPos hitPos = hit.getBlockPos();
        return this.isValidAnchorBlock(hitPos)
                && this.isNearbyAnchorPos(player, hitPos)
                && BlockFlyLegacyMode.isReplaceable(mc.level, hitPos.relative(face));
    }

    private boolean isPlacementHitAllowed(final LocalPlayer player, final EventMotion event,
                                          final @Nullable BlockHitResult hit, final boolean towering) {
        if (hit == null || hit.getType() != HitResult.Type.BLOCK || hit.isInside() || this.anchorPos == null) {
            return false;
        }
        BlockPos hitPos = hit.getBlockPos();
        if (!hitPos.equals(this.anchorPos) && !(event.isOnGround() && this.isNearbyAnchorPos(player, hitPos))) {
            return false;
        }
        Direction face = hit.getDirection();
        if (face == Direction.DOWN) return false;
        boolean holdingJump = mc.options.keyJump.isDown();
        if (face == Direction.UP && !event.isOnGround()
                && BlockFlyLegacyMode.isMoving(BlockFlyLegacyMode.physicalInput())
                && !holdingJump && !towering && !this.bridgeMode.is(BridgeMode.NORMAL)) {
            return false;
        }
        return BlockFlyLegacyMode.isReplaceable(mc.level, hitPos.relative(face));
    }

    private boolean isNearbyAnchorPos(final LocalPlayer player, final BlockPos candidate) {
        if (this.anchorPos == null) return false;
        if (!player.onGround()) return candidate.equals(this.anchorPos);
        return candidate.getY() == this.anchorPos.getY()
                && Math.abs(candidate.getX() - this.anchorPos.getX()) <= 1
                && Math.abs(candidate.getZ() - this.anchorPos.getZ()) <= 1;
    }

    private @Nullable BlockPos findAnchorPos(final LocalPlayer player, final double x, final double y, final double z) {
        if (mc.level == null) return null;
        BlockPos origin = BlockPos.containing(x, y - 1.0, z);
        BlockPos best = null;
        double bestDistSq = Double.POSITIVE_INFINITY;
        for (int cx = origin.getX() - 5; cx <= origin.getX() + 5; cx++) {
            for (int cy = origin.getY() - 1; cy <= origin.getY(); cy++) {
                for (int cz = origin.getZ() - 5; cz <= origin.getZ() + 5; cz++) {
                    BlockPos check = new BlockPos(cx, cy, cz);
                    if (!this.isValidAnchorBlock(check)) continue;
                    Vec3 closestPoint = closestPointInBlock(check, x, y, z);
                    double distSq = closestPoint.distanceToSqr(x, y, z);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = check;
                    }
                }
            }
        }
        if (this.isTower(player, x, y, z) && best != null && best.getY() != Mth.floor(y - 1.5)) {
            return BlockPos.containing(x, y - 1.5, z);
        }
        return best;
    }

    private static Vec3 closestPointInBlock(final BlockPos pos, final double x, final double y, final double z) {
        VoxelShape shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
        if (shape.isEmpty()) {
            return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
        AABB bounds = shape.bounds();
        return new Vec3(
                Mth.clamp(x, pos.getX() + bounds.minX, pos.getX() + bounds.maxX),
                Mth.clamp(y, pos.getY() + bounds.minY, pos.getY() + bounds.maxY),
                Mth.clamp(z, pos.getZ() + bounds.minZ, pos.getZ() + bounds.maxZ));
    }

    private boolean isValidAnchorBlock(final BlockPos pos) {
        if (mc.level == null || !mc.level.hasChunkAt(pos) || mc.level.isOutsideBuildHeight(pos)) return false;
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty()) return false;
        Block block = state.getBlock();
        if (block instanceof AbstractChestBlock<?> || block instanceof AbstractFurnaceBlock
                || block instanceof EnderChestBlock || block instanceof BushBlock
                || block instanceof TallGrassBlock || block == Blocks.SHORT_GRASS
                || block instanceof SnowLayerBlock || block instanceof EnchantingTableBlock
                || block instanceof AnvilBlock || block instanceof CraftingTableBlock) {
            return false;
        }
        return !state.getShape(mc.level, pos).isEmpty();
    }

    private boolean isTower(final LocalPlayer player, final double x, final double y, final double z) {
        return mc.options.keyJump.isDown()
                && !BlockFlyLegacyMode.isMoving(BlockFlyLegacyMode.physicalInput())
                && this.isValidAnchorBlock(BlockPos.containing(x, y - 1.5, z));
    }

    private boolean shouldBuild(final LocalPlayer player, final double x, final double y, final double z) {
        if (mc.level == null) return false;
        BlockPos below = BlockPos.containing(x, y - 0.5, z);
        return BlockFlyLegacyMode.isReplaceable(mc.level, below) && this.isValidStack(player.getMainHandItem());
    }
}
