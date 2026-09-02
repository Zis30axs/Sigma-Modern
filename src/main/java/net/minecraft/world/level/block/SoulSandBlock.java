package net.minecraft.world.level.block;

import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class SoulSandBlock extends Block {
    public static final MapCodec<SoulSandBlock> CODEC = simpleCodec(SoulSandBlock::new);
    private static final VoxelShape SHAPE = Block.column(16.0, 0.0, 14.0);

    @Override
    public MapCodec<SoulSandBlock> codec() {
        return CODEC;
    }

    public SoulSandBlock(final BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getCollisionShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getBlockSupportShape(final BlockState state, final BlockGetter level, final BlockPos pos) {
        // MODIFIED for porting: was VFP block/shape MixinSoulSandBlock#changeSidesShape (@Inject HEAD, cancellable)
        // 1.13 through 1.15.2 inclusive had no block support shape on soul sand, so nothing there treats its
        // top face as a solid support. getVisualShape below is deliberately left alone upstream.
        if (ProtocolTranslator.getTargetVersion().betweenInclusive(ProtocolVersion.v1_13, ProtocolVersion.v1_15_2)) {
            return Shapes.empty();
        }

        return Shapes.block();
    }

    @Override
    protected VoxelShape getVisualShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return Shapes.block();
    }

    @Override
    protected boolean isPathfindable(final BlockState state, final PathComputationType type) {
        return false;
    }

    @Override
    protected float getShadeBrightness(final BlockState state, final BlockGetter level, final BlockPos pos) {
        return 0.2F;
    }

    // MODIFIED for porting: was VFP movement/collision MixinSoulSandBlock#entityInside (@Override, added method)
    // <= 1.14.4 slowed entities down on contact with soul sand instead of through the block's speedFactor.
    // Upstream deliberately does not call super, which is a no-op here since vanilla SoulSandBlock declares
    // no entityInside of its own.
    @Override
    protected void entityInside(
        final BlockState state,
        final Level level,
        final BlockPos pos,
        final Entity entity,
        final InsideBlockEffectApplier effectApplier,
        final boolean isPrecise
    ) {
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_14_4)) {
            entity.setDeltaMovement(entity.getDeltaMovement().multiply(0.4, 1.0, 0.4));
        }
    }

    // MODIFIED for porting: was VFP movement/collision MixinSoulSandBlock#getSpeedFactor (@Override, added method)
    // <= 1.14.4 has no soul sand speedFactor at all - the slowdown there comes from entityInside above, so the
    // modern speedFactor has to be neutralised.
    @Override
    public float getSpeedFactor() {
        return ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_14_4) ? 1.0F : super.getSpeedFactor();
    }
}