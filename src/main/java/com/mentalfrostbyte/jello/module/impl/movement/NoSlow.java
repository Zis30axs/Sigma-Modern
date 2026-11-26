package com.mentalfrostbyte.jello.module.impl.movement;

import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec2;
import org.jspecify.annotations.Nullable;

/**
 * Eliminates or mitigates movement slowdown and sprint cancellation while using items.
 *
 * <p>Includes {@link Mode#VANILLA} for customizable full-speed bypass and {@link Mode#GRIM_TEST}
 * combining 1-tick slowed/un-slowed alternation with {@code Open_Window} container bypass for
 * 100% NoSlow when GrimAC clears {@code slowedByUsingItem}.</p>
 */
public final class NoSlow extends Module {

    public enum Mode {
        VANILLA("Vanilla"),
        GRIM_TEST("GrimTest");

        private final String label;

        Mode(final String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return this.label;
        }
    }

    private final EnumSetting<Mode> mode = this.register(new EnumSetting<>(
            "Mode",
            "Vanilla full NoSlow, or GrimTest 1-tick alternation with Open_Window 100% container bypass.",
            Mode.VANILLA));

    private final NumberSetting forwardMultiplier = this.register(new NumberSetting(
            "Forward Multiplier",
            "Forward movement multiplier while using an item.",
            1.0F, 0.2F, 1.0F, 0.05F));

    private final NumberSetting strafeMultiplier = this.register(new NumberSetting(
            "Strafe Multiplier",
            "Strafe movement multiplier while using an item.",
            1.0F, 0.2F, 1.0F, 0.05F));

    private final BooleanSetting allowSprint = this.register(new BooleanSetting(
            "Allow Sprint",
            "Allow sprinting while item slowdown is bypassed.",
            true));

    private final BooleanSetting containerBypass = this.register(new BooleanSetting(
            "Container Bypass",
            "Switch to 100% NoSlow when an Open_Window packet clears Grim's item slowdown.",
            true));

    private final BooleanSetting closeContainer = this.register(new BooleanSetting(
            "Close Container",
            "Immediately close the intercepted container on the open tick to keep menus synced.",
            true));

    private int useTicks;
    private boolean containerBypassActive;
    private boolean suppressSlowdownThisTick;
    private boolean allowSprintThisTick;
    private int trackedSlot = -1;
    private @Nullable InteractionHand trackedHand;

    public NoSlow() {
        super(ModuleCategory.MOVEMENT, "NoSlow",
                "Prevents or mitigates movement slowdown while eating, drinking, blocking or drawing a bow.");
        this.forwardMultiplier.visibleWhen(() -> this.mode.is(Mode.VANILLA));
        this.strafeMultiplier.visibleWhen(() -> this.mode.is(Mode.VANILLA));
        this.containerBypass.visibleWhen(() -> this.mode.is(Mode.GRIM_TEST));
        this.closeContainer.visibleWhen(() -> this.mode.is(Mode.GRIM_TEST) && this.containerBypass.get());
    }

    @Override
    public void onEnable() {
        this.resetState();
    }

    @Override
    public void onDisable() {
        this.resetState();
    }

    @EventTarget(EventPriority.LOW)
    public void onInput(final EventMovementInput event) {
        LocalPlayer player = mc.player;
        if (player == null) {
            this.resetState();
            return;
        }

        boolean usingItem = player.isUsingItem();
        boolean passenger = player.isPassenger();
        this.stepItemUseTick(usingItem, passenger, player.getInventory().getSelectedSlot(), player.getUsedItemHand());
        if (usingItem && !passenger && !this.allowSprintThisTick) {
            event.setSprint(false);
        }
    }

    @EventTarget
    public void onWorldLoad(final EventLoadWorld event) {
        this.resetState();
    }

    void resetState() {
        this.useTicks = 0;
        this.containerBypassActive = false;
        this.suppressSlowdownThisTick = false;
        this.allowSprintThisTick = false;
        this.trackedSlot = -1;
        this.trackedHand = null;
    }

    /**
     * Advances item-use bypass state by one tick and returns whether item slowdown should be
     * suppressed on the current tick.
     */
    boolean stepItemUseTick(
            final boolean usingItem,
            final boolean passenger,
            final int selectedSlot,
            final @Nullable InteractionHand usedHand) {
        if (!usingItem || passenger) {
            this.resetState();
            return false;
        }

        if ((usedHand == InteractionHand.MAIN_HAND && this.trackedSlot != -1 && this.trackedSlot != selectedSlot)
                || (this.trackedHand != null && this.trackedHand != usedHand)) {
            this.useTicks = 0;
            this.containerBypassActive = false;
        }

        this.trackedSlot = selectedSlot;
        this.trackedHand = usedHand;

        if (this.mode.is(Mode.VANILLA)) {
            this.suppressSlowdownThisTick = true;
            this.allowSprintThisTick = this.allowSprint.get();
        } else if (this.containerBypass.get() && this.containerBypassActive) {
            this.suppressSlowdownThisTick = true;
            this.allowSprintThisTick = this.allowSprint.get();
        } else {
            // Grim sets flaggedLastTick after any un-slowed tick and only clears it after a genuinely
            // slowed tick, so every session starts slowed (even ticks) before alternating un-slowed (odd).
            boolean unSlowedTick = (this.useTicks & 1) == 1;
            this.suppressSlowdownThisTick = unSlowedTick;
            this.allowSprintThisTick = unSlowedTick && this.allowSprint.get();
        }

        this.useTicks = this.useTicks == Integer.MAX_VALUE ? 1 : this.useTicks + 1;
        return this.suppressSlowdownThisTick;
    }

    Vec2 applyInputMultiplier(final Vec2 input, final float vanillaMultiplier) {
        if (vanillaMultiplier >= 1.0F || !this.suppressSlowdownThisTick) {
            return input.scale(vanillaMultiplier);
        }

        if (this.mode.is(Mode.GRIM_TEST)) {
            return input;
        }

        float strafe = Math.max(vanillaMultiplier, this.strafeMultiplier.get());
        float forward = Math.max(vanillaMultiplier, this.forwardMultiplier.get());
        if (strafe == 1.0F && forward == 1.0F) {
            return input;
        }

        return new Vec2(input.x * strafe, input.y * forward);
    }

    boolean onContainerOpenedWhileUsing(final boolean currentlyUsingItem) {
        if (!currentlyUsingItem || !this.mode.is(Mode.GRIM_TEST) || !this.containerBypass.get()) {
            return false;
        }

        this.containerBypassActive = true;
        this.suppressSlowdownThisTick = true;
        this.allowSprintThisTick = this.allowSprint.get();
        return true;
    }

    void onServerEntityUsingItem(final boolean serverUsingItem) {
        if (!serverUsingItem) {
            this.resetState();
        } else if (this.containerBypassActive) {
            // Server metadata confirms item usage after Open_Window, which causes Grim to re-enable
            // slowedByUsingItem; drop back to safe 1-tick alternation starting from a slowed tick.
            this.containerBypassActive = false;
            this.useTicks = 0;
            this.suppressSlowdownThisTick = false;
            this.allowSprintThisTick = false;
        }
    }

    boolean isContainerBypassActive() {
        return this.containerBypassActive;
    }

    boolean isSuppressSlowdownThisTick() {
        return this.suppressSlowdownThisTick;
    }

    boolean isAllowSprintThisTick() {
        return this.allowSprintThisTick;
    }

    boolean shouldCloseInterceptedContainer() {
        return this.closeContainer.get();
    }

    Mode getMode() {
        return this.mode.get();
    }

    void setMode(final Mode mode) {
        this.mode.set(mode);
    }

    void setAllowSprint(final boolean allowSprint) {
        this.allowSprint.set(allowSprint);
    }

    void setContainerBypass(final boolean containerBypass) {
        this.containerBypass.set(containerBypass);
    }

    void setCloseContainer(final boolean closeContainer) {
        this.closeContainer.set(closeContainer);
    }

    void setVanillaMultipliers(final float forward, final float strafe) {
        this.forwardMultiplier.set(forward);
        this.strafeMultiplier.set(strafe);
    }

    NumberSetting getForwardMultiplierSetting() {
        return this.forwardMultiplier;
    }

    NumberSetting getStrafeMultiplierSetting() {
        return this.strafeMultiplier;
    }

    BooleanSetting getContainerBypassSetting() {
        return this.containerBypass;
    }

    BooleanSetting getCloseContainerSetting() {
        return this.closeContainer;
    }

    /** Scales item-use movement input according to the active NoSlow mode for the local player. */
    public static Vec2 scaleItemUseInput(final LocalPlayer player, final Vec2 input, final float vanillaMultiplier) {
        if (mc == null || player != mc.player) {
            return input.scale(vanillaMultiplier);
        }

        NoSlow noSlow = Modules.enabled(NoSlow.class);
        return noSlow != null ? noSlow.applyInputMultiplier(input, vanillaMultiplier) : input.scale(vanillaMultiplier);
    }

    /** Whether the local player may sprint on the current tick despite using an item. */
    public static boolean allowSprintWhileUsing(final LocalPlayer player) {
        if (mc == null || player != mc.player) {
            return false;
        }

        NoSlow noSlow = Modules.enabled(NoSlow.class);
        return noSlow != null && noSlow.isAllowSprintThisTick();
    }

    /** Resets per-use NoSlow state when the local player starts or stops using an item. */
    public static void onItemUseBoundary(final LocalPlayer player) {
        if (mc == null || player != mc.player) {
            return;
        }

        NoSlow noSlow = Modules.enabled(NoSlow.class);
        if (noSlow != null) {
            noSlow.resetState();
        }
    }

    /** Synchronizes container bypass state with server-sent living entity item-use metadata. */
    public static void onServerUsingItemData(final LocalPlayer player, final boolean serverUsingItem) {
        if (mc == null || player != mc.player) {
            return;
        }

        NoSlow noSlow = Modules.enabled(NoSlow.class);
        if (noSlow != null) {
            noSlow.onServerEntityUsingItem(serverUsingItem);
        }
    }

    /**
     * Intercepts server container opens while actively using an item in GrimTest mode, latching 100%
     * NoSlow for the session and optionally sending an immediate container-close packet.
     */
    public static boolean onOpenScreen(final Minecraft minecraft, final int containerId) {
        if (mc == null || minecraft == null || minecraft.player == null) {
            return false;
        }

        NoSlow noSlow = Modules.enabled(NoSlow.class);
        if (noSlow == null || !noSlow.onContainerOpenedWhileUsing(minecraft.player.isUsingItem())) {
            return false;
        }

        if (noSlow.shouldCloseInterceptedContainer() && minecraft.getConnection() != null) {
            minecraft.getConnection().send(new ServerboundContainerClosePacket(containerId));
        }

        return true;
    }
}
