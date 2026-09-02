package net.minecraft.world.entity;

import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.raphimc.viabedrock.api.BedrockProtocolVersion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public class EntityFluidInteraction {
    private final Map<TagKey<Fluid>, EntityFluidInteraction.Tracker> trackerByFluid = new Reference2ObjectArrayMap<>();

    public EntityFluidInteraction(final Set<TagKey<Fluid>> fluids) {
        for (TagKey<Fluid> fluid : fluids) {
            this.trackerByFluid.put(fluid, new EntityFluidInteraction.Tracker());
        }
    }

    public void update(final Entity entity, final boolean ignoreCurrent) {
        this.trackerByFluid.values().forEach(EntityFluidInteraction.Tracker::reset);
        AABB box = entity.getFluidInteractionBox();
        if (box != null) {
            int x0 = Mth.floor(box.minX);
            int y0 = Mth.floor(box.minY);
            int z0 = Mth.floor(box.minZ);
            int x1 = Mth.ceil(box.maxX) - 1;
            int y1 = Mth.ceil(box.maxY) - 1;
            int z1 = Mth.ceil(box.maxZ) - 1;
            if (hasFluidAndLoaded(entity.level(), x0 - 1, y0, z0 - 1, x1 + 1, y1, z1 + 1)) {
                double entityY = entity.getBoundingBox().minY;
                int eyeBlockX = entity.getBlockX();
                // MODIFIED for porting: was VFP movement/liquid MixinEntityFluidInteraction#subtractMagicOffset (@Redirect INVOKE Entity#getEyeY)
                // 1.16 - 1.20.3 test the eye against a point 1/9 of a block below the real eye height.
                double eyeY = ProtocolTranslator.getTargetVersion().betweenInclusive(ProtocolVersion.v1_16, ProtocolVersion.v1_20_3)
                    ? entity.getEyeY() - 0.11111111F
                    : entity.getEyeY();
                int eyeBlockZ = entity.getBlockZ();
                Fluid lastFluidType = null;
                EntityFluidInteraction.Tracker tracker = null;
                BlockGetter level = entity.level();
                BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

                for (int x = x0; x <= x1; x++) {
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            mutablePos.set(x, y, z);
                            FluidState fluidState = level.getFluidState(mutablePos);
                            if (!fluidState.isEmpty()) {
                                double fluidBottom = mutablePos.getY();
                                double fluidTop = fluidBottom + fluidState.getHeight(level, mutablePos);
                                // MODIFIED for porting: was VFP movement/liquid MixinEntityFluidInteraction#removeConditional
                                // (@ModifyExpressionValue on `fluidTop < box.minY`) - <=1.12.2 and Bedrock never skip a fluid block
                                // whose surface sits below the interaction box.
                                final boolean vfpFluidTopBelowBox;
                                if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)
                                    || ProtocolTranslator.getTargetVersion().equals(BedrockProtocolVersion.bedrockLatest)) {
                                    vfpFluidTopBelowBox = false;
                                } else {
                                    vfpFluidTopBelowBox = fluidTop < box.minY;
                                }

                                if (!vfpFluidTopBelowBox) {
                                    Fluid fluidType = fluidState.getType();
                                    if (fluidType != lastFluidType) {
                                        lastFluidType = fluidType;
                                        tracker = this.getTrackerFor(fluidType);
                                    }

                                    if (tracker != null) {
                                        // MODIFIED for porting: was VFP movement/liquid MixinEntityFluidInteraction#addMagicOffset
                                        // (@ModifyExpressionValue on `eyeY <= fluidTop`) - <=1.15.2 counts the eye as submerged up to
                                        // 2/9 of a block above the fluid surface.
                                        if (x == eyeBlockX
                                            && z == eyeBlockZ
                                            && eyeY >= fluidBottom
                                            && (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_15_2)
                                                ? eyeY <= fluidBottom + (fluidState.getHeight(level, mutablePos) + 0.11111111F * 2F)
                                                : eyeY <= fluidTop)) {
                                            tracker.eyesInside = true;
                                        }

                                        // MODIFIED for porting: was VFP movement/liquid MixinEntityFluidInteraction#adjustHeightCalculation
                                        // (@Redirect INVOKE Math#max) - <=1.12.2 and Bedrock report the fluid 0.4 deeper than 1.13+ do.
                                        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)
                                            || ProtocolTranslator.getTargetVersion().equals(BedrockProtocolVersion.bedrockLatest)) {
                                            tracker.height = Math.max((fluidTop - entityY) + 0.4, tracker.height);
                                        } else {
                                            tracker.height = Math.max(fluidTop - entityY, tracker.height);
                                        }

                                        if (!ignoreCurrent) {
                                            Vec3 flow = fluidState.getFlow(level, mutablePos);
                                            if (tracker.height < 0.4) {
                                                // MODIFIED for porting: was VFP movement/liquid MixinEntityFluidInteraction#dontScaleCurrent
                                                // (@Redirect INVOKE Vec3#scale) - <=1.12.2 and Bedrock accumulate the unscaled flow.
                                                if (!ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)
                                                    && !ProtocolTranslator.getTargetVersion().equals(BedrockProtocolVersion.bedrockLatest)) {
                                                    flow = flow.scale(tracker.height);
                                                }
                                            }

                                            tracker.accumulateCurrent(flow);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean hasFluidAndLoaded(final Level level, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
        int sectionX0 = SectionPos.blockToSectionCoord(x0);
        int sectionY0 = SectionPos.blockToSectionCoord(y0);
        int sectionZ0 = SectionPos.blockToSectionCoord(z0);
        int sectionX1 = SectionPos.blockToSectionCoord(x1);
        int sectionY1 = SectionPos.blockToSectionCoord(y1);
        int sectionZ1 = SectionPos.blockToSectionCoord(z1);
        boolean hasFluid = false;

        for (int chunkZ = sectionZ0; chunkZ <= sectionZ1; chunkZ++) {
            for (int chunkX = sectionX0; chunkX <= sectionX1; chunkX++) {
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    return false;
                }

                LevelChunkSection[] sections = chunk.getSections();

                for (int sectionY = sectionY0; sectionY <= sectionY1; sectionY++) {
                    int sectionIndex = chunk.getSectionIndexFromSectionY(sectionY);
                    if (sectionIndex >= 0 && sectionIndex < sections.length) {
                        hasFluid |= sections[sectionIndex].hasFluid();
                    }
                }
            }
        }

        return hasFluid;
    }

    private EntityFluidInteraction.@Nullable Tracker getTrackerFor(final Fluid fluid) {
        for (Entry<TagKey<Fluid>, EntityFluidInteraction.Tracker> entry : this.trackerByFluid.entrySet()) {
            TagKey<Fluid> tag = entry.getKey();
            if (fluid.is(tag)) {
                return entry.getValue();
            }
        }

        return null;
    }

    public void applyCurrentTo(final TagKey<Fluid> fluid, final Entity entity, final double scale) {
        EntityFluidInteraction.Tracker tracker = this.trackerByFluid.get(fluid);
        if (tracker != null) {
            tracker.applyCurrentTo(entity, scale);
        }
    }

    public double getFluidHeight(final TagKey<Fluid> fluid) {
        EntityFluidInteraction.Tracker tracker = this.trackerByFluid.get(fluid);
        return tracker != null ? tracker.height : 0.0;
    }

    public boolean isInFluid(final TagKey<Fluid> fluid) {
        return this.getFluidHeight(fluid) > 0.0;
    }

    public boolean isEyeInFluid(final TagKey<Fluid> fluid) {
        EntityFluidInteraction.Tracker tracker = this.trackerByFluid.get(fluid);
        return tracker != null && tracker.eyesInside;
    }

    static class Tracker {
        private double height;
        private boolean eyesInside;
        private Vec3 accumulatedCurrent = Vec3.ZERO;
        private int currentCount;

        public void reset() {
            this.height = 0.0;
            this.eyesInside = false;
            this.accumulatedCurrent = Vec3.ZERO;
            this.currentCount = 0;
        }

        public void accumulateCurrent(final Vec3 flow) {
            this.accumulatedCurrent = this.accumulatedCurrent.add(flow);
            this.currentCount++;
        }

        public void applyCurrentTo(final Entity entity, final double scale) {
            // MODIFIED for porting: was VFP movement/liquid MixinEntityFluidInteraction_Tracker#useLengthInstead (@Redirect INVOKE
            // Vec3#lengthSqr) + #changeThreshold (@ModifyConstant 1.0E-5F) - <=1.21.11 compares the un-squared magnitude against 0,
            // so every non-zero accumulated current is applied.
            final boolean vfpLegacyCurrentGate = ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_21_11);
            if (this.currentCount != 0
                && !((vfpLegacyCurrentGate ? this.accumulatedCurrent.length() : this.accumulatedCurrent.lengthSqr())
                    < (vfpLegacyCurrentGate ? 0.0 : 1.0E-5F))) {
                Vec3 impulse;
                if (!(entity instanceof Player)) {
                    impulse = this.accumulatedCurrent.normalize();
                } else {
                    // MODIFIED for porting: was VFP movement/liquid MixinEntityFluidInteraction_Tracker#normalizeInsteadScale
                    // (@Redirect INVOKE Vec3#scale ordinal 0) - <=1.12.2 and Bedrock push the player along the normalised current.
                    if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)
                        || ProtocolTranslator.getTargetVersion().equals(BedrockProtocolVersion.bedrockLatest)) {
                        impulse = this.accumulatedCurrent.normalize();
                    } else {
                        impulse = this.accumulatedCurrent.scale(1.0 / this.currentCount);
                    }
                }

                Vec3 oldMovement = entity.getDeltaMovement();
                impulse = impulse.scale(scale);
                double min = 0.003;
                // MODIFIED for porting: was VFP movement/liquid MixinEntityFluidInteraction_Tracker#dontScaleSmallValues
                // (@Redirect INVOKE Vec3#length) - <=1.12.2 and Bedrock never raise a tiny impulse to the 0.0045 minimum.
                final double vfpImpulseLength = ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)
                        || ProtocolTranslator.getTargetVersion().equals(BedrockProtocolVersion.bedrockLatest)
                    ? Double.MAX_VALUE
                    : impulse.length();
                if (Math.abs(oldMovement.x) < 0.003 && Math.abs(oldMovement.z) < 0.003 && vfpImpulseLength < 0.0045000000000000005) {
                    impulse = impulse.normalize().scale(0.0045000000000000005);
                }

                entity.addDeltaMovement(impulse);
            }
        }
    }
}
