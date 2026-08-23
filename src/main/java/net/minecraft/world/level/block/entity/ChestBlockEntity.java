package net.minecraft.world.level.block.entity;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public class ChestBlockEntity extends RandomizableContainerBlockEntity
    implements LidBlockEntity,
    net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeTracker, // MODIFIED for porting: lithium util.inventory_change_listening
    net.caffeinemc.mods.lithium.api.inventory.LithiumInventory, // MODIFIED for porting: lithium block.hopper InventoryAccessors
    net.caffeinemc.mods.lithium.common.block.entity.SleepingBlockEntity { // MODIFIED for porting: lithium world.block_entity_ticking.sleeping.chest_animation
    // MODIFIED for porting: the following fields/methods were lithium's world.block_entity_ticking.sleeping.chest_animation ChestBlockEntityMixin
    // A block entity that has nothing to do parks itself by swapping the ticker inside its tick wrapper for a
    // no-op one, and is woken up again by the events below.
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

    // MODIFIED for porting: lithium chest_animation ChestBlockEntityMixin#checkSleep. Once the lid animation has
    // finished it stays unchanged until the next triggerEvent, which is where the block entity is woken up again.
    private void lithium$checkSleep() {
        if (this.getOpenNess(0.0F) == this.getOpenNess(1.0F)) {
            this.lithium$startSleeping();
        }
    }
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

    private static final int EVENT_SET_OPEN_COUNT = 1;
    private static final Component DEFAULT_NAME = Component.translatable("container.chest");
    private NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
    private final ContainerOpenersCounter openersCounter = new ContainerOpenersCounter() {
        @Override
        protected void onOpen(final Level level, final BlockPos pos, final BlockState blockState) {
            if (blockState.getBlock() instanceof ChestBlock chestBlock) {
                ChestBlockEntity.playSound(level, pos, blockState, chestBlock.getOpenChestSound());
            }
        }

        @Override
        protected void onClose(final Level level, final BlockPos pos, final BlockState blockState) {
            if (blockState.getBlock() instanceof ChestBlock chestBlock) {
                ChestBlockEntity.playSound(level, pos, blockState, chestBlock.getCloseChestSound());
            }
        }

        @Override
        protected void openerCountChanged(final Level level, final BlockPos pos, final BlockState blockState, final int previous, final int current) {
            ChestBlockEntity.this.signalOpenCount(level, pos, blockState, previous, current);
        }

        @Override
        public boolean isOwnContainer(final Player player) {
            if (!(player.containerMenu instanceof ChestMenu)) {
                return false;
            }

            Container container = ((ChestMenu)player.containerMenu).getContainer();
            return container == ChestBlockEntity.this
                || container instanceof CompoundContainer compoundContainer && compoundContainer.contains(ChestBlockEntity.this);
        }
    };
    private final ChestLidController chestLidController = new ChestLidController();

    protected ChestBlockEntity(final BlockEntityType<?> type, final BlockPos worldPosition, final BlockState blockState) {
        super(type, worldPosition, blockState);
    }

    public ChestBlockEntity(final BlockPos worldPosition, final BlockState blockState) {
        this(BlockEntityTypes.CHEST, worldPosition, blockState);
    }

    @Override
    public int getContainerSize() {
        return 27;
    }

    @Override
    protected Component getDefaultName() {
        return DEFAULT_NAME;
    }

    @Override
    protected void loadAdditional(final ValueInput input) {
        super.loadAdditional(input);
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        if (!this.tryLoadLootTable(input)) {
            ContainerHelper.loadAllItems(input, this.items);
        }
    }

    @Override
    protected void saveAdditional(final ValueOutput output) {
        super.saveAdditional(output);
        if (!this.trySaveLootTable(output)) {
            ContainerHelper.saveAllItems(output, this.items);
        }
    }

    public static void lidAnimateTick(final Level level, final BlockPos pos, final BlockState state, final ChestBlockEntity entity) {
        entity.chestLidController.tickLid();
        // MODIFIED for porting: lithium world.block_entity_ticking.sleeping.chest_animation
        // ChestBlockEntityMixin#sleepOnAnimationEnd (RETURN)
        entity.lithium$checkSleep();
    }

    private static void playSound(final Level level, final BlockPos worldPosition, final BlockState blockState, final SoundEvent event) {
        ChestType type = blockState.getValue(ChestBlock.TYPE);
        if (type != ChestType.LEFT) {
            double x = worldPosition.getX() + 0.5;
            double y = worldPosition.getY() + 0.5;
            double z = worldPosition.getZ() + 0.5;
            if (type == ChestType.RIGHT) {
                Direction direction = ChestBlock.getConnectedDirection(blockState);
                x += direction.getStepX() * 0.5;
                z += direction.getStepZ() * 0.5;
            }

            level.playSound(null, x, y, z, event, SoundSource.BLOCKS, 0.5F, level.getRandom().nextFloat() * 0.1F + 0.9F);
        }
    }

    @Override
    public boolean triggerEvent(final int b0, final int b1) {
        if (b0 == 1) {
            // MODIFIED for porting: lithium chest_animation ChestBlockEntityMixin#wakeUpOnSyncedBlockEvent
            if (this.lithium$getSleepingTicker() != null) {
                this.wakeUpNow();
            }

            this.chestLidController.shouldBeOpen(b1 > 0);
            return true;
        } else {
            return super.triggerEvent(b0, b1);
        }
    }

    @Override
    public void startOpen(final ContainerUser containerUser) {
        if (!this.remove && !containerUser.getLivingEntity().isSpectator()) {
            this.openersCounter
                .incrementOpeners(
                    containerUser.getLivingEntity(), this.getLevel(), this.getBlockPos(), this.getBlockState(), containerUser.getContainerInteractionRange()
                );
        }
    }

    @Override
    public void stopOpen(final ContainerUser containerUser) {
        if (!this.remove && !containerUser.getLivingEntity().isSpectator()) {
            this.openersCounter.decrementOpeners(containerUser.getLivingEntity(), this.getLevel(), this.getBlockPos(), this.getBlockState());
        }
    }

    @Override
    public List<ContainerUser> getEntitiesWithContainerOpen() {
        return this.openersCounter.getEntitiesWithContainerOpen(this.getLevel(), this.getBlockPos());
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return this.items;
    }

    // MODIFIED for porting: was lithium-fabric's util.inventory_change_listening ChestBlockEntityMixin - a chest
    // that switches between the single and double variant has to invalidate its inventory listeners.
    @Override
    public void lithium$handleSetBlockState() {
        this.lithium$emitRemoved();
    }

    @Override
    protected void setItems(final NonNullList<ItemStack> items) {
        this.items = items;
        // MODIFIED for porting: lithium util.inventory_change_listening StackListReplacementTracking (RETURN of setItems)
        this.lithium$emitStackListReplaced();
    }

    @Override
    public float getOpenNess(final float a) {
        return this.chestLidController.getOpenness(a);
    }

    public static int getOpenCount(final BlockGetter level, final BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.hasBlockEntity() && level.getBlockEntity(pos) instanceof ChestBlockEntity chestBlockEntity
            ? chestBlockEntity.openersCounter.getOpenerCount()
            : 0;
    }

    public static void swapContents(final ChestBlockEntity one, final ChestBlockEntity two) {
        NonNullList<ItemStack> items = one.getItems();
        one.setItems(two.getItems());
        two.setItems(items);
    }

    @Override
    protected AbstractContainerMenu createMenu(final int containerId, final Inventory inventory) {
        return ChestMenu.threeRows(containerId, inventory, this);
    }

    public void recheckOpen() {
        if (!this.remove) {
            this.openersCounter.recheckOpeners(this.getLevel(), this.getBlockPos(), this.getBlockState());
        }
    }

    protected void signalOpenCount(final Level level, final BlockPos pos, final BlockState blockState, final int previous, final int current) {
        Block block = blockState.getBlock();
        level.blockEvent(pos, block, 1, current);
    }
}