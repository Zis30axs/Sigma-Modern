package com.mentalfrostbyte.jello.music;

/** Bounded, game-independent rules used by the main-thread environment listener. */
final class MusicEnvironmentProbe {
    static final int RANGE = 24;
    static final int SAMPLE_TICKS = 5;

    enum Axis {
        UP(0, 1, 0), DOWN(0, -1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

        final int x, y, z;

        Axis(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    enum Precipitation { NONE, RAIN, SNOW }

    record Space(float enclosure, float size) {
        static final Space OPEN = new Space(0F, 0F);
    }

    record Weather(float rain, float snow) {
        static final Weather CLEAR = new Weather(0F, 0F);
    }

    @FunctionalInterface
    interface LoadedChunk {
        boolean test(int chunkX, int chunkZ);
    }

    @FunctionalInterface
    interface RayDistance {
        /** Distance to the collision shape, or positive infinity for a miss. */
        double sample(Axis axis);
    }

    private MusicEnvironmentProbe() {}

    static Space space(int blockX, int blockZ, LoadedChunk loaded, RayDistance ray) {
        // Include one extra block: collision shapes may inspect a neighbouring block. All checks precede
        // ray casting, so even the first ray never asks the world for an unloaded chunk.
        int minX = Math.floorDiv(blockX - RANGE - 1, 16), maxX = Math.floorDiv(blockX + RANGE + 1, 16);
        int minZ = Math.floorDiv(blockZ - RANGE - 1, 16), maxZ = Math.floorDiv(blockZ + RANGE + 1, 16);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!loaded.test(x, z)) return Space.OPEN;
            }
        }

        boolean roof = false;
        int walls = 0;
        double distanceSum = 0;
        for (Axis axis : Axis.values()) {
            double distance = ray.sample(axis);
            boolean hit = Double.isFinite(distance) && distance >= 0 && distance <= RANGE;
            if (axis == Axis.UP) roof = hit;
            if (axis.y == 0 && hit) walls++;
            // Open directions count as the full range, keeping a cave mouth more spacious than a room.
            distanceSum += hit ? distance : RANGE;
        }
        if (!roof || walls < 2) return Space.OPEN;
        return new Space(walls / 4F, (float)(distanceSum / (6 * RANGE)));
    }

    static Weather weather(float visualRain, Precipitation precipitation, boolean canHaveWeather,
                           boolean seesSky, boolean aboveHeightmap) {
        if (!canHaveWeather || !seesSky || !aboveHeightmap || !Float.isFinite(visualRain) || visualRain <= 0F) {
            return Weather.CLEAR;
        }
        float strength = Math.min(1F, visualRain);
        return switch (precipitation) {
            case NONE -> Weather.CLEAR;
            case RAIN -> new Weather(strength, 0F);
            case SNOW -> new Weather(0F, strength);
        };
    }
}
