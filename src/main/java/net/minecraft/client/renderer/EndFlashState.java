package net.minecraft.client.renderer;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class EndFlashState implements net.irisshaders.iris.mixin.EndFlashAccess { // MODIFIED for porting: iris EndFlashAccess
    public static final int SOUND_DELAY_IN_TICKS = 30;
    private static final int FLASH_INTERVAL_IN_TICKS = 600;
    private static final int MAX_FLASH_OFFSET_IN_TICKS = 200;
    private static final int MIN_FLASH_DURATION_IN_TICKS = 100;
    private static final int MAX_FLASH_DURATION_IN_TICKS = 380;
    private long flashSeed;
    private int offset;
    private int duration;
    private float intensity;
    private float oldIntensity;
    private float xAngle;
    private float yAngle;
    // MODIFIED for porting: iris MixinEndFlash constant - degrees above the horizon
    private static final float IRIS_ABOVE_HORIZON_EPS = 5.0F;

    // MODIFIED for porting: was iris's EndFlashAccess @Accessor("xAngle") / @Accessor("yAngle")
    @Override
    public void setXAngle(final float xAngle) {
        this.xAngle = xAngle;
    }

    @Override
    public void setYAngle(final float yAngle) {
        this.yAngle = yAngle;
    }

    public void tick(final long clockTime) {
        this.calculateFlashParameters(clockTime);
        this.oldIntensity = this.intensity;
        this.intensity = this.calculateIntensity(clockTime);
    }

    private void calculateFlashParameters(final long clockTime) {
        long newSeed = clockTime / 600L;
        if (newSeed != this.flashSeed) {
            RandomSource randomSource = RandomSource.createThreadLocalInstance(newSeed);
            randomSource.nextFloat();
            this.offset = Mth.randomBetweenInclusive(randomSource, 0, 200);
            this.duration = Mth.randomBetweenInclusive(randomSource, 100, Math.min(380, 600 - this.offset));
            // MODIFIED for porting: was iris's MixinEndFlash#iris$calculateNewAngles (@Inject at the first INVOKE of
            // Mth#randomBetween, cancellable) - with a shader pack loaded the flash is kept above the horizon, because the
            // shader's sky is not drawn below it.
            if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && net.irisshaders.iris.Iris.getCurrentPack().isPresent()) {
                this.xAngle = -Mth.randomBetween(randomSource, IRIS_ABOVE_HORIZON_EPS, 60.0F); // [-60, -5]
                this.yAngle = Mth.randomBetween(randomSource, -180.0F, 180.0F);
                this.flashSeed = newSeed;
                return;
            }

            this.xAngle = Mth.randomBetween(randomSource, -60.0F, 10.0F);
            this.yAngle = Mth.randomBetween(randomSource, -180.0F, 180.0F);
            this.flashSeed = newSeed;
        }
    }

    private float calculateIntensity(final long clockTime) {
        long clockTimeWithinInterval = clockTime % 600L;
        return clockTimeWithinInterval >= this.offset && clockTimeWithinInterval <= this.offset + this.duration
            ? Mth.sin((float)(clockTimeWithinInterval - this.offset) * (float) Math.PI / this.duration)
            : 0.0F;
    }

    public float getXAngle() {
        return this.xAngle;
    }

    public float getYAngle() {
        return this.yAngle;
    }

    public float getIntensity(final float partialTicks) {
        return Mth.lerp(partialTicks, this.oldIntensity, this.intensity);
    }

    public boolean flashStartedThisTick() {
        return this.intensity > 0.0F && this.oldIntensity <= 0.0F;
    }
}