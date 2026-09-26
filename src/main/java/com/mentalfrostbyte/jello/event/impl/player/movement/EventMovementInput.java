package com.mentalfrostbyte.jello.event.impl.player.movement;

import com.mentalfrostbyte.jello.event.Event;
import net.minecraft.world.entity.player.Input;

/**
 * The keys the local player is holding this tick, fired right after they are read and before anything uses them.
 *
 * <p>A module may press jump or sneak on the player's behalf; the game then behaves exactly as if the key were
 * held, including telling the server so on versions that send the player's input. The direction keys are for
 * reading only: the movement vector has already been worked out from them.</p>
 */
public class EventMovementInput extends Event {

    private Input input;

    public EventMovementInput(final Input input) {
        this.input = input;
    }

    public Input getInput() {
        return this.input;
    }

    /** Whether a direction key is held that actually moves the player (opposite keys cancel out). */
    public boolean isMoving() {
        return this.input.forward() != this.input.backward() || this.input.left() != this.input.right();
    }

    public void setJump(final boolean jump) {
        this.input = new Input(this.input.forward(), this.input.backward(), this.input.left(), this.input.right(),
                jump, this.input.shift(), this.input.sprint());
    }

    public void setShift(final boolean shift) {
        this.input = new Input(this.input.forward(), this.input.backward(), this.input.left(), this.input.right(),
                this.input.jump(), shift, this.input.sprint());
    }
}
