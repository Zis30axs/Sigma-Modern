package net.minecraft.world.level.block.entity;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

// MODIFIED for porting: implements sodium's ExtendedBlockEntityType (core.render BlockEntityTypeMixin), which lets the
// sodium API attach render predicates to a block entity type.
public class BlockEntityType<T extends BlockEntity> implements net.caffeinemc.mods.sodium.client.render.chunk.ExtendedBlockEntityType<T> {
    // MODIFIED for porting: sodium core.render BlockEntityTypeMixin @Unique field
    @SuppressWarnings("unchecked")
    private net.caffeinemc.mods.sodium.api.blockentity.BlockEntityRenderPredicate<T>[] sodium$renderPredicates =
        new net.caffeinemc.mods.sodium.api.blockentity.BlockEntityRenderPredicate[0];

    @Override
    public net.caffeinemc.mods.sodium.api.blockentity.BlockEntityRenderPredicate<T>[] sodium$getRenderPredicates() {
        return this.sodium$renderPredicates;
    }

    @Override
    public void sodium$addRenderPredicate(final net.caffeinemc.mods.sodium.api.blockentity.BlockEntityRenderPredicate<T> predicate) {
        this.sodium$renderPredicates = org.apache.commons.lang3.ArrayUtils.add(this.sodium$renderPredicates, predicate);
    }

    @Override
    public boolean sodium$removeRenderPredicate(final net.caffeinemc.mods.sodium.api.blockentity.BlockEntityRenderPredicate<T> predicate) {
        int index = org.apache.commons.lang3.ArrayUtils.indexOf(this.sodium$renderPredicates, predicate);
        if (index == org.apache.commons.lang3.ArrayUtils.INDEX_NOT_FOUND) {
            return false;
        }

        this.sodium$renderPredicates = org.apache.commons.lang3.ArrayUtils.remove(this.sodium$renderPredicates, index);
        return true;
    }

    private final BlockEntityType.BlockEntitySupplier<? extends T> factory;
    private final Set<Block> validBlocks;
    private final Holder.Reference<BlockEntityType<?>> builtInRegistryHolder = BuiltInRegistries.BLOCK_ENTITY_TYPE.createIntrusiveHolder(this);

    public BlockEntityType(final BlockEntityType.BlockEntitySupplier<? extends T> factory, final Set<Block> validBlocks) {
        this.factory = factory;
        this.validBlocks = validBlocks;
    }

    public T create(final BlockPos worldPosition, final BlockState blockState) {
        return (T)this.factory.create(worldPosition, blockState);
    }

    public boolean isValid(final BlockState state) {
        return this.validBlocks.contains(state.getBlock());
    }

    @Deprecated
    public Holder.Reference<BlockEntityType<?>> builtInRegistryHolder() {
        return this.builtInRegistryHolder;
    }

    public @Nullable T getBlockEntity(final BlockGetter level, final BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        return (T)(entity != null && entity.getType() == this ? entity : null);
    }

    public boolean onlyOpCanSetNbt() {
        return BlockEntityTypes.OP_ONLY_CUSTOM_DATA.contains(this);
    }

    @FunctionalInterface
    public interface BlockEntitySupplier<T extends BlockEntity> {
        T create(BlockPos worldPosition, BlockState blockState);
    }
}