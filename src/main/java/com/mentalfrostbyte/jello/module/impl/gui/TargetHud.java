package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.player.EventAttack;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.combat.KillAura;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import java.lang.ref.WeakReference;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/** Target selection and policy for the Modern target panel; rendering and dragging live in the UI. */
public final class TargetHud extends Module {
    public enum Mode {
        NORMAL("Normal"), FOLLOW_TARGET("Follow Target");

        private final String label;

        Mode(final String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return this.label;
        }
    }

    private final EnumSetting<Mode> mode = this.register(new EnumSetting<>("Mode",
            "Normal stays where you place it; Follow Target moves beside the target. Hold Alt to adjust either position.", Mode.NORMAL));
    private final NumberSetting scale = this.register(new NumberSetting("Scale",
            "Size of the target panel.", 1.0F, 0.65F, 1.5F, 0.05F));
    private final NumberSetting background = this.register(new NumberSetting("Background",
            "Opacity of the target panel's dark glass.", 0.82F, 0.25F, 1.0F, 0.05F));
    private final BooleanSetting armor = this.register(new BooleanSetting("Armor",
            "Shows the target's actual armor and held items, with durability remaining.", true));
    private final BooleanSetting ping = this.register(new BooleanSetting("Ping",
            "Shows a player's latency when the server supplies it.", true));
    private final NumberSetting holdTime = this.register(new NumberSetting("Hold Time",
            "Seconds to keep your most recently attacked living target after looking away.", 3.0F, 0.5F, 10.0F, 0.5F));
    private final BooleanSetting crosshair = this.register(new BooleanSetting("Crosshair Target",
            "Shows a living entity under your crosshair when there is no active or recent combat target.", true));

    private WeakReference<LivingEntity> recentTarget = new WeakReference<>(null);
    private long attackedAt;
    private long session;

    public TargetHud() {
        super(ModuleCategory.INTERFACE, "TargetHUD", "Shows the current combat target's health, equipment and distance.");
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @EventTarget(EventPriority.LOWEST)
    public void onAttack(final EventAttack event) {
        if (!event.isCancelled() && event.getTarget() instanceof LivingEntity target && this.valid(target)) {
            this.recentTarget = new WeakReference<>(target);
            this.attackedAt = System.nanoTime();
        }
    }

    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) {
        this.reset();
    }

    @Override
    protected void onEnable() {
        this.reset();
    }

    @Override
    protected void onDisable() {
        this.reset();
    }

    private void reset() {
        this.recentTarget.clear();
        this.attackedAt = 0L;
        this.session++;
    }

    /** Aura, then the last attack, then a crosshair target; editing falls back to the local player. */
    public @Nullable LivingEntity getTarget(final boolean editing) {
        if (mc.player == null || mc.level == null) {
            this.recentTarget.clear();
            return null;
        }
        LivingEntity recent = this.recentTarget.get();
        if (!this.valid(recent) || !withinHold(this.attackedAt, System.nanoTime(), this.holdTime.get())) {
            this.recentTarget.clear();
            recent = null;
        }
        KillAura aura = Modules.enabled(KillAura.class);
        LivingEntity active = aura == null ? null : aura.getTarget();
        if (this.valid(active)) return active;
        if (recent != null) return recent;
        if (this.crosshair.get() && mc.crosshairPickEntity instanceof LivingEntity pointed && this.valid(pointed)) return pointed;
        return editing ? mc.player : null;
    }

    private boolean valid(final @Nullable LivingEntity target) {
        return target != null && target != mc.player && target.level() == mc.level
                && target.isAlive() && !target.isRemoved() && !target.isSpectator();
    }

    /** Monotonic elapsed time also handles a nanoTime counter wrapping between two nearby instants. */
    static boolean withinHold(final long started, final long now, final float seconds) {
        long elapsed = now - started;
        return Float.isFinite(seconds) && seconds > 0F && elapsed >= 0L && elapsed < (double) seconds * 1_000_000_000.0;
    }

    public Mode getMode() { return this.mode.get(); }
    public float getScale() { return this.scale.get(); }
    public float getBackground() { return this.background.get(); }
    public boolean showsArmor() { return this.armor.get(); }
    public boolean showsPing() { return this.ping.get(); }
    public float getHoldTime() { return this.holdTime.get(); }
    public long getSession() { return this.session; }
}
