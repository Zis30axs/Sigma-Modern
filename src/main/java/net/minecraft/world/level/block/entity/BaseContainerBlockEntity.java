package net.minecraft.world.level.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.LockCode;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

// MODIFIED for porting: lithium util.inventory_change_listening BaseContainerBlockEntityMixin implements the
// inventory change event plumbing here, because modded inventories inherit from this class without necessarily
// producing the events themselves (that part is InventoryChangeTracker).
public abstract class BaseContainerBlockEntity extends BlockEntity
    implements Container, MenuProvider, Nameable, net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeEmitter {
    private LockCode lockKey = LockCode.NO_LOCK;
    private @Nullable Component name;

    protected BaseContainerBlockEntity(final BlockEntityType<?> type, final BlockPos worldPosition, final BlockState blockState) {
        super(type, worldPosition, blockState);
    }

    // MODIFIED for porting: lithium util.inventory_change_listening BaseContainerBlockEntityMixin @Unique fields
    private it.unimi.dsi.fastutil.objects.ReferenceArraySet<net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeListener> lithium$inventoryChangeListeners = null;
    private it.unimi.dsi.fastutil.objects.ReferenceArraySet<net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeListener> lithium$inventoryHandlingTypeListeners = null;

    // MODIFIED for porting: the following block of methods was lithium's BaseContainerBlockEntityMixin
    @Override
    public void lithium$emitContentModified() {
        it.unimi.dsi.fastutil.objects.ReferenceArraySet<net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeListener> inventoryChangeListeners = this.lithium$inventoryChangeListeners;
        if (inventoryChangeListeners != null) {
            for (net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeListener inventoryChangeListener : inventoryChangeListeners) {
                inventoryChangeListener.lithium$handleInventoryContentModified(this);
            }

            inventoryChangeListeners.clear();
        }
    }

    @Override
    public void lithium$emitStackListReplaced() {
        this.lithium$invalidateChangeListening();
    }

    @Override
    public void lithium$emitRemoved() {
        this.lithium$invalidateChangeListening();
    }

    private void lithium$invalidateChangeListening() {
        // Invalidate listeners to this inventory
        it.unimi.dsi.fastutil.objects.ReferenceArraySet<net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeListener> listeners = this.lithium$inventoryHandlingTypeListeners;
        this.lithium$inventoryHandlingTypeListeners = null; // Prevent concurrent modification
        if (listeners != null && !listeners.isEmpty()) {
            listeners.forEach(listener -> listener.lithium$handleInventoryRemoved(this));
            listeners.clear();
        }

        if (this.lithium$inventoryHandlingTypeListeners == null) {
            this.lithium$inventoryHandlingTypeListeners = listeners;
        }

        if (this instanceof net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeListener listener) {
            listener.lithium$handleInventoryRemoved(this);
        }

        if (this.lithium$inventoryChangeListeners != null) {
            this.lithium$inventoryChangeListeners.clear();
        }

        // Invalidate own listening
        net.caffeinemc.mods.lithium.common.hopper.LithiumStackList lithiumStackList = this instanceof net.caffeinemc.mods.lithium.api.inventory.LithiumInventory
            ? net.caffeinemc.mods.lithium.common.hopper.InventoryHelper.getLithiumStackListOrNull((net.caffeinemc.mods.lithium.api.inventory.LithiumInventory)this)
            : null;
        if (lithiumStackList != null && this instanceof net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeTracker inventoryChangeTracker) {
            lithiumStackList.removeInventoryModificationCallback(inventoryChangeTracker);
        }
    }

    @Override
    public void lithium$emitFirstComparatorAdded() {
        it.unimi.dsi.fastutil.objects.ReferenceArraySet<net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeListener> inventoryChangeListeners = this.lithium$inventoryChangeListeners;
        if (inventoryChangeListeners != null && !inventoryChangeListeners.isEmpty()) {
            inventoryChangeListeners.removeIf(inventoryChangeListener -> inventoryChangeListener.lithium$handleComparatorAdded(this));
        }
    }

    @Override
    public void lithium$forwardContentChangeOnce(final net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeListener inventoryChangeListener, final net.caffeinemc.mods.lithium.common.hopper.LithiumStackList stackList) {
        if (this.lithium$inventoryChangeListeners == null) {
            this.lithium$inventoryChangeListeners = new it.unimi.dsi.fastutil.objects.ReferenceArraySet<>(1);
        }

        stackList.setNextInventoryModificationCallback((net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeTracker)this);
        this.lithium$inventoryChangeListeners.add(inventoryChangeListener);
    }

    @Override
    public void lithium$forwardMajorInventoryChanges(final net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeListener inventoryChangeListener) {
        if (this.lithium$inventoryHandlingTypeListeners == null) {
            this.lithium$inventoryHandlingTypeListeners = new it.unimi.dsi.fastutil.objects.ReferenceArraySet<>(1);
        }

        this.lithium$inventoryHandlingTypeListeners.add(inventoryChangeListener);
    }

    @Override
    public void lithium$stopForwardingMajorInventoryChanges(final net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeListener inventoryChangeListener) {
        if (this.lithium$inventoryHandlingTypeListeners != null) {
            this.lithium$inventoryHandlingTypeListeners.remove(inventoryChangeListener);
        }
    }

    @Override
    protected void loadAdditional(final ValueInput input) {
        super.loadAdditional(input);
        this.lockKey = LockCode.fromTag(input);
        this.name = parseCustomNameSafe(input, "CustomName");
        // MODIFIED for porting: lithium util.inventory_change_listening
        // StackListReplacementTracking$BaseContainerBlockEntityMixin#readNbtStackListReplacement (RETURN)
        if (this instanceof net.caffeinemc.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeTracker lithium$inventoryChangeTracker) {
            lithium$inventoryChangeTracker.lithium$emitStackListReplaced();
        }
    }

    @Override
    protected void saveAdditional(final ValueOutput output) {
        super.saveAdditional(output);
        this.lockKey.addToTag(output);
        output.storeNullable("CustomName", ComponentSerialization.CODEC, this.name);
    }

    @Override
    public Component getName() {
        return this.name != null ? this.name : this.getDefaultName();
    }

    @Override
    public Component getDisplayName() {
        return this.getName();
    }

    @Override
    public @Nullable Component getCustomName() {
        return this.name;
    }

    protected abstract Component getDefaultName();

    public boolean canOpen(final Player player) {
        return this.lockKey.canUnlock(player);
    }

    public static void sendChestLockedNotifications(final Vec3 pos, final Player player, final Component displayName) {
        Level level = player.level();
        player.sendOverlayMessage(Component.translatable("container.isLocked", displayName));
        if (!level.isClientSide()) {
            level.playSound(null, pos.x(), pos.y(), pos.z(), SoundEvents.CHEST_LOCKED, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    public boolean isLocked() {
        return !this.lockKey.equals(LockCode.NO_LOCK);
    }

    protected abstract NonNullList<ItemStack> getItems();

    protected abstract void setItems(NonNullList<ItemStack> items);

    @Override
    public boolean isEmpty() {
        for (ItemStack itemStack : this.getItems()) {
            if (!itemStack.isEmpty()) {
                return false;
            }
        }

        return true;
    }

    @Override
    public ItemStack getItem(final int slot) {
        return this.getItems().get(slot);
    }

    @Override
    public ItemStack removeItem(final int slot, final int count) {
        ItemStack result = ContainerHelper.removeItem(this.getItems(), slot, count);
        if (!result.isEmpty()) {
            this.setChanged();
        }

        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(final int slot) {
        return ContainerHelper.takeItem(this.getItems(), slot);
    }

    @Override
    public void setItem(final int slot, final ItemStack itemStack) {
        this.getItems().set(slot, itemStack);
        itemStack.limitSize(this.getMaxStackSize(itemStack));
        this.setChanged();
    }

    @Override
    public boolean stillValid(final Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        this.getItems().clear();
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(final int containerId, final Inventory inventory, final Player player) {
        if (this.canOpen(player)) {
            return this.createMenu(containerId, inventory);
        }

        sendChestLockedNotifications(Vec3.atCenterOf(this.getBlockPos()), player, this.getDisplayName());
        return null;
    }

    protected abstract AbstractContainerMenu createMenu(final int containerId, final Inventory inventory);

    @Override
    protected void applyImplicitComponents(final DataComponentGetter components) {
        super.applyImplicitComponents(components);
        this.name = components.get(DataComponents.CUSTOM_NAME);
        this.lockKey = components.getOrDefault(DataComponents.LOCK, LockCode.NO_LOCK);
        components.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(this.getItems());
    }

    @Override
    protected void collectImplicitComponents(final DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(DataComponents.CUSTOM_NAME, this.name);
        if (this.isLocked()) {
            components.set(DataComponents.LOCK, this.lockKey);
        }

        components.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(this.getItems()));
    }

    @Override
    public void removeComponentsFromTag(final ValueOutput output) {
        output.discard("CustomName");
        output.discard("lock");
        output.discard("Items");
    }
}