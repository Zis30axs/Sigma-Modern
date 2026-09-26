package com.mentalfrostbyte.jello.selfcheck.engine.grim;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.manager.init.start.AbstractTickEndEvent;
import ac.grim.grimac.player.GrimPlayer;

/**
 * Grim's end-of-server-tick hook. A server calls it after each tick; a server flushes once a tick, so here it runs at
 * the end of every batch of packets read from the server, which is where a tick's worth of packets ends.
 */
final class SigmaTickEnd extends AbstractTickEndEvent {

    void endOfBatch() {
        if (!this.shouldInjectEndTick()) {
            return;
        }
        for (GrimPlayer player : GrimAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            if (!player.disableGrim) {
                this.onEndOfTick(player, true);
            }
        }
    }
}
