package com.mentalfrostbyte.jello.event.impl.player.movement;

import com.mentalfrostbyte.jello.event.CancellableEvent;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/**
 * The local player's movement delta, fired before collision is resolved. Writing to the delta changes
 * how far the player actually moves; cancelling drops the movement.
 *
 * <p>This fires for every mover type, including pistons and server-pushed motion, so listeners that
 * only care about the player walking should check {@link #getMoverType()}.</p>
 */
public class EventMove extends CancellableEvent {

    private final MoverType moverType;
    private double x;
    private double y;
    private double z;

    public EventMove(final MoverType moverType, final Vec3 delta) {
        this.moverType = moverType;
        this.x = delta.x;
        this.y = delta.y;
        this.z = delta.z;
    }

    public MoverType getMoverType() {
        return this.moverType;
    }

    public Vec3 getDelta() {
        return new Vec3(this.x, this.y, this.z);
    }

    public void setDelta(final Vec3 delta) {
        this.x = delta.x;
        this.y = delta.y;
        this.z = delta.z;
    }

    public double getX() {
        return this.x;
    }

    public void setX(final double x) {
        this.x = x;
    }

    public double getY() {
        return this.y;
    }

    public void setY(final double y) {
        this.y = y;
    }

    public double getZ() {
        return this.z;
    }

    public void setZ(final double z) {
        this.z = z;
    }
}
