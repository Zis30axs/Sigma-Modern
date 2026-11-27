package com.mentalfrostbyte.jello.module.impl.player;

import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.network.EventReceivePacket;
import com.mentalfrostbyte.jello.event.impl.game.network.EventSendPacket;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.util.text.ChatUtil;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntConsumer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Splits vanilla server teleport confirmation from GrimAC's movement-packet teleport acceptance ("Model 1").
 *
 * <p>On {@code 1.9+} servers, cross-world transfers ({@link ClientboundRespawnPacket} followed by
 * {@link ClientboundPlayerPositionPacket}) set {@code ServerPlayer.isChangingDimension = true} (server-side PvP and
 * environmental invulnerability) and lock {@code ServerGamePacketListenerImpl.awaitingPositionFromClient = A}. Both
 * states are cleared <em>only</em> when {@link ServerboundAcceptTeleportationPacket} arrives; while unconfirmed,
 * incoming {@link ServerboundMovePlayerPacket}s update head rotation at {@code A} without moving the server entity.</p>
 *
 * <p>Meanwhile, {@code ClientPacketListener#handleMovePlayer} still replies with {@code PosRot(A)}, which satisfies
 * GrimAC's {@code SetbackTeleportUtil#checkTeleportQueue} without needing {@code AcceptTeleportationPacket}, allowing
 * client movement and {@code <= 3.0}-block {@code Reach} checks from local position {@code B} within the server's
 * interaction radius around {@code A} ({@code 4.0} blocks on {@code 1.20.5/1.20.6}, {@code 6.0} blocks elsewhere).</p>
 */
public class GodMode extends Module {

    static final int NO_TELEPORT_ID = Integer.MIN_VALUE;

    private static final Set<Relative> RELATIVE_POSITION_AXES = Set.of(Relative.X, Relative.Y, Relative.Z);

    public enum TriggerMode {
        /** Arms on cross-world {@code Respawn} or subserver {@code Login}, then latches on the spawn teleport. */
        WORLD_CHANGE,
        /** Arms strictly on {@code ClientboundRespawnPacket} (where {@code isChangingDimension} grants invulnerability). */
        RESPAWN_ONLY,
        /** Latches immediately on any incoming {@code ClientboundPlayerPositionPacket} ({@code /tp}, pearl, setback, spawn). */
        ALL_TELEPORTS
    }

    private final EnumSetting<TriggerMode> trigger = this.register(new EnumSetting<>("Trigger",
            "WorldChange arms on Respawn/Login transitions; RespawnOnly restricts to cross-world Respawn; AllTeleports latches on any teleport.",
            TriggerMode.WORLD_CHANGE));

    private final BooleanSetting resyncOnDisable = this.register(new BooleanSetting("Resync On Disable",
            "Sends the latest suppressed TeleportConfirm when disabled so the server clears awaitingPositionFromClient cleanly.",
            true));

    private final BooleanSetting suppressIdleReminder = this.register(new BooleanSetting("Suppress Idle Reminder",
            "On 1.9-1.21.1 (when Grim BadPacketsR is off), skips alternate stationary 20-tick Pos reminders to reduce 1s anchor snapbacks.",
            false));

    private final BooleanSetting notify = this.register(new BooleanSetting("Notify",
            "Prints local status lines when armed on world change and when the server anchor latches or moves.",
            true));

    private final Object stateLock = new Object();

    private boolean armed;
    private boolean latched;
    private boolean dimensionInvulnerable;
    private int lastTeleportId = NO_TELEPORT_ID;
    private int interceptedCount;
    private @Nullable Vec3 anchorPos;
    private boolean awaitingRelativeReplyPosRot;

    private @Nullable Vec3 lastReportedPos;
    private boolean lastReportedOnGround;
    private boolean lastReportedHorizontalCollision;
    private int skippedIdleReminders;

    private volatile boolean releasing;

    public GodMode() {
        super(ModuleCategory.PLAYER, "GodMode",
                "Suppresses TeleportConfirm across world transitions to keep server spawn invulnerability while moving near the anchor.");
    }

    @Override
    protected void onEnable() {
        this.resetState();
    }

    @Override
    protected void onDisable() {
        this.releaseLatch(teleportId -> {
            if (mc == null) {
                return;
            }
            Runnable sendTask = () -> {
                ClientPacketListener connection = mc.getConnection();
                if (connection != null) {
                    connection.send(new ServerboundAcceptTeleportationPacket(teleportId));
                }
            };
            if (mc.isSameThread()) {
                sendTask.run();
            } else {
                mc.execute(sendTask);
            }
        });
    }

    /**
     * Note: {@code ClientPacketListener#handleRespawn} calls {@code mc.setLevel(newLevel)} (firing a non-null
     * {@link EventLoadWorld}) <em>before</em> {@code handleMovePlayer} processes the spawn
     * {@link ClientboundPlayerPositionPacket}. Clearing on non-null levels would disarm the pending latch mid-respawn,
     * so we reset only when detaching to {@code null} (disconnect / reconfiguration).
     */
    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) {
        this.handleWorldChange(event.getLevel() != null);
    }

    void handleWorldChange(final boolean hasAttachedLevel) {
        if (!hasAttachedLevel) {
            this.resetState();
        }
    }

    @EventTarget(EventPriority.HIGHEST)
    public void onReceivePacket(final EventReceivePacket event) {
        if (event.isCancelled()) {
            return;
        }
        this.processInboundPacket(event.getPacket(), currentTargetVersion());
    }

    @EventTarget(EventPriority.HIGHEST)
    public void onSendPacket(final EventSendPacket event) {
        this.processOutboundPacket(event, currentTargetVersion());
    }

    void processInboundPacket(final @Nullable Packet<?> packet, final @Nullable ProtocolVersion version) {
        if (!supportsTeleportConfirm(version)) {
            return;
        }
        this.inspectInboundPacket(packet, version);
    }

    void processOutboundPacket(final EventSendPacket event, final @Nullable ProtocolVersion version) {
        if (event.isCancelled() || this.releasing || !supportsTeleportConfirm(version)) {
            return;
        }

        Packet<?> packet = event.getPacket();
        if (packet instanceof ServerboundAcceptTeleportationPacket accept) {
            this.handleOutboundAcceptTeleport(event, accept);
        } else if (packet instanceof ServerboundMovePlayerPacket move) {
            this.handleOutboundMovePlayer(event, move, version);
        }
    }

    private void inspectInboundPacket(final @Nullable Packet<?> packet, final @Nullable ProtocolVersion version) {
        if (packet == null) {
            return;
        }
        if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> subPacket : bundle.subPackets()) {
                this.inspectInboundPacket(subPacket, version);
            }
            return;
        }

        if (packet instanceof ClientboundStartConfigurationPacket) {
            this.resetState();
        } else if (packet instanceof ClientboundRespawnPacket respawn) {
            this.handleInboundRespawn(respawn);
        } else if (packet instanceof ClientboundLoginPacket) {
            this.handleInboundLogin();
        } else if (packet instanceof ClientboundPlayerPositionPacket positionPacket) {
            this.handleInboundPlayerPosition(positionPacket, version);
        }
    }

    private void handleInboundRespawn(final ClientboundRespawnPacket respawn) {
        TriggerMode mode = this.trigger.get();
        boolean keepsEntityState = respawn.shouldKeep(ClientboundRespawnPacket.KEEP_ENTITY_DATA);
        synchronized (this.stateLock) {
            this.armed = true;
            this.latched = false;
            this.dimensionInvulnerable = keepsEntityState;
            this.lastTeleportId = NO_TELEPORT_ID;
            this.interceptedCount = 0;
            this.anchorPos = null;
            this.awaitingRelativeReplyPosRot = false;
            this.skippedIdleReminders = 0;
        }
        if (mode != TriggerMode.ALL_TELEPORTS) {
            this.notifyLocal(String.format(Locale.ROOT,
                    "[GodMode] Armed on Respawn (%s) - waiting for spawn teleport.",
                    keepsEntityState ? "dimension invulnerability" : "respawn anchor"));
        }
    }

    private void handleInboundLogin() {
        TriggerMode mode = this.trigger.get();
        if (mode == TriggerMode.WORLD_CHANGE) {
            synchronized (this.stateLock) {
                this.armed = true;
                this.latched = false;
                this.dimensionInvulnerable = false;
                this.lastTeleportId = NO_TELEPORT_ID;
                this.interceptedCount = 0;
                this.anchorPos = null;
                this.awaitingRelativeReplyPosRot = false;
                this.lastReportedPos = null;
                this.skippedIdleReminders = 0;
            }
            this.notifyLocal("[GodMode] Armed on server Login - waiting for spawn teleport.");
        } else {
            this.resetState();
        }
    }

    private void handleInboundPlayerPosition(
            final ClientboundPlayerPositionPacket packet,
            final @Nullable ProtocolVersion version
    ) {
        TriggerMode mode = this.trigger.get();
        String notification = null;
        synchronized (this.stateLock) {
            boolean shouldLatch = mode == TriggerMode.ALL_TELEPORTS || this.armed || this.latched;
            if (!shouldLatch) {
                return;
            }

            boolean wasLatched = this.latched;
            Vec3 previousAnchor = this.anchorPos;
            Vec3 reference = this.lastReportedPos != null ? this.lastReportedPos : previousAnchor;
            Vec3 resolvedAnchor = resolveAnchorPosition(packet, reference);
            boolean hasRelativeAxis = hasRelativePositionAxis(packet.relatives());

            this.armed = false;
            this.latched = true;
            this.lastTeleportId = packet.id();
            this.anchorPos = resolvedAnchor;
            this.awaitingRelativeReplyPosRot = hasRelativeAxis;
            this.skippedIdleReminders = 0;

            boolean anchorMoved = previousAnchor == null || previousAnchor.distanceToSqr(resolvedAnchor) > 0.0625;
            if (!wasLatched) {
                notification = String.format(Locale.ROOT,
                        "[GodMode] Latched at (%.2f, %.2f, %.2f) [%s, server range %.1fm, Grim reach 3.0m].",
                        resolvedAnchor.x, resolvedAnchor.y, resolvedAnchor.z,
                        this.dimensionInvulnerable ? "Invulnerable + Anchor" : "Anchor Desync",
                        serverAttackRange(version));
            } else if (anchorMoved) {
                notification = String.format(Locale.ROOT,
                        "[GodMode] Anchor updated -> (%.2f, %.2f, %.2f) [id=%d].",
                        resolvedAnchor.x, resolvedAnchor.y, resolvedAnchor.z,
                        packet.id());
            }
        }

        if (notification != null) {
            this.notifyLocal(notification);
        }
    }

    private void handleOutboundAcceptTeleport(
            final EventSendPacket event,
            final ServerboundAcceptTeleportationPacket packet
    ) {
        synchronized (this.stateLock) {
            if (!this.latched && !(this.trigger.is(TriggerMode.ALL_TELEPORTS))) {
                return;
            }
            this.latched = true;
            this.armed = false;
            this.lastTeleportId = packet.getId();
            this.interceptedCount++;
        }
        event.setCancelled(true);
    }

    private void handleOutboundMovePlayer(
            final EventSendPacket event,
            final ServerboundMovePlayerPacket move,
            final @Nullable ProtocolVersion version
    ) {
        synchronized (this.stateLock) {
            if (move.hasPosition()) {
                Vec3 movePos = new Vec3(move.getX(0.0), move.getY(0.0), move.getZ(0.0));
                if (this.latched && this.awaitingRelativeReplyPosRot && move.hasRotation()) {
                    this.anchorPos = movePos;
                    this.awaitingRelativeReplyPosRot = false;
                }

                if (this.latched
                        && this.suppressIdleReminder.get()
                        && canSuppressIdleReminder(version)
                        && !move.hasRotation()
                        && this.lastReportedPos != null
                        && this.lastReportedPos.distanceToSqr(movePos) == 0.0
                        && this.lastReportedOnGround == move.isOnGround()
                        && this.lastReportedHorizontalCollision == move.horizontalCollision()
                        && this.skippedIdleReminders < 1) {
                    this.skippedIdleReminders++;
                    event.setCancelled(true);
                    return;
                }

                this.lastReportedPos = movePos;
            }

            this.skippedIdleReminders = 0;
            this.lastReportedOnGround = move.isOnGround();
            this.lastReportedHorizontalCollision = move.horizontalCollision();
        }
    }

    void releaseLatch(final IntConsumer confirmSender) {
        Objects.requireNonNull(confirmSender, "confirmSender");
        boolean shouldSendConfirm;
        int teleportIdToConfirm;
        synchronized (this.stateLock) {
            shouldSendConfirm = this.resyncOnDisable.get()
                    && this.latched
                    && this.lastTeleportId != NO_TELEPORT_ID;
            teleportIdToConfirm = this.lastTeleportId;
            this.clearFieldsLocked();
        }

        if (!shouldSendConfirm) {
            return;
        }

        this.releasing = true;
        try {
            confirmSender.accept(teleportIdToConfirm);
        } finally {
            this.releasing = false;
        }
    }

    void resetState() {
        synchronized (this.stateLock) {
            this.clearFieldsLocked();
        }
    }

    private void clearFieldsLocked() {
        this.armed = false;
        this.latched = false;
        this.dimensionInvulnerable = false;
        this.lastTeleportId = NO_TELEPORT_ID;
        this.interceptedCount = 0;
        this.anchorPos = null;
        this.awaitingRelativeReplyPosRot = false;
        this.lastReportedPos = null;
        this.lastReportedOnGround = false;
        this.lastReportedHorizontalCollision = false;
        this.skippedIdleReminders = 0;
    }

    private void notifyLocal(final String message) {
        if (!this.notify.get() || mc == null) {
            return;
        }
        Runnable printTask = () -> {
            if (mc.gui != null && mc.gui.hud != null) {
                ChatUtil.print(message);
            }
        };
        if (mc.isSameThread()) {
            printTask.run();
        } else {
            mc.execute(printTask);
        }
    }

    static Vec3 resolveAnchorPosition(
            final ClientboundPlayerPositionPacket packet,
            final @Nullable Vec3 referencePos
    ) {
        Vec3 base = referencePos != null ? referencePos : Vec3.ZERO;
        Set<Relative> relatives = packet.relatives();
        Vec3 delta = packet.change().position();
        double x = (relatives.contains(Relative.X) ? base.x : 0.0) + delta.x;
        double y = (relatives.contains(Relative.Y) ? base.y : 0.0) + delta.y;
        double z = (relatives.contains(Relative.Z) ? base.z : 0.0) + delta.z;
        return new Vec3(x, y, z);
    }

    private static boolean hasRelativePositionAxis(final Set<Relative> relatives) {
        for (Relative axis : RELATIVE_POSITION_AXES) {
            if (relatives.contains(axis)) {
                return true;
            }
        }
        return false;
    }

    static boolean supportsTeleportConfirm(final @Nullable ProtocolVersion version) {
        return version == null || version.newerThanOrEqualTo(ProtocolVersion.v1_9);
    }

    static boolean canSuppressIdleReminder(final @Nullable ProtocolVersion version) {
        return version != null
                && version.newerThanOrEqualTo(ProtocolVersion.v1_9)
                && version.olderThan(ProtocolVersion.v1_21_2);
    }

    static double serverAttackRange(final @Nullable ProtocolVersion version) {
        // 1.20.5 / 1.20.6 (ProtocolVersion.v1_20_5) used ENTITY_INTERACTION_RANGE (3.0) + 1.0 margin = 4.0 blocks
        // before 1.21 (MC-271565) widened the server margin back to 3.0 (6.0 blocks total).
        if (version != null && version.equalTo(ProtocolVersion.v1_20_5)) {
            return 4.0;
        }
        return 6.0;
    }

    private static @Nullable ProtocolVersion currentTargetVersion() {
        try {
            return ProtocolTranslator.getTargetVersion();
        } catch (final Throwable ignored) {
            return null;
        }
    }

    // Package-private inspection & configuration hooks for unit tests

    TriggerMode getTriggerMode() {
        return this.trigger.get();
    }

    void setTriggerMode(final TriggerMode mode) {
        this.trigger.set(mode);
    }

    boolean isResyncOnDisable() {
        return this.resyncOnDisable.get();
    }

    void setResyncOnDisable(final boolean enabled) {
        this.resyncOnDisable.set(enabled);
    }

    boolean isSuppressIdleReminder() {
        return this.suppressIdleReminder.get();
    }

    void setSuppressIdleReminder(final boolean enabled) {
        this.suppressIdleReminder.set(enabled);
    }

    boolean isNotify() {
        return this.notify.get();
    }

    void setNotify(final boolean enabled) {
        this.notify.set(enabled);
    }

    boolean isArmed() {
        synchronized (this.stateLock) {
            return this.armed;
        }
    }

    boolean isLatched() {
        synchronized (this.stateLock) {
            return this.latched;
        }
    }

    boolean isDimensionInvulnerable() {
        synchronized (this.stateLock) {
            return this.dimensionInvulnerable;
        }
    }

    int getLastTeleportId() {
        synchronized (this.stateLock) {
            return this.lastTeleportId;
        }
    }

    int getInterceptedCount() {
        synchronized (this.stateLock) {
            return this.interceptedCount;
        }
    }

    @Nullable Vec3 getAnchorPos() {
        synchronized (this.stateLock) {
            return this.anchorPos;
        }
    }
}
