package net.minecraft.world.level.block;

import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class TransparentBlock extends HalfTransparentBlock {
    public static final MapCodec<TransparentBlock> CODEC = simpleCodec(TransparentBlock::new);

    protected TransparentBlock(final BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends TransparentBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getVisualShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        // MODIFIED for porting: was VFP block/shape MixinTransparentBlock#useCollisionVisualShape (@Inject HEAD,
        // cancellable). Before 1.16 ice and glass used their collision shape as the visual shape, which is what drives
        // suffocation and the inside-block checks.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_15_2)) {
            return this.getCollisionShape(state, level, pos, context);
        }

        return Shapes.empty();
    }

    @Override
    protected float getShadeBrightness(final BlockState state, final BlockGetter level, final BlockPos pos) {
        return 1.0F;
    }

    @Override
    protected boolean propagatesSkylightDown(final BlockState state) {
        return true;
    }
}