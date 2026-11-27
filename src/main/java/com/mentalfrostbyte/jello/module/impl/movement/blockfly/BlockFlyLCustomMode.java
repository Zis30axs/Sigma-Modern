package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import com.mentalfrostbyte.jello.event.impl.game.action.EventClick;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.module.impl.movement.blockfly.BlockFlyPlacement.Target;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.util.math.Rotations;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** Naven/HeyPixel's LCustom: right-click gating, 2-tick falling prediction, and six-layer support search. */
public final class BlockFlyLCustomMode extends BlockFlyCustomBase {
    record AnchorFace(BlockPos position, Direction facing) {
        BlockPos placedPos() { return this.position.relative(this.facing); }
    }

    private final BooleanSetting whileRightClick;
    private @Nullable AnchorFace navenPos;

    public BlockFlyLCustomMode(final BlockFly parent) {
        super(parent, BlockFly.Mode.L_CUSTOM, "LCustom");
        this.whileRightClick = parent.modeSetting(BlockFly.Mode.L_CUSTOM, new BooleanSetting("LCustom While Right Click", "Only rotate, jump, sneak and place while use is held.", true));
    }

    @Override protected boolean activationHeld() {
        return !this.whileRightClick.get() || GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
    }
    @Override protected float turnSpeed() { return 180.0F; }
    @Override protected boolean rejectUseName() { return true; }

    /** Naven's mode owns its jump/sneak input and never calls the legacy parent tower/sprint routine. */
    @Override public boolean usesSharedMovement() { return false; }

    @Override
    public void enable() {
        super.enable();
        this.navenPos = null;
    }

    @Override
    protected void releaseInput() {
        super.releaseInput();
        this.navenPos = null;
    }

    @Override
    protected @Nullable BlockPos currentAnchorPos() {
        return this.navenPos == null ? null : this.navenPos.position();
    }

    @Override
    protected boolean updatePlan(final LocalPlayer player, final Vec3 eyes) {
        return this.updatePlanForGrimSprint(player, eyes, null);
    }

    @Override
    protected boolean updatePlanForGrimSprint(final LocalPlayer player, final Vec3 eyes,
                                              final @Nullable Rotation storedAim) {
        if (this.navenPos != null && (BlockFlyLegacyMode.isReplaceable(mc.level, this.navenPos.position())
                || !BlockFlyLegacyMode.isReplaceable(mc.level, this.navenPos.placedPos()))) {
            this.navenPos = null;
        }
        Vec3 baseVec = eyes.add(player.getDeltaMovement().scale(2.0));
        if (player.getDeltaMovement().y < 0.01) {
            double fallingY = predictFallingY(player.getY(), player.getDeltaMovement().y, 2);
            baseVec = new Vec3(baseVec.x, Math.max(fallingY + player.getEyeHeight(), baseVec.y), baseVec.z);
        }
        BlockPos base = BlockPos.containing(baseVec.x, this.baseY + 0.1, baseVec.z);
        if (canStandOnUp(mc.level, base)) {
            return this.navenPos != null;
        }
        double range = player.blockInteractionRange();
        AnchorFace fallback = null;
        for (BlockPos placed : pyramid(base)) {
            AnchorFace candidate = findNavenAnchor(mc.level, baseVec, placed);
            if (candidate == null) {
                continue;
            }
            if (storedAim == null || grimRayHitsSupport(eyes, storedAim, candidate.position(), range)) {
                this.navenPos = candidate;
                return true;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        if (fallback != null) {
            this.navenPos = fallback;
            return true;
        }
        return this.navenPos != null;
    }

    @Override
    protected Rotation computeDesiredRotation(final LocalPlayer player, final Vec3 eyes) {
        if (this.navenPos == null) return this.rotation;
        BlockPos anchor = this.navenPos.position();
        double x = randomizeCoord(anchor.getX() + 0.5 - eyes.x);
        double y = randomizeCoord(anchor.getY() + 0.5 - eyes.y);
        double z = randomizeCoord(anchor.getZ() + 0.5 - eyes.z);
        return this.fixed(Rotations.towards(Vec3.ZERO, new Vec3(x, y, z)));
    }

    @Override
    protected boolean hitsSnapAnchor(final LocalPlayer player, final Vec3 eyes, final Rotation look) {
        if (this.navenPos == null || mc.level == null) return false;
        Vec3 snapEyes = new Vec3(player.getX(), player.getY() + 1.62, player.getZ());
        Vec3 end = snapEyes.add(Vec3.directionFromRotation(look.pitch(), look.yaw()).scale(player.blockInteractionRange()));
        BlockHitResult hit = mc.level.clip(new ClipContext(
                snapEyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(this.navenPos.position())
                && hit.getDirection() != Direction.UP;
    }

    @Override
    protected @Nullable Target resolveMotionPlacement(final LocalPlayer player, final EventMotion event,
                                                      final Vec3 eyes, final Rotation currentLook) {
        if (this.navenPos == null) return null;
        if (this.bridgeMode.is(BridgeMode.TELLY_BRIDGE) && this.offGroundTicks < 1) return null;
        Direction face = this.navenPos.facing();
        BlockPos anchor = this.navenPos.position();
        BlockPos placed = this.navenPos.placedPos();
        if (BlockFlyLegacyMode.isReplaceable(mc.level, anchor)
                || !BlockFlyLegacyMode.isReplaceable(mc.level, placed)) {
            this.navenPos = null;
            return null;
        }
        if (!checkNavenFaceVisible(eyes, anchor, face, 20.25)) return null;
        boolean moving = BlockFlyLegacyMode.isMoving(BlockFlyLegacyMode.physicalInput());
        boolean holdingJump = mc.options.keyJump.isDown();
        boolean upGate = face != Direction.UP || event.isOnGround() || !moving || holdingJump
                || this.bridgeMode.is(BridgeMode.NORMAL) || this.bridgeMode.is(BridgeMode.GRIM_SPRINT);
        if (!upGate || !this.shouldBuild(player, event.getX(), event.getY(), event.getZ())) return null;

        BlockHitResult rayHit = BlockFlyPlacement.raycast(player, eyes, currentLook);
        if (rayHit != null && rayHit.getType() == HitResult.Type.BLOCK && !rayHit.isInside()
                && rayHit.getBlockPos().equals(anchor) && rayHit.getDirection() == face) {
            BlockFlyPlacement.Target strict = BlockFlyPlacement.fromHit(player, eyes, rayHit, currentLook);
            if (strict != null) return strict;
        }
        BlockHitResult directHit = new BlockHitResult(navenHitVec(anchor, face), face, anchor, false);
        return new BlockFlyPlacement.Target(placed, directHit, currentLook, eyes, false);
    }

    @Override
    protected @Nullable Target resolveGrimSprintPlacement(final LocalPlayer player, final EventMotion event,
                                                          final Vec3 eyes, final Rotation storedAim) {
        // Prefer an exact outline-ray hit onto pyramid support blocks intersected by storedAim;
        // fall back to Naven's visible-face placement when also intersected by storedAim.
        Vec3 baseVec = new Vec3(event.getX(), event.getY(), event.getZ()).add(player.getDeltaMovement().scale(1.5));
        BlockPos base = BlockPos.containing(baseVec.x, this.baseY + 0.1, baseVec.z);
        double range = player.blockInteractionRange();
        for (BlockPos placed : pyramid(base)) {
            Target candidate = BlockFlyPlacement.find(player, eyes, placed, storedAim);
            if (candidate != null && candidate.hit().getDirection() != Direction.DOWN
                    && eyes.distanceToSqr(candidate.hit().getLocation()) <= 20.25
                    && grimRayHitsSupport(eyes, storedAim, candidate.hit().getBlockPos(), range)) {
                return candidate;
            }
        }
        return this.resolveMotionPlacement(player, event, eyes, storedAim);
    }

    @Override
    public void click(final EventClick event) {
        // Automatic mode leaves ordinary use available, as in the source.
        if (this.whileRightClick.get()) super.click(event);
    }

    private boolean shouldBuild(final LocalPlayer player, final double x, final double y, final double z) {
        if (mc.level == null) return false;
        BlockPos below = BlockPos.containing(x, y - 0.5, z);
        return BlockFlyLegacyMode.isReplaceable(mc.level, below) && this.isValidStack(player.getMainHandItem());
    }

    static double predictFallingY(final double startY, final double startMotionY, final int ticks) {
        double y = startY;
        double vy = startMotionY;
        for (int i = 0; i < ticks; i++) {
            y += vy;
            vy = (vy - 0.08) * 0.98;
        }
        return y;
    }

    static @Nullable AnchorFace findNavenAnchor(final BlockGetter level, final Vec3 baseVec, final BlockPos placedPos) {
        if (!BlockFlyLegacyMode.isReplaceable(level, placedPos)) return null;
        for (Direction offsetDir : Direction.values()) {
            BlockPos anchor = placedPos.relative(offsetDir);
            Direction face = offsetDir.getOpposite();
            if (!canStandOnAnchor(level, anchor)) continue;
            if (checkNavenFaceVisible(baseVec, anchor, face, 20.25)) {
                return new AnchorFace(anchor.immutable(), face);
            }
        }
        return null;
    }

    static boolean checkNavenFaceVisible(final Vec3 eyes, final BlockPos anchor,
                                         final Direction face, final double maxDistSq) {
        Vec3 hit = Vec3.atCenterOf(anchor).add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        Vec3 relevant = hit.subtract(eyes);
        if (relevant.lengthSqr() > maxDistSq || relevant.lengthSqr() <= 1.0E-12) {
            return false;
        }
        Vec3 inwardNormal = Vec3.atLowerCornerOf(face.getOpposite().getUnitVec3i());
        return relevant.dot(inwardNormal) >= 0.0;
    }

    private static boolean canStandOnUp(final BlockGetter level, final BlockPos pos) {
        return canStandOnAnchor(level, pos);
    }

    private static boolean canStandOnAnchor(final BlockGetter level, final BlockPos pos) {
        if (!BlockFlyLegacyMode.isValidAnchor(level, pos)) return false;
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    private static Vec3 navenHitVec(final BlockPos anchor, final Direction face) {
        double x = anchor.getX() + 0.5;
        double y = anchor.getY() + 0.5;
        double z = anchor.getZ() + 0.5;
        if (face != Direction.UP && face != Direction.DOWN) {
            y += 0.08;
        } else {
            x += ThreadLocalRandom.current().nextDouble(-0.3, 0.3);
            z += ThreadLocalRandom.current().nextDouble(-0.3, 0.3);
        }
        if (face == Direction.WEST || face == Direction.EAST) {
            z += ThreadLocalRandom.current().nextDouble(-0.3, 0.3);
        }
        if (face == Direction.SOUTH || face == Direction.NORTH) {
            x += ThreadLocalRandom.current().nextDouble(-0.3, 0.3);
        }
        return new Vec3(x, y, z);
    }

    private static double randomizeCoord(final double value) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return value + random.nextDouble(0.05, 0.08) * (random.nextDouble(0.0, 1.0) * 2.0 - 1.0);
    }
}
