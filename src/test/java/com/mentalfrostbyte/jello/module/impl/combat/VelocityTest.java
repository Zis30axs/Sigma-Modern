package com.mentalfrostbyte.jello.module.impl.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.Setting;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class VelocityTest {

    private static final Vec3 PUSH = new Vec3(0.3, 0.4, -0.2);

    @Test
    void noShareAtAllDropsThePush() {
        assertNull(Velocity.scaled(PUSH, 0.0F, 0.0F), "dropped, so the player keeps their own motion instead of stopping");
    }

    @Test
    void sidewaysAndUpAreScaledApart() {
        assertEquals(new Vec3(0.3 * 0.5F, 0.4, -0.2 * 0.5F), Velocity.scaled(PUSH, 0.5F, 1.0F));
        assertEquals(new Vec3(0.0, 0.4 * 0.25F, 0.0), Velocity.scaled(PUSH, 0.0F, 0.25F), "a 0 axis is zeroed, not kept");
        assertEquals(new Vec3(-0.3, 0.4, 0.2), Velocity.scaled(PUSH, -1.0F, 1.0F), "negative pulls towards the attacker");
    }

    @Test
    void onlyTheCurrentModesSettingsAreShown() {
        Velocity velocity = new Velocity();
        assertTrue(this.visible(velocity, "Horizontal"));
        assertFalse(this.visible(velocity, "Until Jump"));
        assertTrue(this.visible(velocity, "Chance"), "both modes roll the chance");

        this.choose(velocity, Velocity.Mode.JUMP_RESET);
        assertFalse(this.visible(velocity, "Horizontal"));
        assertFalse(this.visible(velocity, "Explosions"));
        assertTrue(this.visible(velocity, "Until Jump"));
        assertTrue(this.visible(velocity, "Chance"));
    }

    private boolean visible(final Velocity velocity, final String name) {
        return velocity.setting(name).map(Setting::isVisible).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private void choose(final Velocity velocity, final Velocity.Mode mode) {
        ((EnumSetting<Velocity.Mode>) velocity.setting("Mode").orElseThrow()).set(mode);
    }
}
