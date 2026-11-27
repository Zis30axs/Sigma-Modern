package com.mentalfrostbyte.jello.module.impl.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.event.EventBus;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.network.EventReceivePacket;
import com.mentalfrostbyte.jello.event.impl.game.network.EventSendPacket;
import com.mentalfrostbyte.jello.gui.ModuleStyleFilter;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.module.impl.player.GodMode.TriggerMode;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GodModeTest {

    private static final double EPS = 1.0E-6;

    @Test
    void registersInPlayerCategoryAndAppearsAcrossSharedModuleFilters() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ModuleManager manager = new ModuleManager();
        manager.registerAll();

        GodMode godMode = manager.get(GodMode.class);
        assertNotNull(godMode);
        assertSame(ModuleCategory.PLAYER, godMode.getCategory());
        assertSame(ModuleStyleFilter.SHARED, ModuleStyleFilter.styleOf(godMode));
        assertTrue(ModuleStyleFilter.JELLO.includes(godMode));
        assertTrue(ModuleStyleFilter.MODERN.includes(godMode));
        assertTrue(ModuleStyleFilter.CLASSIC.includes(godMode));

        assertSame(TriggerMode.WORLD_CHANGE, godMode.getTriggerMode());
        assertTrue(godMode.isResyncOnDisable());
        assertFalse(godMode.isSuppressIdleReminder());
        assertTrue(godMode.isNotify());
    }

    @Test
    void worldChangeRespawnSplitCancelsTeleportConfirmWhileAllowingGrimPosRot() {
        GodMode godMode = new GodMode();
        godMode.setNotify(false);
        godMode.setResyncOnDisable(false);
        godMode.setEnabled(true);
        try {
            // Before a world change, ordinary setbacks/teleports are untouched in WORLD_CHANGE mode.
            EventBus.call(new EventReceivePacket(positionPacket(5, 10.0, 64.0, 10.0, Collections.emptySet())));
            EventSendPacket preRespawnConfirm = EventBus.call(new EventSendPacket(new ServerboundAcceptTeleportationPacket(5)));
            assertFalse(preRespawnConfirm.isCancelled());
            assertFalse(godMode.isLatched());

            // Cross-world transfer (Lobby -> Duel World): Respawn with KEEP_ALL_DATA sets isChangingDimension on server.
            EventBus.call(new EventReceivePacket(new ClientboundRespawnPacket(null, ClientboundRespawnPacket.KEEP_ALL_DATA)));
            assertTrue(godMode.isArmed());
            assertTrue(godMode.isDimensionInvulnerable());

            // ClientPacketListener#handleRespawn attaches the new ClientLevel BEFORE handleMovePlayer runs;
            // non-null EventLoadWorld must NOT disarm the pending latch!
            godMode.handleWorldChange(true);
            assertTrue(godMode.isArmed());

            // Spawn teleport at A = (100, 65, 200).
            EventBus.call(new EventReceivePacket(positionPacket(41, 100.0, 65.0, 200.0, Collections.emptySet())));
            assertFalse(godMode.isArmed());
            assertTrue(godMode.isLatched());
            assertEquals(41, godMode.getLastTeleportId());
            assertVecEquals(new Vec3(100.0, 65.0, 200.0), godMode.getAnchorPos());

            // Vanilla handleMovePlayer sends TeleportConfirm(41) + PosRot(A):
            // TeleportConfirm MUST be cancelled, while PosRot(A) MUST pass through to satisfy GrimAC SetbackTeleportUtil!
            EventSendPacket confirm41 = EventBus.call(new EventSendPacket(new ServerboundAcceptTeleportationPacket(41)));
            EventSendPacket grimReply41 = EventBus.call(new EventSendPacket(
                    new ServerboundMovePlayerPacket.PosRot(100.0, 65.0, 200.0, 90.0F, 0.0F, false, false)));
            assertTrue(confirm41.isCancelled());
            assertFalse(grimReply41.isCancelled());
            assertEquals(1, godMode.getInterceptedCount());

            // 20 ticks later, ServerGamePacketListenerImpl#updateAwaitingTeleport resends A with id=42,
            // followed by an arena start teleport to (120, 64, 205) with id=43 while isChangingDimension stays true.
            EventBus.call(new EventReceivePacket(positionPacket(42, 100.0, 65.0, 200.0, Collections.emptySet())));
            assertTrue(EventBus.call(new EventSendPacket(new ServerboundAcceptTeleportationPacket(42))).isCancelled());
            assertEquals(42, godMode.getLastTeleportId());

            EventBus.call(new EventReceivePacket(positionPacket(43, 120.0, 64.0, 205.0, Collections.emptySet())));
            assertTrue(EventBus.call(new EventSendPacket(new ServerboundAcceptTeleportationPacket(43))).isCancelled());
            assertEquals(43, godMode.getLastTeleportId());
            assertTrue(godMode.isDimensionInvulnerable());
            assertVecEquals(new Vec3(120.0, 64.0, 205.0), godMode.getAnchorPos());
            assertEquals(3, godMode.getInterceptedCount());
        } finally {
            godMode.setEnabled(false);
        }
    }

    @Test
    void bundledRespawnAndPositionPacketLatchesInExactOrder() {
        GodMode godMode = new GodMode();
        godMode.setNotify(false);
        godMode.setResyncOnDisable(false);
        godMode.setEnabled(true);
        try {
            List<Packet<? super ClientGamePacketListener>> subPackets = List.of(
                    new ClientboundRespawnPacket(null, ClientboundRespawnPacket.KEEP_ALL_DATA),
                    positionPacket(99, -24.5, 70.0, 88.25, Collections.emptySet())
            );
            EventBus.call(new EventReceivePacket(new ClientboundBundlePacket(subPackets)));

            assertTrue(godMode.isLatched());
            assertTrue(godMode.isDimensionInvulnerable());
            assertEquals(99, godMode.getLastTeleportId());
            assertVecEquals(new Vec3(-24.5, 70.0, 88.25), godMode.getAnchorPos());

            EventSendPacket confirm = EventBus.call(new EventSendPacket(new ServerboundAcceptTeleportationPacket(99)));
            assertTrue(confirm.isCancelled());
        } finally {
            godMode.setEnabled(false);
        }
    }

    @Test
    void respawnOnlyIgnoresLoginPacketWhereasWorldChangeArmsOnLogin() {
        ClientboundLoginPacket loginPacket = new ClientboundLoginPacket(
                1, false, Set.of(), 20, 8, 8, false, true, false, null, true, false);

        GodMode respawnOnly = new GodMode();
        respawnOnly.setTriggerMode(TriggerMode.RESPAWN_ONLY);
        respawnOnly.setNotify(false);
        respawnOnly.processInboundPacket(loginPacket, ProtocolVersion.v1_20_5);
        assertFalse(respawnOnly.isArmed());
        respawnOnly.processInboundPacket(positionPacket(7, 0.0, 64.0, 0.0, Collections.emptySet()), ProtocolVersion.v1_20_5);
        assertFalse(respawnOnly.isLatched());

        GodMode worldChange = new GodMode();
        worldChange.setTriggerMode(TriggerMode.WORLD_CHANGE);
        worldChange.setNotify(false);
        worldChange.processInboundPacket(loginPacket, ProtocolVersion.v1_20_5);
        assertTrue(worldChange.isArmed());
        assertFalse(worldChange.isDimensionInvulnerable());
        worldChange.processInboundPacket(positionPacket(7, 0.0, 64.0, 0.0, Collections.emptySet()), ProtocolVersion.v1_20_5);
        assertTrue(worldChange.isLatched());
        assertEquals(7, worldChange.getLastTeleportId());
    }

    @Test
    void allTeleportsModeLatchesImmediatelyAndResolvesRelativeCoordinates() {
        GodMode godMode = new GodMode();
        godMode.setTriggerMode(TriggerMode.ALL_TELEPORTS);
        godMode.setNotify(false);

        // Record current reported position (10, 64, -5).
        godMode.processOutboundPacket(
                new EventSendPacket(new ServerboundMovePlayerPacket.Pos(10.0, 64.0, -5.0, true, false)),
                ProtocolVersion.v1_20_5);

        Set<Relative> relativeXZ = EnumSet.of(Relative.X, Relative.Z);
        godMode.processInboundPacket(positionPacket(15, 2.5, 72.0, -1.5, relativeXZ), ProtocolVersion.v1_20_5);
        assertTrue(godMode.isLatched());
        assertVecEquals(new Vec3(12.5, 72.0, -6.5), godMode.getAnchorPos());

        EventSendPacket confirm = new EventSendPacket(new ServerboundAcceptTeleportationPacket(15));
        godMode.processOutboundPacket(confirm, ProtocolVersion.v1_20_5);
        assertTrue(confirm.isCancelled());
    }

    @Test
    void releaseLatchDispatchesLatestConfirmWithoutSelfCancellingAndDisconnectClearsState() {
        GodMode godMode = new GodMode();
        godMode.setNotify(false);
        godMode.setEnabled(true);
        try {
            EventBus.call(new EventReceivePacket(new ClientboundRespawnPacket(null, ClientboundRespawnPacket.KEEP_ALL_DATA)));
            EventBus.call(new EventReceivePacket(positionPacket(10, 0.0, 64.0, 0.0, Collections.emptySet())));
            EventBus.call(new EventReceivePacket(positionPacket(11, 0.0, 64.0, 0.0, Collections.emptySet())));

            List<Integer> confirmedIds = new ArrayList<>();
            godMode.releaseLatch(teleportId -> {
                EventSendPacket releaseEvent = EventBus.call(
                        new EventSendPacket(new ServerboundAcceptTeleportationPacket(teleportId)));
                assertFalse(releaseEvent.isCancelled(), "Re-entrancy guard must let the release TeleportConfirm pass");
                confirmedIds.add(teleportId);
            });

            assertEquals(List.of(11), confirmedIds);
            assertFalse(godMode.isLatched());
            assertEquals(GodMode.NO_TELEPORT_ID, godMode.getLastTeleportId());
            assertNull(godMode.getAnchorPos());

            // Disconnect (EventLoadWorld(null)) or StartConfiguration must drop any pending latch so later disable is a no-op.
            EventBus.call(new EventReceivePacket(new ClientboundRespawnPacket(null, ClientboundRespawnPacket.KEEP_ALL_DATA)));
            EventBus.call(new EventReceivePacket(positionPacket(20, 5.0, 64.0, 5.0, Collections.emptySet())));
            assertTrue(godMode.isLatched());

            EventBus.call(new EventLoadWorld(null));
            assertFalse(godMode.isLatched());

            EventBus.call(new EventReceivePacket(new ClientboundRespawnPacket(null, ClientboundRespawnPacket.KEEP_ALL_DATA)));
            assertTrue(godMode.isArmed());
            EventBus.call(new EventReceivePacket(ClientboundStartConfigurationPacket.INSTANCE));
            assertFalse(godMode.isArmed());
        } finally {
            godMode.setResyncOnDisable(false);
            godMode.setEnabled(false);
        }
    }

    @Test
    void protocolThresholdsAndIdleReminderSuppressionStayWithinGrimBounds() {
        assertFalse(GodMode.supportsTeleportConfirm(ProtocolVersion.v1_8));
        assertTrue(GodMode.supportsTeleportConfirm(ProtocolVersion.v1_9));
        assertTrue(GodMode.supportsTeleportConfirm(ProtocolVersion.v1_20_5));
        assertTrue(GodMode.supportsTeleportConfirm(ProtocolVersion.v1_21_4));

        assertEquals(6.0, GodMode.serverAttackRange(ProtocolVersion.v1_20_3), EPS);
        assertEquals(4.0, GodMode.serverAttackRange(ProtocolVersion.v1_20_5), EPS);
        assertEquals(6.0, GodMode.serverAttackRange(ProtocolVersion.v1_21), EPS);

        assertFalse(GodMode.canSuppressIdleReminder(ProtocolVersion.v1_8));
        assertTrue(GodMode.canSuppressIdleReminder(ProtocolVersion.v1_20_5));
        assertTrue(GodMode.canSuppressIdleReminder(ProtocolVersion.v1_21));
        assertFalse(GodMode.canSuppressIdleReminder(ProtocolVersion.v1_21_2));

        GodMode godMode = new GodMode();
        godMode.setTriggerMode(TriggerMode.ALL_TELEPORTS);
        godMode.setSuppressIdleReminder(true);
        godMode.setNotify(false);

        godMode.processInboundPacket(positionPacket(1, 0.0, 64.0, 0.0, Collections.emptySet()), ProtocolVersion.v1_20_5);
        godMode.processOutboundPacket(
                new EventSendPacket(new ServerboundMovePlayerPacket.PosRot(0.0, 64.0, 0.0, 0.0F, 0.0F, false, false)),
                ProtocolVersion.v1_20_5);

        // Player walks to B = (2.5, 64, 0) and stands still.
        EventSendPacket walkToB = new EventSendPacket(new ServerboundMovePlayerPacket.Pos(2.5, 64.0, 0.0, true, false));
        godMode.processOutboundPacket(walkToB, ProtocolVersion.v1_20_5);
        assertFalse(walkToB.isCancelled());

        // First stationary 20-tick Pos reminder at B is suppressed; second consecutive reminder is allowed through.
        EventSendPacket idleReminder1 = new EventSendPacket(new ServerboundMovePlayerPacket.Pos(2.5, 64.0, 0.0, true, false));
        godMode.processOutboundPacket(idleReminder1, ProtocolVersion.v1_20_5);
        assertTrue(idleReminder1.isCancelled());

        EventSendPacket idleReminder2 = new EventSendPacket(new ServerboundMovePlayerPacket.Pos(2.5, 64.0, 0.0, true, false));
        godMode.processOutboundPacket(idleReminder2, ProtocolVersion.v1_20_5);
        assertFalse(idleReminder2.isCancelled());
    }

    private static ClientboundPlayerPositionPacket positionPacket(
            final int id,
            final double x,
            final double y,
            final double z,
            final Set<Relative> relatives
    ) {
        return ClientboundPlayerPositionPacket.of(
                id,
                new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, 0.0F, 0.0F),
                relatives
        );
    }

    private static void assertVecEquals(final Vec3 expected, final Vec3 actual) {
        assertNotNull(actual);
        assertEquals(expected.x, actual.x, EPS);
        assertEquals(expected.y, actual.y, EPS);
        assertEquals(expected.z, actual.z, EPS);
    }
}
