package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import com.viaversion.viafabricplus.settings.impl.DebugSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class PotatoBlock extends CropBlock {
    public static final MapCodec<PotatoBlock> CODEC = simpleCodec(PotatoBlock::new);
    private static final VoxelShape[] SHAPES = Block.boxes(7, age -> Block.column(16.0, 0.0, 2 + age));
    // MODIFIED for porting: was VFP block/shape MixinCropBlocks#viaFabricPlus$shape_r1_8_x (@Unique constant),
    // copied into this target of the mixin as well.
    private static final VoxelShape vfpShapeR1_8_x = Block.box(0.0, 0.0, 0.0, 16.0, 4.0, 16.0);

    @Override
    public MapCodec<PotatoBlock> codec() {
        return CODEC;
    }

    public PotatoBlock(final BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected ItemLike getBaseSeedId() {
        return Items.POTATO;
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        // MODIFIED for porting: was VFP block/shape MixinCropBlocks#changeOutlineShape (@Inject HEAD, cancellable).
        // PotatoBlock is one of the mixin's three targets and overrides getShape, so it needs the same early return.
        if (DebugSettings.INSTANCE.legacyCropOutlines.isEnabled()) {
            return vfpShapeR1_8_x;
        }

        return SHAPES[this.getAge(state)];
    }
}