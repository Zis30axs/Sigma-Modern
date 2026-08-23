package net.minecraft.world.phys.shapes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

public class EntityCollisionContext implements CollisionContext {
    // MODIFIED for porting: lithium shapes.lazy_shape_context EntityCollisionContextMixin makes both `descending` and
    // `heldItem` lazy: reading the held item needs an inventory access and isDescending() needs entity state, and most
    // collision contexts never ask for either.
    private boolean descending;
    private final double entityBottom;
    private final boolean placement;
    private @Nullable ItemStack heldItem;
    private boolean lithium$isDescendingNeedsInitialization;
    private final boolean alwaysCollideWithFluid;
    private final @Nullable Entity entity;

    protected EntityCollisionContext(
        final boolean descending,
        final boolean placement,
        final double entityBottom,
        final ItemStack heldItem,
        final boolean alwaysCollideWithFluid,
        final @Nullable Entity entity
    ) {
        this.descending = descending;
        this.placement = placement;
        this.entityBottom = entityBottom;
        this.heldItem = heldItem;
        this.alwaysCollideWithFluid = alwaysCollideWithFluid;
        this.entity = entity;
    }

    @Deprecated
    protected EntityCollisionContext(final Entity entity, final boolean alwaysCollideWithFluid, final boolean placement) {
        this(
            // MODIFIED for porting: lithium lazy_shape_context EntityCollisionContextMixin#skipIsDescending
            false,
            placement,
            entity.getY(),
            // MODIFIED for porting: lithium lazy_shape_context EntityCollisionContextMixin#redirectInstanceOf makes the
            // instanceof always fail here; #initFields then nulls the field so it can be filled in on demand.
            ItemStack.EMPTY,
            alwaysCollideWithFluid,
            entity
        );
        this.heldItem = null;
        // MODIFIED for porting: lithium lazy_shape_context EntityCollisionContextMixin#initVars (RETURN)
        this.lithium$isDescendingNeedsInitialization = true;
    }

    // MODIFIED for porting: lithium lazy_shape_context EntityCollisionContextMixin#initHeldItem
    private void lithium$initHeldItem() {
        if (this.heldItem == null) {
            this.heldItem = this.entity instanceof LivingEntity livingEntity ? livingEntity.getMainHandItem() : ItemStack.EMPTY;
        }
    }

    // MODIFIED for porting: lithium lazy_shape_context EntityCollisionContextMixin declares this getter (as @Intrinsic) so
    // that anything reading the held item goes through the lazy initialization.
    public ItemStack getHeldItem() {
        this.lithium$initHeldItem();
        return this.heldItem;
    }

    @Override
    public boolean isHoldingItem(final Item item) {
        // MODIFIED for porting: lithium lazy_shape_context EntityCollisionContextMixin#isHolding (HEAD)
        this.lithium$initHeldItem();
        return this.heldItem.is(item);
    }

    @Override
    public boolean alwaysCollideWithFluid() {
        return this.alwaysCollideWithFluid;
    }

    @Override
    public boolean canStandOnFluid(final FluidState fluidStateAbove, final FluidState fluid) {
        return !(this.entity instanceof LivingEntity livingEntity)
            ? false
            : livingEntity.canStandOnFluid(fluid) && !fluidStateAbove.getType().isSame(fluid.getType());
    }

    @Override
    public VoxelShape getCollisionShape(final BlockState state, final CollisionGetter collisionGetter, final BlockPos pos) {
        return state.getCollisionShape(collisionGetter, pos, this);
    }

    @Override
    public boolean isDescending() {
        // MODIFIED for porting: lithium lazy_shape_context EntityCollisionContextMixin#initIsDescending (HEAD)
        if (this.lithium$isDescendingNeedsInitialization) {
            this.lithium$isDescendingNeedsInitialization = false;
            this.descending = this.entity != null && this.entity.isDescending();
        }

        return this.descending;
    }

    @Override
    public boolean isAbove(final VoxelShape shape, final BlockPos pos, final boolean defaultValue) {
        return this.entityBottom > pos.getY() + shape.max(Direction.Axis.Y) - 1.0E-5F;
    }

    public @Nullable Entity getEntity() {
        return this.entity;
    }

    @Override
    public boolean isPlacement() {
        return this.placement;
    }

    protected static class Empty extends EntityCollisionContext {
        protected static final CollisionContext WITHOUT_FLUID_COLLISIONS = new EntityCollisionContext.Empty(false);
        protected static final CollisionContext WITH_FLUID_COLLISIONS = new EntityCollisionContext.Empty(true);

        public Empty(final boolean alwaysCollideWithFluid) {
            super(false, false, -Double.MAX_VALUE, ItemStack.EMPTY, alwaysCollideWithFluid, null);
        }

        @Override
        public boolean isAbove(final VoxelShape shape, final BlockPos pos, final boolean defaultValue) {
            return defaultValue;
        }
    }
}