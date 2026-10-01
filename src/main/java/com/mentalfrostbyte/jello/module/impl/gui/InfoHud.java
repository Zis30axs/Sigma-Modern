package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;

/**
 * A strip in the bottom-left corner: a miniature of your character, your armor with its wear, and your coordinates.
 * Only Jello draws it ({@code gui.legacy.hud.JelloWidgets}).
 */
public class InfoHud extends Module {

    /** How the coordinates are written. */
    public enum Coordinates {
        NONE,
        /** Whole blocks. */
        NORMAL,
        /** To a tenth of a block. */
        PRECISE
    }

    private final EnumSetting<Coordinates> coordinates = this.register(new EnumSetting<>(
            "Coordinates", "How your position is shown.", Coordinates.NORMAL));

    private final BooleanSetting player = this.register(new BooleanSetting(
            "Show Player", "A miniature of your character.", true));

    private final BooleanSetting armor = this.register(new BooleanSetting(
            "Show Armor", "The armor you are wearing, with a bar for how worn each piece is.", true));

    private final BooleanSetting moveChat = this.register(new BooleanSetting(
            "Move Chat Up", "Lifts the chat clear of the character and armor.", true));

    public InfoHud() {
        super(ModuleCategory.INTERFACE, "InfoHUD", "Jello: your character, armor and position in the bottom-left corner");
    }

    public Coordinates getCoordinates() {
        return this.coordinates.get();
    }

    public boolean showsPlayer() {
        return this.player.get();
    }

    public boolean showsArmor() {
        return this.armor.get();
    }

    /** Whether the chat rises to make room: only while the strip has a character or armor to make room for. */
    public boolean movesChat() {
        return this.moveChat.get() && (this.player.get() || this.armor.get());
    }
}
