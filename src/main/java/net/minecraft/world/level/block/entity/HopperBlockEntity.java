package net.minecraft.world.level.block.entity;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
// MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin
import net.caffeinemc.mods.lithium.api.inventory.LithiumCooldownReceivingInventory;
import net.caffeinemc.mods.lithium.common.block.entity.SleepingBlockEntity;
import net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeTracker;
import net.caffeinemc.mods.lithium.common.block.entity.inventory_comparator_tracking.ComparatorTracker;
import net.caffeinemc.mods.lithium.common.hopper.BlockStateOnlyInventory;
import net.caffeinemc.mods.lithium.common.hopper.HopperCachingState;
import net.caffeinemc.mods.lithium.common.hopper.HopperHelper;
import net.caffeinemc.mods.lithium.common.hopper.InventoryHelper;
import net.caffeinemc.mods.lithium.common.hopper.LithiumStackList;
import net.caffeinemc.mods.lithium.common.hopper.UpdateReceiver;
import net.caffeinemc.mods.lithium.common.services.PlatformModCompat;
import net.caffeinemc.mods.lithium.common.tracking.entity.SectionedInventoryEntityMovementTracker;
import net.caffeinemc.mods.lithium.common.tracking.entity.SectionedItemEntityMovementTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.WorldlyContainerHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

public class HopperBlockEntity extends RandomizableContainerBlockEntity
    implements Hopper,
    net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeTracker, // MODIFIED for porting: lithium util.inventory_change_listening
    net.caffeinemc.mods.lithium.api.inventory.LithiumInventory, // MODIFIED for porting: lithium block.hopper InventoryAccessors
    net.caffeinemc.mods.lithium.common.block.entity.SleepingBlockEntity, // MODIFIED for porting: lithium world.block_entity_ticking.sleeping.hopper
    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin - the hopper caches the inventories it interacts
    // with and listens for the events that can invalidate those caches.
    UpdateReceiver,
    net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeListener,
    net.caffeinemc.mods.lithium.common.tracking.entity.SectionedEntityMovementListener {
    // MODIFIED for porting: the next two methods were lithium's block.hopper InventoryAccessors Mixin, which
    // exposes the raw stack list so lithium can swap in its own LithiumStackList.
    @Override
    public NonNullList<ItemStack> getInventoryLithium() {
        return this.items;
    }

    @Override
    public void setInventoryLithium(final NonNullList<ItemStack> inventory) {
        this.items = inventory;
    }

    public static final int MOVE_ITEM_SPEED = 8;
    public static final int HOPPER_CONTAINER_SIZE = 5;
    private static final int[][] CACHED_SLOTS = new int[54][];
    private static final int NO_COOLDOWN_TIME = -1;
    private static final Component DEFAULT_NAME = Component.translatable("container.hopper");
    private NonNullList<ItemStack> items = NonNullList.withSize(5, ItemStack.EMPTY);
    private int cooldownTime = -1;
    private long tickedGameTime;

    // MODIFIED for porting: the following members were lithium's world.block_entity_ticking.sleeping.hopper
    // HopperBlockEntityMixin.
    private net.caffeinemc.mods.lithium.mixin.world.block_entity_ticking.sleeping.WrappedBlockEntityTickInvokerAccessor lithium$tickWrapper = null;
    private net.minecraft.world.level.block.entity.TickingBlockEntity lithium$sleepingTicker = null;

    @Override
    public net.caffeinemc.mods.lithium.mixin.world.block_entity_ticking.sleeping.WrappedBlockEntityTickInvokerAccessor lithium$getTickWrapper() {
        return this.lithium$tickWrapper;
    }

    @Override
    public void lithium$setTickWrapper(final net.caffeinemc.mods.lithium.mixin.world.block_entity_ticking.sleeping.WrappedBlockEntityTickInvokerAccessor tickWrapper) {
        this.lithium$tickWrapper = tickWrapper;
        this.lithium$setSleepingTicker(null);
    }

    @Override
    public net.minecraft.world.level.block.entity.TickingBlockEntity lithium$getSleepingTicker() {
        return this.lithium$sleepingTicker;
    }

    @Override
    public void lithium$setSleepingTicker(final net.minecraft.world.level.block.entity.TickingBlockEntity sleepingTicker) {
        this.lithium$sleepingTicker = sleepingTicker;
    }

    /**
     * MODIFIED for porting: lithium sleeping.hopper HopperBlockEntityMixin overrides the default
     * {@code lithium$startSleeping} to additionally push the last tick time to Long.MAX_VALUE. Other hoppers
     * transferring into this one will then set a 7 gametick cooldown, and on waking up this hopper is skipped for the
     * current gametick - which keeps the observable hopper cooldown identical to vanilla.
     */
    @Override
    public boolean lithium$startSleeping() {
        if (this.isSleeping()) {
            return false;
        }

        net.caffeinemc.mods.lithium.mixin.world.block_entity_ticking.sleeping.WrappedBlockEntityTickInvokerAccessor tickWrapper = this.lithium$getTickWrapper();
        if (tickWrapper != null) {
            this.lithium$setSleepingTicker(tickWrapper.getWrapped());
            tickWrapper.callSetWrapped(net.caffeinemc.mods.lithium.common.block.entity.SleepingBlockEntity.SLEEPING_BLOCK_ENTITY_TICKER);
            this.tickedGameTime = Long.MAX_VALUE;
            return true;
        }

        return false;
    }

    private Direction facing;

    // MODIFIED for porting: the block of fields and methods below was lithium's block.hopper HopperBlockEntityMixin
    // (@Unique members). See the mod's own README in that package for the design.
    private long myModCountAtLastInsert;
    private long myModCountAtLastExtract;
    private long myModCountAtLastItemCollect;
    private HopperCachingState.BlockInventory insertionMode = HopperCachingState.BlockInventory.UNKNOWN;
    private HopperCachingState.BlockInventory extractionMode = HopperCachingState.BlockInventory.UNKNOWN;
    /** The currently used block inventories. */
    private @Nullable Container insertBlockInventory;
    private @Nullable Container extractBlockInventory;
    /** The currently used inventories in optimized form; if absent, the optimizations are skipped. */
    private net.caffeinemc.mods.lithium.api.inventory.@Nullable LithiumInventory insertInventory;
    private net.caffeinemc.mods.lithium.api.inventory.@Nullable LithiumInventory extractInventory;
    /** Null iff the corresponding LithiumInventory field is null. */
    private @Nullable LithiumStackList insertStackList;
    private @Nullable LithiumStackList extractStackList;
    /** Mod counts used to avoid transfer attempts that are known to fail (no change since the last attempt). */
    private long insertStackListModCount;
    private long extractStackListModCount;
    private @Nullable SectionedItemEntityMovementTracker<ItemEntity> collectItemEntityTracker;
    private boolean collectItemEntityTrackerWasEmpty;
    private @Nullable AABB collectItemEntityBox;
    private long collectItemEntityAttemptTime;
    private @Nullable SectionedInventoryEntityMovementTracker<Container> extractInventoryEntityTracker;
    private @Nullable AABB extractInventoryEntityBox;
    private long extractInventoryEntityFailedSearchTime;
    private @Nullable SectionedInventoryEntityMovementTracker<Container> insertInventoryEntityTracker;
    private @Nullable AABB insertInventoryEntityBox;
    private long insertInventoryEntityFailedSearchTime;
    private boolean shouldCheckSleep;

    public HopperBlockEntity(final BlockPos worldPosition, final BlockState blockState) {
        super(BlockEntityTypes.HOPPER, worldPosition, blockState);
        this.facing = blockState.getValue(HopperBlock.FACING);
    }

    @Override
    protected void loadAdditional(final ValueInput input) {
        super.loadAdditional(input);
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        if (!this.tryLoadLootTable(input)) {
            ContainerHelper.loadAllItems(input, this.items);
        }

        this.cooldownTime = input.getIntOr("TransferCooldown", -1);
    }

    @Override
    protected void saveAdditional(final ValueOutput output) {
        super.saveAdditional(output);
        if (!this.trySaveLootTable(output)) {
            ContainerHelper.saveAllItems(output, this.items);
        }

        output.putInt("TransferCooldown", this.cooldownTime);
    }

    @Override
    public int getContainerSize() {
        return this.items.size();
    }

    @Override
    public ItemStack removeItem(final int slot, final int count) {
        this.unpackLootTable(null);
        return ContainerHelper.removeItem(this.getItems(), slot, count);
    }

    @Override
    public void setItem(final int slot, final ItemStack itemStack) {
        this.unpackLootTable(null);
        this.getItems().set(slot, itemStack);
        itemStack.limitSize(this.getMaxStackSize(itemStack));
    }

    @Override
    public void setBlockState(final BlockState blockState) {
        // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#invalidateOnSetCachedState (HEAD)
        if (this.level != null && !this.level.isClientSide() && blockState.getValue(HopperBlock.FACING) != this.getBlockState().getValue(HopperBlock.FACING)) {
            this.lithium$invalidateCachedData();
        }

        super.setBlockState(blockState);
        this.facing = blockState.getValue(HopperBlock.FACING);
    }

    @Override
    protected Component getDefaultName() {
        return DEFAULT_NAME;
    }

    public static void pushItemsTick(final Level level, final BlockPos pos, final BlockState state, final HopperBlockEntity entity) {
        entity.cooldownTime--;
        entity.tickedGameTime = level.getGameTime();
        if (!entity.isOnCooldown()) {
            entity.setCooldown(0);
            tryMoveItems(level, pos, state, entity, () -> suckInItems(level, entity));
            // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#checkSleepingConditions
            // (INVOKE tryMoveItems, shift AFTER)
            entity.lithium$checkSleepingConditions();
        }
    }

    private static boolean tryMoveItems(
        final Level level, final BlockPos pos, final BlockState state, final HopperBlockEntity entity, final BooleanSupplier action
    ) {
        if (level.isClientSide()) {
            return false;
        }

        if (!entity.isOnCooldown() && state.getValue(HopperBlock.ENABLED)) {
            boolean changed = false;
            // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithiumHopperIsEmpty (@Redirect) - the
            // stack list keeps a running count of occupied slots.
            if (!lithium$isEmpty(entity)) {
                changed = ejectItems(level, pos, entity);
            }

            // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithiumHopperIsFull (@Redirect) - the
            // stack list keeps a running count of full slots.
            if (!lithium$inventoryFull(entity)) {
                changed |= action.getAsBoolean();
            }

            if (changed) {
                entity.setCooldown(8);
                setChanged(level, pos, state);
                return true;
            }
        }

        // MODIFIED for porting: lithium sleeping.hopper HopperBlockEntityMixin#sleepIfNoCooldownAndLocked
        // (RETURN ordinal 2 - this final return)
        if (!entity.isOnCooldown() && !entity.isSleeping() && !state.getValue(HopperBlock.ENABLED)) {
            entity.lithium$startSleeping();
        }

        return false;
    }

    private boolean inventoryFull() {
        for (ItemStack itemStack : this.items) {
            if (itemStack.isEmpty() || itemStack.getCount() != itemStack.getMaxStackSize()) {
                return false;
            }
        }

        return true;
    }

    private static boolean ejectItems(final Level level, final BlockPos blockPos, final HopperBlockEntity self) {
        // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#getLithiumOutputInventory (@Redirect of
        // getAttachedContainer) - the target inventory is cached.
        Container container = self.lithium$getInsertInventory(level);
        if (container == null) {
            return false;
        }

        // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithiumInsert, injected before the
        // isFullContainer call and always cancelling. Falls through to the vanilla code below only for hoppers that are
        // themselves sided inventories, so that other mods' features keep working (e.g. carpet mod lets hoppers insert
        // items into wool blocks).
        if (!(self instanceof WorldlyContainer)) {
            return lithium$insert(level, self, container);
        }

        Direction direction = self.facing.getOpposite();
        if (isFullContainer(container, direction)) {
            return false;
        }

        for (int slot = 0; slot < self.getContainerSize(); slot++) {
            ItemStack itemStack = self.getItem(slot);
            if (!itemStack.isEmpty()) {
                int originalCount = itemStack.getCount();
                ItemStack result = addItem(self, container, self.removeItem(slot, 1), direction);
                if (result.isEmpty()) {
                    container.setChanged();
                    return true;
                }

                itemStack.setCount(originalCount);
                if (originalCount == 1) {
                    self.setItem(slot, itemStack);
                }
            }
        }

        return false;
    }

    private static int[] getSlots(final Container container, final Direction direction) {
        if (container instanceof WorldlyContainer worldlyContainer) {
            return worldlyContainer.getSlotsForFace(direction);
        } else {
            int containerSize = container.getContainerSize();
            if (containerSize < CACHED_SLOTS.length) {
                int[] cachedSlots = CACHED_SLOTS[containerSize];
                if (cachedSlots != null) {
                    return cachedSlots;
                }

                int[] slots = createFlatSlots(containerSize);
                CACHED_SLOTS[containerSize] = slots;
                return slots;
            } else {
                return createFlatSlots(containerSize);
            }
        }
    }

    private static int[] createFlatSlots(final int containerSize) {
        int[] slots = new int[containerSize];
        int i = 0;

        while (i < slots.length) {
            slots[i] = i++;
        }

        return slots;
    }

    private static boolean isFullContainer(final Container container, final Direction direction) {
        int[] slots = getSlots(container, direction);

        for (int slot : slots) {
            ItemStack itemStack = container.getItem(slot);
            if (itemStack.getCount() < itemStack.getMaxStackSize()) {
                return false;
            }
        }

        return true;
    }

    public static boolean suckInItems(final Level level, final Hopper hopper) {
        BlockPos blockPos = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY() + 1.0, hopper.getLevelZ());
        BlockState blockState = level.getBlockState(blockPos);
        // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#getExtractInventory (@Redirect of
        // getSourceContainer) - the source inventory is cached.
        Container container = lithium$getExtractInventory(level, hopper, blockPos, blockState);
        if (container != null) {
            Direction direction = Direction.DOWN;
            // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithiumExtract, injected right after the
            // Direction.DOWN read. Returns null when the source is not an inventory lithium can optimize, in which case the
            // vanilla loop below runs.
            Boolean lithium$extractResult = lithium$extract(hopper, container);
            if (lithium$extractResult != null) {
                return lithium$extractResult;
            }

            for (int slot : getSlots(container, direction)) {
                if (tryTakeInItemFromSlot(hopper, container, slot, direction)) {
                    return true;
                }
            }

            return false;
        } else {
            boolean isBlocked = hopper.isGridAligned()
                && blockState.isCollisionShapeFullBlock(level, blockPos)
                && !blockState.is(BlockTags.DOES_NOT_BLOCK_HOPPERS);
            if (!isBlocked) {
                // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithiumGetInputItemEntities (@Redirect)
                for (ItemEntity entity : lithium$getInputItemEntities(level, hopper)) {
                    if (addItem(hopper, entity)) {
                        return true;
                    }
                }
            }

            return false;
        }
    }

    private static boolean tryTakeInItemFromSlot(final Hopper hopper, final Container container, final int slot, final Direction direction) {
        ItemStack itemStack = container.getItem(slot);
        if (!itemStack.isEmpty() && canTakeItemFromContainer(hopper, container, itemStack, slot, direction)) {
            int originalCount = itemStack.getCount();
            ItemStack result = addItem(container, hopper, container.removeItem(slot, 1), null);
            if (result.isEmpty()) {
                container.setChanged();
                return true;
            }

            itemStack.setCount(originalCount);
            if (originalCount == 1) {
                container.setItem(slot, itemStack);
            }
        }

        return false;
    }

    public static boolean addItem(final Container container, final ItemEntity entity) {
        boolean changed = false;
        ItemStack copy = entity.getItem().copy();
        ItemStack result = addItem(null, container, copy, null);
        if (result.isEmpty()) {
            changed = true;
            entity.setItem(ItemStack.EMPTY);
            entity.discard();
        } else {
            entity.setItem(result);
        }

        return changed;
    }

    public static ItemStack addItem(final @Nullable Container from, final Container container, ItemStack itemStack, final @Nullable Direction direction) {
        if (container instanceof WorldlyContainer worldly && direction != null) {
            int[] slots = worldly.getSlotsForFace(direction);

            for (int i = 0; i < slots.length && !itemStack.isEmpty(); i++) {
                itemStack = tryMoveInItem(from, container, itemStack, slots[i], direction);
            }
        } else {
            int size = container.getContainerSize();

            for (int i = 0; i < size && !itemStack.isEmpty(); i++) {
                itemStack = tryMoveInItem(from, container, itemStack, i, direction);
            }
        }

        return itemStack;
    }

    private static boolean canPlaceItemInContainer(final Container container, final ItemStack itemStack, final int slot, final @Nullable Direction direction) {
        return !container.canPlaceItem(slot, itemStack)
            ? false
            : !(container instanceof WorldlyContainer worldly && !worldly.canPlaceItemThroughFace(slot, itemStack, direction));
    }

    private static boolean canTakeItemFromContainer(
        final Container into, final Container from, final ItemStack itemStack, final int slot, final Direction direction
    ) {
        return !from.canTakeItem(into, slot, itemStack)
            ? false
            : !(from instanceof WorldlyContainer worldly && !worldly.canTakeItemThroughFace(slot, itemStack, direction));
    }

    private static ItemStack tryMoveInItem(
        final @Nullable Container from, final Container container, ItemStack itemStack, final int slot, final @Nullable Direction direction
    ) {
        ItemStack current = container.getItem(slot);
        if (canPlaceItemInContainer(container, itemStack, slot, direction)) {
            boolean success = false;
            boolean wasEmpty = container.isEmpty();
            if (current.isEmpty()) {
                container.setItem(slot, itemStack);
                itemStack = ItemStack.EMPTY;
                success = true;
            } else if (canMergeItems(current, itemStack)) {
                int space = itemStack.getMaxStackSize() - current.getCount();
                int count = Math.min(itemStack.getCount(), space);
                itemStack.shrink(count);
                current.grow(count);
                success = count > 0;
            }

            if (success) {
                if (wasEmpty && container instanceof HopperBlockEntity hopperBlockEntity && !hopperBlockEntity.isOnCustomCooldown()) {
                    int skipTickCount = 0;
                    if (from instanceof HopperBlockEntity fromHopper && hopperBlockEntity.tickedGameTime >= fromHopper.tickedGameTime) {
                        skipTickCount = 1;
                    }

                    hopperBlockEntity.setCooldown(8 - skipTickCount);
                }

                container.setChanged();
            }
        }

        return itemStack;
    }

    private static @Nullable Container getAttachedContainer(final Level level, final BlockPos blockPos, final HopperBlockEntity self) {
        return getContainerAt(level, blockPos.relative(self.facing));
    }

    private static @Nullable Container getSourceContainer(final Level level, final Hopper hopper, final BlockPos pos, final BlockState state) {
        return getContainerAt(level, pos, state, hopper.getLevelX(), hopper.getLevelY() + 1.0, hopper.getLevelZ());
    }

    public static List<ItemEntity> getItemsAtAndAbove(final Level level, final Hopper hopper) {
        AABB aabb = hopper.getSuckAabb().move(hopper.getLevelX() - 0.5, hopper.getLevelY() - 0.5, hopper.getLevelZ() - 0.5);
        return level.getEntitiesOfClass(ItemEntity.class, aabb, EntitySelector.ENTITY_STILL_ALIVE);
    }

    public static @Nullable Container getContainerAt(final Level level, final BlockPos pos) {
        return getContainerAt(level, pos, level.getBlockState(pos), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private static @Nullable Container getContainerAt(
        final Level level, final BlockPos pos, final BlockState state, final double x, final double y, final double z
    ) {
        Container result = getBlockContainer(level, pos, state);
        if (result == null) {
            result = getEntityContainer(level, x, y, z);
        }

        return result;
    }

    private static @Nullable Container getBlockContainer(final Level level, final BlockPos pos, final BlockState state) {
        Block block = state.getBlock();
        if (block instanceof WorldlyContainerHolder worldlyContainerHolder) {
            return worldlyContainerHolder.getContainer(state, level, pos);
        } else if (state.hasBlockEntity() && level.getBlockEntity(pos) instanceof Container container) {
            if (container instanceof ChestBlockEntity && block instanceof ChestBlock chestBlock) {
                container = ChestBlock.getContainer(chestBlock, state, level, pos, true);
            }

            return container;
        } else {
            return null;
        }
    }

    private static @Nullable Container getEntityContainer(final Level level, final double x, final double y, final double z) {
        List<Entity> entities = level.getEntities(
            (Entity)null, new AABB(x - 0.5, y - 0.5, z - 0.5, x + 0.5, y + 0.5, z + 0.5), EntitySelector.CONTAINER_ENTITY_SELECTOR
        );
        return !entities.isEmpty() ? (Container)entities.get(level.getRandom().nextInt(entities.size())) : null;
    }

    private static boolean canMergeItems(final ItemStack a, final ItemStack b) {
        return a.getCount() <= a.getMaxStackSize() && ItemStack.isSameItemSameComponents(a, b);
    }

    @Override
    public double getLevelX() {
        return this.worldPosition.getX() + 0.5;
    }

    @Override
    public double getLevelY() {
        return this.worldPosition.getY() + 0.5;
    }

    @Override
    public double getLevelZ() {
        return this.worldPosition.getZ() + 0.5;
    }

    @Override
    public boolean isGridAligned() {
        return true;
    }

    private void setCooldown(final int time) {
        // MODIFIED for porting: lithium sleeping.hopper HopperBlockEntityMixin#wakeUpOnCooldownSet (HEAD)
        if (time == 7) {
            if (this.tickedGameTime == Long.MAX_VALUE) {
                this.sleepOnlyCurrentTick();
            } else {
                this.wakeUpNow();
            }
        } else if (time > 0 && this.lithium$getSleepingTicker() != null) {
            this.wakeUpNow();
        }

        this.cooldownTime = time;
    }

    private boolean isOnCooldown() {
        return this.cooldownTime > 0;
    }

    private boolean isOnCustomCooldown() {
        return this.cooldownTime > 8;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return this.items;
    }

    @Override
    protected void setItems(final NonNullList<ItemStack> items) {
        this.items = items;
        // MODIFIED for porting: lithium util.inventory_change_listening StackListReplacementTracking (RETURN of setItems)
        this.lithium$emitStackListReplaced();
    }

    public static void entityInside(final Level level, final BlockPos pos, final BlockState blockState, final Entity entity, final HopperBlockEntity hopper) {
        if (entity instanceof ItemEntity itemEntity
            && !itemEntity.getItem().isEmpty()
            && entity.getBoundingBox().move(-pos.getX(), -pos.getY(), -pos.getZ()).intersects(hopper.getSuckAabb())) {
            tryMoveItems(level, pos, blockState, hopper, () -> addItem(hopper, itemEntity));
        }
    }


    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithiumHopperIsEmpty (@Redirect target)
    private static boolean lithium$isEmpty(final HopperBlockEntity hopper) {
        return InventoryHelper.getLithiumStackList(hopper).getOccupiedSlots() == 0;
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithiumHopperIsFull (@Redirect target)
    private static boolean lithium$inventoryFull(final HopperBlockEntity hopper) {
        LithiumStackList stackList = InventoryHelper.getLithiumStackList(hopper);
        return stackList.getFullSlots() == stackList.size();
    }

    /**
     * MODIFIED for porting: was lithium's block.hopper HopperBlockEntityMixin#lithiumInsert.
     * [VanillaCopy] of the general hopper insert logic, modified for the inventory and stack list caching.
     */
    private static boolean lithium$insert(final Level level, final HopperBlockEntity hopperBlockEntity, final Container insertInventory) {
        LithiumStackList hopperStackList = InventoryHelper.getLithiumStackList(hopperBlockEntity);
        if (hopperBlockEntity.insertInventory == insertInventory
            && hopperStackList.getModCount() == hopperBlockEntity.myModCountAtLastInsert
            && hopperBlockEntity.insertStackList != null
            && hopperBlockEntity.insertStackList.getModCount() == hopperBlockEntity.insertStackListModCount) {
            // ComparatorUpdatePattern.NO_UPDATE would be applied here, but it is a no-op: hoppers do not send useless
            // comparator updates.
            return false;
        }

        boolean insertInventoryWasEmptyHopperNotDisabled = insertInventory instanceof HopperBlockEntity receiver
            && !receiver.isOnCustomCooldown()
            && hopperBlockEntity.insertStackList != null
            && hopperBlockEntity.insertStackList.getOccupiedSlots() == 0;
        boolean insertInventoryHandlesModdedCooldown = ((LithiumCooldownReceivingInventory)insertInventory).canReceiveTransferCooldown()
                && hopperBlockEntity.insertStackList != null
            ? hopperBlockEntity.insertStackList.getOccupiedSlots() == 0
            : insertInventory.isEmpty();
        if (!(hopperBlockEntity.insertInventory == insertInventory
            && hopperBlockEntity.insertStackList.getFullSlots() == hopperBlockEntity.insertStackList.size())) {
            Direction fromDirection = hopperBlockEntity.facing.getOpposite();
            int size = hopperStackList.size();

            for (int i = 0; i < size; i++) {
                ItemStack transferStack = hopperStackList.get(i);
                if (!transferStack.isEmpty() && HopperHelper.tryMoveSingleItem(insertInventory, transferStack, fromDirection)) {
                    if (insertInventoryWasEmptyHopperNotDisabled) {
                        HopperBlockEntity receivingHopper = (HopperBlockEntity)insertInventory;
                        int cooldown = 8;
                        if (receivingHopper.tickedGameTime >= hopperBlockEntity.tickedGameTime) {
                            cooldown = 7;
                        }

                        receivingHopper.setCooldown(cooldown);
                    }

                    if (insertInventoryHandlesModdedCooldown) {
                        ((LithiumCooldownReceivingInventory)insertInventory).setTransferCooldown(hopperBlockEntity.tickedGameTime);
                    }

                    insertInventory.setChanged();
                    return true;
                }
            }
        }

        hopperBlockEntity.myModCountAtLastInsert = hopperStackList.getModCount();
        if (hopperBlockEntity.insertStackList != null) {
            hopperBlockEntity.insertStackListModCount = hopperBlockEntity.insertStackList.getModCount();
        }

        return false;
    }

    /**
     * MODIFIED for porting: was lithium's block.hopper HopperBlockEntityMixin#lithiumExtract - an optimized but equivalent
     * replacement of the extract loop. Returns null to fall back to the vanilla loop, which happens for hopper minecarts and
     * for source inventories lithium cannot optimize.
     */
    private static @Nullable Boolean lithium$extract(final Hopper to, final Container from) {
        if (!(to instanceof HopperBlockEntity hopperBlockEntity)) {
            // optimizations not implemented for hopper minecarts
            return null;
        }

        if (from != hopperBlockEntity.extractInventory || hopperBlockEntity.extractStackList == null) {
            // the source inventory is not an optimized inventory, vanilla fallback
            return null;
        }

        LithiumStackList hopperStackList = InventoryHelper.getLithiumStackList(hopperBlockEntity);
        LithiumStackList fromStackList = hopperBlockEntity.extractStackList;
        if (hopperStackList.getModCount() == hopperBlockEntity.myModCountAtLastExtract
            && fromStackList.getModCount() == hopperBlockEntity.extractStackListModCount) {
            if (!(from instanceof ComparatorTracker comparatorTracker) || comparatorTracker.lithium$hasAnyComparatorNearby()) {
                fromStackList.runComparatorUpdatePatternOnFailedExtract(fromStackList, from);
            }

            return false;
        }

        int[] availableSlots = from instanceof WorldlyContainer worldly ? worldly.getSlotsForFace(Direction.DOWN) : null;
        int fromSize = availableSlots != null ? availableSlots.length : from.getContainerSize();

        for (int i = 0; i < fromSize; i++) {
            int fromSlot = availableSlots != null ? availableSlots[i] : i;
            ItemStack itemStack = fromStackList.get(fromSlot);
            if (!itemStack.isEmpty() && canTakeItemFromContainer(to, from, itemStack, fromSlot, Direction.DOWN)) {
                // calling removeItem is necessary due to its side effects (setChanged in RandomizableContainerBlockEntity)
                ItemStack takenItem = from.removeItem(fromSlot, 1);
                if (HopperHelper.tryMoveSingleItem(to, takenItem, null)) {
                    to.setChanged();
                    from.setChanged();
                    return true;
                }

                // put the item back similar to vanilla
                ItemStack restoredStack = fromStackList.get(fromSlot);
                if (restoredStack.isEmpty()) {
                    restoredStack = takenItem;
                } else {
                    restoredStack.grow(1);
                }

                // calling setItem is necessary due to its side effects (setChanged in RandomizableContainerBlockEntity)
                from.setItem(fromSlot, restoredStack);
            }
        }

        hopperBlockEntity.myModCountAtLastExtract = hopperStackList.getModCount();
        hopperBlockEntity.extractStackListModCount = fromStackList.getModCount();
        return false;
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#getExtractInventory (@Redirect target)
    private static @Nullable Container lithium$getExtractInventory(
        final Level level, final Hopper hopper, final BlockPos extractBlockPos, final BlockState extractBlockState
    ) {
        if (!(hopper instanceof HopperBlockEntity hopperBlockEntity)) {
            // Hopper minecarts do not cache inventories
            return getSourceContainer(level, hopper, extractBlockPos, extractBlockState);
        }

        Container blockInventory = hopperBlockEntity.lithium$getExtractBlockInventory(level, extractBlockPos, extractBlockState);
        return blockInventory != null ? blockInventory : hopperBlockEntity.lithium$getExtractEntityInventory(level);
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithiumGetInputItemEntities (@Redirect target)
    private static List<ItemEntity> lithium$getInputItemEntities(final Level level, final Hopper hopper) {
        if (!(hopper instanceof HopperBlockEntity hopperBlockEntity)) {
            // optimizations not implemented for hopper minecarts
            return getItemsAtAndAbove(level, hopper);
        }

        if (hopperBlockEntity.collectItemEntityTracker == null) {
            hopperBlockEntity.lithium$initCollectItemEntityTracker();
        }

        long modCount = InventoryHelper.getLithiumStackList(hopperBlockEntity).getModCount();
        if ((hopperBlockEntity.collectItemEntityTrackerWasEmpty || hopperBlockEntity.myModCountAtLastItemCollect == modCount)
            && hopperBlockEntity.collectItemEntityTracker.isUnchangedSince(hopperBlockEntity.collectItemEntityAttemptTime)) {
            hopperBlockEntity.collectItemEntityAttemptTime = hopperBlockEntity.tickedGameTime;
            return Collections.emptyList();
        }

        hopperBlockEntity.myModCountAtLastItemCollect = modCount;
        hopperBlockEntity.shouldCheckSleep = false;
        List<ItemEntity> itemEntities = hopperBlockEntity.collectItemEntityTracker.getEntities(hopperBlockEntity.collectItemEntityBox);
        hopperBlockEntity.collectItemEntityAttemptTime = hopperBlockEntity.tickedGameTime;
        // Set unchanged so that if this extract fails and there is no other change to hoppers or items, extracting items can
        // be skipped.
        hopperBlockEntity.collectItemEntityTrackerWasEmpty = itemEntities.isEmpty();
        return itemEntities;
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithium$invalidateCacheOnNeighborUpdate
    @Override
    public void lithium$invalidateCacheOnNeighborUpdate(final boolean fromAbove) {
        // Clear the block inventory cache (composter inventories and no inventory present) on block update / observer update
        if (fromAbove) {
            if (this.extractionMode == HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY
                || this.extractionMode == HopperCachingState.BlockInventory.BLOCK_STATE) {
                this.lithium$invalidateBlockExtractionData();
            }
        } else if (this.insertionMode == HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY
            || this.insertionMode == HopperCachingState.BlockInventory.BLOCK_STATE) {
            this.lithium$invalidateBlockInsertionData();
        }
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithium$invalidateCacheOnUndirectedNeighborUpdate
    @Override
    public void lithium$invalidateCacheOnUndirectedNeighborUpdate() {
        if (this.extractionMode == HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY
            || this.extractionMode == HopperCachingState.BlockInventory.BLOCK_STATE) {
            this.lithium$invalidateBlockExtractionData();
        }

        if (this.insertionMode == HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY
            || this.insertionMode == HopperCachingState.BlockInventory.BLOCK_STATE) {
            this.lithium$invalidateBlockInsertionData();
        }
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithium$invalidateCacheOnNeighborUpdate
    @Override
    public void lithium$invalidateCacheOnNeighborUpdate(final Direction fromDirection) {
        boolean fromAbove = fromDirection == Direction.UP;
        if (fromAbove || this.getBlockState().getValue(HopperBlock.FACING) == fromDirection) {
            this.lithium$invalidateCacheOnNeighborUpdate(fromAbove);
        }
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithium$getExtractEntityInventory
    private @Nullable Container lithium$getExtractEntityInventory(final Level level) {
        if (this.extractInventoryEntityTracker == null) {
            this.lithium$initExtractInventoryTracker(level);
        }

        if (this.extractInventoryEntityTracker.isUnchangedSince(this.extractInventoryEntityFailedSearchTime)) {
            this.extractInventoryEntityFailedSearchTime = this.tickedGameTime;
            return null;
        }

        this.extractInventoryEntityFailedSearchTime = Long.MIN_VALUE;
        this.shouldCheckSleep = false;
        List<Container> inventoryEntities = this.extractInventoryEntityTracker.getEntities(this.extractInventoryEntityBox);
        if (inventoryEntities.isEmpty()) {
            this.extractInventoryEntityFailedSearchTime = this.tickedGameTime;
            // Only set unchanged when no entity is present. This allows shortcutting this case; shortcutting the
            // entity-present case would require checking its change counter.
            return null;
        }

        Container inventory = inventoryEntities.get(level.getRandom().nextInt(inventoryEntities.size()));
        if (inventory instanceof net.caffeinemc.mods.lithium.api.inventory.LithiumInventory optimizedInventory) {
            LithiumStackList extractInventoryStackList = InventoryHelper.getLithiumStackList(optimizedInventory);
            if (inventory != this.extractInventory || this.extractStackList != extractInventoryStackList) {
                // Not caching the inventory (NO_BLOCK_INVENTORY prevents it): this makes change counting on the entity
                // inventory possible without caching it as a block inventory.
                this.lithium$cacheExtractLithiumInventory(optimizedInventory);
            }
        }

        return inventory;
    }

    /**
     * MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#cacheInsertBlockInventory. Makes this hopper remember
     * the given block / block entity inventory.
     */
    private void lithium$cacheInsertBlockInventory(final @Nullable Container insertInventory) {
        if (insertInventory instanceof net.caffeinemc.mods.lithium.api.inventory.LithiumInventory optimizedInventory) {
            this.lithium$cacheInsertLithiumInventory(optimizedInventory);
        } else {
            this.insertInventory = null;
            this.insertStackList = null;
            this.insertStackListModCount = 0L;
        }

        if (insertInventory instanceof BlockEntity || insertInventory instanceof net.minecraft.world.CompoundContainer) {
            this.insertBlockInventory = insertInventory;
            if (insertInventory instanceof InventoryChangeTracker tracker) {
                this.insertionMode = HopperCachingState.BlockInventory.REMOVAL_TRACKING_BLOCK_ENTITY;
                tracker.listenForMajorInventoryChanges(this);
            } else {
                this.insertionMode = HopperCachingState.BlockInventory.BLOCK_ENTITY;
            }
        } else if (insertInventory == null) {
            this.insertBlockInventory = null;
            this.insertionMode = HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY;
        } else {
            this.insertBlockInventory = insertInventory;
            this.insertionMode = insertInventory instanceof BlockStateOnlyInventory
                ? HopperCachingState.BlockInventory.BLOCK_STATE
                : HopperCachingState.BlockInventory.UNKNOWN;
        }
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#cacheInsertLithiumInventory
    private void lithium$cacheInsertLithiumInventory(final net.caffeinemc.mods.lithium.api.inventory.LithiumInventory optimizedInventory) {
        LithiumStackList insertInventoryStackList = InventoryHelper.getLithiumStackList(optimizedInventory);
        this.insertInventory = optimizedInventory;
        this.insertStackList = insertInventoryStackList;
        this.insertStackListModCount = insertInventoryStackList.getModCount() - 1L;
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#cacheExtractLithiumInventory
    private void lithium$cacheExtractLithiumInventory(final net.caffeinemc.mods.lithium.api.inventory.LithiumInventory optimizedInventory) {
        LithiumStackList extractInventoryStackList = InventoryHelper.getLithiumStackList(optimizedInventory);
        this.extractInventory = optimizedInventory;
        this.extractStackList = extractInventoryStackList;
        this.extractStackListModCount = extractInventoryStackList.getModCount() - 1L;
    }

    /**
     * MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#cacheExtractBlockInventory. Makes this hopper remember
     * the given block / block entity inventory.
     */
    private void lithium$cacheExtractBlockInventory(final @Nullable Container extractInventory) {
        if (extractInventory instanceof net.caffeinemc.mods.lithium.api.inventory.LithiumInventory optimizedInventory) {
            this.lithium$cacheExtractLithiumInventory(optimizedInventory);
        } else {
            this.extractInventory = null;
            this.extractStackList = null;
            this.extractStackListModCount = 0L;
        }

        if (extractInventory instanceof BlockEntity || extractInventory instanceof net.minecraft.world.CompoundContainer) {
            this.extractBlockInventory = extractInventory;
            if (extractInventory instanceof InventoryChangeTracker tracker) {
                this.extractionMode = HopperCachingState.BlockInventory.REMOVAL_TRACKING_BLOCK_ENTITY;
                tracker.listenForMajorInventoryChanges(this);
            } else {
                this.extractionMode = HopperCachingState.BlockInventory.BLOCK_ENTITY;
            }
        } else if (extractInventory == null) {
            this.extractBlockInventory = null;
            this.extractionMode = HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY;
        } else {
            this.extractBlockInventory = extractInventory;
            this.extractionMode = extractInventory instanceof BlockStateOnlyInventory
                ? HopperCachingState.BlockInventory.BLOCK_STATE
                : HopperCachingState.BlockInventory.UNKNOWN;
        }
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithium$getExtractBlockInventory
    public @Nullable Container lithium$getExtractBlockInventory(final Level level, final BlockPos extractBlockPos, final BlockState extractBlockState) {
        Container blockInventory = this.extractBlockInventory;
        if (this.extractionMode == HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY) {
            return null;
        } else if (this.extractionMode == HopperCachingState.BlockInventory.BLOCK_STATE
            || this.extractionMode == HopperCachingState.BlockInventory.REMOVAL_TRACKING_BLOCK_ENTITY) {
            return blockInventory;
        } else if (this.extractionMode == HopperCachingState.BlockInventory.BLOCK_ENTITY) {
            BlockEntity blockEntity = (BlockEntity)Objects.requireNonNull(blockInventory);
            // Movable block entity compatibility - position comparison
            if (!blockEntity.isRemoved() && blockEntity.getBlockPos().equals(extractBlockPos)) {
                net.caffeinemc.mods.lithium.api.inventory.LithiumInventory optimizedInventory = this.extractInventory;
                if (optimizedInventory == null) {
                    return blockInventory;
                }

                // This check is necessary as sometimes the stack list is silently replaced (e.g. a command making a furnace
                // read its inventory from nbt).
                if (InventoryHelper.getLithiumStackList(optimizedInventory) == this.extractStackList) {
                    return optimizedInventory;
                }

                this.lithium$invalidateBlockExtractionData();
            }
        }

        // No cached inventory: get it like vanilla and cache it
        blockInventory = getBlockContainer(level, extractBlockPos, extractBlockState);
        blockInventory = HopperHelper.replaceDoubleInventory(blockInventory);
        this.lithium$cacheExtractBlockInventory(blockInventory);
        return blockInventory;
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithium$getInsertBlockInventory
    public @Nullable Container lithium$getInsertBlockInventory(final Level level) {
        Container blockInventory = this.insertBlockInventory;
        if (this.insertionMode == HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY) {
            return null;
        } else if (this.insertionMode == HopperCachingState.BlockInventory.BLOCK_STATE
            || this.insertionMode == HopperCachingState.BlockInventory.REMOVAL_TRACKING_BLOCK_ENTITY) {
            return blockInventory;
        } else if (this.insertionMode == HopperCachingState.BlockInventory.BLOCK_ENTITY) {
            BlockEntity blockEntity = (BlockEntity)Objects.requireNonNull(blockInventory);
            // Movable block entity compatibility - position comparison
            BlockPos transferPos = this.getBlockPos().relative(this.facing);
            if (!blockEntity.isRemoved() && blockEntity.getBlockPos().equals(transferPos)) {
                net.caffeinemc.mods.lithium.api.inventory.LithiumInventory optimizedInventory = this.insertInventory;
                if (optimizedInventory == null) {
                    return blockInventory;
                }

                // This check is necessary as sometimes the stack list is silently replaced (e.g. a command making a furnace
                // read its inventory from nbt).
                if (InventoryHelper.getLithiumStackList(optimizedInventory) == this.insertStackList) {
                    return optimizedInventory;
                }

                this.lithium$invalidateBlockInsertionData();
            }
        }

        // No cached inventory: get it like vanilla and cache it
        BlockPos insertBlockPos = this.getBlockPos().relative(this.facing);
        BlockState blockState = level.getBlockState(insertBlockPos);
        blockInventory = getBlockContainer(level, insertBlockPos, blockState);
        blockInventory = HopperHelper.replaceDoubleInventory(blockInventory);
        this.lithium$cacheInsertBlockInventory(blockInventory);
        return blockInventory;
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#getInsertInventory
    public @Nullable Container lithium$getInsertInventory(final Level level) {
        Container blockInventory = this.lithium$getInsertBlockInventory(level);
        if (blockInventory != null) {
            return blockInventory;
        }

        if (this.insertInventoryEntityTracker == null) {
            this.lithium$initInsertInventoryTracker(level);
        }

        if (this.insertInventoryEntityTracker.isUnchangedSince(this.insertInventoryEntityFailedSearchTime)) {
            this.insertInventoryEntityFailedSearchTime = this.tickedGameTime;
            return null;
        }

        this.insertInventoryEntityFailedSearchTime = Long.MIN_VALUE;
        this.shouldCheckSleep = false;
        List<Container> inventoryEntities = this.insertInventoryEntityTracker.getEntities(this.insertInventoryEntityBox);
        if (inventoryEntities.isEmpty()) {
            // Remember the failed entity search timestamp. This allows shortcutting if no entity movement happens.
            this.insertInventoryEntityFailedSearchTime = this.tickedGameTime;
            return null;
        }

        Container inventory = inventoryEntities.get(level.getRandom().nextInt(inventoryEntities.size()));
        if (inventory instanceof net.caffeinemc.mods.lithium.api.inventory.LithiumInventory optimizedInventory) {
            LithiumStackList insertInventoryStackList = InventoryHelper.getLithiumStackList(optimizedInventory);
            if (inventory != this.insertInventory || this.insertStackList != insertInventoryStackList) {
                this.lithium$cacheInsertLithiumInventory(optimizedInventory);
            }
        }

        return inventory;
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#initCollectItemEntityTracker
    private void lithium$initCollectItemEntityTracker() {
        AABB inputBox = this.getSuckAabb().move(this.worldPosition.getX(), this.worldPosition.getY(), this.worldPosition.getZ());
        this.collectItemEntityBox = inputBox;
        this.collectItemEntityTracker = SectionedItemEntityMovementTracker.registerAt((ServerLevel)this.level, inputBox, ItemEntity.class);
        this.collectItemEntityAttemptTime = Long.MIN_VALUE;
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#initExtractInventoryTracker
    private void lithium$initExtractInventoryTracker(final Level level) {
        BlockPos pos = this.worldPosition.relative(Direction.UP);
        this.extractInventoryEntityBox = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        this.extractInventoryEntityTracker = SectionedInventoryEntityMovementTracker.registerAt(
            (ServerLevel)this.level, this.extractInventoryEntityBox, Container.class
        );
        this.extractInventoryEntityFailedSearchTime = Long.MIN_VALUE;
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#initInsertInventoryTracker
    private void lithium$initInsertInventoryTracker(final Level level) {
        BlockPos pos = this.worldPosition.relative(this.facing);
        this.insertInventoryEntityBox = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        this.insertInventoryEntityTracker = SectionedInventoryEntityMovementTracker.registerAt(
            (ServerLevel)this.level, this.insertInventoryEntityBox, Container.class
        );
        this.insertInventoryEntityFailedSearchTime = Long.MIN_VALUE;
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#invalidateCachedData
    private void lithium$invalidateCachedData() {
        this.shouldCheckSleep = false;
        this.lithium$invalidateInsertionData();
        this.lithium$invalidateExtractionData();
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#invalidateInsertionData
    private void lithium$invalidateInsertionData() {
        if (this.level instanceof ServerLevel serverLevel && this.insertInventoryEntityTracker != null) {
            this.insertInventoryEntityTracker.unRegister(serverLevel);
            this.insertInventoryEntityTracker = null;
            this.insertInventoryEntityBox = null;
            this.insertInventoryEntityFailedSearchTime = 0L;
        }

        this.lithium$invalidateBlockInsertionData();
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#invalidateBlockInsertionData
    private void lithium$invalidateBlockInsertionData() {
        if (this.insertionMode == HopperCachingState.BlockInventory.REMOVAL_TRACKING_BLOCK_ENTITY) {
            ((InventoryChangeTracker)Objects.requireNonNull(this.insertBlockInventory)).stopListenForMajorInventoryChanges(this);
        }

        this.insertionMode = HopperCachingState.BlockInventory.UNKNOWN;
        this.insertBlockInventory = null;
        this.insertInventory = null;
        this.insertStackList = null;
        this.insertStackListModCount = 0L;
        this.wakeUpNow();
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#invalidateExtractionData
    private void lithium$invalidateExtractionData() {
        if (this.level instanceof ServerLevel serverLevel) {
            if (this.extractInventoryEntityTracker != null) {
                this.extractInventoryEntityTracker.unRegister(serverLevel);
                this.extractInventoryEntityTracker = null;
                this.extractInventoryEntityBox = null;
                this.extractInventoryEntityFailedSearchTime = 0L;
            }

            if (this.collectItemEntityTracker != null) {
                this.collectItemEntityTracker.unRegister(serverLevel);
                this.collectItemEntityTracker = null;
                this.collectItemEntityBox = null;
                this.collectItemEntityTrackerWasEmpty = false;
            }
        }

        this.lithium$invalidateBlockExtractionData();
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#invalidateBlockExtractionData
    private void lithium$invalidateBlockExtractionData() {
        if (this.extractionMode == HopperCachingState.BlockInventory.REMOVAL_TRACKING_BLOCK_ENTITY) {
            ((InventoryChangeTracker)Objects.requireNonNull(this.extractBlockInventory)).stopListenForMajorInventoryChanges(this);
        }

        this.extractionMode = HopperCachingState.BlockInventory.UNKNOWN;
        this.extractBlockInventory = null;
        this.extractInventory = null;
        this.extractStackList = null;
        this.extractStackListModCount = 0L;
        this.wakeUpNow();
    }

    /**
     * MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#checkSleepingConditions. A hopper may go to sleep once
     * every source of change it depends on has a listener registered that wakes it up again.
     */
    private void lithium$checkSleepingConditions() {
        if (this.isOnCooldown() || this.getLevel() == null || this.isSleeping()) {
            return;
        }

        if (!this.shouldCheckSleep) {
            this.shouldCheckSleep = true;
            return;
        }

        boolean listenToExtractTracker = false;
        boolean listenToInsertTracker = false;
        boolean listenToExtractEntities = false;
        boolean listenToItemEntities = false;
        boolean listenToInsertEntities = false;
        LithiumStackList thisStackList = InventoryHelper.getLithiumStackList(this);
        if (this.extractionMode != HopperCachingState.BlockInventory.BLOCK_STATE && thisStackList.getFullSlots() != thisStackList.size()) {
            if (this.extractionMode == HopperCachingState.BlockInventory.REMOVAL_TRACKING_BLOCK_ENTITY) {
                Container blockInventory = this.extractBlockInventory;
                if (this.extractStackList == null || !(blockInventory instanceof InventoryChangeTracker)) {
                    return;
                }

                if (this.extractStackList.maybeSendsComparatorUpdatesOnFailedExtract() && this.extractStackList.getOccupiedSlots() != 0) {
                    if (blockInventory instanceof ComparatorTracker comparatorTracker && !comparatorTracker.lithium$hasAnyComparatorNearby()) {
                        listenToExtractTracker = true;
                    } else {
                        // The inventory is not empty (0 != number of occupied slots) and maybe sends comparator updates on
                        // failed extract attempts, so the hopper must not sleep to be able to send the observable comparator
                        // updates.
                        return;
                    }
                } else {
                    listenToExtractTracker = true;
                }
            } else if (this.extractionMode == HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY) {
                BlockState hopperState = this.getBlockState();
                if (PlatformModCompat.INSTANCE.canHopperInteractWithApiBlockInventory(this, hopperState, true)) {
                    return;
                }

                listenToExtractEntities = true;
                BlockPos blockPos = this.getBlockPos().above();
                BlockState blockState = this.getLevel().getBlockState(blockPos);
                if (!blockState.isCollisionShapeFullBlock(this.getLevel(), blockPos) || blockState.is(BlockTags.DOES_NOT_BLOCK_HOPPERS)) {
                    listenToItemEntities = true;
                }
            } else {
                return;
            }
        }

        if (this.insertionMode != HopperCachingState.BlockInventory.BLOCK_STATE && thisStackList.getOccupiedSlots() > 0) {
            if (this.insertionMode == HopperCachingState.BlockInventory.REMOVAL_TRACKING_BLOCK_ENTITY) {
                if (this.insertStackList == null || !(this.insertBlockInventory instanceof InventoryChangeTracker)) {
                    return;
                }

                listenToInsertTracker = true;
            } else if (this.insertionMode == HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY) {
                BlockState hopperState = this.getBlockState();
                if (PlatformModCompat.INSTANCE.canHopperInteractWithApiBlockInventory(this, hopperState, false)) {
                    return;
                }

                listenToInsertEntities = true;
            } else {
                return;
            }
        }

        if (listenToExtractTracker) {
            ((InventoryChangeTracker)this.extractBlockInventory).listenForContentChangesOnce(this.extractStackList, this);
        }

        if (listenToInsertTracker) {
            ((InventoryChangeTracker)this.insertBlockInventory).listenForContentChangesOnce(this.insertStackList, this);
        }

        if (listenToInsertEntities) {
            if (this.insertInventoryEntityTracker == null) {
                return;
            }

            this.insertInventoryEntityTracker.listenToEntityMovementOnce(this);
        }

        if (listenToExtractEntities) {
            if (this.extractInventoryEntityTracker == null) {
                return;
            }

            this.extractInventoryEntityTracker.listenToEntityMovementOnce(this);
        }

        if (listenToItemEntities) {
            if (this.collectItemEntityTracker == null) {
                return;
            }

            this.collectItemEntityTracker.listenToEntityMovementOnce(this);
        }

        this.listenForContentChangesOnce(thisStackList, this);
        this.lithium$startSleeping();
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithium$handleInventoryContentModified
    @Override
    public void lithium$handleInventoryContentModified(final Container inventory) {
        this.wakeUpNow();
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithium$handleInventoryRemoved
    @Override
    public void lithium$handleInventoryRemoved(final Container inventory) {
        this.wakeUpNow();
        if (inventory == this.insertBlockInventory) {
            this.lithium$invalidateBlockInsertionData();
        }

        if (inventory == this.extractBlockInventory) {
            this.lithium$invalidateBlockExtractionData();
        }

        if (inventory == this) {
            this.lithium$invalidateCachedData();
        }
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithium$handleComparatorAdded
    @Override
    public boolean lithium$handleComparatorAdded(final Container inventory) {
        if (inventory == this.extractBlockInventory) {
            this.wakeUpNow();
            return true;
        }

        return false;
    }

    // MODIFIED for porting: lithium block.hopper HopperBlockEntityMixin#lithium$handleEntityMovement
    @Override
    public void lithium$handleEntityMovement(final Object category) {
        this.wakeUpNow();
    }

    @Override
    protected AbstractContainerMenu createMenu(final int containerId, final Inventory inventory) {
        return new HopperMenu(containerId, inventory, this);
    }
}