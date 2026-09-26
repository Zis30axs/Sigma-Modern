package com.mentalfrostbyte.jello.anticheat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.anticheat.alert.Suspects;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SuspectsTest {

    @Test
    void aPlayerWhoWasNeverFlaggedIsNotListed() {
        Suspects suspects = new Suspects();
        UUID id = UUID.randomUUID();
        suspects.update(id, "Steve", "Speed", 0.0);
        assertTrue(suspects.ranked().isEmpty());
        assertEquals(0.0, suspects.totalLevel(id), 1.0E-9);
    }

    @Test
    void suspectsAreRankedByTotalLevelAcrossChecks() {
        Suspects suspects = new Suspects();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        suspects.update(a, "A", "Speed", 2.0);
        suspects.update(b, "B", "Speed", 1.0);
        suspects.update(b, "B", "Flight", 2.5);

        List<Suspects.Entry> ranked = suspects.ranked();
        assertEquals(List.of("B", "A"), ranked.stream().map(Suspects.Entry::name).toList());
        assertEquals(3.5, ranked.get(0).total(), 1.0E-9);
        assertEquals(2, ranked.get(0).levels().size());
    }

    @Test
    void aLevelThatDrainedAwayDropsOutOfTheList() {
        Suspects suspects = new Suspects();
        UUID id = UUID.randomUUID();
        suspects.update(id, "Steve", "Speed", 1.0);
        assertEquals(1, suspects.ranked().size());
        suspects.update(id, "Steve", "Speed", 0.0);
        assertTrue(suspects.ranked().isEmpty());
        assertTrue(suspects.ranked().stream().noneMatch(e -> e.uuid().equals(id)));
    }

    @Test
    void anAlertRemembersWhatForAndWhen() {
        Suspects suspects = new Suspects();
        UUID id = UUID.randomUUID();
        suspects.update(id, "Steve", "Speed", 3.0);
        suspects.alerted(id, "Speed", "9.0 blocks/s", 42L);
        Suspects.Entry entry = suspects.ranked().get(0);
        assertEquals("Speed", entry.lastCheck());
        assertEquals("9.0 blocks/s", entry.lastDetail());
        assertEquals(42L, entry.lastAlertNanos());
    }

    @Test
    void removeAndClearForgetSuspects() {
        Suspects suspects = new Suspects();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        suspects.update(a, "A", "Speed", 1.0);
        suspects.update(b, "B", "Speed", 1.0);
        suspects.remove(a);
        assertEquals(1, suspects.ranked().size());
        suspects.clear();
        assertTrue(suspects.ranked().isEmpty());
    }
}
