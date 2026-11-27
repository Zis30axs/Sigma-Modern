package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.jello.setting.InventoryLayoutSetting;
import com.mentalfrostbyte.jello.setting.ItemListSetting;
import com.mentalfrostbyte.jello.setting.Setting;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/** Settings with a full editor shared by the client presentations. */
public enum SettingEditor {
    ITEM_LIST, SLOT_LAYOUT, TRAINING;

    public static @Nullable SettingEditor forSetting(Setting<?> setting) {
        if (setting instanceof ItemListSetting) return ITEM_LIST;
        if (setting instanceof InventoryLayoutSetting) return SLOT_LAYOUT;
        return AimAssistTrainingScreen.isTrainingSetting(setting) ? TRAINING : null;
    }

    Screen open(@Nullable Screen parent, Setting<?> setting) {
        return switch (this) {
            case ITEM_LIST -> new ProjectileWhitelistScreen(parent, (ItemListSetting) setting);
            case SLOT_LAYOUT -> new InventoryLayoutScreen(parent, (InventoryLayoutSetting) setting);
            case TRAINING -> AimAssistTrainingScreen.open(parent);
        };
    }

    public String summary(Setting<?> setting) {
        return switch (this) {
            case ITEM_LIST -> ModuleScreenStyle.tr("selected_count", ((ItemListSetting) setting).get().size());
            case SLOT_LAYOUT -> ModuleScreenStyle.tr("assigned_count", ((InventoryLayoutSetting) setting).get().size());
            case TRAINING -> AimAssistTrainingScreen.settingLabel();
        };
    }
}
