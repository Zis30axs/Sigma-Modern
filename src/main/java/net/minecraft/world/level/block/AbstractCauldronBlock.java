package net.minecraft.world.level.block;

import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.raphimc.viabedrock.api.BedrockProtocolVersion;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public abstract class AbstractCauldronBlock extends Block {
    protected static final int FLOOR_LEVEL = 4;
    private static final VoxelShape SHAPE_INSIDE = Block.column(12.0, 4.0, 16.0);
    protected static final VoxelShape SHAPE = Util.make(
        () -> {
            int legWidth = 4;
            int legHeight = 3;
            int legThickness = 2;
            return Shapes.join(
                Shapes.block(),
                Shapes.or(Block.column(16.0, 8.0, 0.0, 3.0), Block.column(8.0, 16.0, 0.0, 3.0), Block.column(12.0, 0.0, 3.0), SHAPE_INSIDE),
                BooleanOp.ONLY_FIRST
            );
        }
    );
    // MODIFIED for porting: was VFP block/shape MixinAbstractCauldronBlock#viaFabricPlus$collision_shape_r1_12_2_bedrock
    // (@Unique constant) - the open five-box shell (floor plus four walls) that <= 1.12.2 and Bedrock use.
    private static final VoxelShape vfpCollisionShapeR1_12_2Bedrock = Shapes.or(
        Shapes.box(0.0, 0.0, 0.0, 1.0, 0.3125, 1.0),
        Shapes.box(0.0, 0.0, 0.0, 0.125, 1.0, 1.0),
        Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, 0.125),
        Shapes.box(0.875, 0.0, 0.0, 1.0, 1.0, 1.0),
        Shapes.box(0.0, 0.0, 0.875, 1.0, 1.0, 1.0)
    );
    protected final CauldronInteraction.Dispatcher interactions;

    @Override
    protected abstract MapCodec<? extends AbstractCauldronBlock> codec();

    public AbstractCauldronBlock(final BlockBehaviour.Properties properties, final CauldronInteraction.Dispatcher interactions) {
        super(properties);
        this.interactions = interactions;
    }

    protected double getContentHeight(final BlockState state) {
        return 0.0;
    }

    @Override
    protected InteractionResult useItemOn(
        final ItemStack itemStack,
        final BlockState state,
        final Level level,
        final BlockPos pos,
        final Player player,
        final InteractionHand hand,
        final BlockHitResult hitResult
    ) {
        CauldronInteraction behavior = this.interactions.get(itemStack);
        return behavior.interact(state, level, pos, player, hand, itemStack);
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        // MODIFIED for porting: was VFP block/shape MixinAbstractCauldronBlock#changeOutlineShape
        // (@Inject HEAD, cancellable) - <= 1.12.2 outlined the cauldron as a plain full block, Bedrock outlines the
        // open shell. Upstream's MoreCulling-only getOcclusionShape workaround is not ported: that mod does not
        // exist in this tree.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)) {
            return Shapes.block();
        } else if (ProtocolTranslator.getTargetVersion().equals(BedrockProtocolVersion.bedrockLatest)) {
            return vfpCollisionShapeR1_12_2Bedrock;
        }

        return SHAPE;
    }

    // MODIFIED for porting: was VFP block/shape MixinAbstractCauldronBlock#getCollisionShape (@Override, added method)
    // Needed because BlockBehaviour#getCollisionShape falls back to getShape, so the <= 1.12.2 full-block outline
    // above would otherwise also become a full collision box instead of the walkable shell.
    @Override
    protected VoxelShape getCollisionShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)
            || ProtocolTranslator.getTargetVersion().equals(BedrockProtocolVersion.bedrockLatest)) {
            return vfpCollisionShapeR1_12_2Bedrock;
        }

        return super.getCollisionShape(state, level, pos, context);
    }

    @Override
    protected VoxelShape getInteractionShape(final BlockState state, final BlockGetter level, final BlockPos pos) {
        return SHAPE_INSIDE;
    }

    @Override
    protected boolean hasAnalogOutputSignal(final BlockState state) {
        return true;
    }

    @Override
    protected boolean isPathfindable(final BlockState state, final PathComputationType type) {
        return false;
    }

    public abstract boolean isFull(final BlockState state);

    @Override
    protected void tick(final BlockState cauldronState, final ServerLevel level, final BlockPos pos, final RandomSource random) {
        BlockPos stalactitePos = PointedDripstoneBlock.findStalactiteTipAboveCauldron(level, pos);
        if (stalactitePos != null) {
            Fluid fluid = PointedDripstoneBlock.getCauldronFillFluidType(level, stalactitePos);
            if (fluid != Fluids.EMPTY && this.canReceiveStalactiteDrip(fluid)) {
                this.receiveStalactiteDrip(cauldronState, level, pos, fluid);
            }
        }
    }

    protected boolean canReceiveStalactiteDrip(final Fluid fluid) {
        return false;
    }

    protected void receiveStalactiteDrip(final BlockState state, final Level level, final BlockPos pos, final Fluid fluid) {
    }
}