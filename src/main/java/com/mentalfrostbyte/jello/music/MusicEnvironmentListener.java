package com.mentalfrostbyte.jello.music;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Samples the world on the game thread; the audio thread receives only immutable numeric snapshots. */
public final class MusicEnvironmentListener {
    private final MusicEffects effects;
    private ClientLevel sampledLevel;
    private LocalPlayer sampledPlayer;
    private int ticksUntilSpaceSample;
    private MusicEnvironmentProbe.Space space = MusicEnvironmentProbe.Space.OPEN;

    public MusicEnvironmentListener(MusicEffects effects) {
        this.effects = Objects.requireNonNull(effects);
    }

    @EventTarget
    public void onTick(EventTick event) {
        if (!event.isPre()) return;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || player.isRemoved() || player.level() != level) {
            reset();
            return;
        }
        if (this.sampledLevel != level || this.sampledPlayer != player) {
            reset();
            this.sampledLevel = level;
            this.sampledPlayer = player;
        }

        Vec3 eye = player.getEyePosition();
        BlockPos eyeBlock = BlockPos.containing(eye);
        // ClientLevel.hasChunkAt takes a path that reports true even for missing chunks in this port.
        if (!level.getChunkSource().hasChunk(Math.floorDiv(eyeBlock.getX(), 16), Math.floorDiv(eyeBlock.getZ(), 16))) {
            reset();
            return;
        }

        MusicEnvironment.Liquid liquid = player.isEyeInFluid(FluidTags.LAVA) ? MusicEnvironment.Liquid.LAVA
            : player.isEyeInFluid(FluidTags.WATER) ? MusicEnvironment.Liquid.WATER : MusicEnvironment.Liquid.NONE;
        if (this.ticksUntilSpaceSample-- <= 0) {
            this.space = MusicEnvironmentProbe.space(eyeBlock.getX(), eyeBlock.getZ(), level.getChunkSource()::hasChunk,
                axis -> cast(level, player, eye, axis));
            this.ticksUntilSpaceSample = MusicEnvironmentProbe.SAMPLE_TICKS - 1;
        }

        MusicEnvironmentProbe.Weather weather = weather(level, eyeBlock);
        this.effects.setEnvironment(new MusicEnvironment(liquid, weather.rain(), weather.snow(), this.space.enclosure(), this.space.size()));
    }

    @EventTarget
    public void onLoadWorld(EventLoadWorld event) {
        reset();
    }

    /** Clears world references and cached acoustics on disconnect, replacement, or listener shutdown. */
    public void reset() {
        this.sampledLevel = null;
        this.sampledPlayer = null;
        this.ticksUntilSpaceSample = 0;
        this.space = MusicEnvironmentProbe.Space.OPEN;
        this.effects.setEnvironment(MusicEnvironment.NEUTRAL);
    }

    private static double cast(ClientLevel level, LocalPlayer player, Vec3 eye, MusicEnvironmentProbe.Axis axis) {
        Vec3 end = eye.add(axis.x * MusicEnvironmentProbe.RANGE, axis.y * MusicEnvironmentProbe.RANGE, axis.z * MusicEnvironmentProbe.RANGE);
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? eye.distanceTo(hit.getLocation()) : Double.POSITIVE_INFINITY;
    }

    private static MusicEnvironmentProbe.Weather weather(ClientLevel level, BlockPos eye) {
        if (!level.canHaveWeather()) return MusicEnvironmentProbe.Weather.CLEAR;
        // Both getters match the renderer: visual weather includes the Weather module, and precipitation
        // includes local biome temperature as well as ViaFabricPlus's classic-protocol snow override.
        float rain = level.getRainLevel(1F);
        if (!(rain > 0F)) return MusicEnvironmentProbe.Weather.CLEAR;
        MusicEnvironmentProbe.Precipitation precipitation = switch (level.getPrecipitationAt(eye)) {
            case NONE -> MusicEnvironmentProbe.Precipitation.NONE;
            case RAIN -> MusicEnvironmentProbe.Precipitation.RAIN;
            case SNOW -> MusicEnvironmentProbe.Precipitation.SNOW;
        };
        return MusicEnvironmentProbe.weather(rain, precipitation, true, level.canSeeSky(eye),
            eye.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, eye.getX(), eye.getZ()));
    }
}
