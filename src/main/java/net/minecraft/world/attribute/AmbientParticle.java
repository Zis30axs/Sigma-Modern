package net.minecraft.world.attribute;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;

public record AmbientParticle(ParticleOptions particle, float probability) {
    public static final Codec<AmbientParticle> CODEC = RecordCodecBuilder.create(
        i -> i.group(
                ParticleTypes.CODEC.fieldOf("particle").forGetter(s -> s.particle),
                Codec.floatRange(0.0F, 1.0F).fieldOf("probability").forGetter(s -> s.probability)
            )
            .apply(i, AmbientParticle::new)
    );

    /**
     * MODIFIED for porting: lithium client_tick.particle.biome_particles AmbientParticleSettingsMixin#findMaximumChance
     * (&lt;init&gt; RETURN). Tracks the largest ambient particle probability of all loaded ambient particle settings, so that
     * {@link net.minecraft.client.multiplayer.ClientLevel} can roll the random chance before looking up the biome.
     */
    public AmbientParticle {
        int currentMaximumChance = net.caffeinemc.mods.lithium.common.client.SharedFields.MAXIMUM_BIOME_PARTICLE_CHANCE.get();
        while (probability > Float.intBitsToFloat(currentMaximumChance)) {
            currentMaximumChance = net.caffeinemc.mods.lithium.common.client.SharedFields.MAXIMUM_BIOME_PARTICLE_CHANCE
                .compareAndExchange(currentMaximumChance, Float.floatToIntBits(probability));
        }
    }

    public boolean canSpawn(final RandomSource random) {
        // MODIFIED for porting: lithium client_tick.particle.biome_particles AmbientParticleSettingsMixin
        // #getAdjustedProbability (@ModifyExpressionValue on the probability field read). ClientLevel already rolled the
        // chance against the global maximum, so the remaining chance here has to be scaled by that maximum.
        return random.nextFloat() <= this.probability / Float.intBitsToFloat(net.caffeinemc.mods.lithium.common.client.SharedFields.MAXIMUM_BIOME_PARTICLE_CHANCE.get());
    }

    public static List<AmbientParticle> of(final ParticleOptions particle, final float probability) {
        return List.of(new AmbientParticle(particle, probability));
    }
}