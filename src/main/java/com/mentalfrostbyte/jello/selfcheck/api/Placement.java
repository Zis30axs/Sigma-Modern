package com.mentalfrostbyte.jello.selfcheck.api;

/**
 * Where a transaction goes in the stream of packets the client is sent.
 *
 * <p>A server-side anticheat sends a transaction while it is sending some packet P and learns from the answer
 * whether the client had handled P when it sent a given movement. The first two placements only mean something
 * while {@link SelfCheckEngine#onClientbound} is running for P; anywhere else they behave like {@link #NOW}.</p>
 */
public enum Placement {
    /** Ahead of the packet being delivered, as if written while that packet was being encoded. */
    BEFORE_CURRENT,
    /** Right behind the packet being delivered, as a task that runs after it was sent. */
    AFTER_CURRENT,
    /** Behind everything the client has been sent so far. */
    NOW
}
