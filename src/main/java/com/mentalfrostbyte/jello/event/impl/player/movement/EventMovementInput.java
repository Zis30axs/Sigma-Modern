package com.mentalfrostbyte.jello.event.impl.player.movement;

import com.mentalfrostbyte.jello.event.Event;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * The keys the local player is holding this tick, fired right after they are read and before anything uses them.
 *
 * <p>A module may alter this tick's input without latching physical keys. Direction changes update both the
 * reported keys and the movement vector, including fractional scripted movement.</p>
 */
public class EventMovementInput extends Event {

    private Input input;
    private Vec2 moveVector;

    public EventMovementInput(final Input input) {
        this(input, new Vec2((input.left() ? 1 : 0) - (input.right() ? 1 : 0),
                (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0)).normalized());
    }

    public EventMovementInput(final Input input, final Vec2 moveVector) {
        this.input = input;
        this.moveVector = moveVector;
    }

    public Input getInput() {
        return this.input;
    }

    public void setInput(final Input input) {
        this.input = input;
        this.moveVector = new Vec2((input.left() ? 1 : 0) - (input.right() ? 1 : 0),
                (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0)).normalized();
    }

    public Vec2 getMoveVector() { return this.moveVector; }
    public float getForward() { return this.moveVector.y; }
    public float getStrafe() { return this.moveVector.x; }

    public void setForward(final float forward) {
        float value = Float.isFinite(forward) ? Math.clamp(forward, -1.0F, 1.0F) : 0.0F;
        this.moveVector = new Vec2(this.moveVector.x, value);
        this.input = new Input(value > 0, value < 0, this.input.left(), this.input.right(),
                this.input.jump(), this.input.shift(), this.input.sprint());
    }

    public void setStrafe(final float strafe) {
        float value = Float.isFinite(strafe) ? Math.clamp(strafe, -1.0F, 1.0F) : 0.0F;
        this.moveVector = new Vec2(value, this.moveVector.y);
        this.input = new Input(this.input.forward(), this.input.backward(), value > 0, value < 0,
                this.input.jump(), this.input.shift(), this.input.sprint());
    }

    public void setSprint(final boolean sprint) {
        this.input = new Input(this.input.forward(), this.input.backward(), this.input.left(), this.input.right(),
                this.input.jump(), this.input.shift(), sprint);
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
