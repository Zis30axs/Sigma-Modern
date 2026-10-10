package com.mentalfrostbyte.jello.module.impl.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import org.junit.jupiter.api.Test;

class AntiBotTest {
    private static final int ALL_CHECKS = AntiBot.MISSING_PROFILE | AntiBot.INVALID_NAME | AntiBot.DUPLICATE_NAME;

    @Test
    void conservativeDefaultsOnlyEnableTheTabProfileCheck() {
        AntiBot module = new AntiBot();
        assertFalse(module.isEnabled());
        assertTrue(((BooleanSetting) module.setting("Tab Profile").orElseThrow()).get());
        assertFalse(((BooleanSetting) module.setting("Invalid Name").orElseThrow()).get());
        assertFalse(((BooleanSetting) module.setting("Duplicate Name").orElseThrow()).get());
        assertEquals(40, ((NumberSetting) module.setting("Grace Ticks").orElseThrow()).getInt());
        assertEquals(20, ((NumberSetting) module.setting("Confirm Ticks").orElseThrow()).getInt());
    }

    @Test
    void newPlayersReceiveTheFullGracePeriodEvenWithContinuousEvidence() {
        AntiBot.Evidence evidence = new AntiBot.Evidence();
        Object player = new Object();
        sampleFrames(evidence, player, AntiBot.MISSING_PROFILE, 39);
        assertFalse(evidence.isBot(player, ALL_CHECKS, 40, 20));
        sampleFrames(evidence, player, AntiBot.MISSING_PROFILE, 1);
        assertTrue(evidence.isBot(player, ALL_CHECKS, 40, 20));
    }

    @Test
    void oldPlayersStillNeedContinuousConfirmationOfANewSignal() {
        AntiBot.Evidence evidence = new AntiBot.Evidence();
        Object player = new Object();
        sampleFrames(evidence, player, 0, 40);
        sampleFrames(evidence, player, AntiBot.MISSING_PROFILE, 19);
        assertFalse(evidence.isBot(player, ALL_CHECKS, 40, 20));
        sampleFrames(evidence, player, AntiBot.MISSING_PROFILE, 1);
        assertTrue(evidence.isBot(player, ALL_CHECKS, 40, 20));
    }

    @Test
    void aRecoveredProfileImmediatelyClearsTheVerdictAndRestartsConfirmation() {
        AntiBot.Evidence evidence = new AntiBot.Evidence();
        Object player = new Object();
        sampleFrames(evidence, player, AntiBot.MISSING_PROFILE, 40);
        assertTrue(evidence.isBot(player, ALL_CHECKS, 40, 20));
        sampleFrames(evidence, player, 0, 1);
        assertFalse(evidence.isBot(player, ALL_CHECKS, 40, 20));
        sampleFrames(evidence, player, AntiBot.MISSING_PROFILE, 19);
        assertFalse(evidence.isBot(player, ALL_CHECKS, 40, 20));
        sampleFrames(evidence, player, AntiBot.MISSING_PROFILE, 1);
        assertTrue(evidence.isBot(player, ALL_CHECKS, 40, 20));
    }

    @Test
    void intermittentEvidenceDoesNotAccumulateIntoAConfirmedBot() {
        AntiBot.Evidence evidence = new AntiBot.Evidence();
        Object player = new Object();
        for (int cycle = 0; cycle < 20; cycle++) {
            sampleFrames(evidence, player, AntiBot.MISSING_PROFILE, 19);
            assertFalse(evidence.isBot(player, ALL_CHECKS, 40, 20));
            sampleFrames(evidence, player, 0, 1);
        }
        assertFalse(evidence.isBot(player, ALL_CHECKS, 40, 20));
    }

    @Test
    void alternatingDifferentSignalsCannotBorrowEachOthersConfirmationTicks() {
        AntiBot.Evidence evidence = new AntiBot.Evidence();
        Object player = new Object();
        for (int tick = 0; tick < 80; tick++) {
            int flag = tick % 2 == 0 ? AntiBot.MISSING_PROFILE : AntiBot.INVALID_NAME;
            sampleFrames(evidence, player, flag, 1);
            assertFalse(evidence.isBot(player, ALL_CHECKS, 40, 20));
        }
        sampleFrames(evidence, player, AntiBot.DUPLICATE_NAME, 20);
        assertTrue(evidence.isBot(player, AntiBot.DUPLICATE_NAME, 40, 20));
        assertFalse(evidence.isBot(player, AntiBot.MISSING_PROFILE | AntiBot.INVALID_NAME, 40, 20));
    }

    @Test
    void oneSignalCanRecoverWhileAnotherRemainsConfirmed() {
        AntiBot.Evidence evidence = new AntiBot.Evidence();
        Object player = new Object();
        sampleFrames(evidence, player, AntiBot.MISSING_PROFILE | AntiBot.INVALID_NAME, 40);
        sampleFrames(evidence, player, AntiBot.INVALID_NAME, 1);
        assertFalse(evidence.isBot(player, AntiBot.MISSING_PROFILE, 40, 20));
        assertTrue(evidence.isBot(player, AntiBot.INVALID_NAME, 40, 20));
    }

    @Test
    void disabledChecksAndUnobservedPlayersNeverProduceABotVerdict() {
        AntiBot.Evidence evidence = new AntiBot.Evidence();
        Object player = new Object();
        sampleFrames(evidence, player, AntiBot.INVALID_NAME | AntiBot.DUPLICATE_NAME, 40);
        assertFalse(evidence.isBot(player, AntiBot.MISSING_PROFILE, 40, 20));
        assertFalse(evidence.isBot(player, 0, 40, 20));
        assertFalse(evidence.isBot(new Object(), ALL_CHECKS, 40, 20));
        assertTrue(evidence.isBot(player, AntiBot.INVALID_NAME, 40, 20));
        assertTrue(evidence.isBot(player, AntiBot.DUPLICATE_NAME, 40, 20));
    }

    @Test
    void replacementEntitiesWithEqualValuesDoNotInheritOldEvidence() {
        AntiBot.Evidence evidence = new AntiBot.Evidence();
        Object oldPlayer = new String("same-uuid-or-name");
        Object replacement = new String("same-uuid-or-name");
        assertEquals(oldPlayer, replacement);
        sampleFrames(evidence, oldPlayer, AntiBot.MISSING_PROFILE, 40);
        assertTrue(evidence.isBot(oldPlayer, ALL_CHECKS, 40, 20));
        assertFalse(evidence.isBot(replacement, ALL_CHECKS, 40, 20));
        evidence.beginFrame();
        evidence.sample(oldPlayer, AntiBot.MISSING_PROFILE);
        evidence.sample(replacement, AntiBot.MISSING_PROFILE);
        evidence.endFrame();
        assertEquals(2, evidence.size());
        assertTrue(evidence.isBot(oldPlayer, ALL_CHECKS, 40, 20));
        assertFalse(evidence.isBot(replacement, ALL_CHECKS, 40, 20));
    }

    @Test
    void leavingTheObservedWorldPrunesEvidenceAndReturningStartsFresh() {
        AntiBot.Evidence evidence = new AntiBot.Evidence();
        Object player = new Object();
        sampleFrames(evidence, player, AntiBot.MISSING_PROFILE, 40);
        evidence.beginFrame();
        evidence.endFrame();
        assertEquals(0, evidence.size());
        assertFalse(evidence.isBot(player, ALL_CHECKS, 40, 20));
        sampleFrames(evidence, player, AntiBot.MISSING_PROFILE, 1);
        assertFalse(evidence.isBot(player, ALL_CHECKS, 40, 20));
    }

    @Test
    void clearRemovesAllSessionEvidence() {
        AntiBot.Evidence evidence = new AntiBot.Evidence();
        Object player = new Object();
        sampleFrames(evidence, player, ALL_CHECKS, 40);
        evidence.clear();
        assertEquals(0, evidence.size());
        assertFalse(evidence.isBot(player, ALL_CHECKS, 40, 20));
        sampleFrames(evidence, player, ALL_CHECKS, 1);
        assertFalse(evidence.isBot(player, ALL_CHECKS, 40, 20));
    }

    @Test
    void longLivedEvidenceRetainsItsVerdictWithoutCounterWraparound() {
        AntiBot.Evidence evidence = new AntiBot.Evidence();
        Object player = new Object();
        sampleFrames(evidence, player, ALL_CHECKS, 10_000);
        assertTrue(evidence.isBot(player, ALL_CHECKS, 200, 200));
        sampleFrames(evidence, player, 0, 1);
        assertFalse(evidence.isBot(player, ALL_CHECKS, 200, 200));
    }

    @Test
    void oversizedPlayerListsCannotGrowTheEvidenceStoreWithoutBound() {
        AntiBot.Evidence evidence = new AntiBot.Evidence();
        evidence.beginFrame();
        for (int i = 0; i < 4096; i++) {
            evidence.sample(new Object(), ALL_CHECKS);
        }
        evidence.endFrame();
        assertEquals(2048, evidence.size());
        evidence.beginFrame();
        evidence.endFrame();
        assertEquals(0, evidence.size());
        sampleFrames(evidence, new Object(), 0, 1);
        assertEquals(1, evidence.size(), "capacity is available again after old entities leave");
    }

    @Test
    void validNamesIncludeLegacyShortAccountsAndTheFullSixteenCharacterLimit() {
        for (String name : new String[] {"a", "AB", "_", "0", "Player_01", "Abcdefghijklm_09"}) {
            assertTrue(AntiBot.validProfileName(name), name);
        }
        assertTrue(AntiBot.validProfileName("a".repeat(16)));
    }

    @Test
    void invalidNamesRejectWhitespaceControlsUnicodeAndExcessLength() {
        assertFalse(AntiBot.validProfileName(null));
        for (String name : new String[] {"", " ", "Player Name", " Player", "Player ", "Player\n",
                "Player\t", "Player\u0000", "Player-01", "Player.01", "\u00a7aPlayer", "\u73a9\u5bb6", "\uFF21BC", "a".repeat(17)}) {
            assertFalse(AntiBot.validProfileName(name), name);
        }
    }

    private static void sampleFrames(final AntiBot.Evidence evidence, final Object player, final int flags, final int count) {
        for (int tick = 0; tick < count; tick++) {
            evidence.beginFrame();
            evidence.sample(player, flags);
            evidence.endFrame();
        }
    }
}
