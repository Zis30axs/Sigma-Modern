package net.fabricmc.fabric.api.particle.v1;

import net.minecraft.core.particles.SimpleParticleType;

// MODIFIED for porting: embedded stand-in for fabric-api
public final class FabricParticleTypes {
    private FabricParticleTypes() {
    }

    public static SimpleParticleType simple(final boolean alwaysSpawn) {
        return new SimpleParticleType(alwaysSpawn);
    }
}
