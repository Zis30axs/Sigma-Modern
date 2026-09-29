package net.minecraft.client.player;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ClientInput {
    public Input keyPresses = Input.EMPTY;
    public Vec2 moveVector = Vec2.ZERO;

    public void tick() {
    }

    // Sigma hook: the keys a module chose for this tick (the movement corrector turns the direction keys), from which the
    // movement vector is worked out again. An input that has no vector of its own to work out only takes the keys.
    public void replaceKeyPresses(final Input keys) {
        this.keyPresses = keys;
    }

    public Vec2 getMoveVector() {
        return this.moveVector;
    }

    public boolean hasForwardImpulse() {
        return this.moveVector.y > 1.0E-5F;
    }

    public void makeJump() {
        this.keyPresses = new Input(
            this.keyPresses.forward(),
            this.keyPresses.backward(),
            this.keyPresses.left(),
            this.keyPresses.right(),
            true,
            this.keyPresses.shift(),
            this.keyPresses.sprint()
        );
    }
}
