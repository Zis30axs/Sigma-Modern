package net.minecraft.client.player;

import com.mojang.authlib.GameProfile;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.Zone;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class RemotePlayer extends AbstractClientPlayer {
    private Vec3 lerpDeltaMovement = Vec3.ZERO;
    private int lerpDeltaMovementSteps;

    public RemotePlayer(final ClientLevel level, final GameProfile gameProfile) {
        super(level, gameProfile);
        this.noPhysics = true;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(final double distance) {
        // MODIFIED for porting: was VFP world.entity_distance MixinRemotePlayer#revert10thMultiplication
        // (@Inject HEAD cancellable). Targets <=1.8 use the plain Entity check without the 10x box inflation, so
        // remote players stop being rendered at the 1.8 cutoff.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) {
            return super.shouldRenderAtSqrDistance(distance);
        }

        double size = this.getBoundingBox().getSize() * 10.0;
        if (Double.isNaN(size)) {
            size = 1.0;
        }

        size *= 64.0 * getViewScale();
        return distance < size * size;
    }

    @Override
    public boolean hurtClient(final DamageSource source) {
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        this.calculateEntityAnimation(false);
    }

    @Override
    public void aiStep() {
        if (this.isInterpolating()) {
            this.getInterpolation().interpolate();
        }

        if (this.lerpHeadSteps > 0) {
            this.lerpHeadRotationStep(this.lerpHeadSteps, this.lerpYHeadRot);
            this.lerpHeadSteps--;
        }

        if (this.lerpDeltaMovementSteps > 0) {
            this.addDeltaMovement(
                new Vec3(
                    (this.lerpDeltaMovement.x - this.getDeltaMovement().x) / this.lerpDeltaMovementSteps,
                    (this.lerpDeltaMovement.y - this.getDeltaMovement().y) / this.lerpDeltaMovementSteps,
                    (this.lerpDeltaMovement.z - this.getDeltaMovement().z) / this.lerpDeltaMovementSteps
                )
            );
            this.lerpDeltaMovementSteps--;
        }

        this.updateSwingTime();
        this.updateBob();

        try (Zone ignored = Profiler.get().zone("push")) {
            this.pushEntities();
        }
    }

    @Override
    public void lerpMotion(final Vec3 movement) {
        this.lerpDeltaMovement = movement;
        this.lerpDeltaMovementSteps = this.getType().updateInterval() + 1;
    }

    @Override
    protected void updatePlayerPose() {
        // MODIFIED for porting: was VFP entity.pose MixinRemotePlayer#onUpdatePose (@Inject HEAD, not cancellable)
        // Targets <=1.13.2 never send pose metadata, so remote players have to pick their pose client-side through
        // Player#updatePlayerPose instead of keeping the empty 26.2 override.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_13_2)) {
            super.updatePlayerPose();
        }
    }

    @Override
    public void recreateFromPacket(final ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        this.setOldPosAndRot();
    }
}