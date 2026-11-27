package com.mentalfrostbyte.jello.module.impl.misc;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jspecify.annotations.Nullable;

/**
 * Automatically reconnects to the last multiplayer server after a disconnect screen appears.
 *
 * <p>{@code ConnectScreen#startConnecting} records the last server and resolved address even when this module
 * is currently off, so enabling {@code AutoReconnect} or getting disconnected right after joining still knows
 * where to reconnect. {@code DisconnectedScreen} reads {@link #delayNanos()}, {@link #canAttemptRetry()} and
 * {@link #lastTarget()} to drive the countdown and manual reconnect button.</p>
 */
public class AutoReconnect extends Module {

    public record Target(ServerAddress address, ServerData data) {
    }

    private static volatile @Nullable Target lastTarget;

    private final NumberSetting delay = this.register(new NumberSetting(
            "Delay", "Seconds to wait on the disconnect screen before reconnecting.",
            5.0F, 1.0F, 60.0F, 0.5F));

    private final NumberSetting maxRetries = this.register(new NumberSetting(
            "Max Retries", "Maximum consecutive reconnect attempts (0 means unlimited).",
            0.0F, 0.0F, 20.0F, 1.0F));

    private int attempts;

    public AutoReconnect() {
        super(ModuleCategory.MISC, "AutoReconnect", "Automatically reconnects to the last server after disconnecting");
    }

    /** Called by {@code ConnectScreen#startConnecting} whenever the client joins a multiplayer server. */
    public static void remember(final ServerAddress address, final ServerData data) {
        if (address != null && data != null) {
            lastTarget = data.requiresFreshAdmission ? null : new Target(address, data);
        }
    }

    public static @Nullable Target lastTarget() {
        return lastTarget;
    }

    public long delayNanos() {
        return (long) (this.delay.get() * 1_000_000_000.0);
    }

    public int maxRetries() {
        return this.maxRetries.getInt();
    }

    public int attempts() {
        return this.attempts;
    }

    public boolean canAttemptRetry() {
        int cap = this.maxRetries();
        return cap <= 0 || this.attempts < cap;
    }

    public void recordAttempt() {
        this.attempts++;
    }

    public void resetAttempts() {
        this.attempts = 0;
    }

    @Override
    protected void onEnable() {
        this.attempts = 0;
    }

    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) {
        this.attempts = 0;
    }
}
