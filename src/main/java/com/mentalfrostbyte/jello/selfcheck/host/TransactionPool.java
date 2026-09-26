package com.mentalfrostbyte.jello.selfcheck.host;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The ping ids the self-check sends on its own behalf, and who is waiting for each answer.
 *
 * <p>The ids live in {@code 0x6000-0x7FFF}. Up to 1.16.4 there is no ping packet: ViaVersion carries the id as a
 * window-confirmation action number, a 16-bit value with the window id packed into bits 16-23, and the client only
 * answers window 0 (see {@code ClientCommonPacketListenerImpl.handlePing}), so the ids have to fit in 15 bits.
 * Whose answer is whose is not decided by the id - the ping and pong carry their own type
 * ({@link SelfCheckPing}, {@link SelfCheckPong}) - so a server that happens to use the same number is harmless.</p>
 *
 * <p>An id stays outstanding until it is answered, even if its owner has been switched off meanwhile.</p>
 */
public final class TransactionPool<T> {

    public static final int FIRST = 0x6000;
    public static final int LAST = 0x7FFF;
    private static final int SIZE = LAST - FIRST + 1;

    private final BitSet outstanding = new BitSet(SIZE);
    private final Map<Integer, T> owners = new HashMap<>();
    private int next;

    /** Takes a free id for {@code owner}, or returns -1 when every id is still waiting for its answer. */
    public synchronized int allocate(final T owner) {
        for (int tried = 0; tried < SIZE; tried++) {
            int index = this.next;
            this.next = (this.next + 1) % SIZE;
            if (!this.outstanding.get(index)) {
                this.outstanding.set(index);
                int id = FIRST + index;
                this.owners.put(id, owner);
                return id;
            }
        }
        return -1;
    }

    /** Whether {@code id} was handed out and not yet answered. */
    public synchronized boolean isOutstanding(final int id) {
        return id >= FIRST && id <= LAST && this.outstanding.get(id - FIRST);
    }

    /** Marks {@code id} answered (or given up on) and returns who was waiting, or null if nobody was. */
    public synchronized @Nullable T complete(final int id) {
        if (!this.isOutstanding(id)) {
            return null;
        }
        this.outstanding.clear(id - FIRST);
        return this.owners.remove(id);
    }

    public synchronized int outstandingCount() {
        return this.outstanding.cardinality();
    }
}
