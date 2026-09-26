package com.mentalfrostbyte.jello.selfcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.selfcheck.host.TransactionPool;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TransactionPoolTest {

    @Test
    void idsFitAWindowZeroActionNumber() {
        // <= 1.16.4 carries the id as a 16-bit action number with the window id in bits 16-23; only window 0 is answered
        assertTrue(TransactionPool.FIRST > 0 && TransactionPool.LAST <= Short.MAX_VALUE);
        TransactionPool<String> pool = new TransactionPool<>();
        int id = pool.allocate("a");
        assertEquals(0, (id >> 16) & 0xFF);
        assertEquals(id, (short) id);
    }

    @Test
    void everyOutstandingIdIsDistinctUntilThePoolRunsDry() {
        TransactionPool<String> pool = new TransactionPool<>();
        int size = TransactionPool.LAST - TransactionPool.FIRST + 1;
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < size; i++) {
            assertTrue(seen.add(pool.allocate("a")));
        }
        assertEquals(-1, pool.allocate("a"), "every id is waiting for an answer");

        int freed = TransactionPool.FIRST + 5;
        assertEquals("a", pool.complete(freed));
        assertEquals(freed, pool.allocate("b"), "an answered id can be used again");
    }

    @Test
    void anIdIsAnsweredOnceAndOnlyIfItWasHandedOut() {
        TransactionPool<String> pool = new TransactionPool<>();
        int id = pool.allocate("owner");

        assertTrue(pool.isOutstanding(id));
        assertEquals("owner", pool.complete(id));
        assertFalse(pool.isOutstanding(id));
        assertNull(pool.complete(id));
        assertNull(pool.complete(-1));
        assertNull(pool.complete(TransactionPool.LAST + 1));
    }

    @Test
    void idsAreHandedOutInTurnSoARecentOneIsNotReusedAtOnce() {
        TransactionPool<String> pool = new TransactionPool<>();
        int first = pool.allocate("a");
        pool.complete(first);
        int second = pool.allocate("a");
        assertEquals(first + 1, second);
    }
}
