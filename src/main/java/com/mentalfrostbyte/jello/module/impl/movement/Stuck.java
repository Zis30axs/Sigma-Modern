package com.mentalfrostbyte.jello.module.impl.movement;

import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.player.EventLivingUpdate;
import com.mentalfrostbyte.jello.event.impl.player.EventUpdate;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventJump;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMove;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Holds local movement while normal player ticks, look packets, item use and teleport acknowledgements continue.
 * The stationary idea was compared with LiquidBounce Freeze and Meteor Velocity; no packet queuing, tick
 * cancellation, position restoration or saved velocity is used here.
 */
public final class Stuck extends Module {
    private final Holds holds = new Holds();
    private @Nullable LocalPlayer player;

    public Stuck() {
        super(ModuleCategory.MOVEMENT, "Stuck", "Holds your movement in place while allowing camera movement and item use.");
    }

    /**
     * Temporarily borrows this module for at most 200 client ticks. Repeating a hold renews that owner's lease.
     * Only a module opened by a borrower is closed when its final lease ends. Call on the game thread.
     */
    public boolean hold(final Object owner, final int maxTicks) {
        Objects.requireNonNull(owner, "owner");
        LocalPlayer current = mc.player;
        if (!valid(current)) return false;
        if (this.player != null && this.player != current) {
            this.setEnabled(false);
            return false;
        }
        boolean alreadyEnabled = this.isEnabled();
        if (!alreadyEnabled) this.setEnabled(true);
        // A toggle listener can immediately turn the module back off.
        if (!this.isEnabled()) return false;
        this.holds.acquire(owner, maxTicks, alreadyEnabled);
        this.player = current;
        freeze(current);
        return true;
    }

    /** Releases only this caller's hold; never disables a Stuck that the user had enabled beforehand. */
    public void release(final Object owner) {
        Objects.requireNonNull(owner, "owner");
        if (this.holds.release(owner)) this.setEnabled(false);
    }

    public boolean isHeldBy(final Object owner) {
        return this.isEnabled() && this.holds.contains(owner);
    }

    @Override
    protected void onEnable() {
        this.holds.clear();
        this.player = mc.player;
        if (valid(this.player)) freeze(this.player);
    }

    @Override
    protected void onDisable() {
        // A cancelled rescue starts falling from rest, never from velocity saved before the freeze.
        if (this.player == mc.player && valid(this.player)) this.player.setDeltaMovement(Vec3.ZERO);
        this.player = null;
        this.holds.clear();
    }

    @EventTarget(EventPriority.LOWEST)
    public void onTick(final EventTick event) {
        LocalPlayer current = this.currentPlayer();
        if (current == null) return;
        if (event.isPost() && this.holds.tick()) {
            this.setEnabled(false);
            return;
        }
        freeze(current);
    }

    @EventTarget(EventPriority.LOWEST)
    public void onUpdate(final EventUpdate event) {
        LocalPlayer current = this.currentPlayer();
        if (current != null) freeze(current);
    }

    @EventTarget(EventPriority.LOWEST)
    public void onLivingUpdate(final EventLivingUpdate event) {
        LocalPlayer current = this.currentPlayer();
        if (current != null) freeze(current);
    }

    @EventTarget(EventPriority.LOWEST)
    public void onMovementInput(final EventMovementInput event) {
        if (this.currentPlayer() != null) event.setInput(stationary(event.getInput()));
    }

    @EventTarget(EventPriority.LOWEST)
    public void onMove(final EventMove event) {
        LocalPlayer current = this.currentPlayer();
        if (current == null) return;
        event.setDelta(Vec3.ZERO);
        event.cancel();
        current.setDeltaMovement(Vec3.ZERO);
    }

    @EventTarget(EventPriority.LOWEST)
    public void onJump(final EventJump event) {
        if (this.currentPlayer() != null && event.isPre()) event.cancel();
    }

    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) {
        // A held old player must never carry an automatic or manual freeze into the next world.
        this.setEnabled(false);
    }

    private @Nullable LocalPlayer currentPlayer() {
        LocalPlayer current = mc.player;
        if (!valid(current) || (this.player != null && this.player != current)) {
            this.setEnabled(false);
            return null;
        }
        this.player = current;
        return current;
    }

    private static boolean valid(final @Nullable LocalPlayer player) {
        return player != null && mc.level != null && player.level() == mc.level
                && player.isAlive() && !player.isRemoved() && !player.isPassenger() && !player.isSpectator();
    }

    private static void freeze(final LocalPlayer player) {
        player.setDeltaMovement(Vec3.ZERO);
        player.setSprinting(false);
        player.input.keyPresses = stationary(player.input.keyPresses);
        player.input.moveVector = Vec2.ZERO;
    }

    static Input stationary(final Input input) {
        // Keep the crouch pose, including its collision box, while suppressing travel and jump.
        return new Input(false, false, false, false, false, input.shift(), false);
    }

    /** Pure bounded lease bookkeeping. Identity prevents unrelated callers with equal values sharing ownership. */
    static final class Holds {
        private final Map<Object, Long> deadlines = new IdentityHashMap<>();
        private long tick;
        private boolean openedAutomatically;

        void acquire(final Object owner, final int maxTicks, final boolean alreadyEnabled) {
            Objects.requireNonNull(owner, "owner");
            if (this.deadlines.isEmpty()) this.openedAutomatically = !alreadyEnabled;
            this.deadlines.put(owner, this.tick + Math.clamp(maxTicks, 1, 200));
        }

        boolean contains(final Object owner) {
            return this.deadlines.containsKey(owner);
        }

        boolean release(final Object owner) {
            this.deadlines.remove(owner);
            return this.openedAutomatically && this.deadlines.isEmpty();
        }

        /** Run at tick POST so a hold acquired during PRE receives its full first physics step. */
        boolean tick() {
            this.tick++;
            this.deadlines.values().removeIf(deadline -> deadline <= this.tick);
            return this.openedAutomatically && this.deadlines.isEmpty();
        }

        void clear() {
            this.deadlines.clear();
            this.tick = 0;
            this.openedAutomatically = false;
        }
    }
}
