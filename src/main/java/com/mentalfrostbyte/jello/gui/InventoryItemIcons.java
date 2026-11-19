package com.mentalfrostbyte.jello.gui;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Renderable palette stacks, including on the title screen before a server binds item components. */
final class InventoryItemIcons {
    private InventoryItemIcons() { }

    static ItemStack get(String id) {
        var key = Identifier.parse(id);
        var reference = BuiltInRegistries.ITEM.get(key).orElse(null);
        if (reference == null) return ItemStack.EMPTY;
        if (reference.areComponentsBound()) return new ItemStack(reference);
        var item = reference.value();
        var components = DataComponentMap.builder()
                .set(DataComponents.ITEM_MODEL, key)
                .set(DataComponents.ITEM_NAME, Component.translatable(item.getDescriptionId())).build();
        return new ItemStack(Holder.direct(item, components));
    }
}
