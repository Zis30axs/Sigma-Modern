package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.action.EventClick;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.util.math.Rotations;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import com.mentalfrostbyte.jello.util.movement.MovementCorrection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** SigmaClient Legit: predictive Eagle deadlines, shaped face targeting, gradual aim and bounded tremor. */
public final class BlockFlyLegitMode extends BlockFlyMode {
    @Override public boolean usesSharedMovement() { return true; }
    private Rotation look;
    private Rotation lastSent;
    private Vec3 lastEyes;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final double EDGE_SCAN_STEP = 0.04D;
    private static final double SAFETY_MARGIN_MS = 55.0D;
    private static final long NEVER = Long.MAX_VALUE;
    private final BooleanSetting eagle;
    private final NumberSetting eagleMinDelay;
    private final NumberSetting eagleMaxDelay;
    private final NumberSetting eagleLookahead;
    private final NumberSetting horizontalSpeed;
    private final NumberSetting verticalSpeed;
    private final NumberSetting edgeAim;
    private final NumberSetting aimNoise;
    private final BooleanSetting zitter;
    private final NumberSetting zitterAmount;
    private final NumberSetting zitterMinDelay;
    private final NumberSetting zitterMaxDelay;
    private final NumberSetting placeMinDelay;
    private final NumberSetting placeMaxDelay;


    /*
     * The mode sends a silent placement yaw, so physical WASD must not be
     * reinterpreted against that server-side rotation.  We lock the world-space
     * bridge direction once an edge/target has been acquired and only unlock
     * it for an explicit opposite physical input or a lifecycle reset.
     */
    private boolean bridgeDirectionLocked;
    private double bridgeDirectionX;
    private double bridgeDirectionZ;
    private float inputReferenceYaw;

    private boolean forceSneak;
    private boolean edgeEpisodeActive;
    private double approachX;
    private double approachZ;
    private long eagleTriggerNanos = NEVER;
    private long eagleReleaseNanos = NEVER;

    private PlacementTarget target;
    private boolean serverAimReady;
    private boolean placedThisTick;
    private long nextPlaceNanos;

    private PlacementKey noiseKey;
    private double noiseFromLateral;
    private double noiseFromVertical;
    private double noiseToLateral;
    private double noiseToVertical;
    private long noiseStartNanos;
    private long noiseEndNanos;

    private PlacementKey zitterKey;
    private double zitterFromYaw;
    private double zitterFromPitch;
    private double zitterToYaw;
    private double zitterToPitch;
    private int zitterSign = 1;
    private long zitterStartNanos;
    private long zitterEndNanos;

    public BlockFlyLegitMode(final BlockFly parent) {
        super(parent);
        this.parent.modeSetting(BlockFly.Mode.LEGIT, this.eagle = new BooleanSetting("Legit Eagle",
                "Sneak before walking beyond the current support block.", true));
        this.parent.modeSetting(BlockFly.Mode.LEGIT, this.eagleMinDelay = new NumberSetting("Legit Eagle Min Delay",
                "Minimum reaction delay in ms; negative values sneak early.", 25.0F,
                -1000.0F, 5000.0F, 5.0F));
        this.parent.modeSetting(BlockFly.Mode.LEGIT, this.eagleMaxDelay = new NumberSetting("Legit Eagle Max Delay",
                "Maximum reaction delay in ms; safety always wins before a fall.", 90.0F,
                -1000.0F, 5000.0F, 5.0F));
        this.parent.modeSetting(BlockFly.Mode.LEGIT, this.eagleLookahead = new NumberSetting("Legit Eagle Lookahead",
                "Forward edge-raycast distance in blocks.", 1.7F, 0.5F, 4.0F, 0.1F));
        this.parent.modeSetting(BlockFly.Mode.LEGIT, this.horizontalSpeed = new NumberSetting("Legit Rotation HSpeed",
                "Maximum silent yaw movement per tick.", 18.0F, 1.0F, 90.0F, 1.0F));
        this.parent.modeSetting(BlockFly.Mode.LEGIT, this.verticalSpeed = new NumberSetting("Legit Rotation VSpeed",
                "Maximum silent pitch movement per tick.", 14.0F, 1.0F, 90.0F, 1.0F));
        this.parent.modeSetting(BlockFly.Mode.LEGIT, this.edgeAim = new NumberSetting("Legit Edge Aim",
                "Vertical point on a horizontal block face.", 0.82F, 0.55F, 0.94F, 0.01F));
        this.parent.modeSetting(BlockFly.Mode.LEGIT, this.aimNoise = new NumberSetting("Legit Aim Noise",
                "Slow face-tangent aim drift in blocks.", 0.04F, 0.0F, 0.18F, 0.005F));
        this.parent.modeSetting(BlockFly.Mode.LEGIT, this.zitter = new BooleanSetting("Legit Zitter",
                "Add bounded low-frequency aim tremor without leaving the clicked face.", true));
        this.parent.modeSetting(BlockFly.Mode.LEGIT, this.zitterAmount = new NumberSetting("Legit Zitter Amount",
                "Maximum yaw tremor in degrees.", 0.35F, 0.0F, 2.5F, 0.05F));
        this.parent.modeSetting(BlockFly.Mode.LEGIT, this.zitterMinDelay = new NumberSetting("Legit Zitter Min Delay",
                "Minimum time between tremor direction changes in ms.", 65.0F,
                20.0F, 1000.0F, 5.0F));
        this.parent.modeSetting(BlockFly.Mode.LEGIT, this.zitterMaxDelay = new NumberSetting("Legit Zitter Max Delay",
                "Maximum time between tremor direction changes in ms.", 145.0F,
                20.0F, 1000.0F, 5.0F));
        this.parent.modeSetting(BlockFly.Mode.LEGIT, this.placeMinDelay = new NumberSetting("Legit Place Min Delay",
                "Minimum delay between legitimate placement attempts in ms.", 65.0F,
                0.0F, 1000.0F, 5.0F));
        this.parent.modeSetting(BlockFly.Mode.LEGIT, this.placeMaxDelay = new NumberSetting("Legit Place Max Delay",
                "Maximum delay between legitimate placement attempts in ms.", 125.0F,
                0.0F, 1000.0F, 5.0F));
    }

    @Override public void enable() {
        clearRuntimeState();
        this.look = mc.player == null ? new Rotation(0, 0) : new Rotation(mc.player.getYRot(), mc.player.getXRot());
        this.lastSent = this.look;
        this.lastEyes = mc.player == null ? Vec3.ZERO : mc.player.getEyePosition();
    }

    @Override public void disable() {
        clearRuntimeState();
        this.parent.clearRotation();
    }

    @Override public void tick(final EventTick event) {
        if (!event.isPre()) return;
        this.placedThisTick = false;
        if (!canOperate()) {
            clearTransientState();
            this.parent.clearRotation();
            return;
        }
        this.parent.replenish();
        long now = System.nanoTime();
        MotionIntent intent = getMotionIntent();
        updateEagle(intent, now);
        PlacementTarget oldTarget = this.target;
        PlacementTarget newTarget = this.parent.hasBlocks() ? findPlacementTarget(intent) : null;
        if (newTarget == null) {
            clearAimState();
            this.parent.clearRotation();
            return;
        }
        lockBridgeDirection(intent);
        PlacementKey newKey = newTarget.key();
        boolean sameTarget = oldTarget != null && oldTarget.key().equals(newKey);
        this.serverAimReady = sameTarget && isExactHit(serverRayCast(), newTarget);
        if (!sameTarget) this.nextPlaceNanos = safeAddNanos(now, randomDelayNanos(this.placeMinDelay, this.placeMaxDelay, 0, 30_000));
        this.target = newTarget.withAim(getSafeNoisyAimPoint(newTarget, updateAimNoise(newKey, now)));
        applySilentRotation(this.target, now);
    }

    @Override public void input(final EventMovementInput event) {
        if (this.forceSneak && canOperate() && this.eagle.get()) event.setShift(true);
    }

    @Override public void motion(final EventMotion event) {
        if (!canOperate() || this.target == null) return;
        if (event.isPre()) {
            this.parent.aim(event, this.look, true, MovementCorrection.SILENT);
        } else if (!event.isCancelled()) {
            this.lastEyes = new Vec3(event.getX(), event.getY() + mc.player.getEyeHeight(), event.getZ());
            this.lastSent = new Rotation(event.getYaw(), event.getPitch());
            tryPlace();
        }
    }

    @Override public void click(final EventClick event) {
        // A manual use in this tick must not race the placement after its movement packet.
        if (event.getButton() == EventClick.Button.RIGHT && (this.placedThisTick || this.target != null && canOperate())) event.cancel();
    }

    private boolean canOperate() {
        return mc.player != null && mc.level != null && mc.gameMode != null && mc.getConnection() != null
                && mc.gui.screen() == null && mc.isWindowActive() && mc.mouseHandler.isMouseGrabbed()
                && mc.player.isAlive() && !mc.player.isSpectator() && !mc.player.isPassenger()
                && !mc.player.getAbilities().flying && !mc.player.isFallFlying() && !mc.player.isInWater()
                && !mc.player.isInLava() && mc.player.onGround() && mc.player.fallDistance <= 0
                && !mc.options.keyJump.isDown();
    }
    private void clearRuntimeState() {
        this.clearTransientState();
        this.nextPlaceNanos = 0L;
        this.noiseKey = null;
        this.noiseStartNanos = 0L;
        this.noiseEndNanos = 0L;
        this.zitterKey = null;
        this.zitterStartNanos = 0L;
        this.zitterEndNanos = 0L;
        this.resetBridgeDirection();
    }

    private void clearTransientState() {
        this.resetEagle();
        this.clearAimState();
        this.resetBridgeDirection();
        this.placedThisTick = false;
    }

    private void clearAimState() {
        this.target = null;
        this.serverAimReady = false;
        this.noiseKey = null;
        this.zitterKey = null;
    }

    private void resetEagle() {
        this.forceSneak = false;
        this.edgeEpisodeActive = false;
        this.approachX = 0.0D;
        this.approachZ = 0.0D;
        this.eagleTriggerNanos = NEVER;
        this.eagleReleaseNanos = NEVER;
    }

    private void resetBridgeDirection() {
        this.bridgeDirectionLocked = false;
        this.bridgeDirectionX = 0.0D;
        this.bridgeDirectionZ = 0.0D;
        this.inputReferenceYaw = 0.0F;
    }

    private MotionIntent getMotionIntent() {
        double forward = 0.0D;
        double strafe = 0.0D;
        if (mc.options.keyUp.isDown()) forward += 1.0D;
        if (mc.options.keyDown.isDown()) forward -= 1.0D;
        if (mc.options.keyLeft.isDown()) strafe += 1.0D;
        if (mc.options.keyRight.isDown()) strafe -= 1.0D;

        if (forward == 0.0D && strafe == 0.0D && mc.player.input != null) {
            forward = mc.player.input.getMoveVector().y;
            strafe = mc.player.input.getMoveVector().x;
        }

        if (!this.bridgeDirectionLocked) {
            this.inputReferenceYaw = mc.player.getYRot();
        }
        double yaw = Math.toRadians(this.bridgeDirectionLocked
                ? this.inputReferenceYaw
                : mc.player.getYRot());
        double inputX = forward * -Math.sin(yaw) + strafe * Math.cos(yaw);
        double inputZ = forward * Math.cos(yaw) + strafe * Math.sin(yaw);
        double inputLength = Math.hypot(inputX, inputZ);

        Vec3 motion = mc.player.getDeltaMovement();
        double motionSpeed = Math.hypot(motion.x, motion.z);
        if (inputLength > 1.0E-4D) {
            inputX /= inputLength;
            inputZ /= inputLength;
            if (this.bridgeDirectionLocked) {
                double explicitDirection = inputX * this.bridgeDirectionX
                        + inputZ * this.bridgeDirectionZ;
                if (explicitDirection < -0.6D) {
                    this.resetBridgeDirection();
                    return new MotionIntent(inputX, inputZ, Math.max(motionSpeed, 0.055D));
                }
                return new MotionIntent(this.bridgeDirectionX, this.bridgeDirectionZ,
                        Math.max(motionSpeed, 0.055D));
            }
            if (motionSpeed > 0.02D) {
                double motionX = motion.x / motionSpeed;
                double motionZ = motion.z / motionSpeed;
                if (inputX * motionX + inputZ * motionZ > 0.25D) {
                    inputX = inputX * 0.7D + motionX * 0.3D;
                    inputZ = inputZ * 0.7D + motionZ * 0.3D;
                    double blendedLength = Math.hypot(inputX, inputZ);
                    inputX /= blendedLength;
                    inputZ /= blendedLength;
                }
            }
            return new MotionIntent(inputX, inputZ, Math.max(motionSpeed, 0.055D));
        }

        if (motionSpeed > 0.015D) {
            if (this.bridgeDirectionLocked) {
                return new MotionIntent(this.bridgeDirectionX, this.bridgeDirectionZ, motionSpeed);
            }
            return new MotionIntent(motion.x / motionSpeed, motion.z / motionSpeed, motionSpeed);
        }
        if (this.bridgeDirectionLocked) {
            return new MotionIntent(this.bridgeDirectionX, this.bridgeDirectionZ, 0.035D);
        }
        return null;
    }

    private void lockBridgeDirection(MotionIntent intent) {
        if (intent == null || this.bridgeDirectionLocked) {
            return;
        }
        double length = Math.hypot(intent.x, intent.z);
        if (length <= 1.0E-4D) {
            return;
        }
        this.bridgeDirectionLocked = true;
        this.bridgeDirectionX = intent.x / length;
        this.bridgeDirectionZ = intent.z / length;
    }

    private void updateEagle(MotionIntent intent, long now) {
        if (!this.eagle.get()) {
            this.resetEagle();
            return;
        }
        if (intent == null) {
            if (!this.forceSneak) {
                this.resetEagle();
            }
            return;
        }

        if (this.edgeEpisodeActive) {
            double dot = intent.x * this.approachX + intent.z * this.approachZ;
            if (dot < 0.6D) {
                this.resetEagle();
            }
        }
        if (!this.edgeEpisodeActive) {
            this.approachX = intent.x;
            this.approachZ = intent.z;
        }

        double effectiveMinimumDelay = this.effectiveMinimumDelay(
                this.eagleMinDelay, this.eagleMaxDelay, -30_000.0F, 30_000.0F);
        double earlyLeadMs = Math.max(0.0D, -effectiveMinimumDelay);
        double lookahead = this.safeNumber(this.eagleLookahead, 1.7F, 0.5F, 4.0F);
        lookahead = Mth.clamp(Math.max(lookahead,
                intent.speed * ((earlyLeadMs + 250.0D) / 50.0D)), 0.5D, 4.0D);

        double rayGap = this.firstRayGapDistance(intent.x, intent.z, lookahead);
        double unsafeDistance = this.firstUnsupportedFootDistance(
                intent.x, intent.z, Math.min(4.5D, lookahead + 0.55D));
        boolean warning = Double.isFinite(rayGap) || Double.isFinite(unsafeDistance);

        if (!warning) {
            this.handleSafeEagleRelease(intent, now, Double.POSITIVE_INFINITY);
            return;
        }

        if (!Double.isFinite(unsafeDistance)) {
            unsafeDistance = Math.min(4.5D, rayGap + mc.player.getBbWidth() * 0.65D);
        }
        double speed = Math.max(0.02D, intent.speed);
        double unsafeMs = Math.max(0.0D, unsafeDistance / speed * 50.0D);
        long hardDeadline = safeAddNanos(now,
                millisToNanos(Math.max(0.0D, unsafeMs - SAFETY_MARGIN_MS)));

        if (!this.edgeEpisodeActive) {
            this.edgeEpisodeActive = true;
            this.approachX = intent.x;
            this.approachZ = intent.z;
            this.lockBridgeDirection(intent);
            double sampledDelay = this.randomDelayMillis(
                    this.eagleMinDelay, this.eagleMaxDelay, -30_000, 30_000);
            this.eagleTriggerNanos = eagleDeadline(now, unsafeMs, sampledDelay);
        } else {
            this.eagleTriggerNanos = Math.min(this.eagleTriggerNanos, hardDeadline);
        }

        double immediateDistance = Math.max(0.08D, speed * 1.5D + 0.02D);
        if (unsafeDistance <= immediateDistance || now >= this.eagleTriggerNanos) {
            this.forceSneak = true;
            this.eagleReleaseNanos = NEVER;
        }

        if (this.forceSneak) {
            boolean stable = unsafeDistance > Math.max(0.48D, speed * 4.0D)
                    && this.hasFootSupportAt(0.0D, 0.0D)
                    && this.hasFootSupportAt(intent.x * Math.max(0.12D, speed * 1.5D),
                    intent.z * Math.max(0.12D, speed * 1.5D));
            if (stable) {
                if (this.eagleReleaseNanos == NEVER) {
                    this.eagleReleaseNanos = safeAddNanos(now,
                            millisToNanos(ThreadLocalRandom.current().nextLong(60L, 121L)));
                } else if (now >= this.eagleReleaseNanos) {
                    this.resetEagle();
                }
            } else {
                this.eagleReleaseNanos = NEVER;
            }
        }
    }

    private void handleSafeEagleRelease(MotionIntent intent, long now, double unsafeDistance) {
        if (!this.forceSneak) {
            this.resetEagle();
            return;
        }
        boolean stable = unsafeDistance > 0.48D
                && this.hasFootSupportAt(0.0D, 0.0D)
                && this.hasFootSupportAt(intent.x * 0.12D, intent.z * 0.12D);
        if (!stable) {
            this.eagleReleaseNanos = NEVER;
            return;
        }
        if (this.eagleReleaseNanos == NEVER) {
            this.eagleReleaseNanos = safeAddNanos(now,
                    millisToNanos(ThreadLocalRandom.current().nextLong(60L, 121L)));
        } else if (now >= this.eagleReleaseNanos) {
            this.resetEagle();
        }
    }

    private double firstRayGapDistance(double directionX, double directionZ, double distance) {
        AABB box = mc.player.getBoundingBox();
        double halfWidth = Math.max(0.2D, mc.player.getBbWidth() * 0.5D - 0.015D);
        double perpendicularX = -directionZ;
        double perpendicularZ = directionX;
        for (double step = 0.0D; step <= distance + 1.0E-6D; step += EDGE_SCAN_STEP) {
            double leadingX = (box.minX + box.maxX) * 0.5D + directionX * (halfWidth + step);
            double leadingZ = (box.minZ + box.maxZ) * 0.5D + directionZ * (halfWidth + step);
            double lateral = halfWidth * 0.72D;
            if (!this.hasDownwardRaySupport(leadingX, leadingZ)
                    || !this.hasDownwardRaySupport(
                    leadingX + perpendicularX * lateral,
                    leadingZ + perpendicularZ * lateral)
                    || !this.hasDownwardRaySupport(
                    leadingX - perpendicularX * lateral,
                    leadingZ - perpendicularZ * lateral)) {
                return step;
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private boolean hasDownwardRaySupport(double x, double z) {
        return this.downwardSupportRay(x, z) != null;
    }

    private BlockHitResult downwardSupportRay(double x, double z) {
        double feetY = mc.player.getBoundingBox().minY;
        Vec3 start = new Vec3(x, feetY + 0.08D, z);
        Vec3 end = new Vec3(x, feetY - 0.82D, z);
        BlockHitResult ray = mc.level.clip(new ClipContext(
                start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, mc.player));
        if (ray.getType() == HitResult.Type.BLOCK
                && ray.getLocation().y <= feetY + 0.08D
                && feetY - ray.getLocation().y <= 0.72D) {
            return ray;
        }
        return null;
    }

    private double firstUnsupportedFootDistance(double directionX, double directionZ, double distance) {
        for (double step = 0.0D; step <= distance + 1.0E-6D; step += EDGE_SCAN_STEP) {
            if (!this.hasFootSupportAt(directionX * step, directionZ * step)) {
                return step;
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private boolean hasFootSupportAt(double offsetX, double offsetZ) {
        AABB box = mc.player.getBoundingBox();
        double inset = Math.min(0.03D, Math.max(0.005D, mc.player.getBbWidth() * 0.08D));
        AABB probe = new AABB(
                box.minX + inset + offsetX,
                box.minY - 0.10D,
                box.minZ + inset + offsetZ,
                box.maxX - inset + offsetX,
                box.minY + 0.01D,
                box.maxZ - inset + offsetZ);
        return !mc.level.noCollision(mc.player, probe);
    }

    private PlacementTarget findPlacementTarget(MotionIntent intent) {
        if (intent == null) {
            return null;
        }
        int supportY = this.getSupportBlockY();
        double scanDistance = this.safeNumber(this.eagleLookahead, 1.7F, 0.5F, 4.0F) + 0.9D;
        double perpendicularX = -intent.z;
        double perpendicularZ = intent.x;
        double[] lateralOffsets = {0.0D, 0.24D, -0.24D};
        Set<BlockPos> candidates = new LinkedHashSet<>();
        for (double distance = 0.28D; distance <= scanDistance; distance += 0.10D) {
            for (double lateral : lateralOffsets) {
                double x = mc.player.getX() + intent.x * distance + perpendicularX * lateral;
                double z = mc.player.getZ() + intent.z * distance + perpendicularZ * lateral;
                candidates.add(new BlockPos(Mth.floor(x), supportY, Mth.floor(z)));
            }
        }

        PlacementTarget closest = null;
        for (BlockPos placePos : candidates) {
            if (!this.isReplaceable(placePos)) {
                continue;
            }
            PlacementTarget candidate = this.findAnchor(placePos, intent.x, intent.z);
            if (candidate != null && this.isCloserPlacementTarget(candidate, closest)) {
                closest = candidate;
            }
        }
        return closest;
    }

    private PlacementTarget findAnchor(BlockPos placePos, double directionX, double directionZ) {
        Direction primary = this.primaryAnchorDirection(directionX, directionZ);
        Direction[] ordered = this.orderedAnchorDirections(primary);
        PlacementTarget closest = null;
        for (Direction anchorDirection : ordered) {
            BlockPos anchor = placePos.relative(anchorDirection);
            Direction face = anchorDirection.getOpposite();
            BlockState state = mc.level.getBlockState(anchor);
            if (!this.isUsableAnchor(state, anchor)) {
                continue;
            }
            Vec3 aim = this.buildAimPoint(anchor, face, 0.0D, 0.0D);
            if (aim == null) {
                continue;
            }
            double reach = mc.player.blockInteractionRange();
            if (mc.player.getEyePosition().distanceToSqr(aim) > reach * reach) {
                continue;
            }
            PlacementTarget candidate = new PlacementTarget(placePos, anchor, face, aim);
            float[] rotation = this.rotationsTo(aim);
            if (this.isExactHit(this.rayCast(rotation[0], rotation[1]), candidate)) {
                closest = this.closerAimTarget(candidate, closest);
            }
        }

        BlockPos anchor = placePos.below();
        Direction face = Direction.UP;
        BlockState state = mc.level.getBlockState(anchor);
        if (this.isUsableAnchor(state, anchor)) {
            Vec3 aim = this.buildAimPoint(anchor, face, 0.0D, 0.0D);
            if (aim != null) {
                double reach = mc.player.blockInteractionRange();
                PlacementTarget candidate = new PlacementTarget(placePos, anchor, face, aim);
                float[] rotation = this.rotationsTo(aim);
                if (mc.player.getEyePosition().distanceToSqr(aim) <= reach * reach
                        && this.isExactHit(this.rayCast(rotation[0], rotation[1]), candidate)) {
                    closest = this.closerAimTarget(candidate, closest);
                }
            }
        }
        return closest;
    }

    private boolean isCloserPlacementTarget(PlacementTarget candidate, PlacementTarget current) {
        if (current == null) {
            return true;
        }
        double candidateDistance = this.placeDistanceSquared(candidate);
        double currentDistance = this.placeDistanceSquared(current);
        if (Math.abs(candidateDistance - currentDistance) > 1.0E-9D) {
            return candidateDistance < currentDistance;
        }
        return this.isCloserAimTarget(candidate, current);
    }

    private double placeDistanceSquared(PlacementTarget target) {
        double x = target.placePos.getX() + 0.5D - mc.player.getX();
        double z = target.placePos.getZ() + 0.5D - mc.player.getZ();
        return x * x + z * z;
    }

    private PlacementTarget closerAimTarget(PlacementTarget candidate, PlacementTarget current) {
        return current == null || this.isCloserAimTarget(candidate, current) ? candidate : current;
    }

    private boolean isCloserAimTarget(PlacementTarget candidate, PlacementTarget current) {
        Vec3 eyes = mc.player.getEyePosition();
        return eyes.distanceToSqr(candidate.aimPoint)
                + 1.0E-9D < eyes.distanceToSqr(current.aimPoint);
    }

    private Direction primaryAnchorDirection(double directionX, double directionZ) {
        if (Math.abs(directionX) > Math.abs(directionZ)) {
            return directionX > 0.0D ? Direction.WEST : Direction.EAST;
        }
        return directionZ > 0.0D ? Direction.NORTH : Direction.SOUTH;
    }

    private Direction[] orderedAnchorDirections(Direction primary) {
        Direction firstSide;
        Direction secondSide;
        if (primary == Direction.NORTH || primary == Direction.SOUTH) {
            firstSide = Direction.WEST;
            secondSide = Direction.EAST;
        } else {
            firstSide = Direction.NORTH;
            secondSide = Direction.SOUTH;
        }
        return new Direction[]{primary, firstSide, secondSide, primary.getOpposite()};
    }

    private boolean isReplaceable(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return state.getFluidState().isEmpty()
                && (state.isAir() || state.canBeReplaced());
    }

    private boolean isUsableAnchor(BlockState state, BlockPos pos) {
        if (state.isAir() || state.canBeReplaced()
                || !state.getFluidState().isEmpty()
                || state.getBlock() instanceof BaseEntityBlock
                || state.getBlock() instanceof CraftingTableBlock) {
            return false;
        }
        return !state.getShape(mc.level, pos).isEmpty();
    }

    private int getSupportBlockY() {
        AABB box = mc.player.getBoundingBox();
        double centerX = (box.minX + box.maxX) * 0.5D;
        double centerZ = (box.minZ + box.maxZ) * 0.5D;
        double offset = Math.max(0.08D, mc.player.getBbWidth() * 0.25D);
        double[][] points = {
                {centerX, centerZ},
                {centerX + offset, centerZ}, {centerX - offset, centerZ},
                {centerX, centerZ + offset}, {centerX, centerZ - offset}
        };
        for (double[] point : points) {
            BlockHitResult support = this.downwardSupportRay(point[0], point[1]);
            if (support != null) {
                return support.getBlockPos().getY();
            }
        }
        return Mth.floor(box.minY - 0.01D);
    }

    private AimOffset updateAimNoise(PlacementKey key, long now) {
        double amount = this.safeNumber(this.aimNoise, 0.04F, 0.0F, 0.18F);
        if (!key.equals(this.noiseKey)) {
            this.noiseKey = key;
            this.noiseFromLateral = this.randomSymmetric(amount);
            this.noiseFromVertical = this.randomSymmetric(amount * 0.75D);
            this.noiseToLateral = this.randomSymmetric(amount);
            this.noiseToVertical = this.randomSymmetric(amount * 0.75D);
            this.noiseStartNanos = now;
            this.noiseEndNanos = safeAddNanos(now,
                    millisToNanos(ThreadLocalRandom.current().nextLong(140L, 321L)));
        } else if (now >= this.noiseEndNanos) {
            this.noiseFromLateral = this.noiseToLateral;
            this.noiseFromVertical = this.noiseToVertical;
            this.noiseToLateral = this.randomSymmetric(amount);
            this.noiseToVertical = this.randomSymmetric(amount * 0.75D);
            this.noiseStartNanos = now;
            this.noiseEndNanos = safeAddNanos(now,
                    millisToNanos(ThreadLocalRandom.current().nextLong(140L, 321L)));
        }
        double progress = smoothProgress(now, this.noiseStartNanos, this.noiseEndNanos);
        return new AimOffset(
                lerp(this.noiseFromLateral, this.noiseToLateral, progress),
                lerp(this.noiseFromVertical, this.noiseToVertical, progress));
    }

    private Vec3 getSafeNoisyAimPoint(PlacementTarget target, AimOffset noise) {
        double[] scales = {1.0D, 0.5D, 0.0D};
        for (double scale : scales) {
            Vec3 point = this.buildAimPoint(target.anchor, target.face,
                    noise.lateral * scale, noise.vertical * scale);
            if (point == null) {
                continue;
            }
            PlacementTarget adjusted = target.withAim(point);
            float[] rotations = this.rotationsTo(point);
            if (this.isExactHit(this.rayCast(rotations[0], rotations[1]), adjusted)) {
                return point;
            }
        }
        return target.aimPoint;
    }

    private Vec3 buildAimPoint(BlockPos anchor, Direction face,
                                    double lateralNoise, double verticalNoise) {
        AABB bounds = this.getFaceBounds(anchor, face);
        if (bounds == null) {
            return null;
        }
        double x = anchor.getX() + 0.5D;
        double y = anchor.getY() + 0.5D;
        double z = anchor.getZ() + 0.5D;
        if (face == Direction.UP || face == Direction.DOWN) {
            x = anchor.getX() + this.insideShapeCoordinate(
                    bounds.minX, bounds.maxX, 0.5D, lateralNoise);
            z = anchor.getZ() + this.insideShapeCoordinate(
                    bounds.minZ, bounds.maxZ, 0.5D, verticalNoise);
            y = anchor.getY() + (face == Direction.UP ? bounds.maxY : bounds.minY);
        } else {
            y = anchor.getY() + this.insideShapeCoordinate(bounds.minY, bounds.maxY,
                    this.safeNumber(this.edgeAim, 0.82F, 0.55F, 0.94F), verticalNoise);
            if (face == Direction.EAST || face == Direction.WEST) {
                x = anchor.getX() + (face == Direction.EAST ? bounds.maxX : bounds.minX);
                z = anchor.getZ() + this.insideShapeCoordinate(
                        bounds.minZ, bounds.maxZ, 0.5D, lateralNoise);
            } else {
                z = anchor.getZ() + (face == Direction.SOUTH ? bounds.maxZ : bounds.minZ);
                x = anchor.getX() + this.insideShapeCoordinate(
                        bounds.minX, bounds.maxX, 0.5D, lateralNoise);
            }
        }
        return new Vec3(x, y, z);
    }

    private AABB getFaceBounds(BlockPos anchor, Direction face) {
        VoxelShape shape = mc.level.getBlockState(anchor).getShape(mc.level, anchor);
        if (shape.isEmpty()) {
            return null;
        }
        AABB selected = null;
        for (AABB candidate : shape.toAabbs()) {
            if (selected == null || this.isBetterFaceBounds(candidate, selected, face)) {
                selected = candidate;
            }
        }
        return selected;
    }

    private boolean isBetterFaceBounds(AABB candidate, AABB current, Direction face) {
        double candidatePlane = this.facePlane(candidate, face);
        double currentPlane = this.facePlane(current, face);
        boolean positive = face == Direction.EAST || face == Direction.SOUTH || face == Direction.UP;
        if (Math.abs(candidatePlane - currentPlane) > 1.0E-6D) {
            return positive ? candidatePlane > currentPlane : candidatePlane < currentPlane;
        }
        double candidateHeight = candidate.maxY - candidate.minY;
        double currentHeight = current.maxY - current.minY;
        if (Math.abs(candidateHeight - currentHeight) > 1.0E-6D) {
            return candidateHeight > currentHeight;
        }
        return candidate.maxY > current.maxY;
    }

    private double facePlane(AABB bounds, Direction face) {
        switch (face) {
            case EAST:
                return bounds.maxX;
            case WEST:
                return bounds.minX;
            case SOUTH:
                return bounds.maxZ;
            case NORTH:
                return bounds.minZ;
            case UP:
                return bounds.maxY;
            case DOWN:
            default:
                return bounds.minY;
        }
    }

    private double insideShapeCoordinate(double minimum, double maximum,
                                         double fraction, double noise) {
        double span = Math.max(0.0D, maximum - minimum);
        if (span <= 1.0E-6D) {
            return (minimum + maximum) * 0.5D;
        }
        double margin = Math.min(0.04D, span * 0.18D);
        double desired = minimum + span * Mth.clamp(fraction, 0.08D, 0.94D) + noise;
        return Mth.clamp(desired, minimum + margin, maximum - margin);
    }

    private void applySilentRotation(PlacementTarget target, long now) {
        float[] desired = this.rotationsTo(target.aimPoint);
        AimOffset tremor = this.updateZitter(target.key(), now);
        double[] scales = {1.0D, 0.5D, 0.0D};
        for (double scale : scales) {
            float candidateYaw = desired[0] + (float) (tremor.lateral * scale);
            float candidatePitch = Mth.clamp(
                    desired[1] + (float) (tremor.vertical * scale), -90.0F, 90.0F);
            if (this.isExactHit(this.rayCast(candidateYaw, candidatePitch), target)) {
                desired[0] = candidateYaw;
                desired[1] = candidatePitch;
                break;
            }
        }

        float oldYaw = this.lastSent.yaw();
        float oldPitch = this.lastSent.pitch();
        if (!Float.isFinite(oldYaw)) {
            oldYaw = mc.player.getYRot();
        }
        if (!Float.isFinite(oldPitch)) {
            oldPitch = mc.player.getXRot();
        }
        Rotation from = new Rotation(oldYaw, oldPitch);
        Rotation limited = Rotations.limit(from, Rotations.continuous(from, new Rotation(desired[0], desired[1])),
                this.horizontalSpeed.get(), this.verticalSpeed.get());
        Rotation fixed = Rotations.quantize(from, limited, Rotations.mouseStep(mc.options.sensitivity().get()));
        float yaw = fixed.yaw();
        float pitch = fixed.pitch();
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            return;
        }
        this.look = new Rotation(yaw, pitch);
        this.parent.requestRotation(this.look, true, MovementCorrection.SILENT);
    }

    private AimOffset updateZitter(PlacementKey key, long now) {
        if (!this.zitter.get()) {
            this.zitterKey = null;
            return AimOffset.ZERO;
        }
        double amount = this.safeNumber(this.zitterAmount, 0.35F, 0.0F, 2.5F);
        if (amount <= 0.0D) {
            return AimOffset.ZERO;
        }
        if (!key.equals(this.zitterKey)) {
            this.zitterKey = key;
            this.zitterSign = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
            this.zitterFromYaw = 0.0D;
            this.zitterFromPitch = 0.0D;
            this.zitterToYaw = this.zitterSign * amount
                    * ThreadLocalRandom.current().nextDouble(0.65D, 1.0D);
            this.zitterToPitch = this.randomSymmetric(amount * 0.25D);
            this.zitterStartNanos = now;
            this.zitterEndNanos = safeAddNanos(now,
                    this.randomDelayNanos(this.zitterMinDelay, this.zitterMaxDelay, 20, 5000));
        } else if (now >= this.zitterEndNanos) {
            this.zitterFromYaw = this.zitterToYaw;
            this.zitterFromPitch = this.zitterToPitch;
            this.zitterSign *= -1;
            this.zitterToYaw = this.zitterSign * amount
                    * ThreadLocalRandom.current().nextDouble(0.65D, 1.0D);
            this.zitterToPitch = this.randomSymmetric(amount * 0.25D);
            this.zitterStartNanos = now;
            this.zitterEndNanos = safeAddNanos(now,
                    this.randomDelayNanos(this.zitterMinDelay, this.zitterMaxDelay, 20, 5000));
        }
        double progress = smoothProgress(now, this.zitterStartNanos, this.zitterEndNanos);
        return new AimOffset(
                lerp(this.zitterFromYaw, this.zitterToYaw, progress),
                lerp(this.zitterFromPitch, this.zitterToPitch, progress));
    }

    private boolean tryPlace() {
        if (this.placedThisTick || this.target == null || !this.serverAimReady || !canOperate()
                || System.nanoTime() < this.nextPlaceNanos || !isReplaceable(this.target.placePos)) return false;
        BlockHitResult ray = serverRayCast();
        if (!isExactHit(ray, this.target)) return false;
        this.nextPlaceNanos = safeAddNanos(System.nanoTime(), randomDelayNanos(this.placeMinDelay, this.placeMaxDelay, 0, 30_000));
        BlockFlyPlacement.Target placement = new BlockFlyPlacement.Target(this.target.placePos, ray, this.lastSent, this.lastEyes);
        if (!this.parent.place(placement)) return false;
        this.placedThisTick = true;
        this.serverAimReady = false;
        return true;
    }
    private float[] rotationsTo(Vec3 point) {
        Vec3 eye = mc.player.getEyePosition();
        double deltaX = point.x - eye.x;
        double deltaY = point.y - eye.y;
        double deltaZ = point.z - eye.z;
        double horizontal = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        float yaw = (float) (Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(deltaY, horizontal));
        return new float[]{yaw, Mth.clamp(pitch, -90.0F, 90.0F)};
    }

    private BlockHitResult rayCast(float yaw, float pitch) {
        return this.rayCast(mc.player.getEyePosition(), yaw, pitch);
    }

    private BlockHitResult serverRayCast() {
        return this.rayCast(this.lastEyes, this.lastSent.yaw(), this.lastSent.pitch());
    }

    private BlockHitResult rayCast(Vec3 eyes, float yaw, float pitch) {
        float yawRadians = (float) Math.toRadians(yaw);
        float pitchRadians = (float) Math.toRadians(pitch);
        double x = -Mth.sin(yawRadians) * Mth.cos(pitchRadians);
        double y = -Mth.sin(pitchRadians);
        double z = Mth.cos(yawRadians) * Mth.cos(pitchRadians);
        double reach = mc.player.blockInteractionRange();
        return mc.level.clip(new ClipContext(
                eyes, eyes.add(x * reach, y * reach, z * reach),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, mc.player));
    }

    private boolean isExactHit(BlockHitResult ray, PlacementTarget expected) {
        return ray != null
                && ray.getType() == HitResult.Type.BLOCK
                && expected.anchor.equals(ray.getBlockPos())
                && expected.face == ray.getDirection()
                && expected.placePos.equals(expected.anchor.relative(expected.face));
    }

    private float safeNumber(NumberSetting setting, float fallback,
                             float minimum, float maximum) {
        Float value = setting.get();
        float number = value == null ? fallback : value;
        if (!Float.isFinite(number)) {
            number = fallback;
        }
        return Mth.clamp(number, minimum, maximum);
    }

    private long randomDelayNanos(NumberSetting minimum,
                                  NumberSetting maximum,
                                  int hardMinimum, int hardMaximum) {
        return millisToNanos(this.randomDelayMillis(
                minimum, maximum, hardMinimum, hardMaximum));
    }

    private long randomDelayMillis(NumberSetting minimum,
                                   NumberSetting maximum,
                                   int hardMinimum, int hardMaximum) {
        long first = Math.round(this.safeNumber(minimum, minimum.getDefaultValue(),
                hardMinimum, hardMaximum));
        long second = Math.round(this.safeNumber(maximum, maximum.getDefaultValue(),
                hardMinimum, hardMaximum));
        long low = Math.min(first, second);
        long high = Math.max(first, second);
        if (low == high) {
            return low;
        }
        return ThreadLocalRandom.current().nextLong(low, high + 1L);
    }

    private double effectiveMinimumDelay(NumberSetting first,
                                         NumberSetting second,
                                         float hardMinimum, float hardMaximum) {
        float firstValue = this.safeNumber(first, first.getDefaultValue(), hardMinimum, hardMaximum);
        float secondValue = this.safeNumber(second, second.getDefaultValue(), hardMinimum, hardMaximum);
        return Math.min(firstValue, secondValue);
    }

    private double randomSymmetric(double amount) {
        if (!(amount > 0.0D) || !Double.isFinite(amount)) {
            return 0.0D;
        }
        return ThreadLocalRandom.current().nextDouble(-amount, amount);
    }

    static long millisToNanos(double milliseconds) {
        if (!Double.isFinite(milliseconds)) {
            return 0L;
        }
        double clamped = Mth.clamp(milliseconds, 0.0D, 60_000.0D);
        return (long) (clamped * NANOS_PER_MILLI);
    }

    static long eagleDeadline(final long now, final double unsafeMs, final double sampledDelay) {
        double reaction = sampledDelay < 0 ? unsafeMs + sampledDelay : sampledDelay;
        double safety = Math.max(0, unsafeMs - SAFETY_MARGIN_MS);
        return safeAddNanos(now, millisToNanos(Math.max(0, Math.min(reaction, safety))));
    }

    static long safeAddNanos(long base, long delta) {
        if (delta <= 0L) {
            return base;
        }
        return base > Long.MAX_VALUE - delta ? Long.MAX_VALUE : base + delta;
    }

    static double smoothProgress(long now, long start, long end) {
        if (end <= start) {
            return 1.0D;
        }
        double linear = Mth.clamp((double) (now - start) / (double) (end - start),
                0.0D, 1.0D);
        return linear * linear * (3.0D - 2.0D * linear);
    }

    private static double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    private static final class MotionIntent {
        private final double x;
        private final double z;
        private final double speed;

        private MotionIntent(double x, double z, double speed) {
            this.x = x;
            this.z = z;
            this.speed = speed;
        }
    }

    private static final class PlacementTarget {
        private final BlockPos placePos;
        private final BlockPos anchor;
        private final Direction face;
        private final Vec3 aimPoint;

        private PlacementTarget(BlockPos placePos, BlockPos anchor,
                                Direction face, Vec3 aimPoint) {
            this.placePos = placePos;
            this.anchor = anchor;
            this.face = face;
            this.aimPoint = aimPoint;
        }

        private PlacementKey key() {
            return new PlacementKey(this.placePos, this.anchor, this.face);
        }

        private PlacementTarget withAim(Vec3 point) {
            return new PlacementTarget(this.placePos, this.anchor, this.face, point);
        }
    }

    private static final class PlacementKey {
        private final BlockPos placePos;
        private final BlockPos anchor;
        private final Direction face;

        private PlacementKey(BlockPos placePos, BlockPos anchor, Direction face) {
            this.placePos = placePos;
            this.anchor = anchor;
            this.face = face;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof PlacementKey)) return false;
            PlacementKey other = (PlacementKey) object;
            return this.placePos.equals(other.placePos)
                    && this.anchor.equals(other.anchor)
                    && this.face == other.face;
        }

        @Override
        public int hashCode() {
            int result = this.placePos.hashCode();
            result = 31 * result + this.anchor.hashCode();
            result = 31 * result + this.face.hashCode();
            return result;
        }
    }

    private static final class AimOffset {
        private static final AimOffset ZERO = new AimOffset(0.0D, 0.0D);
        private final double lateral;
        private final double vertical;

        private AimOffset(double lateral, double vertical) {
            this.lateral = lateral;
            this.vertical = vertical;
        }
    }
}
