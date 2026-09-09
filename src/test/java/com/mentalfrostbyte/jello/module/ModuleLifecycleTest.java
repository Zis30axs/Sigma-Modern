package com.mentalfrostbyte.jello.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.event.Event;
import com.mentalfrostbyte.jello.event.EventBus;
import com.mentalfrostbyte.jello.event.EventTarget;
import org.junit.jupiter.api.Test;

/**
 * Proves Module.setEnabled(boolean) is transactional (F3): a subscription exists exactly while
 * {@code enabled} is true, and a failing onEnable()/onDisable() cannot leave the two disagreeing.
 *
 * <p>{@link Ping} is private to this file so these tests cannot interfere with subscriptions any other
 * test class registers - {@link EventBus}'s subscriber map is static and keyed by exact event class,
 * with no reset between tests.</p>
 */
class ModuleLifecycleTest {

    @Test
    void successfulEnableSubscribesExactlyOnce() {
        ProbeModule module = new ProbeModule("Probe-enable-once", null, null);

        module.setEnabled(true);
        assertTrue(module.isEnabled());

        EventBus.call(new Ping());
        assertEquals(1, module.hits);
        EventBus.call(new Ping());
        assertEquals(2, module.hits, "a second listener copy would double-count instead of incrementing by one");

        module.setEnabled(false);
    }

    @Test
    void successfulDisableUnsubscribes() {
        ProbeModule module = new ProbeModule("Probe-disable", null, null);
        module.setEnabled(true);

        module.setEnabled(false);
        assertFalse(module.isEnabled());

        EventBus.call(new Ping());
        assertEquals(0, module.hits);
    }

    @Test
    void failingOnEnableLeavesModuleDisabledAndUnsubscribed() {
        ProbeModule module = new ProbeModule("Probe-enable-fails",
                () -> { throw new IllegalStateException("onEnable boom"); }, null);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> module.setEnabled(true));
        assertEquals("onEnable boom", failure.getMessage());
        assertFalse(module.isEnabled());

        EventBus.call(new Ping());
        assertEquals(0, module.hits, "a module whose onEnable() threw must never receive events");
    }

    @Test
    void failingOnDisableStillLeavesModuleDisabledAndUnsubscribed() {
        ProbeModule module = new ProbeModule("Probe-disable-fails", null,
                () -> { throw new IllegalStateException("onDisable boom"); });
        module.setEnabled(true);

        EventBus.call(new Ping());
        assertEquals(1, module.hits);

        assertThrows(IllegalStateException.class, () -> module.setEnabled(false));
        assertFalse(module.isEnabled(), "state must not lie even though onDisable() threw");

        EventBus.call(new Ping());
        assertEquals(1, module.hits, "still unsubscribed despite onDisable() throwing");
    }

    private static final class Ping extends Event {
    }

    private static final class ProbeModule extends Module {

        private final Runnable onEnableAction;
        private final Runnable onDisableAction;
        int hits;

        ProbeModule(final String name, final Runnable onEnableAction, final Runnable onDisableAction) {
            super(ModuleCategory.MISC, name, "test");
            this.onEnableAction = onEnableAction;
            this.onDisableAction = onDisableAction;
        }

        @Override
        protected void onEnable() {
            if (this.onEnableAction != null) {
                this.onEnableAction.run();
            }
        }

        @Override
        protected void onDisable() {
            if (this.onDisableAction != null) {
                this.onDisableAction.run();
            }
        }

        @EventTarget
        void onPing(final Ping event) {
            this.hits++;
        }
    }
}
