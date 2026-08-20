package net.minecraft.client.renderer.shaderpack;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ShaderPackProgramResolver {
    private static final Map<ShaderPackProgramResolver.TerrainProgram, List<String>> TERRAIN_FALLBACKS = createTerrainFallbacks();
    private final ShaderPackProgramSet programs;
    private final EnumMap<ShaderPackProgramResolver.TerrainProgram, Optional<ShaderPackProgramResolver.ResolvedProgram>> cache = new EnumMap<>(
        ShaderPackProgramResolver.TerrainProgram.class
    );

    public ShaderPackProgramResolver(final ShaderPackProgramSet programs) {
        this.programs = programs;
    }

    public Optional<ShaderPackProgramResolver.ResolvedProgram> resolve(final ShaderPackProgramResolver.TerrainProgram requested) {
        return this.cache.computeIfAbsent(requested, this::resolveUncached);
    }

    private Optional<ShaderPackProgramResolver.ResolvedProgram> resolveUncached(final ShaderPackProgramResolver.TerrainProgram requested) {
        List<String> chain = TERRAIN_FALLBACKS.get(requested);
        for (int index = 0; index < chain.size(); index++) {
            String candidate = chain.get(index);
            Optional<ShaderPackProgramSet.Program> program = this.programs.find(candidate)
                .filter(ShaderPackProgramResolver::hasGraphicsStages);
            if (program.isPresent()) {
                return Optional.of(new ShaderPackProgramResolver.ResolvedProgram(requested.fileBase(), candidate, index == 0, program.get(), chain));
            }
        }

        return Optional.empty();
    }

    private static boolean hasGraphicsStages(final ShaderPackProgramSet.Program program) {
        return program.stages().contains(ShaderPackProgramSet.Stage.VERTEX) && program.stages().contains(ShaderPackProgramSet.Stage.FRAGMENT);
    }

    private static Map<ShaderPackProgramResolver.TerrainProgram, List<String>> createTerrainFallbacks() {
        EnumMap<ShaderPackProgramResolver.TerrainProgram, List<String>> result = new EnumMap<>(ShaderPackProgramResolver.TerrainProgram.class);
        result.put(
            ShaderPackProgramResolver.TerrainProgram.SOLID,
            List.of("gbuffers_terrain_solid", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
        );
        result.put(
            ShaderPackProgramResolver.TerrainProgram.CUTOUT,
            List.of("gbuffers_terrain_cutout", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
        );
        result.put(
            ShaderPackProgramResolver.TerrainProgram.TRANSLUCENT,
            List.of("gbuffers_water", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
        );
        return Map.copyOf(result);
    }

    public enum TerrainProgram {
        SOLID("gbuffers_terrain_solid"),
        CUTOUT("gbuffers_terrain_cutout"),
        TRANSLUCENT("gbuffers_water");

        private final String fileBase;

        TerrainProgram(final String fileBase) {
            this.fileBase = fileBase;
        }

        public String fileBase() {
            return this.fileBase;
        }
    }

    public record ResolvedProgram(
        String requestedName,
        String resolvedName,
        boolean direct,
        ShaderPackProgramSet.Program program,
        List<String> fallbackChain
    ) {
    }
}
