package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.action.EventClick;
import com.mentalfrostbyte.jello.event.impl.game.action.EventKeyPress;
import com.mentalfrostbyte.jello.event.impl.game.network.EventReceivePacket;
import com.mentalfrostbyte.jello.event.impl.game.network.EventSendPacket;
import com.mentalfrostbyte.jello.event.impl.game.render.EventRender2D;
import com.mentalfrostbyte.jello.event.impl.game.render.EventRender3D;
import com.mentalfrostbyte.jello.event.impl.player.EventGetFovModifier;
import com.mentalfrostbyte.jello.event.impl.player.EventLivingUpdate;
import com.mentalfrostbyte.jello.event.impl.player.EventUpdate;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMove;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventSafeWalk;
import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.util.game.MinecraftInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** A BlockFly policy. Only the owning module subscribes to the event bus. */
public abstract class BlockFlyMode implements MinecraftInstance {
    protected final BlockFly parent;

    protected BlockFlyMode(final BlockFly parent) {
        this.parent = parent;
    }

    public void enable() { }
    public void disable() { }
    public void tick(final EventTick event) { }
    public void update(final EventUpdate event) { }
    public void livingUpdate(final EventLivingUpdate event) { }
    public void motion(final EventMotion event) { }
    public void move(final EventMove event) { }
    public void input(final EventMovementInput event) { }
    public void safeWalk(final EventSafeWalk event) { }
    public void fov(final EventGetFovModifier event) { }
    public void render(final EventRender2D event) { }
    public void render3D(final EventRender3D event) { }
    public void sendPacket(final EventSendPacket event) { }
    public void receivePacket(final EventReceivePacket event) { }
    public void click(final EventClick event) { }
    public void keyPress(final EventKeyPress event) { }
    public void worldLoad(final EventLoadWorld event) { }
    public boolean usesSharedMovement() { return false; }
    public ItemStack renderedItem(final InteractionHand hand, final ItemStack original) { return original; }
    public com.mentalfrostbyte.jello.util.math.Rotations.Rotation cameraRotation() { return null; }
}
