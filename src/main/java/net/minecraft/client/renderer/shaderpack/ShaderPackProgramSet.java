package net.minecraft.client.renderer.shaderpack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class ShaderPackProgramSet {
    private final List<ShaderPackProgramSet.Program> programs;

    private ShaderPackProgramSet(final List<ShaderPackProgramSet.Program> programs) {
        this.programs = List.copyOf(programs);
    }

    public static ShaderPackProgramSet discover(final ShaderPackSource source) throws IOException {
        Map<String, EnumSet<ShaderPackProgramSet.Stage>> stagesByProgram = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String file : source.files()) {
            ShaderPackProgramSet.Stage stage = ShaderPackProgramSet.Stage.fromFile(file).orElse(null);
            if (stage == null) {
                continue;
            }

            String name = file.substring(0, file.length() - stage.extension.length());
            stagesByProgram.computeIfAbsent(name, key -> EnumSet.noneOf(ShaderPackProgramSet.Stage.class)).add(stage);
        }

        List<ShaderPackProgramSet.Program> programs = new ArrayList<>(stagesByProgram.size());
        stagesByProgram.forEach((name, stages) -> programs.add(new ShaderPackProgramSet.Program(name, Set.copyOf(stages))));
        return new ShaderPackProgramSet(programs);
    }

    public List<ShaderPackProgramSet.Program> programs() {
        return this.programs;
    }

    public Optional<ShaderPackProgramSet.Program> find(final String name) {
        return this.programs.stream().filter(program -> program.name.equalsIgnoreCase(name)).findFirst();
    }

    public long renderableProgramCount() {
        return this.programs.stream().filter(ShaderPackProgramSet.Program::isRenderable).count();
    }

    public record Program(String name, Set<ShaderPackProgramSet.Stage> stages) {
        public boolean isRenderable() {
            return this.stages.contains(ShaderPackProgramSet.Stage.COMPUTE)
                || this.stages.contains(ShaderPackProgramSet.Stage.VERTEX) && this.stages.contains(ShaderPackProgramSet.Stage.FRAGMENT);
        }
    }

    public enum Stage {
        VERTEX(".vsh"),
        FRAGMENT(".fsh"),
        GEOMETRY(".gsh"),
        COMPUTE(".csh"),
        TESS_CONTROL(".tcs"),
        TESS_EVALUATION(".tes");

        private final String extension;

        Stage(final String extension) {
            this.extension = extension;
        }

        private static Optional<ShaderPackProgramSet.Stage> fromFile(final String file) {
            for (ShaderPackProgramSet.Stage stage : values()) {
                if (file.endsWith(stage.extension)) {
                    return Optional.of(stage);
                }
            }

            return Optional.empty();
        }
    }
}
