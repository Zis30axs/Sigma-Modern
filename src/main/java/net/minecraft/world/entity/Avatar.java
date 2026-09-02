package net.minecraft.world.entity;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public abstract class Avatar extends LivingEntity {
    public static final HumanoidArm DEFAULT_MAIN_HAND = HumanoidArm.RIGHT;
    public static final int DEFAULT_MODEL_CUSTOMIZATION = 0;
    public static final float DEFAULT_EYE_HEIGHT = 1.62F;
    public static final Vec3 DEFAULT_VEHICLE_ATTACHMENT = new Vec3(0.0, 0.6, 0.0);
    private static final float CROUCH_BB_HEIGHT = 1.5F;
    private static final float SWIMMING_BB_WIDTH = 0.6F;
    public static final float SWIMMING_BB_HEIGHT = 0.6F;
    protected static final EntityDimensions STANDING_DIMENSIONS = EntityDimensions.scalable(0.6F, 1.8F)
        .withEyeHeight(1.62F)
        .withAttachments(EntityAttachments.builder().attach(EntityAttachment.VEHICLE, DEFAULT_VEHICLE_ATTACHMENT));
    protected static final Map<Pose, EntityDimensions> POSES = ImmutableMap.<Pose, EntityDimensions>builder()
        .put(Pose.STANDING, STANDING_DIMENSIONS)
        .put(Pose.SLEEPING, SLEEPING_DIMENSIONS)
        .put(Pose.FALL_FLYING, EntityDimensions.scalable(0.6F, 0.6F).withEyeHeight(0.4F))
        .put(Pose.SWIMMING, EntityDimensions.scalable(0.6F, 0.6F).withEyeHeight(0.4F))
        .put(Pose.SPIN_ATTACK, EntityDimensions.scalable(0.6F, 0.6F).withEyeHeight(0.4F))
        .put(
            Pose.CROUCHING,
            EntityDimensions.scalable(0.6F, 1.5F)
                .withEyeHeight(1.27F)
                .withAttachments(EntityAttachments.builder().attach(EntityAttachment.VEHICLE, DEFAULT_VEHICLE_ATTACHMENT))
        )
        .put(Pose.DYING, EntityDimensions.fixed(0.2F, 0.2F).withEyeHeight(1.62F))
        .build();
    // MODIFIED for porting: was VFP movement.constants MixinAvatar#viaFabricPlus$sneaking_dimensions_v1_13_2 / _v1_8 (@Unique constants)
    private static final EntityDimensions vfpSneakingDimensionsV1_13_2 = EntityDimensions.scalable(0.6F, 1.65F)
        .withEyeHeight(1.54F)
        .withAttachments(EntityAttachments.builder().attach(EntityAttachment.VEHICLE, DEFAULT_VEHICLE_ATTACHMENT));
    private static final EntityDimensions vfpSneakingDimensionsV1_8 = EntityDimensions.scalable(0.6F, 1.8F)
        .withEyeHeight(1.54F)
        .withAttachments(EntityAttachments.builder().attach(EntityAttachment.VEHICLE, DEFAULT_VEHICLE_ATTACHMENT));
    protected static final EntityDataAccessor<HumanoidArm> DATA_PLAYER_MAIN_HAND = SynchedEntityData.defineId(Avatar.class, EntityDataSerializers.HUMANOID_ARM);
    protected static final EntityDataAccessor<Byte> DATA_PLAYER_MODE_CUSTOMISATION = SynchedEntityData.defineId(Avatar.class, EntityDataSerializers.BYTE);

    protected Avatar(final EntityType<? extends LivingEntity> type, final Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(final SynchedEntityData.Builder entityData) {
        super.defineSynchedData(entityData);
        entityData.define(DATA_PLAYER_MAIN_HAND, DEFAULT_MAIN_HAND);
        entityData.define(DATA_PLAYER_MODE_CUSTOMISATION, (byte)0);
    }

    @Override
    public HumanoidArm getMainArm() {
        return this.entityData.get(DATA_PLAYER_MAIN_HAND);
    }

    public void setMainArm(final HumanoidArm mainArm) {
        this.entityData.set(DATA_PLAYER_MAIN_HAND, mainArm);
    }

    public boolean isModelPartShown(final PlayerModelPart part) {
        return (this.getEntityData().get(DATA_PLAYER_MODE_CUSTOMISATION) & part.getMask()) == part.getMask();
    }

    @Override
    public EntityDimensions getDefaultDimensions(final Pose pose) {
        // MODIFIED for porting: was VFP movement.constants MixinAvatar#modifyDimensions (@Inject HEAD cancellable)
        // 1.8 sneaked at full 1.8F height, 1.9-1.13.2 at 1.65F; both kept the 1.54F eye height instead of 1.5F/1.27F.
        if (pose == Pose.CROUCHING) {
            if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) {
                return vfpSneakingDimensionsV1_8;
            } else if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_13_2)) {
                return vfpSneakingDimensionsV1_13_2;
            }
        }

        return POSES.getOrDefault(pose, STANDING_DIMENSIONS);
    }

    public abstract ResolvableProfile getProfile();
}