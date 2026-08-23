package net.minecraft.world.entity;

import com.mojang.serialization.Codec;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import net.minecraft.world.item.ItemStack;

// MODIFIED for porting: was lithium's entity.equipment_tracking EntityEquipmentMixin. The equipment keeps track of
// whether any of its stacks may have a tickable enchantment and whether its contents changed since the last time the
// changes were sent to clients, so that the expensive per-tick scans can be skipped.
public class EntityEquipment implements
    net.caffeinemc.mods.lithium.common.entity.EquipmentInfo,
    net.caffeinemc.mods.lithium.common.util.change_tracking.ChangeSubscriber.CountChangeSubscriber<ItemStack>,
    net.caffeinemc.mods.lithium.common.world.in_world_tracking.MaybeInLevelObject {
    public static final Codec<EntityEquipment> CODEC = Codec.unboundedMap(EquipmentSlot.CODEC, ItemStack.CODEC).xmap(items -> {
        EnumMap<EquipmentSlot, ItemStack> map = new EnumMap<>(EquipmentSlot.class);
        map.putAll((Map<? extends EquipmentSlot, ? extends ItemStack>)items);
        return new EntityEquipment(map);
    }, equipment -> {
        Map<EquipmentSlot, ItemStack> items = new EnumMap<>(equipment.items);
        items.values().removeIf(ItemStack::isEmpty);
        return items;
    });
    private final EnumMap<EquipmentSlot, ItemStack> items;
    // MODIFIED for porting: lithium entity.equipment_tracking EntityEquipmentMixin @Unique fields
    private boolean shouldTickEnchantments = false;
    private ItemStack recheckEnchantmentForStack = null;
    private boolean hasUnsentEquipmentChanges = true;
    private boolean inLevel = false;

    private EntityEquipment(final EnumMap<EquipmentSlot, ItemStack> items) {
        this.items = items;
    }

    public EntityEquipment() {
        this(new EnumMap<>(EquipmentSlot.class));
    }

    public ItemStack set(final EquipmentSlot slot, final ItemStack itemStack) {
        ItemStack oldStack = Objects.requireNonNullElse(this.items.put(slot, itemStack), ItemStack.EMPTY);
        // MODIFIED for porting: lithium entity.equipment_tracking EntityEquipmentMixin#updateOnSet (@WrapMethod)
        if (this.inLevel) {
            this.onEquipmentReplaced(oldStack, itemStack);
        }

        return oldStack;
    }

    public ItemStack get(final EquipmentSlot slot) {
        return this.items.getOrDefault(slot, ItemStack.EMPTY);
    }

    public boolean isEmpty() {
        for (ItemStack item : this.items.values()) {
            if (!item.isEmpty()) {
                return false;
            }
        }

        return true;
    }

    public void tick(final Entity owner) {
        for (Entry<EquipmentSlot, ItemStack> entry : this.items.entrySet()) {
            ItemStack item = entry.getValue();
            if (!item.isEmpty()) {
                item.inventoryTick(owner.level(), owner, entry.getKey());
            }
        }
    }

    public void setAll(final EntityEquipment equipment) {
        // MODIFIED for porting: lithium entity.equipment_tracking EntityEquipmentMixin#updateBeforeSetAll (HEAD)
        if (this.inLevel) {
            this.invalidateData();
        }

        this.items.clear();
        this.items.putAll(equipment.items);
        // MODIFIED for porting: lithium entity.equipment_tracking EntityEquipmentMixin#updateAfterSetAll (RETURN)
        if (this.inLevel) {
            this.initializeData();
        }
    }

    public void dropAll(final LivingEntity dropper) {
        for (ItemStack item : this.items.values()) {
            dropper.drop(item, true, false);
        }

        this.clear();
    }

    public void clear() {
        this.items.replaceAll((s, v) -> ItemStack.EMPTY);
        // MODIFIED for porting: lithium entity.equipment_tracking EntityEquipmentMixin#updateOnClear (RETURN)
        if (this.inLevel) {
            this.invalidateData();
        }
    }

    // MODIFIED for porting: everything below was lithium's entity.equipment_tracking EntityEquipmentMixin
    @Override
    public boolean lithium$isInLevel() {
        return this.inLevel;
    }

    @Override
    public void lithium$handleAddedToLevel(final net.minecraft.world.level.Level level) {
        this.inLevel = true;
        this.initializeData();

        net.caffeinemc.mods.lithium.common.world.in_world_tracking.MaybeInLevelObject.super.lithium$handleAddedToLevel(level);
    }

    @Override
    public void lithium$handleRemovedFromLevel(final net.minecraft.world.level.Level level) {
        this.inLevel = false;
        this.invalidateData();

        net.caffeinemc.mods.lithium.common.world.in_world_tracking.MaybeInLevelObject.super.lithium$handleRemovedFromLevel(level);
    }

    @Override
    public boolean lithium$shouldTickEnchantments() {
        if (!this.inLevel) {
            return true;
        }

        this.processScheduledEnchantmentCheck(null);
        return this.shouldTickEnchantments;
    }

    @Override
    public boolean lithium$hasUnsentEquipmentChanges() {
        if (!this.inLevel) {
            return true;
        }

        return this.hasUnsentEquipmentChanges;
    }

    @Override
    public void lithium$onEquipmentChangesSent() {
        if (!this.inLevel) {
            return;
        }

        this.hasUnsentEquipmentChanges = false;
    }

    private void invalidateData() {
        this.shouldTickEnchantments = false;
        this.recheckEnchantmentForStack = null;
        this.hasUnsentEquipmentChanges = true;

        for (ItemStack oldStack : this.items.values()) {
            if (!oldStack.isEmpty()) {
                ((net.caffeinemc.mods.lithium.common.util.change_tracking.ChangePublisher<ItemStack>)(Object)oldStack).lithium$unsubscribeWithData(this, 0);
            }
        }
    }

    private void initializeData() {
        this.shouldTickEnchantments = false;
        this.recheckEnchantmentForStack = null;
        this.hasUnsentEquipmentChanges = true;

        for (ItemStack newStack : this.items.values()) {
            if (!newStack.isEmpty()) {
                if (!this.shouldTickEnchantments) {
                    this.shouldTickEnchantments = stackHasTickableEnchantment(newStack);
                }

                if (!newStack.isEmpty()) {
                    ((net.caffeinemc.mods.lithium.common.util.change_tracking.ChangePublisher<ItemStack>)(Object)newStack).lithium$subscribe(this, 0);
                }
            }
        }
    }

    private void onEquipmentReplaced(final ItemStack oldStack, final ItemStack newStack) {
        if (!this.shouldTickEnchantments) {
            if (this.recheckEnchantmentForStack == oldStack) {
                this.recheckEnchantmentForStack = null;
            }

            this.shouldTickEnchantments = stackHasTickableEnchantment(newStack);
        }

        this.hasUnsentEquipmentChanges = true;
        if (!oldStack.isEmpty()) {
            ((net.caffeinemc.mods.lithium.common.util.change_tracking.ChangePublisher<ItemStack>)(Object)oldStack).lithium$unsubscribeWithData(this, 0);
        }

        if (!newStack.isEmpty()) {
            ((net.caffeinemc.mods.lithium.common.util.change_tracking.ChangePublisher<ItemStack>)(Object)newStack).lithium$subscribe(this, 0);
        }
    }

    private static boolean stackHasTickableEnchantment(final ItemStack stack) {
        if (!stack.isEmpty()) {
            net.minecraft.world.item.enchantment.ItemEnchantments enchantments = stack.get(
                net.minecraft.core.component.DataComponents.ENCHANTMENTS
            );
            if (enchantments != null && !enchantments.isEmpty()) {
                for (net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> enchantmentEntry : enchantments.keySet()) {
                    if (!enchantmentEntry.value()
                        .getEffects(net.minecraft.world.item.enchantment.EnchantmentEffectComponents.TICK)
                        .isEmpty()) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    @Override
    public void lithium$notify(final ItemStack publisher, final int zero) {
        this.hasUnsentEquipmentChanges = true;
        if (!this.shouldTickEnchantments) {
            this.processScheduledEnchantmentCheck(publisher);
            this.scheduleEnchantmentCheck(publisher);
        }
    }

    private void scheduleEnchantmentCheck(final ItemStack toCheck) {
        this.recheckEnchantmentForStack = toCheck;
    }

    private void processScheduledEnchantmentCheck(final ItemStack ignoredStack) {
        if (this.recheckEnchantmentForStack != null && this.recheckEnchantmentForStack != ignoredStack) {
            this.shouldTickEnchantments = stackHasTickableEnchantment(this.recheckEnchantmentForStack);
            this.recheckEnchantmentForStack = null;
        }
    }

    @Override
    public void lithium$notifyCount(final ItemStack publisher, final int zero, final int newCount) {
        if (newCount == 0) {
            ((net.caffeinemc.mods.lithium.common.util.change_tracking.ChangePublisher<ItemStack>)(Object)publisher).lithium$unsubscribeWithData(this, zero);
        }

        this.onEquipmentReplaced(publisher, ItemStack.EMPTY);
    }

    @Override
    public void lithium$forceUnsubscribe(final ItemStack publisher, final int zero) {
        throw new UnsupportedOperationException();
    }
}