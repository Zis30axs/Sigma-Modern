package com.mentalfrostbyte.jello.module.impl.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.module.impl.movement.InvMove.GrimProtocolProfile;
import com.mentalfrostbyte.jello.module.impl.movement.InvMove.QueuedAction;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.Vec2;
import org.junit.jupiter.api.Test;

class InvMoveTest {

    private static final float EPS = 1.0E-5F;

    @Test
    void registersInMovementCategoryWithExpectedSettingsAndVisibility() {
        InvMove invMove = new InvMove();
        ModuleManager manager = new ModuleManager();
        manager.register(invMove);

        assertSame(invMove, manager.get(InvMove.class));
        assertSame(ModuleCategory.MOVEMENT, invMove.getCategory());
        assertEquals("Grim", EnumSetting.label(InvMove.Behavior.GRIM));
        assertSame(InvMove.Behavior.NORMAL, invMove.getBehavior());

        assertFalse(invMove.getGrimBlockWhileUsingSetting().isVisible());
        assertFalse(invMove.getStopTicksSetting().isVisible());
        assertFalse(invMove.getMaxBlinkMsSetting().isVisible());
        assertFalse(invMove.getClickDelayMsSetting().isVisible());
        assertTrue(invMove.getArrowSpeedSetting().isVisible());

        invMove.setBehavior(InvMove.Behavior.GRIM);
        assertTrue(invMove.getGrimBlockWhileUsingSetting().isVisible());
        assertFalse(invMove.getStopTicksSetting().isVisible());
        assertFalse(invMove.getMaxBlinkMsSetting().isVisible());
        assertFalse(invMove.getClickDelayMsSetting().isVisible());

        invMove.setBehavior(InvMove.Behavior.BLINK);
        assertFalse(invMove.getGrimBlockWhileUsingSetting().isVisible());
        assertTrue(invMove.getMaxBlinkMsSetting().isVisible());

        invMove.setBehavior(InvMove.Behavior.INTAVE);
        assertTrue(invMove.getStopTicksSetting().isVisible());
        assertTrue(invMove.getClickDelayMsSetting().isVisible());
    }

    @Test
    void protocolProfilesMatchExactGrimVersionThresholds() {
        GrimProtocolProfile p1_8 = GrimProtocolProfile.forVersion(ProtocolVersion.v1_8);
        assertTrue(p1_8.requiresPreFlyingWindow(), "1.8 CLICK_WINDOW is checked by Grim Post");
        assertFalse(p1_8.hasEndTickInput());
        assertTrue(p1_8.enforcesClickOrder(), "1.8 enforces PacketOrderA");
        assertFalse(p1_8.normalizeDiagonals(), "1.8 diagonals must remain unnormalized");
        assertTrue(p1_8.suppressOpenInventoryAchievement(), "1.8 suppresses OPEN_INVENTORY_ACHIEVEMENT");

        GrimProtocolProfile p1_12_2 = GrimProtocolProfile.forVersion(ProtocolVersion.v1_12_2);
        assertTrue(p1_12_2.requiresPreFlyingWindow(), "1.12.2 CLICK_WINDOW is checked by Grim Post");
        assertFalse(p1_12_2.hasEndTickInput());
        assertTrue(p1_12_2.enforcesClickOrder(), "1.12.2 enforces PacketOrderA");
        assertFalse(p1_12_2.normalizeDiagonals());
        assertFalse(p1_12_2.suppressOpenInventoryAchievement(), "1.12+ has no OPEN_INVENTORY_ACHIEVEMENT");

        GrimProtocolProfile p1_20_1 = GrimProtocolProfile.forVersion(ProtocolVersion.v1_20);
        assertFalse(p1_20_1.requiresPreFlyingWindow());
        assertFalse(p1_20_1.hasEndTickInput(), "1.20.1 has no on-foot PLAYER_INPUT packet");
        assertFalse(p1_20_1.enforcesClickOrder());
        assertFalse(p1_20_1.normalizeDiagonals(), "<= 1.21.4 diagonals must remain unnormalized");
        assertFalse(p1_20_1.suppressOpenInventoryAchievement());

        GrimProtocolProfile p1_21_4 = GrimProtocolProfile.forVersion(ProtocolVersion.v1_21_4);
        assertFalse(p1_21_4.requiresPreFlyingWindow());
        assertTrue(p1_21_4.hasEndTickInput(), "1.21.2+ enables Grim knownInput.moving() & PacketOrderO");
        assertFalse(p1_21_4.enforcesClickOrder());
        assertFalse(p1_21_4.normalizeDiagonals(), "1.21.4 is the last version with unnormalized diagonals");

        GrimProtocolProfile p26_2 = GrimProtocolProfile.forVersion(ProtocolVersion.v1_21_5);
        assertFalse(p26_2.requiresPreFlyingWindow());
        assertTrue(p26_2.hasEndTickInput());
        assertFalse(p26_2.enforcesClickOrder());
        assertTrue(p26_2.normalizeDiagonals(), "1.21.5+ / 26.2 normalizes diagonal moveVector");
    }

    @Test
    void grimImmediateDispatchRespectsMultiActionsCAndDPostAndPacketOrderO() {
        InvMove invMove = new InvMove();
        invMove.setBehavior(InvMove.Behavior.GRIM);

        GrimProtocolProfile p1_12_2 = GrimProtocolProfile.forVersion(ProtocolVersion.v1_12_2);
        GrimProtocolProfile p1_20_1 = GrimProtocolProfile.forVersion(ProtocolVersion.v1_20);
        GrimProtocolProfile p26_2 = GrimProtocolProfile.MODERN_DEFAULT;

        // < 1.13 never sends asynchronously outside EventMotion(PRE), even when stationary, to avoid Grim Post.
        assertFalse(invMove.canSendGrimImmediately(p1_12_2, false, false, false, false));

        // 1.20.1 (<= 1.21.1): stationary or walking without sprint/sneak can dispatch immediately; sprinting/sneaking queues.
        assertTrue(invMove.canSendGrimImmediately(p1_20_1, false, false, true, false));
        assertFalse(invMove.canSendGrimImmediately(p1_20_1, true, false, false, false));
        assertFalse(invMove.canSendGrimImmediately(p1_20_1, false, true, false, false));

        // 26.2 (>= 1.21.2): requires !sprinting, !sneaking, !knownInput.moving(), and !betweenFlyingAndTickEnd.
        assertTrue(invMove.canSendGrimImmediately(p26_2, false, false, false, false));
        assertFalse(invMove.canSendGrimImmediately(p26_2, false, false, true, false), "MultiActionsC/D input=true");
        assertFalse(invMove.canSendGrimImmediately(p26_2, true, false, false, false), "MultiActionsC/D sprinting=true");
        assertFalse(invMove.canSendGrimImmediately(p26_2, false, true, false, false), "MultiActionsC/D sneaking=true");
        assertFalse(invMove.canSendGrimImmediately(p26_2, false, false, false, true), "PacketOrderO flying..tickEnd");

        // Once an action is queued, new actions must never jump ahead of the queue.
        invMove.enqueueAction(QueuedAction.click(0, ContainerInput.PICKUP, () -> {}));
        assertFalse(invMove.canSendGrimImmediately(p26_2, false, false, false, false));
        assertFalse(invMove.canSendGrimImmediately(p1_20_1, false, false, false, false));
    }

    @Test
    void grimFlushTickInputMicroReleasesPlayerInputOnModernAndKeepsUnnormalizedWalkOnLegacy() {
        InvMove invMove = new InvMove();
        invMove.setBehavior(InvMove.Behavior.GRIM);

        Input diagonalSprintSneak = new Input(true, false, true, false, true, true, true);

        // 1.21.2+ / 26.2: zeroes PLAYER_INPUT for 1 tick so Grim knownInput.moving() & sneaking & sprinting become false.
        EventMovementInput modernEvent = new EventMovementInput(diagonalSprintSneak);
        invMove.applyGrimFlushTickInput(modernEvent, GrimProtocolProfile.MODERN_DEFAULT, diagonalSprintSneak);
        assertTrue(invMove.isGrimQuietTickPrepared());
        assertEquals(Input.EMPTY, modernEvent.getInput());
        assertEquals(Vec2.ZERO, modernEvent.getMoveVector());

        // <= 1.21.1 (e.g. 1.12.2 / 1.20.1): keeps walking + jumping continuously without pause, strips sneak & sprint,
        // and preserves unnormalized (1.0, 1.0) diagonal vector to match Grim's <= 1.21.4 PredictionEngine.
        invMove.resetTransientFlags();
        GrimProtocolProfile p1_12_2 = GrimProtocolProfile.forVersion(ProtocolVersion.v1_12_2);
        EventMovementInput legacyEvent = new EventMovementInput(Input.EMPTY, Vec2.ZERO);
        invMove.applyGrimFlushTickInput(legacyEvent, p1_12_2, diagonalSprintSneak);
        assertTrue(invMove.isGrimQuietTickPrepared());
        assertTrue(legacyEvent.getInput().forward());
        assertTrue(legacyEvent.getInput().left());
        assertTrue(legacyEvent.getInput().jump());
        assertFalse(legacyEvent.getInput().shift(), "Sneak must be cleared for MultiActionsC/D");
        assertFalse(legacyEvent.getInput().sprint(), "Sprint must be cleared for MultiActionsC/D");
        assertEquals(1.0F, legacyEvent.getMoveVector().x, EPS);
        assertEquals(1.0F, legacyEvent.getMoveVector().y, EPS);
    }

    @Test
    void grimPacketOrderABatchesConflictingPickupAndQuickMoveAcrossTicksOn1_12_2WhileKeepingCloseLast() {
        InvMove legacyInvMove = new InvMove();
        legacyInvMove.setBehavior(InvMove.Behavior.GRIM);
        GrimProtocolProfile p1_12_2 = GrimProtocolProfile.forVersion(ProtocolVersion.v1_12_2);

        List<String> wire = new ArrayList<>();
        legacyInvMove.enqueueAction(QueuedAction.click(1, ContainerInput.QUICK_MOVE, () -> wire.add("QUICK_10")));
        legacyInvMove.enqueueAction(QueuedAction.click(1, ContainerInput.QUICK_MOVE, () -> wire.add("QUICK_11")));
        legacyInvMove.enqueueAction(QueuedAction.click(1, ContainerInput.PICKUP, () -> wire.add("PICKUP_12")));
        legacyInvMove.enqueueAction(QueuedAction.click(1, ContainerInput.PICKUP_ALL, () -> wire.add("PICKUP_ALL_12")));
        legacyInvMove.enqueueAction(QueuedAction.close(1, () -> wire.add("CLOSE_1")));

        // Tick 1 on 1.12.2: sends both QUICK_MOVE clicks, stops before PICKUP to satisfy Grim PacketOrderA,
        // and keeps CLOSE_1 behind the remaining clicks so the container is not closed prematurely.
        int firstBatch = legacyInvMove.flushGrimTickBatch(p1_12_2);
        assertEquals(2, firstBatch);
        assertEquals(List.of("QUICK_10", "QUICK_11"), wire);
        assertEquals(3, legacyInvMove.queuedActionCount());

        // Tick 2 on 1.12.2: sends PICKUP + PICKUP_ALL + trailing CLOSE_1 in order.
        int secondBatch = legacyInvMove.flushGrimTickBatch(p1_12_2);
        assertEquals(3, secondBatch);
        assertEquals(List.of("QUICK_10", "QUICK_11", "PICKUP_12", "PICKUP_ALL_12", "CLOSE_1"), wire);
        assertEquals(0, legacyInvMove.queuedActionCount());

        // Reverse conflict order (PICKUP followed by QUICK_MOVE) also splits cleanly across ticks on 1.12.2.
        wire.clear();
        legacyInvMove.enqueueAction(QueuedAction.click(0, ContainerInput.PICKUP, () -> wire.add("PICKUP_9")));
        legacyInvMove.enqueueAction(QueuedAction.click(0, ContainerInput.QUICK_MOVE, () -> wire.add("QUICK_9")));
        assertEquals(1, legacyInvMove.flushGrimTickBatch(p1_12_2));
        assertEquals(List.of("PICKUP_9"), wire);
        assertEquals(1, legacyInvMove.flushGrimTickBatch(p1_12_2));
        assertEquals(List.of("PICKUP_9", "QUICK_9"), wire);

        // On >= 1.13 (1.20.1 / 26.2), PacketOrderA does not apply, so the entire sequence flushes in 1 single tick.
        InvMove modernInvMove = new InvMove();
        modernInvMove.setBehavior(InvMove.Behavior.GRIM);
        wire.clear();
        modernInvMove.enqueueAction(QueuedAction.click(2, ContainerInput.QUICK_MOVE, () -> wire.add("QUICK_10")));
        modernInvMove.enqueueAction(QueuedAction.click(2, ContainerInput.PICKUP, () -> wire.add("PICKUP_12")));
        modernInvMove.enqueueAction(QueuedAction.close(2, () -> wire.add("CLOSE_2")));

        assertEquals(3, modernInvMove.flushGrimTickBatch(GrimProtocolProfile.MODERN_DEFAULT));
        assertEquals(List.of("QUICK_10", "PICKUP_12", "CLOSE_2"), wire);
        assertEquals(0, modernInvMove.queuedActionCount());
    }

    @Test
    void eventMovementInputSetInputOverloadRespectsDiagonalNormalizationFlag() {
        Input diagonal = new Input(true, false, true, false, false, false, false);

        EventMovementInput unnormalized = new EventMovementInput(Input.EMPTY, Vec2.ZERO);
        unnormalized.setInput(diagonal, false);
        assertEquals(1.0F, unnormalized.getMoveVector().x, EPS);
        assertEquals(1.0F, unnormalized.getMoveVector().y, EPS);

        EventMovementInput normalized = new EventMovementInput(Input.EMPTY, Vec2.ZERO);
        normalized.setInput(diagonal, true);
        assertEquals(1.0F, normalized.getMoveVector().length(), EPS);
    }
}
