package net.minecraft.world.entity.vehicle.boat;

import com.google.common.collect.Lists;
import com.viaversion.viafabricplus.features.entity.dimensions.EntityRidingOffsetsPre1_20_2;
import com.viaversion.viafabricplus.features.entity.legacy_boat_model.PositionInterpolator1_8;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.BlockUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LilyPadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.raphimc.vialegacy.api.LegacyProtocolVersion;
import org.jspecify.annotations.Nullable;

// MODIFIED for porting: was VFP entity/legacy_boat_model MixinAbstractBoat (IAbstractBoat implementation).
// PositionInterpolator1_8 casts the boat to IAbstractBoat to drive the 1.8 interpolation state below.
public abstract class AbstractBoat extends VehicleEntity
    implements Leashable, com.viaversion.viafabricplus.injection.access.entity.legacy_boat_model.IAbstractBoat {
    private static final EntityDataAccessor<Boolean> DATA_ID_PADDLE_LEFT = SynchedEntityData.defineId(AbstractBoat.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_ID_PADDLE_RIGHT = SynchedEntityData.defineId(AbstractBoat.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_ID_BUBBLE_TIME = SynchedEntityData.defineId(AbstractBoat.class, EntityDataSerializers.INT);
    public static final int PADDLE_LEFT = 0;
    public static final int PADDLE_RIGHT = 1;
    private static final int TIME_TO_EJECT = 60;
    private static final float PADDLE_SPEED = (float) (Math.PI / 8);
    public static final double PADDLE_SOUND_TIME = (float) (Math.PI / 4);
    public static final int BUBBLE_TIME = 60;
    private final float[] paddlePositions = new float[2];
    private float outOfControlTicks;
    private float deltaRotation;
    private final InterpolationHandler interpolation = new InterpolationHandler(this, 3);
    // MODIFIED for porting: was VFP entity/legacy_boat_model MixinAbstractBoat @Unique state
    // (viaFabricPlus$positionInterpolator / $speedMultiplier / $boatInterpolationSteps / $boatVelocity).
    // Only used for <= 1.8 targets, where the boat runs the 1.8 physics and interpolation instead.
    private final InterpolationHandler vfpPositionInterpolator = new PositionInterpolator1_8(this);
    private double vfpSpeedMultiplier = 0.07D;
    private int vfpBoatInterpolationSteps;
    private Vec3 vfpBoatVelocity = Vec3.ZERO;
    private boolean inputLeft;
    private boolean inputRight;
    private boolean inputUp;
    private boolean inputDown;
    private double waterLevel;
    private float landFriction;
    private AbstractBoat.Status status;
    private AbstractBoat.Status oldStatus;
    private double lastYd;
    private boolean isAboveBubbleColumn;
    private boolean bubbleColumnDirectionIsDown;
    private float bubbleMultiplier;
    private float bubbleAngle;
    private float bubbleAngleO;
    private Leashable.@Nullable LeashData leashData;
    private final Supplier<Item> dropItem;

    public AbstractBoat(final EntityType<? extends AbstractBoat> type, final Level level, final Supplier<Item> dropItem) {
        super(type, level);
        this.dropItem = dropItem;
        this.blocksBuilding = true;
    }

    public void setInitialPos(final double x, final double y, final double z) {
        this.setPos(x, y, z);
        this.xo = x;
        this.yo = y;
        this.zo = z;
    }

    @Override
    protected Entity.MovementEmission getMovementEmission() {
        return Entity.MovementEmission.EVENTS;
    }

    @Override
    protected void defineSynchedData(final SynchedEntityData.Builder entityData) {
        super.defineSynchedData(entityData);
        entityData.define(DATA_ID_PADDLE_LEFT, false);
        entityData.define(DATA_ID_PADDLE_RIGHT, false);
        entityData.define(DATA_ID_BUBBLE_TIME, 0);
    }

    @Override
    public boolean canCollideWith(final Entity entity) {
        return canVehicleCollide(this, entity);
    }

    public static boolean canVehicleCollide(final Entity vehicle, final Entity entity) {
        return (entity.canBeCollidedWith(vehicle) || entity.isPushable()) && !vehicle.isPassengerOfSameVehicle(entity);
    }

    @Override
    public boolean canBeCollidedWith(final @Nullable Entity other) {
        return true;
    }

    @Override
    public boolean isPushable() {
        return true;
    }

    @Override
    public Vec3 getRelativePortalPosition(final Direction.Axis axis, final BlockUtil.FoundRectangle portalArea) {
        return LivingEntity.resetForwardDirectionOfRelativePortalPosition(super.getRelativePortalPosition(axis, portalArea));
    }

    protected abstract double rideHeight(final EntityDimensions dimensions);

    @Override
    protected Vec3 getPassengerAttachmentPoint(final Entity passenger, final EntityDimensions dimensions, final float scale) {
        float offset = this.getSinglePassengerXOffset();
        if (this.getPassengers().size() > 1) {
            int index = this.getPassengers().indexOf(passenger);
            if (index == 0) {
                offset = 0.2F;
            } else {
                offset = -0.6F;
            }

            if (passenger instanceof Animal) {
                offset += 0.2F;
            }
        }

        return new Vec3(0.0, this.rideHeight(dimensions), offset).yRot(-this.getYRot() * (float) (Math.PI / 180.0));
    }

    @Override
    public void onAboveBubbleColumn(final boolean dragDown, final BlockPos pos) {
        if (this.level() instanceof ServerLevel) {
            this.isAboveBubbleColumn = true;
            this.bubbleColumnDirectionIsDown = dragDown;
            if (this.getBubbleTime() == 0) {
                this.setBubbleTime(60);
            }
        }

        if (!this.isUnderWater() && this.random.nextInt(100) == 0) {
            this.level()
                .playLocalSound(
                    this.getX(), this.getY(), this.getZ(), this.getSwimSplashSound(), this.getSoundSource(), 1.0F, 0.8F + 0.4F * this.random.nextFloat(), false
                );
            this.level()
                .addParticle(
                    ParticleTypes.SPLASH, this.getX() + this.random.nextFloat(), this.getY() + 0.7, this.getZ() + this.random.nextFloat(), 0.0, 0.0, 0.0
                );
            this.gameEvent(GameEvent.SPLASH, this.getControllingPassenger());
        }
    }

    @Override
    public void push(final Entity entity) {
        // MODIFIED for porting: was VFP entity/legacy_boat_model MixinAbstractBoat#pushAwayFrom1_8
        // (@Inject HEAD cancellable). <= 1.8 boats have no boat specific push rules and use the plain
        // entity push instead.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) {
            super.push(entity);
            return;
        }

        if (entity instanceof AbstractBoat) {
            if (entity.getBoundingBox().minY < this.getBoundingBox().maxY) {
                super.push(entity);
            }
        } else if (entity.getBoundingBox().minY <= this.getBoundingBox().minY) {
            super.push(entity);
        }
    }

    @Override
    public void animateHurt(final float yaw) {
        this.setHurtDir(-this.getHurtDir());
        this.setHurtTime(10);
        this.setDamage(this.getDamage() * 11.0F);
    }

    @Override
    public boolean isPickable() {
        return !this.isRemoved();
    }

    @Override
    public InterpolationHandler getInterpolation() {
        // MODIFIED for porting: was VFP entity/legacy_boat_model MixinAbstractBoat#replaceInterpolation
        // (@Inject HEAD cancellable). <= 1.8 uses the 1.8 interpolator, which is stepped by the boat tick
        // instead of by InterpolationHandler#interpolate.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) {
            return this.vfpPositionInterpolator;
        }

        return this.interpolation;
    }

    // MODIFIED for porting: was VFP entity/legacy_boat_model MixinAbstractBoat#lerpMotion (merged override).
    // <= 1.8 remembers the velocity the server sent; PositionInterpolator1_8 hands it back as the boat's
    // delta movement while the client is not the controller.
    @Override
    public void lerpMotion(final Vec3 clientVelocity) {
        super.lerpMotion(clientVelocity);
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) {
            this.vfpBoatVelocity = clientVelocity;
        }
    }

    @Override
    public Direction getMotionDirection() {
        return this.getDirection().getClockWise();
    }

    @Override
    public void tick() {
        // MODIFIED for porting: was VFP entity/legacy_boat_model MixinAbstractBoat#tick1_8
        // (@Inject HEAD cancellable). <= 1.8 replaces the whole boat tick with the 1.8 boat physics.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) {
            this.vfpTick1_8();
            return;
        }

        this.oldStatus = this.status;
        this.status = this.getStatus();
        if (this.status != AbstractBoat.Status.UNDER_WATER && this.status != AbstractBoat.Status.UNDER_FLOWING_WATER) {
            this.outOfControlTicks = 0.0F;
        } else {
            this.outOfControlTicks++;
        }

        if (!this.level().isClientSide() && this.outOfControlTicks >= 60.0F) {
            this.ejectPassengers();
        }

        if (this.getHurtTime() > 0) {
            this.setHurtTime(this.getHurtTime() - 1);
        }

        if (this.getDamage() > 0.0F) {
            this.setDamage(this.getDamage() - 1.0F);
        }

        super.tick();
        this.interpolation.interpolate();
        if (this.isLocalInstanceAuthoritative()) {
            if (!(this.getFirstPassenger() instanceof Player)) {
                this.setPaddleState(false, false);
            }

            this.floatBoat();
            if (this.level().isClientSide()) {
                this.controlBoat();
                this.level().sendPacketToServer(new ServerboundPaddleBoatPacket(this.getPaddleState(0), this.getPaddleState(1)));
            }

            this.move(MoverType.SELF, this.getDeltaMovement());
        } else {
            this.setDeltaMovement(Vec3.ZERO);
        }

        this.applyEffectsFromBlocks();
        this.applyEffectsFromBlocks();
        this.tickBubbleColumn();

        for (int i = 0; i <= 1; i++) {
            if (this.getPaddleState(i)) {
                if (!this.isSilent()
                    && this.paddlePositions[i] % (float) (Math.PI * 2) <= (float) (Math.PI / 4)
                    && (this.paddlePositions[i] + (float) (Math.PI / 8)) % (float) (Math.PI * 2) >= (float) (Math.PI / 4)) {
                    SoundEvent sound = this.getPaddleSound();
                    if (sound != null) {
                        Vec3 viewVector = this.getViewVector(1.0F);
                        double dx = i == 1 ? -viewVector.z : viewVector.z;
                        double dz = i == 1 ? viewVector.x : -viewVector.x;
                        this.level()
                            .playSound(
                                null,
                                this.getX() + dx,
                                this.getY(),
                                this.getZ() + dz,
                                sound,
                                this.getSoundSource(),
                                1.0F,
                                0.8F + 0.4F * this.random.nextFloat()
                            );
                    }
                }

                this.paddlePositions[i] = this.paddlePositions[i] + (float) (Math.PI / 8);
            } else {
                this.paddlePositions[i] = 0.0F;
            }
        }

        // MODIFIED for porting: lithium entity.collisions.unpushable_cramming AbstractBoatMixin#getOtherPushableEntities
        List<Entity> entities = Entity.lithium$getOtherPushableEntities(
            this.level(), this, this.getBoundingBox().inflate(0.2F, -0.01F, 0.2F), EntitySelector.pushableBy(this)
        );
        if (!entities.isEmpty()) {
            boolean addNewPassengers = !this.level().isClientSide() && !(this.getControllingPassenger() instanceof Player);

            for (Entity entity : entities) {
                if (!entity.hasPassenger(this)) {
                    if (addNewPassengers
                        && this.getPassengers().size() < this.getMaxPassengers()
                        && !entity.isPassenger()
                        && this.hasEnoughSpaceFor(entity)
                        && entity instanceof LivingEntity
                        && !entity.is(EntityTypeTags.CANNOT_BE_PUSHED_ONTO_BOATS)) {
                        entity.startRiding(this);
                    } else {
                        this.push(entity);
                    }
                }
            }
        }
    }

    // MODIFIED for porting: was VFP entity/legacy_boat_model MixinAbstractBoat#tick1_8 body.
    // The 1.8 and older boat physics: five slice submersion sampling, splash particles above 0.2625
    // (<= 1.7.6) / 0.2975, client side interpolation stepping or dead reckoning, buoyancy, passenger
    // acceleration in three eras, the 0.35 speed clamp with the speed multiplier ramp, snow / lily pad
    // clearing (> 1.6.4 only) and the +-20 degree yaw follow of the travel direction.
    private void vfpTick1_8() {
        super.tick();
        if (this.getHurtTime() > 0) {
            this.setHurtTime(this.getHurtTime() - 1);
        }

        if (this.getDamage() > 0) {
            this.setDamage(this.getDamage() - 1);
        }

        this.xo = this.getX();
        this.yo = this.getY();
        this.zo = this.getZ();
        final int yPartitions = 5;
        double percentSubmerged = 0;

        for (int partitionIndex = 0; partitionIndex < yPartitions; partitionIndex++) {
            final double minY = this.getBoundingBox().minY + this.getBoundingBox().getYsize() * partitionIndex / yPartitions - 0.125;
            final double maxY = this.getBoundingBox().minY + this.getBoundingBox().getYsize() * (partitionIndex + 1) / yPartitions - 0.125;
            final AABB box = new AABB(
                this.getBoundingBox().minX, minY, this.getBoundingBox().minZ, this.getBoundingBox().maxX, maxY, this.getBoundingBox().maxZ
            );
            if (BlockPos.betweenClosedStream(box).anyMatch(pos -> this.level().getFluidState(pos).is(FluidTags.WATER))) {
                percentSubmerged += 1.0 / yPartitions;
            }
        }

        final double oldHorizontalSpeed = this.getDeltaMovement().horizontalDistance();
        if (oldHorizontalSpeed > (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_7_6) ? 0.2625D : 0.2975D)) {
            final double rx = Math.cos(this.getYRot() * Math.PI / 180);
            final double rz = Math.sin(this.getYRot() * Math.PI / 180);

            for (int i = 0; i < 1 + oldHorizontalSpeed * 60; i++) {
                final double dForward = this.random.nextFloat() * 2 - 1;
                final double dSideways = (this.random.nextInt(2) * 2 - 1) * 0.7D;
                if (this.random.nextBoolean()) {
                    final double x = this.getX() - rx * dForward * 0.8 + rz * dSideways;
                    final double z = this.getZ() - rz * dForward * 0.8 - rx * dSideways;
                    this.level()
                        .addParticle(
                            ParticleTypes.SPLASH,
                            x,
                            this.getY() - 0.125D,
                            z,
                            this.getDeltaMovement().x,
                            this.getDeltaMovement().y,
                            this.getDeltaMovement().z
                        );
                } else {
                    final double x = this.getX() + rx + rz * dForward * 0.7;
                    final double z = this.getZ() + rz - rx * dForward * 0.7;
                    this.level()
                        .addParticle(
                            ParticleTypes.SPLASH,
                            x,
                            this.getY() - 0.125D,
                            z,
                            this.getDeltaMovement().x,
                            this.getDeltaMovement().y,
                            this.getDeltaMovement().z
                        );
                }
            }
        }

        if (this.level().isClientSide() && !this.isVehicle()) {
            if (this.vfpBoatInterpolationSteps > 0) {
                final InterpolationHandler.InterpolationData data = this.vfpPositionInterpolator.interpolationData;
                final double newX = this.getX() + (data.position.x - this.getX()) / this.vfpBoatInterpolationSteps;
                final double newY = this.getY() + (data.position.y - this.getY()) / this.vfpBoatInterpolationSteps;
                final double newZ = this.getZ() + (data.position.z - this.getZ()) / this.vfpBoatInterpolationSteps;
                final double newYaw = this.getYRot() + Mth.wrapDegrees(data.yRot - this.getYRot()) / this.vfpBoatInterpolationSteps;
                final double newPitch = this.getXRot() + (data.xRot - this.getXRot()) / this.vfpBoatInterpolationSteps;
                this.vfpBoatInterpolationSteps--;
                this.setPos(newX, newY, newZ);
                this.setRot((float) newYaw, (float) newPitch);
            } else {
                this.setPos(
                    this.getX() + this.getDeltaMovement().x, this.getY() + this.getDeltaMovement().y, this.getZ() + this.getDeltaMovement().z
                );
                if (this.onGround()) {
                    this.setDeltaMovement(this.getDeltaMovement().scale(0.5D));
                }

                this.setDeltaMovement(this.getDeltaMovement().multiply(0.99D, 0.95D, 0.99D));
            }
        } else {
            if (percentSubmerged < 1) {
                final double normalizedDistanceFromMiddle = percentSubmerged * 2 - 1;
                this.setDeltaMovement(this.getDeltaMovement().add(0, 0.04D * normalizedDistanceFromMiddle, 0));
            } else {
                if (this.getDeltaMovement().y < 0) {
                    this.setDeltaMovement(this.getDeltaMovement().multiply(1, 0.5D, 1));
                }

                this.setDeltaMovement(this.getDeltaMovement().add(0, 0.007D, 0));
            }

            if (this.getControllingPassenger() != null) {
                final LivingEntity passenger = this.getControllingPassenger();
                if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(LegacyProtocolVersion.r1_5_2)) {
                    final double xAcceleration = passenger.getDeltaMovement().x * this.vfpSpeedMultiplier;
                    final double zAcceleration = passenger.getDeltaMovement().z * this.vfpSpeedMultiplier;
                    this.setDeltaMovement(this.getDeltaMovement().add(xAcceleration, 0, zAcceleration));
                } else if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(LegacyProtocolVersion.r1_6_4)) {
                    if (passenger.zza > 0) {
                        final double xAcceleration = -Math.sin(passenger.getYRot() * Math.PI / 180) * this.vfpSpeedMultiplier * 0.05D;
                        final double zAcceleration = Math.cos(passenger.getYRot() * Math.PI / 180) * this.vfpSpeedMultiplier * 0.05D;
                        this.setDeltaMovement(this.getDeltaMovement().add(xAcceleration, 0, zAcceleration));
                    }
                } else {
                    final float boatAngle = passenger.getYRot() - passenger.xxa * 90.0F;
                    final double xAcceleration = -Math.sin(boatAngle * Math.PI / 180) * this.vfpSpeedMultiplier * passenger.zza * 0.05D;
                    final double zAcceleration = Math.cos(boatAngle * Math.PI / 180) * this.vfpSpeedMultiplier * passenger.zza * 0.05D;
                    this.setDeltaMovement(this.getDeltaMovement().add(xAcceleration, 0, zAcceleration));
                }
            }

            double newHorizontalSpeed = this.getDeltaMovement().horizontalDistance();
            if (newHorizontalSpeed > 0.35D) {
                final double multiplier = 0.35D / newHorizontalSpeed;
                this.setDeltaMovement(this.getDeltaMovement().multiply(multiplier, 1, multiplier));
                newHorizontalSpeed = 0.35D;
            }

            if (newHorizontalSpeed > oldHorizontalSpeed && this.vfpSpeedMultiplier < 0.35D) {
                this.vfpSpeedMultiplier = this.vfpSpeedMultiplier + (0.35D - this.vfpSpeedMultiplier) / 35;
                if (this.vfpSpeedMultiplier > 0.35D) {
                    this.vfpSpeedMultiplier = 0.35D;
                }
            } else {
                this.vfpSpeedMultiplier = this.vfpSpeedMultiplier - (this.vfpSpeedMultiplier - 0.07D) / 35;
                if (this.vfpSpeedMultiplier < 0.07D) {
                    this.vfpSpeedMultiplier = 0.07D;
                }
            }

            if (ProtocolTranslator.getTargetVersion().newerThan(LegacyProtocolVersion.r1_6_4)) {
                for (int i = 0; i < 4; i++) {
                    final int dx = Mth.floor(this.getX() + ((i % 2) - 0.5D) * 0.8D);
                    //noinspection IntegerDivisionInFloatingPointContext
                    final int dz = Mth.floor(this.getZ() + ((i / 2) - 0.5D) * 0.8D);

                    for (int ddy = 0; ddy < 2; ddy++) {
                        final int dy = Mth.floor(this.getY()) + ddy;
                        final BlockPos pos = new BlockPos(dx, dy, dz);
                        final Block block = this.level().getBlockState(pos).getBlock();
                        if (block == Blocks.SNOW) {
                            this.level().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                            this.horizontalCollision = false;
                        } else if (block == Blocks.LILY_PAD) {
                            this.level().destroyBlock(pos, true);
                            this.horizontalCollision = false;
                        }
                    }
                }
            }

            if (this.onGround()) {
                this.setDeltaMovement(this.getDeltaMovement().scale(0.5D));
            }

            this.move(MoverType.SELF, this.getDeltaMovement());
            if (!this.horizontalCollision || oldHorizontalSpeed <= 0.2975D) {
                this.setDeltaMovement(this.getDeltaMovement().multiply(0.99D, 0.95D, 0.99D));
            }

            this.setXRot(0);
            final double deltaX = this.xo - this.getX();
            final double deltaZ = this.zo - this.getZ();
            if (deltaX * deltaX + deltaZ * deltaZ > 0.001D) {
                final double yawDelta = Mth.clamp(Mth.wrapDegrees(Mth.atan2(deltaZ, deltaX) * 180 / Math.PI - this.getYRot()), -20, 20);
                this.setYRot((float) (this.getYRot() + yawDelta));
            }
        }
    }

    private void tickBubbleColumn() {
        if (this.level().isClientSide()) {
            int clientBubbleTime = this.getBubbleTime();
            if (clientBubbleTime > 0) {
                this.bubbleMultiplier += 0.05F;
            } else {
                this.bubbleMultiplier -= 0.1F;
            }

            this.bubbleMultiplier = Mth.clamp(this.bubbleMultiplier, 0.0F, 1.0F);
            this.bubbleAngleO = this.bubbleAngle;
            this.bubbleAngle = 10.0F * (float)Math.sin(0.5 * this.tickCount) * this.bubbleMultiplier;
        } else {
            if (!this.isAboveBubbleColumn) {
                this.setBubbleTime(0);
            }

            int bubbleTime = this.getBubbleTime();
            if (bubbleTime > 0) {
                this.setBubbleTime(--bubbleTime);
                int diff = 60 - bubbleTime - 1;
                if (diff > 0 && bubbleTime == 0) {
                    this.setBubbleTime(0);
                    Vec3 movement = this.getDeltaMovement();
                    if (this.bubbleColumnDirectionIsDown) {
                        this.setDeltaMovement(movement.add(0.0, -0.7, 0.0));
                        this.ejectPassengers();
                    } else {
                        this.setDeltaMovement(movement.x, this.hasPassenger(e -> e instanceof Player) ? 2.7 : 0.6, movement.z);
                    }
                }

                this.isAboveBubbleColumn = false;
            }
        }
    }

    protected @Nullable SoundEvent getPaddleSound() {
        return switch (this.getStatus()) {
            case IN_WATER, UNDER_WATER, UNDER_FLOWING_WATER -> SoundEvents.BOAT_PADDLE_WATER;
            case ON_LAND -> SoundEvents.BOAT_PADDLE_LAND;
            default -> null;
        };
    }

    public void setPaddleState(final boolean left, final boolean right) {
        this.entityData.set(DATA_ID_PADDLE_LEFT, left);
        this.entityData.set(DATA_ID_PADDLE_RIGHT, right);
    }

    public float getRowingTime(final int side, final float a) {
        return this.getPaddleState(side) ? Mth.clampedLerp(a, this.paddlePositions[side] - (float) (Math.PI / 8), this.paddlePositions[side]) : 0.0F;
    }

    @Override
    public Leashable.@Nullable LeashData getLeashData() {
        return this.leashData;
    }

    @Override
    public void setLeashData(final Leashable.@Nullable LeashData leashData) {
        this.leashData = leashData;
    }

    @Override
    public Vec3 getLeashOffset() {
        return new Vec3(0.0, 0.88F * this.getBbHeight(), 0.64F * this.getBbWidth());
    }

    @Override
    public boolean supportQuadLeash() {
        return true;
    }

    @Override
    public Vec3[] getQuadLeashOffsets() {
        return Leashable.createQuadLeashOffsets(this, 0.0, 0.64, 0.382, 0.88);
    }

    private AbstractBoat.Status getStatus() {
        AbstractBoat.Status waterStatus = this.isUnderwater();
        if (waterStatus != null) {
            this.waterLevel = this.getBoundingBox().maxY;
            return waterStatus;
        } else if (this.checkInWater()) {
            return AbstractBoat.Status.IN_WATER;
        } else {
            float friction = this.getGroundFriction();
            if (friction > 0.0F) {
                this.landFriction = friction;
                return AbstractBoat.Status.ON_LAND;
            } else {
                return AbstractBoat.Status.IN_AIR;
            }
        }
    }

    public float getWaterLevelAbove() {
        AABB aabb = this.getBoundingBox();
        int minX = Mth.floor(aabb.minX);
        int maxX = Mth.ceil(aabb.maxX);
        int minY = Mth.floor(aabb.maxY);
        int maxY = Mth.ceil(aabb.maxY - this.lastYd);
        int minZ = Mth.floor(aabb.minZ);
        int maxZ = Mth.ceil(aabb.maxZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        label39:
        for (int y = minY; y < maxY; y++) {
            float blockHeight = 0.0F;

            for (int x = minX; x < maxX; x++) {
                for (int z = minZ; z < maxZ; z++) {
                    pos.set(x, y, z);
                    FluidState fluidState = this.level().getFluidState(pos);
                    if (fluidState.is(FluidTags.WATER)) {
                        blockHeight = Math.max(blockHeight, fluidState.getHeight(this.level(), pos));
                    }

                    if (blockHeight >= 1.0F) {
                        continue label39;
                    }
                }
            }

            if (blockHeight < 1.0F) {
                return pos.getY() + blockHeight;
            }
        }

        return maxY + 1;
    }

    public float getGroundFriction() {
        AABB bb = this.getBoundingBox();
        AABB box = new AABB(bb.minX, bb.minY - 0.001, bb.minZ, bb.maxX, bb.minY, bb.maxZ);
        int x0 = Mth.floor(box.minX) - 1;
        int x1 = Mth.ceil(box.maxX) + 1;
        int y0 = Mth.floor(box.minY) - 1;
        int y1 = Mth.ceil(box.maxY) + 1;
        int z0 = Mth.floor(box.minZ) - 1;
        int z1 = Mth.ceil(box.maxZ) + 1;
        VoxelShape boatShape = Shapes.create(box);
        float friction = 0.0F;
        int count = 0;
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();

        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                int edges = (x != x0 && x != x1 - 1 ? 0 : 1) + (z != z0 && z != z1 - 1 ? 0 : 1);
                if (edges != 2) {
                    for (int y = y0; y < y1; y++) {
                        if (edges <= 0 || y != y0 && y != y1 - 1) {
                            blockPos.set(x, y, z);
                            BlockState blockState = this.level().getBlockState(blockPos);
                            if (!(blockState.getBlock() instanceof LilyPadBlock)
                                && Shapes.joinIsNotEmpty(blockState.getCollisionShape(this.level(), blockPos).move(blockPos), boatShape, BooleanOp.AND)) {
                                friction += blockState.getBlock().getFriction();
                                count++;
                            }
                        }
                    }
                }
            }
        }

        return friction / count;
    }

    private boolean checkInWater() {
        AABB bb = this.getBoundingBox();
        int minX = Mth.floor(bb.minX);
        int maxX = Mth.ceil(bb.maxX);
        int minY = Mth.floor(bb.minY);
        int maxY = Mth.ceil(bb.minY + 0.001);
        int minZ = Mth.floor(bb.minZ);
        int maxZ = Mth.ceil(bb.maxZ);
        boolean inWater = false;
        this.waterLevel = -Double.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    pos.set(x, y, z);
                    FluidState fluidState = this.level().getFluidState(pos);
                    if (fluidState.is(FluidTags.WATER)) {
                        float height = y + fluidState.getHeight(this.level(), pos);
                        this.waterLevel = Math.max(height, this.waterLevel);
                        inWater |= bb.minY < height;
                    }
                }
            }
        }

        return inWater;
    }

    private AbstractBoat.@Nullable Status isUnderwater() {
        AABB aabb = this.getBoundingBox();
        double maxY = aabb.maxY + 0.001;
        int x0 = Mth.floor(aabb.minX);
        int x1 = Mth.ceil(aabb.maxX);
        int y0 = Mth.floor(aabb.maxY);
        int y1 = Mth.ceil(maxY);
        int z0 = Mth.floor(aabb.minZ);
        int z1 = Mth.ceil(aabb.maxZ);
        boolean underWater = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                for (int z = z0; z < z1; z++) {
                    pos.set(x, y, z);
                    FluidState fluidState = this.level().getFluidState(pos);
                    if (fluidState.is(FluidTags.WATER) && maxY < pos.getY() + fluidState.getHeight(this.level(), pos)) {
                        if (!fluidState.isSource()) {
                            return AbstractBoat.Status.UNDER_FLOWING_WATER;
                        }

                        underWater = true;
                    }
                }
            }
        }

        return underWater ? AbstractBoat.Status.UNDER_WATER : null;
    }

    @Override
    protected double getDefaultGravity() {
        return 0.04;
    }

    private void floatBoat() {
        double vspeed = -this.getGravity();
        double buoyancy = 0.0;
        float invFriction = 0.05F;
        if (this.oldStatus == AbstractBoat.Status.IN_AIR && this.status != AbstractBoat.Status.IN_AIR && this.status != AbstractBoat.Status.ON_LAND) {
            this.waterLevel = this.getY(1.0);
            double targetY = this.getWaterLevelAbove() - this.getBbHeight() + 0.101;
            // MODIFIED for porting: was VFP movement/collision MixinAbstractBoat#alwaysUpdatePosition
            // (@Redirect on Level#noCollision). <= 1.20.5 snapped the boat to the water surface without
            // testing the target box for collisions.
            if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_20_5)
                || this.level().noCollision(this, this.getBoundingBox().move(0.0, targetY - this.getY(), 0.0))) {
                this.setPos(this.getX(), targetY, this.getZ());
                this.setDeltaMovement(this.getDeltaMovement().multiply(1.0, 0.0, 1.0));
                this.lastYd = 0.0;
            }

            this.status = AbstractBoat.Status.IN_WATER;
        } else {
            if (this.status == AbstractBoat.Status.IN_WATER) {
                buoyancy = (this.waterLevel - this.getY()) / this.getBbHeight();
                invFriction = 0.9F;
            } else if (this.status == AbstractBoat.Status.UNDER_FLOWING_WATER) {
                vspeed = -7.0E-4;
                invFriction = 0.9F;
            } else if (this.status == AbstractBoat.Status.UNDER_WATER) {
                buoyancy = 0.01F;
                invFriction = 0.45F;
            } else if (this.status == AbstractBoat.Status.IN_AIR) {
                invFriction = 0.9F;
            } else if (this.status == AbstractBoat.Status.ON_LAND) {
                invFriction = this.landFriction;
                if (this.getControllingPassenger() instanceof Player) {
                    this.landFriction /= 2.0F;
                }
            }

            Vec3 movement = this.getDeltaMovement();
            this.setDeltaMovement(movement.x * invFriction, movement.y + vspeed, movement.z * invFriction);
            this.deltaRotation *= invFriction;
            if (buoyancy > 0.0) {
                Vec3 deltaMovement = this.getDeltaMovement();
                this.setDeltaMovement(deltaMovement.x, (deltaMovement.y + buoyancy * (this.getDefaultGravity() / 0.65)) * 0.75, deltaMovement.z);
            }
        }
    }

    @Override
    protected float getAirDrag() {
        return 1.0F;
    }

    private void controlBoat() {
        if (this.isVehicle()) {
            float acceleration = 0.0F;
            if (this.inputLeft) {
                this.deltaRotation--;
            }

            if (this.inputRight) {
                this.deltaRotation++;
            }

            if (this.inputRight != this.inputLeft && !this.inputUp && !this.inputDown) {
                acceleration += 0.005F;
            }

            this.setYRot(this.getYRot() + this.deltaRotation);
            if (this.inputUp) {
                acceleration += 0.04F;
            }

            if (this.inputDown) {
                acceleration -= 0.005F;
            }

            this.setDeltaMovement(
                this.getDeltaMovement()
                    .add(
                        Mth.sin(-this.getYRot() * (float) (Math.PI / 180.0)) * acceleration,
                        0.0,
                        Mth.cos(this.getYRot() * (float) (Math.PI / 180.0)) * acceleration
                    )
            );
            this.setPaddleState(this.inputRight && !this.inputLeft || this.inputUp, this.inputLeft && !this.inputRight || this.inputUp);
        }
    }

    protected float getSinglePassengerXOffset() {
        return 0.0F;
    }

    public boolean hasEnoughSpaceFor(final Entity entity) {
        return entity.getBbWidth() < this.getBbWidth();
    }

    @Override
    protected void positionRider(final Entity passenger, final Entity.MoveFunction moveFunction) {
        // MODIFIED for porting: was VFP entity/dimensions MixinAbstractBoat#updatePassengerPosition1_8
        // (@Inject HEAD cancellable). <= 1.8 seats boat passengers with the pre-1.20.2 riding offsets and
        // skips the vanilla yaw carry, rotation clamp and multi-passenger body rotation offset.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) {
            final Vec3 newPosition = EntityRidingOffsetsPre1_20_2.getMountedHeightOffset(this, passenger).add(this.position());
            moveFunction.accept(
                passenger, newPosition.x, newPosition.y + EntityRidingOffsetsPre1_20_2.getHeightOffset(passenger), newPosition.z
            );
            return;
        }

        super.positionRider(passenger, moveFunction);
        if (!passenger.is(EntityTypeTags.CAN_TURN_IN_BOATS)) {
            passenger.setYRot(passenger.getYRot() + this.deltaRotation);
            passenger.setYHeadRot(passenger.getYHeadRot() + this.deltaRotation);
            this.clampRotation(passenger);
            if (passenger instanceof Animal animal && this.getPassengers().size() == this.getMaxPassengers()) {
                int rotationOffset = passenger.getId() % 2 == 0 ? 90 : 270;
                passenger.setYBodyRot(animal.yBodyRot + rotationOffset);
                passenger.setYHeadRot(passenger.getYHeadRot() + rotationOffset);
            }
        }
    }

    @Override
    public Vec3 getDismountLocationForPassenger(final LivingEntity passenger) {
        Vec3 direction = getCollisionHorizontalEscapeVector(this.getBbWidth() * Mth.SQRT_OF_TWO, passenger.getBbWidth(), passenger.getYRot());
        double targetX = this.getX() + direction.x;
        double targetZ = this.getZ() + direction.z;
        BlockPos targetBlockPos = BlockPos.containing(targetX, this.getBoundingBox().maxY, targetZ);
        BlockPos belowBlockPos = targetBlockPos.below();
        if (!this.level().isWaterAt(belowBlockPos)) {
            List<Vec3> targets = Lists.newArrayList();
            double targetFloor = this.level().getBlockFloorHeight(targetBlockPos);
            if (DismountHelper.isBlockFloorValid(targetFloor)) {
                targets.add(new Vec3(targetX, targetBlockPos.getY() + targetFloor, targetZ));
            }

            double belowFloor = this.level().getBlockFloorHeight(belowBlockPos);
            if (DismountHelper.isBlockFloorValid(belowFloor)) {
                targets.add(new Vec3(targetX, belowBlockPos.getY() + belowFloor, targetZ));
            }

            for (Pose dismountPose : passenger.getDismountPoses()) {
                for (Vec3 target : targets) {
                    if (DismountHelper.canDismountTo(this.level(), target, passenger, dismountPose)) {
                        passenger.setPose(dismountPose);
                        return target;
                    }
                }
            }
        }

        return super.getDismountLocationForPassenger(passenger);
    }

    protected void clampRotation(final Entity passenger) {
        passenger.setYBodyRot(this.getYRot());
        float delta = Mth.wrapDegrees(passenger.getYRot() - this.getYRot());
        float targetDelta = Mth.clamp(delta, -105.0F, 105.0F);
        passenger.yRotO += targetDelta - delta;
        passenger.setYRot(passenger.getYRot() + targetDelta - delta);
        passenger.setYHeadRot(passenger.getYRot());
    }

    @Override
    public void onPassengerTurned(final Entity passenger) {
        // MODIFIED for porting: was VFP entity/legacy_boat_model MixinAbstractBoat#onPassengerLookAround1_8
        // (@Inject HEAD cancellable). <= 1.8 boats do not clamp the passenger's rotation.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) {
            super.onPassengerTurned(passenger);
            return;
        }

        this.clampRotation(passenger);
    }

    @Override
    protected void addAdditionalSaveData(final ValueOutput output) {
        this.writeLeashData(output, this.leashData);
    }

    @Override
    protected void readAdditionalSaveData(final ValueInput input) {
        this.readLeashData(input);
    }

    @Override
    public InteractionResult interact(final Player player, final InteractionHand hand, final Vec3 location) {
        // MODIFIED for porting: was VFP entity/interaction MixinAbstractBoat#makeNotLeashable
        // (@Redirect on VehicleEntity#interact). <= 1.20.5 boats cannot be leashed, so the inherited
        // Entity/Leashable leash handling is skipped and the interaction falls through to the ride path.
        InteractionResult superInteraction = ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_20_5)
            ? InteractionResult.PASS
            : super.interact(player, hand, location);
        if (superInteraction != InteractionResult.PASS) {
            return superInteraction;
        } else {
            return player.isSecondaryUseActive() || !(this.outOfControlTicks < 60.0F) || !this.level().isClientSide() && !player.startRiding(this)
                ? InteractionResult.PASS
                : InteractionResult.SUCCESS;
        }
    }

    @Override
    public void remove(final Entity.RemovalReason reason) {
        if (!this.level().isClientSide() && reason.shouldDestroy() && this.isLeashed()) {
            this.dropLeash();
        }

        super.remove(reason);
    }

    @Override
    protected void checkFallDamage(final double ya, final boolean onGround, final BlockState onState, final BlockPos pos) {
        // MODIFIED for porting: was VFP entity/legacy_boat_model MixinAbstractBoat#fall1_8
        // (@Inject HEAD cancellable). <= 1.6.4 has no boat specific fall handling at all; 1.7 - 1.8 keeps
        // the vanilla body but puts the boat onto land first.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(LegacyProtocolVersion.r1_6_4)) {
            super.checkFallDamage(ya, onGround, onState, pos);
            return;
        }

        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) {
            this.status = AbstractBoat.Status.ON_LAND;
        }

        this.lastYd = this.getDeltaMovement().y;
        if (!this.isPassenger()) {
            if (onGround) {
                this.resetFallDistance();
            } else if (!this.level().getFluidState(this.blockPosition().below()).is(FluidTags.WATER) && ya < 0.0) {
                this.fallDistance -= (float)ya;
            }
        }
    }

    public boolean getPaddleState(final int side) {
        return this.entityData.get(side == 0 ? DATA_ID_PADDLE_LEFT : DATA_ID_PADDLE_RIGHT) && this.getControllingPassenger() != null;
    }

    private void setBubbleTime(final int val) {
        this.entityData.set(DATA_ID_BUBBLE_TIME, val);
    }

    private int getBubbleTime() {
        return this.entityData.get(DATA_ID_BUBBLE_TIME);
    }

    public float getBubbleAngle(final float a) {
        return Mth.lerp(a, this.bubbleAngleO, this.bubbleAngle);
    }

    @Override
    protected boolean canAddPassenger(final Entity passenger) {
        // MODIFIED for porting: was VFP entity/legacy_boat_model MixinAbstractBoat#canAddPassenger1_8
        // (@Inject HEAD cancellable). <= 1.8 boats seat a single passenger and have no eye-in-water check.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) {
            return super.canAddPassenger(passenger);
        }

        return this.getPassengers().size() < this.getMaxPassengers() && !this.isEyeInFluid(FluidTags.WATER);
    }

    protected int getMaxPassengers() {
        return 2;
    }

    @Override
    public @Nullable LivingEntity getControllingPassenger() {
        return this.getFirstPassenger() instanceof LivingEntity passenger ? passenger : super.getControllingPassenger();
    }

    public void setInput(final boolean left, final boolean right, final boolean up, final boolean down) {
        this.inputLeft = left;
        this.inputRight = right;
        this.inputUp = up;
        this.inputDown = down;
    }

    @Override
    public boolean isUnderWater() {
        return this.status == AbstractBoat.Status.UNDER_WATER || this.status == AbstractBoat.Status.UNDER_FLOWING_WATER;
    }

    @Override
    protected final Item getDropItem() {
        return this.dropItem.get();
    }

    @Override
    public final ItemStack getPickResult() {
        return new ItemStack(this.dropItem.get());
    }

    @Override
    protected @Nullable AABB modifyPassengerFluidInteractionBox(final AABB passengerBox) {
        if (this.isUnderWater()) {
            return passengerBox;
        }

        AABB boatBox = this.getBoundingBox();
        if (boatBox.maxY >= passengerBox.maxY) {
            return null;
        }

        double minY = Math.max(passengerBox.minY, boatBox.maxY);
        return new AABB(passengerBox.minX, minY, passengerBox.minZ, passengerBox.maxX, passengerBox.maxY, passengerBox.maxZ);
    }

    // MODIFIED for porting: was VFP entity/legacy_boat_model MixinAbstractBoat IAbstractBoat implementation.
    // PositionInterpolator1_8 writes the interpolation step count and the last server velocity through
    // these three methods, and the <= 1.8 tick above reads them back.
    @Override
    public void viaFabricPlus$setBoatInterpolationSteps(final int steps) {
        this.vfpBoatInterpolationSteps = steps;
    }

    @Override
    public Vec3 viaFabricPlus$getBoatVelocity() {
        return this.vfpBoatVelocity;
    }

    @Override
    public void viaFabricPlus$setBoatVelocity(final Vec3 velocity) {
        this.vfpBoatVelocity = velocity;
    }

    public enum Status {
        IN_WATER,
        UNDER_WATER,
        UNDER_FLOWING_WATER,
        ON_LAND,
        IN_AIR;
    }
}