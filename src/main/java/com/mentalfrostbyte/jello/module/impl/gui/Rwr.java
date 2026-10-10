package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;

/** Client-observed threat hints, never a claim that another client has locked onto the player. */
public final class Rwr extends Module {
    public enum Style {
        WT("WT"), MODERN("Modern");

        private final String label;
        Style(String label) { this.label = label; }
        @Override public String toString() { return this.label; }
    }

    private final EnumSetting<Style> style = this.register(new EnumSetting<>("Style",
            "WT uses an instrument display; Modern uses glass and the Modern HUD palette.", Style.WT));
    private final NumberSetting range = this.register(new NumberSetting("Range",
            "Maximum distance in blocks for client-observed contacts and incoming projectiles.", 64F, 16F, 128F, 8F));
    private final BooleanSetting players = this.register(new BooleanSetting("Players",
            "Shows nearby players and estimates when their visible viewing direction points at you. AIM is not a real lock.", true));
    private final BooleanSetting projectiles = this.register(new BooleanSetting("Projectiles",
            "Warns when an arrow or trident is predicted to reach you within two seconds, unless a block intercepts it.", true));
    private final NumberSetting scale = this.register(new NumberSetting("Scale",
            "Size of the RWR display.", 1F, 0.65F, 1.5F, 0.05F));
    private final NumberSetting background = this.register(new NumberSetting("Background",
            "Opacity of the RWR display's background.", 0.65F, 0F, 1F, 0.05F));
    private long session;

    public Rwr() {
        super(ModuleCategory.INTERFACE, "RWR", "Shows estimated player aim and incoming arrow or trident directions; Alt + drag moves it");
    }

    @Override protected void onEnable() { this.session++; }
    @Override protected void onDisable() { this.session++; }
    @EventTarget public void onLoadWorld(EventLoadWorld event) { this.session++; }

    public Style getStyle() { return this.style.get(); }
    public float getRange() { return this.range.get(); }
    public boolean showsPlayers() { return this.players.get(); }
    public boolean showsProjectiles() { return this.projectiles.get(); }
    public float getScale() { return this.scale.get(); }
    public float getBackground() { return this.background.get(); }
    public long getSession() { return this.session; }
}
