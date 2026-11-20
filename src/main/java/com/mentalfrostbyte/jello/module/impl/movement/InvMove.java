package com.mentalfrostbyte.jello.module.impl.movement;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.network.EventSendPacket;
import com.mentalfrostbyte.jello.event.impl.player.EventUpdate;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractCommandBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.JigsawBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import org.lwjgl.glfw.GLFW;

/**
 * Lets you keep moving while an inventory, chest or other non-typing screen is open, with both generic
 * (LBNG-style) and anti-cheat specialized {@link Behavior} presets.
 *
 * <p>Reads live physical key states on {@link EventMovementInput} rather than mutating {@link KeyMapping}
 * latches, so keys never stick when closing a screen or toggling the module.</p>
 */
public class InvMove extends Module {

    public enum Behavior {
        /** Move freely in any non-typing screen without restricting clicks or movement. */
        NORMAL,
        /** Pause movement briefly whenever a container click is sent. */
        SAFE,
        /** Only move in non-container screens (ClickGUI, HUD, Pause); never move inside inventories/chests. */
        UNDETECTABLE,
        /** Block container clicks while actively moving so you never click slots in motion. */
        STOP_ON_ACTION,
        /** Queue container clicks while moving and flush them when you stop, close the container, or hit MaxBlinkMs. */
        BLINK,
        /** Intave preset: disables sprint, auto-sneaks while moving in containers, and buffers clicks for a brief stop. */
        INTAVE,
        /** Grim preset: drops sprint before clicks, blocks clicks while using an item, and zeroes movement on click ticks. */
        GRIM,
        /** Hypixel preset: drops sprint and briefly pauses movement + spaces queued clicks while inside containers. */
        HYPIXEL,
        /** AAC/NCP preset: drops sprint and pauses movement briefly around each container click. */
        AAC
    }

    public enum SprintControl {
        /** Allow sprinting normally while a screen is open. */
        ALLOW,
        /** Never sprint while a screen is open. */
        NO_SPRINT,
        /** Drop sprint right before sending a container click packet. */
        STOP_ON_CLICK
    }

    private static final double MOVING_SPEED_SQ = 1.0E-4;

    private final EnumSetting<Behavior> behavior = this.register(new EnumSetting<>(
            "Behavior", "Generic (NORMAL/SAFE/UNDETECTABLE/STOP_ON_ACTION/BLINK) or specialized (INTAVE/GRIM/HYPIXEL/AAC) mode.",
            Behavior.NORMAL));

    private final EnumSetting<SprintControl> sprintControl = this.register(new EnumSetting<>(
            "SprintControl", "How sprinting is handled while a screen is open or during clicks.", SprintControl.ALLOW));

    private final NumberSetting stopTicks = this.register(new NumberSetting(
            "StopTicks", "Ticks to pause movement around container clicks (SAFE / INTAVE / HYPIXEL / AAC).",
            2.0F, 1.0F, 10.0F, 1.0F));

    private final NumberSetting maxBlinkMs = this.register(new NumberSetting(
            "MaxBlinkMs", "Maximum time (ms) to hold queued container clicks in BLINK mode before flushing.",
            1500.0F, 200.0F, 5000.0F, 100.0F));

    private final NumberSetting clickDelayMs = this.register(new NumberSetting(
            "ClickDelayMs", "Minimum interval (ms) between flushed clicks in INTAVE / HYPIXEL modes.",
            80.0F, 0.0F, 300.0F, 10.0F));

    private final BooleanSetting jump = this.register(new BooleanSetting(
            "Jump", "Allow jumping while a screen is open.", true));

    private final BooleanSetting sneak = this.register(new BooleanSetting(
            "Sneak", "Allow sneaking while a screen is open (off by default to avoid crouching on shift-click).", false));

    private final BooleanSetting arrowRotate = this.register(new BooleanSetting(
            "ArrowRotate", "Rotate the camera with the arrow keys while a screen is open.", true));

    private final NumberSetting arrowSpeed = this.register(new NumberSetting(
            "ArrowSpeed", "Camera rotation speed in degrees per tick when using arrow keys.",
            4.0F, 1.0F, 15.0F, 0.5F));

    private final Deque<Packet<?>> packetQueue = new ArrayDeque<>();
    private long queueStartMs;
    private long lastClickSentMs;
    private int pauseTicksRemaining;
    private int deferFlushTicks;
    private boolean flushing;

    public InvMove() {
        super(ModuleCategory.MOVEMENT, "InvMove", "Lets you walk, jump and turn while an inventory or GUI is open");
        this.stopTicks.visibleWhen(() -> this.behavior.is(Behavior.SAFE)
                || this.behavior.is(Behavior.INTAVE)
                || this.behavior.is(Behavior.HYPIXEL)
                || this.behavior.is(Behavior.AAC));
        this.maxBlinkMs.visibleWhen(() -> this.behavior.is(Behavior.BLINK));
        this.clickDelayMs.visibleWhen(() -> this.behavior.is(Behavior.INTAVE) || this.behavior.is(Behavior.HYPIXEL));
        this.arrowSpeed.visibleWhen(this.arrowRotate::get);
    }

    @Override
    protected void onDisable() {
        this.flushAllQueued();
        this.pauseTicksRemaining = 0;
        this.deferFlushTicks = 0;
    }

    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) {
        this.packetQueue.clear();
        this.queueStartMs = 0L;
        this.lastClickSentMs = 0L;
        this.pauseTicksRemaining = 0;
        this.deferFlushTicks = 0;
    }

    @EventTarget
    public void onUpdate(final EventUpdate event) {
        if (!event.isPre() || mc.player == null) {
            return;
        }

        if (this.pauseTicksRemaining > 0) {
            this.pauseTicksRemaining--;
        }

        if (this.deferFlushTicks > 0) {
            this.deferFlushTicks--;
        }

        if (this.packetQueue.isEmpty()) {
            return;
        }

        boolean screenClosed = !(mc.gui.screen() instanceof AbstractContainerScreen<?>);
        if (screenClosed) {
            this.flushAllQueued();
            return;
        }

        Behavior mode = this.behavior.get();
        if (mode == Behavior.BLINK) {
            boolean timedOut = System.currentTimeMillis() - this.queueStartMs >= this.maxBlinkMs.get();
            if (timedOut || !this.isPlayerMoving()) {
                this.flushAllQueued();
            }
            return;
        }

        if (mode == Behavior.INTAVE || mode == Behavior.HYPIXEL) {
            long now = System.currentTimeMillis();
            if (this.deferFlushTicks == 0 && now - this.lastClickSentMs >= this.clickDelayMs.get()) {
                this.flushOneQueued();
                this.lastClickSentMs = now;
            }
        }
    }

    @EventTarget
    public void onMovementInput(final EventMovementInput event) {
        if (mc.player == null || mc.level == null) {
            return;
        }

        Screen screen = mc.gui.screen();
        if (screen == null || !this.canMoveIn(screen)) {
            return;
        }

        Behavior mode = this.behavior.get();
        boolean inContainer = screen instanceof AbstractContainerScreen<?>;

        if (this.pauseTicksRemaining > 0) {
            // Keep sneak active in INTAVE while paused so crouch state does not flicker.
            boolean holdSneak = mode == Behavior.INTAVE && inContainer;
            event.setInput(new Input(false, false, false, false, false, holdSneak, false));
            return;
        }

        Window window = mc.getWindow();
        boolean forward = isPhysicallyDown(window, mc.options.keyUp);
        boolean backward = isPhysicallyDown(window, mc.options.keyDown);
        boolean left = isPhysicallyDown(window, mc.options.keyLeft);
        boolean right = isPhysicallyDown(window, mc.options.keyRight);
        boolean movingInput = forward != backward || left != right;

        boolean allowJump = this.jump.get() && !(mode == Behavior.INTAVE && inContainer);
        boolean jumping = allowJump && isPhysicallyDown(window, mc.options.keyJump);

        boolean forceSneak = mode == Behavior.INTAVE && inContainer && movingInput;
        boolean sneaking = forceSneak || (this.sneak.get() && isPhysicallyDown(window, mc.options.keyShift));

        boolean blockSprint = this.sprintControl.is(SprintControl.NO_SPRINT)
                || (inContainer && (mode == Behavior.INTAVE || mode == Behavior.HYPIXEL || mode == Behavior.AAC));
        boolean sprinting = !blockSprint && isPhysicallyDown(window, mc.options.keySprint);

        if (blockSprint && mc.player.isSprinting()) {
            mc.player.setSprinting(false);
        }

        event.setInput(new Input(forward, backward, left, right, jumping, sneaking, sprinting));

        if (this.arrowRotate.get()) {
            float step = this.arrowSpeed.get();
            float yawDelta = 0.0F;
            float pitchDelta = 0.0F;
            if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT)) yawDelta -= step;
            if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT)) yawDelta += step;
            if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_UP)) pitchDelta -= step;
            if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_DOWN)) pitchDelta += step;

            if (yawDelta != 0.0F || pitchDelta != 0.0F) {
                mc.player.setYRot(mc.player.getYRot() + yawDelta);
                mc.player.setXRot(Mth.clamp(mc.player.getXRot() + pitchDelta, -90.0F, 90.0F));
            }
        }
    }

    @EventTarget
    public void onSendPacket(final EventSendPacket event) {
        if (this.flushing || mc.player == null) {
            return;
        }

        Packet<?> packet = event.getPacket();
        if (packet instanceof ServerboundContainerClosePacket) {
            if (!this.packetQueue.isEmpty()) {
                this.flushAllQueued();
            }
            return;
        }

        if (!(packet instanceof ServerboundContainerClickPacket)) {
            return;
        }

        Behavior mode = this.behavior.get();
        boolean dropSprint = this.sprintControl.is(SprintControl.STOP_ON_CLICK)
                || mode == Behavior.INTAVE
                || mode == Behavior.GRIM
                || mode == Behavior.HYPIXEL
                || mode == Behavior.AAC;
        if (dropSprint && mc.player.isSprinting()) {
            this.sendStopSprint();
        }

        switch (mode) {
            case SAFE, AAC -> this.pauseTicksRemaining = Math.max(this.pauseTicksRemaining, this.stopTicks.getInt());
            case STOP_ON_ACTION -> {
                if (this.isPlayerMoving()) {
                    event.setCancelled(true);
                }
            }
            case BLINK -> {
                if (this.isPlayerMoving()) {
                    this.enqueueClick(packet);
                    event.setCancelled(true);
                }
            }
            case INTAVE, HYPIXEL -> {
                this.pauseTicksRemaining = Math.max(this.pauseTicksRemaining, this.stopTicks.getInt());
                if (this.isPlayerMoving() || !this.packetQueue.isEmpty()) {
                    this.enqueueClick(packet);
                    this.deferFlushTicks = Math.max(this.deferFlushTicks, 1);
                    event.setCancelled(true);
                } else {
                    this.lastClickSentMs = System.currentTimeMillis();
                }
            }
            case GRIM -> {
                if (mc.player.isUsingItem()) {
                    event.setCancelled(true);
                    return;
                }
                this.pauseTicksRemaining = Math.max(this.pauseTicksRemaining, 1);
            }
            case NORMAL, UNDETECTABLE -> {
            }
        }
    }

    private void enqueueClick(final Packet<?> packet) {
        if (this.packetQueue.isEmpty()) {
            this.queueStartMs = System.currentTimeMillis();
        }
        this.packetQueue.addLast(packet);
    }

    private void sendStopSprint() {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || mc.player == null) {
            return;
        }
        mc.player.setSprinting(false);
        this.flushing = true;
        try {
            connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        } finally {
            this.flushing = false;
        }
    }

    private void flushOneQueued() {
        if (this.packetQueue.isEmpty()) {
            return;
        }
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            this.packetQueue.clear();
            return;
        }
        this.flushing = true;
        try {
            if (mc.player != null && mc.player.isSprinting()) {
                mc.player.setSprinting(false);
                connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
            }
            Packet<?> next = this.packetQueue.pollFirst();
            if (next != null) {
                connection.send(next);
            }
        } finally {
            this.flushing = false;
            if (this.packetQueue.isEmpty()) {
                this.queueStartMs = 0L;
            }
        }
    }

    private void flushAllQueued() {
        if (this.packetQueue.isEmpty()) {
            return;
        }
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            this.packetQueue.clear();
            return;
        }
        this.flushing = true;
        try {
            if (mc.player != null && mc.player.isSprinting()) {
                mc.player.setSprinting(false);
                connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
            }
            while (!this.packetQueue.isEmpty()) {
                connection.send(this.packetQueue.pollFirst());
            }
        } finally {
            this.flushing = false;
            this.queueStartMs = 0L;
        }
    }

    private boolean isPlayerMoving() {
        if (mc.player == null) {
            return false;
        }
        double dx = mc.player.getDeltaMovement().x;
        double dz = mc.player.getDeltaMovement().z;
        if (dx * dx + dz * dz > MOVING_SPEED_SQ) {
            return true;
        }
        Window window = mc.getWindow();
        return isPhysicallyDown(window, mc.options.keyUp)
                || isPhysicallyDown(window, mc.options.keyDown)
                || isPhysicallyDown(window, mc.options.keyLeft)
                || isPhysicallyDown(window, mc.options.keyRight);
    }

    private boolean canMoveIn(final Screen screen) {
        if (this.behavior.is(Behavior.UNDETECTABLE) && screen instanceof AbstractContainerScreen<?>) {
            return false;
        }

        if (screen instanceof ChatScreen
                || screen instanceof AbstractSignEditScreen
                || screen instanceof BookEditScreen
                || screen instanceof AbstractCommandBlockEditScreen
                || screen instanceof AnvilScreen
                || screen instanceof StructureBlockEditScreen
                || screen instanceof JigsawBlockEditScreen) {
            return false;
        }

        return !(screen.getFocused() instanceof EditBox);
    }

    private static boolean isPhysicallyDown(final Window window, final KeyMapping mapping) {
        InputConstants.Key key;
        try {
            key = InputConstants.getKey(mapping.saveString());
        } catch (final IllegalArgumentException ignored) {
            return false;
        }

        if (key.equals(InputConstants.UNKNOWN)) {
            return false;
        }
        if (key.getType() == InputConstants.Type.KEYSYM) {
            return InputConstants.isKeyDown(window, key.getValue());
        }
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window.handle(), key.getValue()) == GLFW.GLFW_PRESS;
        }
        return false;
    }
}
