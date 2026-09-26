package com.mentalfrostbyte.jello.music;

import static com.mentalfrostbyte.jello.music.MusicEnvironmentProbe.Axis.*;
import static com.mentalfrostbyte.jello.music.MusicEnvironmentProbe.Precipitation.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MusicEnvironmentProbeTest {
    private static final double MISS = Double.POSITIVE_INFINITY;

    @Test
    void outdoorsAndASingleCliffHaveNoReverb() {
        assertEquals(MusicEnvironmentProbe.Space.OPEN, space(axis -> axis == DOWN ? 1.6 : MISS));
        assertEquals(MusicEnvironmentProbe.Space.OPEN, space(axis -> axis == WEST || axis == DOWN ? 2 : MISS));
        // An overhang above a single wall is still not an enclosed room.
        assertEquals(MusicEnvironmentProbe.Space.OPEN, space(axis -> axis == UP || axis == WEST || axis == DOWN ? 2 : MISS));
    }

    @Test
    void courtyardWithoutARoofAndSpacesBeyondTheProbeRangeRemainOpen() {
        assertEquals(MusicEnvironmentProbe.Space.OPEN, space(axis -> axis == UP ? MISS : 3));
        assertEquals(MusicEnvironmentProbe.Space.OPEN, space(axis -> 25));
    }

    @Test
    void roomAndCaveUseWallCountForEnclosureAndDistanceForSize() {
        MusicEnvironmentProbe.Space room = space(axis -> 3);
        MusicEnvironmentProbe.Space cave = space(axis -> 15);
        assertEquals(1F, room.enclosure());
        assertEquals(3F / 24F, room.size(), 0.0001F);
        assertEquals(1F, cave.enclosure());
        assertTrue(cave.size() > room.size());

        MusicEnvironmentProbe.Space mouth = space(axis -> axis == NORTH || axis == SOUTH ? MISS : 3);
        assertEquals(0.5F, mouth.enclosure());
        assertTrue(mouth.size() > room.size());
    }

    @Test
    void samplesEachWorldAxisExactlyOnce() {
        Set<MusicEnvironmentProbe.Axis> sampled = EnumSet.noneOf(MusicEnvironmentProbe.Axis.class);
        space(axis -> {
            assertTrue(sampled.add(axis), "An axis must not be sampled twice");
            assertEquals(1, Math.abs(axis.x) + Math.abs(axis.y) + Math.abs(axis.z));
            return 3;
        });
        assertEquals(EnumSet.allOf(MusicEnvironmentProbe.Axis.class), sampled);
    }

    @Test
    void checksAllChunksBeforeCastingAndHandlesNegativeCoordinates() {
        Set<String> chunks = new HashSet<>();
        MusicEnvironmentProbe.space(-1, -1, (x, z) -> {
            chunks.add(x + ":" + z);
            return true;
        }, axis -> {
            assertEquals(16, chunks.size(), "All region checks must finish before any ray");
            return 3;
        });
        assertTrue(chunks.contains("-2:-2"));
        assertTrue(chunks.contains("1:1"));
        assertEquals(MusicEnvironmentProbe.Space.OPEN, MusicEnvironmentProbe.space(-1, -1,
            (x, z) -> x != 1 || z != 1,
            axis -> fail("A missing region must not perform ray casts")));
    }

    @Test
    void invalidRayDistancesCannotProduceInvalidAcoustics() {
        MusicEnvironmentProbe.Space invalid = space(axis -> switch (axis) {
            case UP -> 2;
            case NORTH, SOUTH -> 4;
            case DOWN -> -1;
            case WEST -> Double.NaN;
            case EAST -> Double.NEGATIVE_INFINITY;
        });
        assertEquals(0.5F, invalid.enclosure());
        assertTrue(Float.isFinite(invalid.size()) && invalid.size() >= 0 && invalid.size() <= 1);
    }

    @Test
    void visualRainStrengthSelectsRainOrSnowAndCanRepresentAWeatherOverride() {
        assertEquals(new MusicEnvironmentProbe.Weather(0.7F, 0F), MusicEnvironmentProbe.weather(0.7F, RAIN, true, true, true));
        assertEquals(new MusicEnvironmentProbe.Weather(0F, 0.4F), MusicEnvironmentProbe.weather(0.4F, SNOW, true, true, true));
        // A clear-weather visual override supplies zero even when the server's weather is rainy.
        assertEquals(MusicEnvironmentProbe.Weather.CLEAR, MusicEnvironmentProbe.weather(0F, RAIN, true, true, true));
    }

    @Test
    void roofHeightmapDryBiomeAndWeatherlessDimensionEachSuppressWeather() {
        assertEquals(MusicEnvironmentProbe.Weather.CLEAR, MusicEnvironmentProbe.weather(1F, RAIN, true, false, true));
        assertEquals(MusicEnvironmentProbe.Weather.CLEAR, MusicEnvironmentProbe.weather(1F, RAIN, true, true, false));
        assertEquals(MusicEnvironmentProbe.Weather.CLEAR, MusicEnvironmentProbe.weather(1F, NONE, true, true, true));
        assertEquals(MusicEnvironmentProbe.Weather.CLEAR, MusicEnvironmentProbe.weather(1F, SNOW, false, true, true));
    }

    @Test
    void weatherStrengthIsFiniteAndBounded() {
        for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1F}) {
            assertEquals(MusicEnvironmentProbe.Weather.CLEAR, MusicEnvironmentProbe.weather(invalid, RAIN, true, true, true));
        }
        assertEquals(new MusicEnvironmentProbe.Weather(1F, 0F), MusicEnvironmentProbe.weather(3F, RAIN, true, true, true));
    }

    private static MusicEnvironmentProbe.Space space(MusicEnvironmentProbe.RayDistance ray) {
        return MusicEnvironmentProbe.space(0, 0, (x, z) -> true, ray);
    }
}
