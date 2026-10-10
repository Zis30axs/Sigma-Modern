package com.mentalfrostbyte.jello.module.impl.combat;

import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.module.impl.player.AutoPearl;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.setting.Setting;
import com.mentalfrostbyte.jello.util.client.MouseClickTracker.InputContext;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.Random;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/** Generates ordinary attacks on the game thread, without synthesizing raw input or changing held keys. */
public final class AutoClicker extends Module {
    public enum Mode { AUTO, HELPER }

    private final EnumSetting<Mode> mode = register(new EnumSetting<>("Mode",
            "Auto repeats while the attack key is held. Helper adds clicks after each physical press.", Mode.AUTO));
    private final NumberSetting minCps = register(new NumberSetting("Min CPS",
            "Minimum requested Auto CPS. Frame rate and vanilla attack delays can lower actual CPS.", 8, 1, 30, 1));
    private final NumberSetting maxCps = register(new NumberSetting("Max CPS",
            "Maximum Auto CPS. Reversed Min and Max values are handled automatically.", 12, 1, 30, 1));
    private final NumberSetting zitter = register(new NumberSetting("Zitter",
            "Slow correlated CPS drift amplitude. Zero disables the drift.", 1, 0, 5, 0.1F));
    private final NumberSetting jitter = register(new NumberSetting("Jitter",
            "Per-click interval variation in milliseconds, kept inside the CPS range.", 4, 0, 30, 1));
    private final NumberSetting ms = register(new NumberSetting("MS",
            "Milliseconds between Zitter drift updates.", 250, 50, 2000, 10));
    private final NumberSetting random = register(new NumberSetting("Random",
            "Independent random pace variation in percent. Zero uses the midpoint before drift and jitter.", 35, 0, 100, 5));
    private final NumberSetting extraClicks = register(new NumberSetting("Extra Clicks",
            "Maximum extra clicks after each physical press. A new press replaces unfinished extras.", 2, 1, 5, 1));
    private final NumberSetting helperDelay = register(new NumberSetting("Helper Delay",
            "Milliseconds between Helper clicks, starting after the original click is processed.", 35, 5, 250, 5));

    private final AutoClickScheduler scheduler = new AutoClickScheduler(new Random());
    private @Nullable LocalPlayer owner;
    private @Nullable ClientLevel level;
    private InputConstants.@Nullable Key pendingKey;
    private long pendingAt;
    private boolean autoArmed;

    public AutoClicker() {
        super(ModuleCategory.COMBAT, "AutoClicker",
                "Repeats held attacks with variable CPS, or adds timed clicks after each physical attack.");
        for (Setting<?> setting : new Setting<?>[]{minCps, maxCps, zitter, jitter, ms, random}) {
            setting.visibleWhen(() -> mode.is(Mode.AUTO));
        }
        extraClicks.visibleWhen(() -> mode.is(Mode.HELPER));
        helperDelay.visibleWhen(() -> mode.is(Mode.HELPER));
        for (Setting<?> setting : settings()) setting.onChange(this::clear);
    }

    @Override protected void onEnable() { reset(); }
    @Override protected void onDisable() { reset(); }
    @EventTarget public void onWorld(EventLoadWorld event) { reset(); }

    @EventTarget(EventPriority.LOWEST)
    public void onTick(EventTick event) {
        if (!event.isPre()) return;
        syncSession();
        if (!available() || busy()) clear();
    }

    /** Only accepted physical PRESS callbacks call this; queued menu input cannot arm Helper later. */
    public static void physicalPress(InputConstants.Key key, InputContext captured) {
        AutoClicker module = Modules.enabled(AutoClicker.class);
        if (module == null || !mc.options.keyAttack.matches(key)) return;
        module.syncSession();
        if (!captured.gameplay() || captured.world() != mc.level || !module.available() || busy()) {
            module.clear();
            return;
        }
        module.clear();
        module.pendingKey = key;
        module.pendingAt = nowMs();
    }

    /** Called inside a non-generated, accepted native attack; extras never precede the original click. */
    public void originalClick() {
        syncSession();
        long now = nowMs();
        if (!canClick()) { clear(); return; }
        // Keyboard repeat and older clicks in the native queue must preserve an already armed burst.
        boolean armed = scheduler.consumedNativeClick(now, pendingPhysicalClick(now), mode.is(Mode.HELPER),
                autoSettings(), extraClicks.getInt(), helperDelay.getInt());
        pendingKey = null;
        if (armed && mode.is(Mode.AUTO)) autoArmed = true;
    }

    /** After the frame's camera update and raycast. True authorizes exactly one native startAttack call. */
    public boolean poll(boolean frameAllowsAttack) {
        syncSession();
        if (!frameAllowsAttack || !canClick()) { clear(); return false; }
        long now = nowMs();
        if (pendingKey != null) {
            // The vanilla input queue has not consumed this press yet; don't generate an earlier click.
            if (pendingPhysicalClick(now)) return false;
            clear();
        }
        if (mode.is(Mode.HELPER)) return scheduler.pollHelper(now);
        if (!mc.options.keyAttack.isDown()) { clear(); return false; }
        if (!autoArmed) {
            scheduler.beginAuto(now, autoSettings());
            autoArmed = true;
            return false;
        }
        return scheduler.pollAuto(now, autoSettings());
    }

    private boolean pendingPhysicalClick(long now) {
        return pendingKey != null && mc.options.keyAttack.matches(pendingKey)
                && now - pendingAt >= 0 && now - pendingAt <= 500;
    }

    private AutoClickScheduler.AutoSettings autoSettings() {
        return new AutoClickScheduler.AutoSettings(minCps.get(), maxCps.get(), zitter.get(), jitter.get(), ms.getInt(), random.get());
    }

    private boolean available() {
        return mc.player != null && mc.level != null && mc.gameMode != null && mc.player.isAlive()
                && !mc.player.isSpectator() && !mc.gameMode.isSpectator() && !mc.player.isHandsBusy()
                && !mc.player.isUsingItem() && !mc.options.keyUse.isDown()
                && mc.gui.screen() == null && mc.gui.overlay() == null && !mc.isPaused()
                && mc.isWindowActive() && mc.mouseHandler.isMouseGrabbed() && mc.getCameraEntity() == mc.player
                && mc.player.connection.getConnection().isConnected();
    }

    private boolean canClick() {
        return available() && !busy() && mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.BLOCK
                && !mc.gameMode.isDestroying();
    }

    private static boolean busy() {
        KillAura aura = Modules.enabled(KillAura.class);
        BlockFly scaffold = Modules.enabled(BlockFly.class);
        AutoProjectile projectile = Modules.enabled(AutoProjectile.class);
        return AutoPearl.isRescuing() || (aura != null && aura.isActivelyWorking())
                || (scaffold != null && scaffold.isActivelyWorking())
                || (projectile != null && projectile.isActivelyWorking());
    }

    private void syncSession() {
        if (owner != mc.player || level != mc.level) {
            clear(); owner = mc.player; level = mc.level;
        }
    }

    private void clear() { scheduler.reset(); autoArmed = false; pendingKey = null; pendingAt = 0; }
    private void reset() { clear(); owner = null; level = null; }
    private static long nowMs() { return System.nanoTime() / 1_000_000L; }
}
