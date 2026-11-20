package net.minecraft.client.renderer.fog.environment;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public abstract class MobEffectFogEnvironment extends FogEnvironment {
    public abstract Holder<MobEffect> getMobEffect();

    @Override
    public boolean providesColor() {
        return false;
    }

    @Override
    public boolean modifiesDarkness() {
        return true;
    }

    @Override
    public boolean isApplicable(final @Nullable FogType fogType, final Entity entity) {
        com.mentalfrostbyte.jello.module.impl.render.NoRender noRender =
            com.mentalfrostbyte.jello.module.Modules.enabled(com.mentalfrostbyte.jello.module.impl.render.NoRender.class);
        if (noRender != null) {
            Holder<MobEffect> effect = this.getMobEffect();
            if (noRender.hideBlindness() && effect.equals(net.minecraft.world.effect.MobEffects.BLINDNESS)) {
                return false;
            }
            if (noRender.hideDarkness() && effect.equals(net.minecraft.world.effect.MobEffects.DARKNESS)) {
                return false;
            }
        }
        return entity instanceof LivingEntity livingEntity && livingEntity.hasEffect(this.getMobEffect());
    }
}