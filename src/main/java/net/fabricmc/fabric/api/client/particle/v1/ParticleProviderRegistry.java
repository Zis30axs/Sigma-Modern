package net.fabricmc.fabric.api.client.particle.v1;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;

// MODIFIED for porting: embedded stand-in for fabric-api (registration stored; wiring happens in the port bootstrap)
public final class ParticleProviderRegistry {
    private static final ParticleProviderRegistry INSTANCE = new ParticleProviderRegistry();
    private final Map<ParticleType<?>, ParticleProvider<?>> providers = new ConcurrentHashMap<>();
    private final Map<ParticleType<?>, Function<SpriteSet, ? extends ParticleProvider<?>>> spriteSetFactories =
            new ConcurrentHashMap<>();

    private ParticleProviderRegistry() {
    }

    public static ParticleProviderRegistry getInstance() {
        return INSTANCE;
    }

    public <T extends ParticleOptions> void register(final ParticleType<T> type, final ParticleProvider<T> provider) {
        providers.put(type, provider);
    }

    public <T extends ParticleOptions> void register(
            final ParticleType<T> type,
            final Function<SpriteSet, ? extends ParticleProvider<T>> spriteSetFactory
    ) {
        this.spriteSetFactories.put(type, spriteSetFactory);
    }

    public Map<ParticleType<?>, ParticleProvider<?>> getProviders() {
        return providers;
    }

    public Map<ParticleType<?>, Function<SpriteSet, ? extends ParticleProvider<?>>> getSpriteSetFactories() {
        return spriteSetFactories;
    }
}