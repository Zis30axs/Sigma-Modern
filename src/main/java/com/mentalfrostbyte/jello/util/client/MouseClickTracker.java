package com.mentalfrostbyte.jello.util.client;

import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/** Physical gameplay clicks only. Mouse callbacks and HUD queries run on the game thread. */
public final class MouseClickTracker {
    static final int CAPACITY = 256;
    static final long WINDOW_NANOS = 1_000_000_000L;
    private static final Counter COUNTER = new Counter();

    private MouseClickTracker() {}

    /** Context at callback delivery, before ViaFabricPlus may queue a screen event for a later tick. */
    public record InputContext(@Nullable Object world, boolean gameplay) {}

    public static InputContext captureContext(final Minecraft client) {
        boolean gameplay = gameplay(client);
        return new InputContext(gameplay ? client.level : null, gameplay);
    }

    /** Called before an input listener consumes the raw button or a platform quirk remaps it. */
    public static void onButton(final Minecraft client, final int button, final int action, final InputContext captured) {
        COUNTER.button(button, action, captured, client.level, gameplay(client), System.nanoTime());
    }

    /** Per-frame cleanup also runs while the HUD or its keyboard widget is hidden. */
    public static void syncContext(final Minecraft client) {
        COUNTER.sync(client.level, gameplay(client), System.nanoTime());
    }

    public static int getLeftCps(final Minecraft client) {
        return COUNTER.cps(0, client.level, gameplay(client), System.nanoTime());
    }

    public static int getRightCps(final Minecraft client) {
        return COUNTER.cps(1, client.level, gameplay(client), System.nanoTime());
    }

    public static void reset() {
        COUNTER.reset();
    }

    private static boolean gameplay(final Minecraft client) {
        return client.player != null && client.level != null && client.isWindowActive()
                && client.gui.screen() == null && client.gui.overlay() == null
                && client.mouseHandler != null && client.mouseHandler.isMouseGrabbed();
    }

    /** A value-only window, with context identity supplied by the game-facing adapter above. */
    static final class Counter {
        private final Clicks left = new Clicks();
        private final Clicks right = new Clicks();
        private WeakReference<Object> world = new WeakReference<>(null);
        private boolean accepting;
        private boolean hasClock;
        private long previousTime;

        void button(final int button, final int action, final @Nullable Object currentWorld,
                    final boolean gameplay, final long now) {
            this.button(button, action, new InputContext(currentWorld, gameplay), currentWorld, gameplay, now);
        }

        void button(final int button, final int action, final InputContext captured, final @Nullable Object currentWorld,
                    final boolean gameplay, final long now) {
            this.sync(currentWorld, gameplay, now);
            if (!this.accepting || !captured.gameplay || captured.world != currentWorld) return;
            Clicks clicks = this.channel(button);
            if (clicks == null) return;
            if (action == 0) {
                clicks.down = false;
            } else if (action == 1 && !clicks.down) {
                clicks.down = true;
                clicks.add(now);
            }
        }

        int cps(final int button, final @Nullable Object currentWorld, final boolean gameplay, final long now) {
            this.sync(currentWorld, gameplay, now);
            Clicks clicks = this.channel(button);
            return clicks == null || !this.accepting ? 0 : clicks.size;
        }

        void sync(final @Nullable Object currentWorld, final boolean gameplay, final long now) {
            boolean changedWorld = this.world.get() != currentWorld;
            // Subtraction survives nanoTime's signed wrap; a negative elapsed time is an actual rollback.
            boolean rollback = this.hasClock && now - this.previousTime < 0L;
            if (changedWorld || rollback || currentWorld == null || !gameplay) {
                this.left.clear();
                this.right.clear();
            }
            if (changedWorld) this.world = new WeakReference<>(currentWorld);
            this.accepting = currentWorld != null && gameplay;
            this.hasClock = true;
            this.previousTime = now;
            this.left.expire(now);
            this.right.expire(now);
        }

        void reset() {
            this.left.clear();
            this.right.clear();
            this.world.clear();
            this.accepting = false;
            this.hasClock = false;
            this.previousTime = 0L;
        }

        private @Nullable Clicks channel(final int button) {
            return button == 0 ? this.left : button == 1 ? this.right : null;
        }
    }

    private static final class Clicks {
        private final long[] times = new long[CAPACITY];
        private int first;
        private int size;
        private boolean down;

        void add(final long now) {
            this.times[(this.first + this.size) % CAPACITY] = now;
            if (this.size == CAPACITY) this.first = (this.first + 1) % CAPACITY;
            else this.size++;
        }

        void expire(final long now) {
            while (this.size > 0 && now - this.times[this.first] >= WINDOW_NANOS) {
                this.first = (this.first + 1) % CAPACITY;
                this.size--;
            }
        }

        void clear() {
            this.first = this.size = 0;
            this.down = false;
        }
    }
}
