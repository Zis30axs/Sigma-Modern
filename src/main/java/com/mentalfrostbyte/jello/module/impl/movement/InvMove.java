package com.mentalfrostbyte.jello.module.impl.movement;

import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.network.EventSendPacket;
import com.mentalfrostbyte.jello.event.impl.player.EventUpdate;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
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
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.ContainerInput;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Lets you keep moving while an inventory, chest or other non-typing screen is open, with both generic
 * (LBNG-style) and anti-cheat specialized {@link Behavior} presets.
 *
 * <p>Reads live physical key states on {@link EventMovementInput} rather than mutating {@link KeyMapping}
 * latches, so keys never stick when closing a screen or toggling the module.</p>
 *
 * <p>In {@link Behavior#GRIM}, container clicks and close packets are synchronized with GrimAC's exact
 * protocol checks across {@code 1.8.x}–{@code 26.2}:
 * <ul>
 *   <li>{@code MultiActionsC} &amp; {@code MultiActionsD}: ensures {@code isSprinting == false},
 *       {@code isSneaking == false}, and (on {@code >= 1.21.2} where {@code supportsEndTick()} is active)
 *       {@code knownInput.moving() == false} via natural {@code LocalPlayer} pre-movement state transitions
 *       before flushing queued clicks/closes, while keeping continuous walk input on {@code <= 1.21.1}.</li>
 *   <li>{@code Post} ({@code < 1.13}) &amp; {@code PacketOrderO} ({@code >= 1.21.2}): flushes inside the
 *       pre-flying {@link EventMotion} window before {@code ServerboundMovePlayerPacket} and
 *       {@code CLIENT_TICK_END}, never mid-flying.</li>
 *   <li>{@code BadPacketsF}: lets {@code LocalPlayer#sendIsSprintingIfNeeded()} own {@code STOP_SPRINTING}
 *       with {@code wasSprinting} kept in lockstep so duplicate sprint packets never occur.</li>
 *   <li>{@code PacketOrderA} ({@code <= 1.12.2}) &amp; {@code PacketOrderK} ({@code < 1.12}): separates
 *       conflicting {@code PICKUP}/{@code QUICK_MOVE} click batches across ticks on legacy protocols and
 *       suppresses/guards {@code OPEN_INVENTORY} same-tick collisions.</li>
 * </ul>
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
        /** Grim preset: protocol-aware pre-flying queue + 1-tick quiet state sync for MultiActionsC/D, Post & PacketOrder. */
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

    /**
     * Protocol-level capabilities relevant to GrimAC's inventory checks for a given ViaFabricPlus target version.
     *
     * @param requiresPreFlyingWindow          {@code < 1.13}: Grim {@code Post} flags {@code CLICK_WINDOW} after {@code Flying}.
     * @param hasEndTickInput                  {@code >= 1.21.2}: Grim {@code MultiActionsC/D} checks {@code knownInput.moving()}
     *                                         and {@code PacketOrderO} forbids packets between {@code Flying} and {@code CLIENT_TICK_END}.
     * @param enforcesClickOrder               {@code <= 1.12.2}: Grim {@code PacketOrderA} forbids mixing {@code PICKUP} and {@code QUICK_MOVE}
     *                                         in the same tick.
     * @param normalizeDiagonals               {@code >= 1.21.5}: {@code KeyboardInput} normalizes diagonal {@code moveVector}s; {@code <= 1.21.4}
     *                                         must stay unnormalized ({@code length = sqrt(2)}) to match Grim's prediction engine.
     * @param suppressOpenInventoryAchievement {@code <= 1.11.1}: suppresses client {@code OPEN_INVENTORY_ACHIEVEMENT} so Grim
     *                                         {@code PacketOrderE/F/G/K/L} cannot flag open-inventory ordering.
     */
    public record GrimProtocolProfile(
            boolean requiresPreFlyingWindow,
            boolean hasEndTickInput,
            boolean enforcesClickOrder,
            boolean normalizeDiagonals,
            boolean suppressOpenInventoryAchievement
    ) {
        public static final GrimProtocolProfile MODERN_DEFAULT =
                new GrimProtocolProfile(false, true, false, true, false);

        public static GrimProtocolProfile forVersion(final @Nullable ProtocolVersion version) {
            if (version == null) {
                return MODERN_DEFAULT;
            }
            return new GrimProtocolProfile(
                    version.olderThan(ProtocolVersion.v1_13),
                    version.newerThanOrEqualTo(ProtocolVersion.v1_21_2),
                    version.olderThanOrEqualTo(ProtocolVersion.v1_12_2),
                    version.newerThan(ProtocolVersion.v1_21_4),
                    version.olderThanOrEqualTo(ProtocolVersion.v1_11_1)
            );
        }
    }

    enum ActionKind {
        CLICK_WINDOW,
        CLOSE_WINDOW,
        CONTAINER_AUX
    }

    record QueuedAction(
            ActionKind kind,
            int containerId,
            @Nullable ContainerInput containerInput,
            Runnable send
    ) {
        QueuedAction {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(send, "send");
        }

        static QueuedAction click(final int containerId, final @Nullable ContainerInput input, final Runnable send) {
            return new QueuedAction(ActionKind.CLICK_WINDOW, containerId, input, send);
        }

        static QueuedAction close(final int containerId, final Runnable send) {
            return new QueuedAction(ActionKind.CLOSE_WINDOW, containerId, null, send);
        }

        static QueuedAction aux(final int containerId, final Runnable send) {
            return new QueuedAction(ActionKind.CONTAINER_AUX, containerId, null, send);
        }
    }

    private static final double MOVING_SPEED_SQ = 1.0E-4;

    private final EnumSetting<Behavior> behavior = this.register(new EnumSetting<>(
            "Behavior", "Generic (NORMAL/SAFE/UNDETECTABLE/STOP_ON_ACTION/BLINK) or specialized (INTAVE/GRIM/HYPIXEL/AAC) mode.",
            Behavior.NORMAL));

    private final EnumSetting<SprintControl> sprintControl = this.register(new EnumSetting<>(
            "SprintControl", "How sprinting is handled while a screen is open or during clicks.", SprintControl.ALLOW));

    private final BooleanSetting grimBlockWhileUsing = this.register(new BooleanSetting(
            "GrimBlockWhileUsing", "Block inventory clicks while actively using an item in Grim mode to prevent NoSlow desync.",
            true));

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

    private final Object queueLock = new Object();
    private final Deque<QueuedAction> actionQueue = new ArrayDeque<>();
    private long queueStartMs;
    private long lastClickSentMs;
    private int pauseTicksRemaining;
    private int deferFlushTicks;
    private boolean flushing;

    /** Grim state: true once {@link #onMovementInput} has prepared a quiet tick ready to flush at {@link EventMotion}. */
    private boolean grimQuietTickPrepared;
    /** Grim state: true if {@code OPEN_INVENTORY} was sent during the current client tick ({@code PacketOrderK}). */
    private boolean openedInventoryThisTick;
    /** Grim state: true between {@code ServerboundMovePlayerPacket} and {@code CLIENT_TICK_END} ({@code PacketOrderO}). */
    private boolean betweenFlyingAndTickEnd;

    public InvMove() {
        super(ModuleCategory.MOVEMENT, "InvMove", "Lets you walk, jump and turn while an inventory or GUI is open");
        this.grimBlockWhileUsing.visibleWhen(() -> this.behavior.is(Behavior.GRIM));
        this.stopTicks.visibleWhen(() -> this.behavior.is(Behavior.SAFE)
                || this.behavior.is(Behavior.INTAVE)
                || this.behavior.is(Behavior.HYPIXEL)
                || this.behavior.is(Behavior.AAC));
        this.maxBlinkMs.visibleWhen(() -> this.behavior.is(Behavior.BLINK));
        this.clickDelayMs.visibleWhen(() -> this.behavior.is(Behavior.INTAVE) || this.behavior.is(Behavior.HYPIXEL));
        this.arrowSpeed.visibleWhen(this.arrowRotate::get);
    }

    // ------------------------------------------------------------------------- Vanilla call-site hooks

    /**
     * Called from {@code Minecraft#handleKeybinds} before sending the {@code <= 1.11.1}
     * {@code OPEN_INVENTORY_ACHIEVEMENT} packet.
     */
    public static boolean shouldSuppressOpenInventoryAchievement() {
        InvMove invMove = Modules.enabled(InvMove.class);
        if (invMove == null || !invMove.behavior.is(Behavior.GRIM)) {
            return false;
        }
        return currentProtocolProfile().suppressOpenInventoryAchievement();
    }

    /**
     * Called from {@code MultiPlayerGameMode#handleContainerInput} BEFORE {@code containerMenu.clicked(...)}
     * mutates client-side slots so cancelled clicks never create client-side ghost items.
     */
    public static boolean shouldBlockContainerClick() {
        InvMove invMove = Modules.enabled(InvMove.class);
        if (invMove == null || mc.player == null) {
            return false;
        }
        Behavior mode = invMove.behavior.get();
        if (mode == Behavior.STOP_ON_ACTION) {
            return invMove.isPlayerMoving();
        }
        if (mode == Behavior.GRIM && invMove.grimBlockWhileUsing.get()) {
            return mc.player.isUsingItem();
        }
        return false;
    }

    /**
     * Called from {@code MultiPlayerGameMode#handleContainerInput} across all protocols ({@code 1.8.x}–{@code 26.2}),
     * including ViaFabricPlus {@code PacketWrapper} container click paths on {@code <= 1.21.4}.
     */
    public static void dispatchContainerClick(
            final int containerId,
            final ContainerInput containerInput,
            final Runnable sendClick
    ) {
        InvMove invMove = Modules.enabled(InvMove.class);
        if (invMove == null || mc.player == null) {
            sendClick.run();
            return;
        }
        invMove.handleOutgoingClick(containerId, containerInput, sendClick);
    }

    // ------------------------------------------------------------------------- Lifecycle & Events

    @Override
    protected void onDisable() {
        if (mc.player != null && mc.getConnection() != null && !this.behavior.is(Behavior.GRIM)) {
            this.flushAllQueued();
        } else if (mc.player != null && mc.getConnection() != null && this.behavior.is(Behavior.GRIM)) {
            GrimProtocolProfile profile = currentProtocolProfile();
            if (this.isLiveImmediateGrimSafe(profile)) {
                this.flushAllQueuedRaw();
            } else {
                this.clearQueueState();
            }
        } else {
            this.clearQueueState();
        }
        this.resetTransientFlags();
    }

    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) {
        this.clearQueueState();
        this.resetTransientFlags();
    }

    @EventTarget
    public void onTick(final EventTick event) {
        if (event.isPre()) {
            this.betweenFlyingAndTickEnd = false;
            if (mc.player != null
                    && this.behavior.is(Behavior.GRIM)
                    && mc.player.isPassenger()
                    && this.hasQueuedActions()
                    && this.grimQuietTickPrepared) {
                GrimProtocolProfile profile = currentProtocolProfile();
                if (profile.requiresPreFlyingWindow() && !this.openedInventoryThisTick) {
                    if (mc.player.wasSprinting()) {
                        this.sendStopSprint();
                    }
                    if (this.isLiveServerQuietForGrim(profile)) {
                        this.flushGrimTickBatch(profile);
                    }
                    this.grimQuietTickPrepared = false;
                }
            }
            return;
        }

        // POST tick: CLIENT_TICK_END has already been sent for 1.21.2+.
        this.betweenFlyingAndTickEnd = false;
        this.openedInventoryThisTick = false;

        if (mc.player != null && this.behavior.is(Behavior.GRIM)) {
            GrimProtocolProfile profile = currentProtocolProfile();
            if (mc.player.isPassenger() && this.hasQueuedActions() && this.grimQuietTickPrepared) {
                if (!profile.requiresPreFlyingWindow()) {
                    if (this.isLiveServerQuietForGrim(profile)) {
                        this.flushGrimTickBatch(profile);
                    }
                    this.grimQuietTickPrepared = false;
                }
                // On < 1.13 while riding, keep grimQuietTickPrepared = true into next EventTick(PRE) before Flying.
                return;
            }
        }

        this.grimQuietTickPrepared = false;
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

        if (!this.hasQueuedActions()) {
            return;
        }

        Behavior mode = this.behavior.get();
        if (mode == Behavior.GRIM) {
            // Grim flushes exclusively at EventMotion(PRE) (or passenger tick boundaries) after quiet state packets.
            return;
        }

        boolean screenClosed = !(mc.gui.screen() instanceof AbstractContainerScreen<?>);
        if (screenClosed) {
            this.flushAllQueued();
            return;
        }

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

    @EventTarget(EventPriority.LOW)
    public void onMovementInput(final EventMovementInput event) {
        if (mc.player == null || mc.level == null) {
            return;
        }

        Behavior mode = this.behavior.get();
        GrimProtocolProfile profile = currentProtocolProfile();
        Screen screen = mc.gui.screen();
        boolean movableScreen = screen != null && this.canMoveIn(screen);

        // CRITICAL for Grim: even if the player just pressed E/ESC to close the screen (so screen == null),
        // any queued clicks + ServerboundContainerClosePacket still need this 1 quiet tick before flushing!
        if (mode == Behavior.GRIM && this.hasQueuedActions()) {
            Input baseKeys = movableScreen
                    ? this.pollPhysicalScreenInput(mc.getWindow(), screen, mode)
                    : event.getInput();
            this.applyGrimFlushTickInput(event, profile, baseKeys);
            if (mc.player.isSprinting()) {
                mc.player.setSprinting(false);
            }
            if (movableScreen) {
                this.applyArrowRotation(mc.getWindow());
            }
            return;
        }

        if (!movableScreen) {
            return;
        }

        boolean inContainer = screen instanceof AbstractContainerScreen<?>;
        if (this.pauseTicksRemaining > 0) {
            // Keep sneak active in INTAVE while paused so crouch state does not flicker.
            boolean holdSneak = mode == Behavior.INTAVE && inContainer;
            event.setInput(new Input(false, false, false, false, false, holdSneak, false), profile.normalizeDiagonals());
            return;
        }

        Window window = mc.getWindow();
        Input desired = this.pollPhysicalScreenInput(window, screen, mode);
        if (!desired.sprint() && mc.player.isSprinting() && this.shouldBlockSprintInScreen(inContainer, mode)) {
            mc.player.setSprinting(false);
        }

        event.setInput(desired, profile.normalizeDiagonals());
        this.applyArrowRotation(window);
    }

    @EventTarget(EventPriority.LOWEST)
    public void onMotion(final EventMotion event) {
        if (!event.isPre() || event.isCancelled() || mc.player == null) {
            return;
        }

        if (this.behavior.is(Behavior.GRIM) && this.hasQueuedActions()) {
            GrimProtocolProfile profile = currentProtocolProfile();
            if (this.grimQuietTickPrepared
                    && !this.openedInventoryThisTick
                    && this.isLiveServerQuietForGrim(profile)) {
                this.flushGrimTickBatch(profile);
            }
        }

        this.betweenFlyingAndTickEnd = true;
    }

    @EventTarget
    public void onSendPacket(final EventSendPacket event) {
        if (this.flushing || mc.player == null) {
            return;
        }

        Packet<?> packet = event.getPacket();
        if (packet instanceof ServerboundPlayerCommandPacket commandPacket) {
            if (commandPacket.getAction() == ServerboundPlayerCommandPacket.Action.OPEN_INVENTORY) {
                this.openedInventoryThisTick = true;
            }
            return;
        }

        if (packet instanceof ServerboundContainerClosePacket closePacket) {
            if (this.behavior.is(Behavior.GRIM)) {
                GrimProtocolProfile profile = currentProtocolProfile();
                if (!this.isLiveImmediateGrimSafe(profile)) {
                    this.enqueueAction(QueuedAction.close(closePacket.getContainerId(), () -> this.sendPacketDirect(closePacket)));
                    event.setCancelled(true);
                }
                return;
            }

            if (this.hasQueuedActions()) {
                this.flushAllQueued();
            }
            return;
        }

        if (packet instanceof ServerboundPlaceRecipePacket recipePacket) {
            if (this.hasQueuedActions()) {
                this.enqueueAction(QueuedAction.aux(recipePacket.containerId(), () -> this.sendPacketDirect(recipePacket)));
                event.setCancelled(true);
            }
            return;
        }

        if (packet instanceof ServerboundContainerButtonClickPacket buttonPacket) {
            if (this.hasQueuedActions()) {
                this.enqueueAction(QueuedAction.aux(buttonPacket.containerId(), () -> this.sendPacketDirect(buttonPacket)));
                event.setCancelled(true);
            }
            return;
        }

        if (packet instanceof ServerboundContainerSlotStateChangedPacket statePacket) {
            if (this.hasQueuedActions()) {
                this.enqueueAction(QueuedAction.aux(statePacket.containerId(), () -> this.sendPacketDirect(statePacket)));
                event.setCancelled(true);
            }
            return;
        }

        if (packet instanceof ServerboundSetCreativeModeSlotPacket creativePacket) {
            if (this.hasQueuedActions()) {
                this.enqueueAction(QueuedAction.aux(0, () -> this.sendPacketDirect(creativePacket)));
                event.setCancelled(true);
            }
            return;
        }

        // Fallback for any code sending a raw >= 1.21.5 ServerboundContainerClickPacket outside handleContainerInput.
        if (packet instanceof ServerboundContainerClickPacket clickPacket) {
            Behavior mode = this.behavior.get();
            if (mode == Behavior.STOP_ON_ACTION && this.isPlayerMoving()) {
                event.setCancelled(true);
                return;
            }
            if (mode == Behavior.GRIM && this.grimBlockWhileUsing.get() && mc.player.isUsingItem()) {
                event.setCancelled(true);
                return;
            }

            boolean queued = this.processClickPolicy(
                    mode,
                    clickPacket.containerId(),
                    clickPacket.containerInput(),
                    () -> this.sendPacketDirect(clickPacket)
            );
            if (queued) {
                event.setCancelled(true);
            }
        }
    }

    // ------------------------------------------------------------------------- Click & Queue Engine

    private void handleOutgoingClick(
            final int containerId,
            final ContainerInput containerInput,
            final Runnable sendClick
    ) {
        Behavior mode = this.behavior.get();
        boolean queued = this.processClickPolicy(mode, containerId, containerInput, sendClick);
        if (!queued) {
            this.runFlushing(sendClick);
        }
    }

    /**
     * Applies the active {@link Behavior} policy to an outgoing container click.
     *
     * @return {@code true} if the click was queued for deferred sending; {@code false} if it should be sent immediately.
     */
    private boolean processClickPolicy(
            final Behavior mode,
            final int containerId,
            final ContainerInput containerInput,
            final Runnable sendClick
    ) {
        if (mode == Behavior.GRIM) {
            GrimProtocolProfile profile = currentProtocolProfile();
            if (this.isLiveImmediateGrimSafe(profile)) {
                return false;
            }
            this.enqueueAction(QueuedAction.click(containerId, containerInput, sendClick));
            return true;
        }

        boolean dropSprint = this.sprintControl.is(SprintControl.STOP_ON_CLICK)
                || mode == Behavior.INTAVE
                || mode == Behavior.HYPIXEL
                || mode == Behavior.AAC;
        if (dropSprint && mc.player != null && (mc.player.isSprinting() || mc.player.wasSprinting())) {
            this.sendStopSprint();
        }

        switch (mode) {
            case SAFE, AAC -> {
                this.pauseTicksRemaining = Math.max(this.pauseTicksRemaining, this.stopTicks.getInt());
                return false;
            }
            case BLINK -> {
                if (this.isPlayerMoving() || this.hasQueuedActions()) {
                    this.enqueueAction(QueuedAction.click(containerId, containerInput, sendClick));
                    return true;
                }
                return false;
            }
            case INTAVE, HYPIXEL -> {
                this.pauseTicksRemaining = Math.max(this.pauseTicksRemaining, this.stopTicks.getInt());
                if (this.isPlayerMoving() || this.hasQueuedActions()) {
                    this.enqueueAction(QueuedAction.click(containerId, containerInput, sendClick));
                    this.deferFlushTicks = Math.max(this.deferFlushTicks, 1);
                    return true;
                }
                this.lastClickSentMs = System.currentTimeMillis();
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------------- Testable Grim Core Logic

    public Behavior getBehavior() {
        return this.behavior.get();
    }

    public void setBehavior(final Behavior mode) {
        this.behavior.set(mode);
    }

    public EnumSetting<Behavior> getBehaviorSetting() {
        return this.behavior;
    }

    public EnumSetting<SprintControl> getSprintControlSetting() {
        return this.sprintControl;
    }

    public BooleanSetting getGrimBlockWhileUsingSetting() {
        return this.grimBlockWhileUsing;
    }

    public NumberSetting getStopTicksSetting() {
        return this.stopTicks;
    }

    public NumberSetting getMaxBlinkMsSetting() {
        return this.maxBlinkMs;
    }

    public NumberSetting getClickDelayMsSetting() {
        return this.clickDelayMs;
    }

    public NumberSetting getArrowSpeedSetting() {
        return this.arrowSpeed;
    }

    int queuedActionCount() {
        synchronized (this.queueLock) {
            return this.actionQueue.size();
        }
    }

    boolean isGrimQuietTickPrepared() {
        return this.grimQuietTickPrepared;
    }

    void enqueueAction(final QueuedAction action) {
        synchronized (this.queueLock) {
            if (this.actionQueue.isEmpty()) {
                this.queueStartMs = System.currentTimeMillis();
            }
            this.actionQueue.addLast(action);
        }
    }

    boolean hasQueuedActions() {
        synchronized (this.queueLock) {
            return !this.actionQueue.isEmpty();
        }
    }

    static boolean isMovingInput(final Input input) {
        return input.forward() || input.backward() || input.left() || input.right();
    }

    /**
     * Pure Grim safety gate for sending a {@code CLICK_WINDOW} or {@code CLOSE_WINDOW} immediately without queuing.
     */
    boolean canSendGrimImmediately(
            final GrimProtocolProfile profile,
            final boolean serverSprinting,
            final boolean serverSneaking,
            final boolean serverMovingInput,
            final boolean inFlyingToTickEndWindow
    ) {
        if (this.hasQueuedActions() || this.openedInventoryThisTick) {
            return false;
        }
        if (profile.requiresPreFlyingWindow()) {
            // < 1.13 Post flags CLICK_WINDOW outside the pre-flying window; always synchronize to EventMotion(PRE).
            return false;
        }
        if (profile.hasEndTickInput() && (inFlyingToTickEndWindow || serverMovingInput)) {
            return false;
        }
        return !serverSprinting && !serverSneaking;
    }

    /**
     * Pure Grim check right at {@link EventMotion} (after {@code LocalPlayer} has transmitted {@code PLAYER_INPUT},
     * {@code STOP_SPRINTING}, and {@code STOP_SNEAKING}) confirming the server's tracked state is clean for flushing.
     */
    static boolean isServerQuietForGrim(
            final GrimProtocolProfile profile,
            final boolean serverSprinting,
            final boolean serverSneaking,
            final boolean serverMovingInput
    ) {
        if (serverSprinting || serverSneaking) {
            return false;
        }
        return !profile.hasEndTickInput() || !serverMovingInput;
    }

    /**
     * Configures {@link EventMovementInput} for a single Grim quiet flush tick:
     * <ul>
     *   <li>On {@code >= 1.21.2} ({@code hasEndTickInput}): zeroes directional/jump/shift/sprint keys for 1 tick so
     *       {@code LocalPlayer} sends {@code ServerboundPlayerInputPacket(Input.EMPTY)} before {@link EventMotion}.</li>
     *   <li>On {@code <= 1.21.1}: keeps directional walk + jump active (with version-accurate diagonal vector) while
     *       clearing {@code shift} and {@code sprint} so walking never pauses during clicks/closes.</li>
     * </ul>
     */
    void applyGrimFlushTickInput(
            final EventMovementInput event,
            final GrimProtocolProfile profile,
            final Input baseInput
    ) {
        this.grimQuietTickPrepared = true;
        if (profile.hasEndTickInput()) {
            event.setInput(Input.EMPTY, profile.normalizeDiagonals());
            return;
        }
        Input walkOnly = new Input(
                baseInput.forward(),
                baseInput.backward(),
                baseInput.left(),
                baseInput.right(),
                baseInput.jump(),
                false,
                false
        );
        event.setInput(walkOnly, profile.normalizeDiagonals());
    }

    /**
     * Flushes all queued actions permitted in a single pre-flying tick under {@code profile}.
     * On {@code <= 1.12.2} ({@code PacketOrderA}), stops before mixing {@code PICKUP}/{@code PICKUP_ALL} and
     * {@code QUICK_MOVE} in the same tick, leaving remaining clicks (and any trailing {@code CLOSE_WINDOW})
     * for the following tick.
     *
     * @return number of actions dispatched in this tick batch.
     */
    int flushGrimTickBatch(final GrimProtocolProfile profile) {
        int sent = 0;
        boolean sawPickup = false;
        boolean sawQuickMove = false;

        this.flushing = true;
        try {
            while (true) {
                QueuedAction next;
                synchronized (this.queueLock) {
                    QueuedAction head = this.actionQueue.peekFirst();
                    if (head == null) {
                        this.queueStartMs = 0L;
                        break;
                    }
                    if (profile.enforcesClickOrder() && head.kind() == ActionKind.CLICK_WINDOW && head.containerInput() != null) {
                        ContainerInput clickType = head.containerInput();
                        boolean isPickup = clickType == ContainerInput.PICKUP || clickType == ContainerInput.PICKUP_ALL;
                        boolean isQuickMove = clickType == ContainerInput.QUICK_MOVE;
                        if ((isPickup && sawQuickMove) || (isQuickMove && sawPickup)) {
                            // Defer conflicting click type (and everything ordered after it) to next tick.
                            break;
                        }
                        if (isPickup) {
                            sawPickup = true;
                        } else if (isQuickMove) {
                            sawQuickMove = true;
                        }
                    }
                    next = this.actionQueue.pollFirst();
                    if (this.actionQueue.isEmpty()) {
                        this.queueStartMs = 0L;
                    }
                }
                next.send().run();
                sent++;
            }
        } finally {
            this.flushing = false;
        }
        return sent;
    }

    private boolean isLiveImmediateGrimSafe(final GrimProtocolProfile profile) {
        if (mc.player == null) {
            return true;
        }
        boolean serverSprinting = mc.player.isSprinting() || mc.player.wasSprinting();
        boolean serverSneaking = mc.player.isShiftKeyDown()
                || mc.player.vfpWasSneaking()
                || mc.player.getLastSentInput().shift();
        boolean serverMovingInput = isMovingInput(mc.player.getLastSentInput());
        return this.canSendGrimImmediately(
                profile,
                serverSprinting,
                serverSneaking,
                serverMovingInput,
                this.betweenFlyingAndTickEnd
        );
    }

    private boolean isLiveServerQuietForGrim(final GrimProtocolProfile profile) {
        if (mc.player == null) {
            return false;
        }
        boolean serverSprinting = mc.player.isSprinting() || mc.player.wasSprinting();
        boolean serverSneaking = mc.player.isShiftKeyDown()
                || mc.player.vfpWasSneaking()
                || mc.player.getLastSentInput().shift();
        boolean serverMovingInput = isMovingInput(mc.player.getLastSentInput());
        return isServerQuietForGrim(profile, serverSprinting, serverSneaking, serverMovingInput);
    }

    private static GrimProtocolProfile currentProtocolProfile() {
        try {
            return GrimProtocolProfile.forVersion(ProtocolTranslator.getTargetVersion());
        } catch (final Throwable ignored) {
            return GrimProtocolProfile.MODERN_DEFAULT;
        }
    }

    // ------------------------------------------------------------------------- Input & Flush Helpers

    private boolean shouldBlockSprintInScreen(final boolean inContainer, final Behavior mode) {
        return this.sprintControl.is(SprintControl.NO_SPRINT)
                || (inContainer && (mode == Behavior.INTAVE || mode == Behavior.HYPIXEL || mode == Behavior.AAC));
    }

    private Input pollPhysicalScreenInput(final Window window, final Screen screen, final Behavior mode) {
        boolean inContainer = screen instanceof AbstractContainerScreen<?>;
        boolean forward = isPhysicallyDown(window, mc.options.keyUp);
        boolean backward = isPhysicallyDown(window, mc.options.keyDown);
        boolean left = isPhysicallyDown(window, mc.options.keyLeft);
        boolean right = isPhysicallyDown(window, mc.options.keyRight);
        boolean movingInput = forward != backward || left != right;

        boolean allowJump = this.jump.get() && !(mode == Behavior.INTAVE && inContainer);
        boolean jumping = allowJump && isPhysicallyDown(window, mc.options.keyJump);

        boolean forceSneak = mode == Behavior.INTAVE && inContainer && movingInput;
        boolean sneaking = forceSneak || (this.sneak.get() && isPhysicallyDown(window, mc.options.keyShift));

        boolean blockSprint = this.shouldBlockSprintInScreen(inContainer, mode);
        boolean physicalSprint = isPhysicallyDown(window, mc.options.keySprint);
        Sprint sprintModule = Modules.enabled(Sprint.class);
        boolean autoSprint = sprintModule != null && sprintModule.shouldAutoSprint(forward, backward, movingInput, sneaking);
        boolean sprinting = !blockSprint && (physicalSprint || autoSprint);

        return new Input(forward, backward, left, right, jumping, sneaking, sprinting);
    }

    private void applyArrowRotation(final Window window) {
        if (!this.arrowRotate.get() || mc.player == null) {
            return;
        }
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

    private void runFlushing(final Runnable action) {
        boolean prev = this.flushing;
        this.flushing = true;
        try {
            action.run();
        } finally {
            this.flushing = prev;
        }
    }

    private void sendPacketDirect(final Packet<?> packet) {
        ClientPacketListener connection = mc.getConnection();
        if (connection != null) {
            this.runFlushing(() -> connection.send(packet));
        }
    }

    private void sendStopSprint() {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || mc.player == null) {
            return;
        }
        mc.player.setSprinting(false);
        if (!mc.player.wasSprinting()) {
            return;
        }
        mc.player.setWasSprinting(false);
        this.runFlushing(() -> connection.send(
                new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING)));
    }

    private void flushOneQueued() {
        if (!this.hasQueuedActions()) {
            return;
        }
        if (mc.getConnection() == null) {
            this.clearQueueState();
            return;
        }
        this.flushing = true;
        try {
            if (mc.player != null && (mc.player.isSprinting() || mc.player.wasSprinting())) {
                this.sendStopSprint();
            }
            QueuedAction next;
            synchronized (this.queueLock) {
                next = this.actionQueue.pollFirst();
                if (this.actionQueue.isEmpty()) {
                    this.queueStartMs = 0L;
                }
            }
            if (next != null) {
                next.send().run();
            }
        } finally {
            this.flushing = false;
        }
    }

    private void flushAllQueued() {
        if (!this.hasQueuedActions()) {
            return;
        }
        if (mc.getConnection() == null) {
            this.clearQueueState();
            return;
        }
        this.flushing = true;
        try {
            if (mc.player != null && (mc.player.isSprinting() || mc.player.wasSprinting())) {
                this.sendStopSprint();
            }
            this.flushAllQueuedRaw();
        } finally {
            this.flushing = false;
        }
    }

    private void flushAllQueuedRaw() {
        this.flushing = true;
        try {
            while (true) {
                QueuedAction next;
                synchronized (this.queueLock) {
                    next = this.actionQueue.pollFirst();
                    if (next == null) {
                        this.queueStartMs = 0L;
                        break;
                    }
                }
                next.send().run();
            }
        } finally {
            this.flushing = false;
        }
    }

    void clearQueueState() {
        synchronized (this.queueLock) {
            this.actionQueue.clear();
            this.queueStartMs = 0L;
        }
    }

    void resetTransientFlags() {
        this.lastClickSentMs = 0L;
        this.pauseTicksRemaining = 0;
        this.deferFlushTicks = 0;
        this.grimQuietTickPrepared = false;
        this.openedInventoryThisTick = false;
        this.betweenFlyingAndTickEnd = false;
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
