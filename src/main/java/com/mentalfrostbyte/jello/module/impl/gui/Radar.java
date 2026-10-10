package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.setting.KeySetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/** Display policy for the local radar; it never acquires a combat target or sends packets. */
public final class Radar extends Module {
    public enum Style {
        WT("War Thunder"), MODERN("Modern");

        private final String label;

        Style(final String label) { this.label = label; }

        @Override
        public String toString() { return this.label; }
    }

    public enum Mode {
        SRC("SRC (Search)"), ACQ("ACQ (Acquire)"), TWS("TWS (Track While Scan)");

        private final String label;

        Mode(final String label) { this.label = label; }

        @Override
        public String toString() { return this.label; }
    }

    public enum Focus {
        OFF("Off"), FADE_OTHERS("Fade Others"), TARGET_ONLY("Target Only");

        private final String label;

        Focus(String label) { this.label = label; }

        @Override public String toString() { return this.label; }
    }

    private final EnumSetting<Style> style = this.register(new EnumSetting<>(
            "Style", "War Thunder's green instrument display or Modern's glass panel.", Style.WT));
    private final EnumSetting<Mode> mode = this.register(new EnumSetting<>(
            "Mode", "SRC sweeps for contacts; ACQ uses a narrow 30 degree sector; TWS maintains contact tracks.", Mode.TWS));
    private final BooleanSetting hotKey = this.register(new BooleanSetting(
            "HotKey", "Enables the combat acquisition shortcut. Press again to leave TRK.", true));
    private final KeySetting pressKey = this.register(new KeySetting(
            "Press Key", "Click to bind the TRK shortcut, then press a key combination. Escape cancels; Delete clears.",
            new KeySetting.Chord(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F), GLFW.GLFW_MOD_ALT)));
    private final EnumSetting<Focus> trkFocus = this.register(new EnumSetting<>(
            "TRK Focus", "Highlights a locked target and temporarily fades or hides other NameTags and ESP.", Focus.FADE_OTHERS));
    private final NumberSetting otherOpacity = this.register(new NumberSetting(
            "Other Opacity", "Opacity of other NameTags and ESP while the TRK target is visible.", 0.2F, 0F, 1F, 0.05F));
    private final BooleanSetting headOn = this.register(new BooleanSetting(
            "Head On", "HDN: only show approaching contacts whose own movement is toward you. Does not apply to ACQ.", false));
    private final NumberSetting range = this.register(new NumberSetting(
            "Range", "Maximum contact distance in blocks, limited to entities received from the server.",
            64F, 16F, 256F, 8F));
    private final NumberSetting scanAngle = this.register(new NumberSetting(
            "Scan Angle", "Total forward search sector in degrees. ACQ always uses 30 degrees.",
            120F, 30F, 180F, 10F));
    private final BooleanSetting players = this.register(new BooleanSetting(
            "Players", "Shows nearby players.", true));
    private final BooleanSetting mobs = this.register(new BooleanSetting(
            "Mobs", "Shows nearby living mobs.", false));
    private final BooleanSetting invisibles = this.register(new BooleanSetting(
            "Invisibles", "Shows invisible contacts received from the server.", false));
    private final BooleanSetting teams = this.register(new BooleanSetting(
            "Teams", "Includes teammates in the contact display.", true));
    private final NumberSetting scale = this.register(new NumberSetting(
            "Scale", "Size of the radar. Hold Alt to move it.", 1F, 0.65F, 1.5F, 0.05F));
    private final NumberSetting background = this.register(new NumberSetting(
            "Background", "Opacity of the instrument background.", 0.65F, 0F, 1F, 0.05F));
    private final NumberSetting scanPeriod = this.register(new NumberSetting(
            "Scan Period", "Seconds per full sweep.", 3F, 1F, 6F, 0.25F));

    private long session;

    public Radar() {
        super(ModuleCategory.INTERFACE, "Radar", "Shows nearby contacts on a forward radar scope with SRC, ACQ and TWS modes.");
        this.headOn.visibleWhen(() -> !this.mode.is(Mode.ACQ));
        this.scanAngle.visibleWhen(() -> !this.mode.is(Mode.ACQ));
        this.pressKey.visibleWhen(this.hotKey::get);
        this.otherOpacity.visibleWhen(() -> this.trkFocus.is(Focus.FADE_OTHERS));
    }

    @Override
    protected void onEnable() { this.session++; }

    @Override
    protected void onDisable() { this.session++; }

    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) { this.session++; }

    public Style getStyle() { return this.style.get(); }
    public Mode getMode() { return this.mode.get(); }
    public boolean isHeadOn() { return !this.mode.is(Mode.ACQ) && this.headOn.get(); }
    public float getRange() { return this.range.get(); }
    public float getScanAngle() { return this.mode.is(Mode.ACQ) ? 30F : this.scanAngle.get(); }
    public boolean showsPlayers() { return this.players.get(); }
    public boolean showsMobs() { return this.mobs.get(); }
    public boolean showsInvisibles() { return this.invisibles.get(); }
    public boolean showsTeams() { return this.teams.get(); }
    public float getScale() { return this.scale.get(); }
    public float getBackground() { return this.background.get(); }
    public float getScanPeriod() { return this.scanPeriod.get(); }
    public long getSession() { return this.session; }
    public Focus getTrkFocus() { return this.trkFocus.get(); }
    public float getOtherOpacity() { return this.otherOpacity.get(); }
    public boolean matchesLockKey(KeyEvent event) { return this.hotKey.get() && this.pressKey.matches(event); }
    public String getLockKeyLabel() { return this.hotKey.get() && this.pressKey.get().isBound() ? this.pressKey.displayLabel() : ""; }
}
