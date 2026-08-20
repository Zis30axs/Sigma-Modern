package net.minecraft.client.renderer.shaderpack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShaderPackTerrainTransformer {
    private static final Pattern VERSION = Pattern.compile("(?m)^[\\t ]*#\\s*version\\s+[^\\n]*$");
    private static final Pattern EXTENSION = Pattern.compile("(?m)^[\\t ]*#\\s*extension\\s+[^\\n]*$");
    private static final Pattern UNIFORM = Pattern.compile("\\buniform[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*;");
    private static final Pattern VARYING = Pattern.compile("\\bvarying[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*;");
    private static final Pattern VERTEX_INPUT = Pattern.compile("\\b(?:attribute|in)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*;");
    private static final Pattern FRAGMENT_OUTPUT = Pattern.compile("\\bout[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*;");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(");
    private static final Pattern FRAG_DATA = Pattern.compile("\\bgl_FragData\\s*\\[\\s*([^]\\s]+)\\s*]");
    private static final Pattern DRAWBUFFERS = Pattern.compile("(?i)DRAWBUFFERS\\s*:\\s*([0-9]+)");
    private static final Pattern RENDERTARGETS = Pattern.compile("(?i)RENDERTARGETS\\s*:\\s*([0-9,\\t ]+)");
    private static final Set<String> ALLOWED_GL = Set.of("gl_Position", "gl_FragCoord", "gl_FrontFacing", "gl_PointCoord", "gl_VertexID", "gl_InstanceID");
    private static final String TERRAIN_VERTEX = "vec4(Position + (ChunkPosition - CameraBlockPos) + CameraOffset, 1.0)";
    private static final String LIGHTMAP_MATRIX = "mat4(vec4(0.00390625,0,0,0),vec4(0,0.00390625,0,0),vec4(0,0,0.00390625,0),vec4(0.03125,0.03125,0.03125,1))";
    private static final String VERTEX_HEADER = """
        layout(std140) uniform Globals { ivec3 CameraBlockPos; vec3 CameraOffset; vec2 ScreenSize; float GlintAlpha; float GameTime; int MenuBlurRadius; int UseRgss; };
        layout(std140) uniform Fog { vec4 FogColor; float FogEnvironmentalStart; float FogEnvironmentalEnd; float FogRenderDistanceStart; float FogRenderDistanceEnd; float FogSkyEnd; float FogCloudsEnd; };
        layout(std140) uniform Projection { mat4 ProjMat; };
        layout(std140) uniform ChunkSection { mat4 ModelViewMat; float ChunkVisibility; ivec2 TextureSize; ivec3 ChunkPosition; };
        uniform sampler2D Sampler0;
        uniform sampler2D Sampler2;
        in vec3 Position;
        in vec4 Color;
        in vec2 UV0;
        in ivec2 UV2;
        """;
    private static final String FRAGMENT_HEADER = """
        layout(std140) uniform Globals { ivec3 CameraBlockPos; vec3 CameraOffset; vec2 ScreenSize; float GlintAlpha; float GameTime; int MenuBlurRadius; int UseRgss; };
        layout(std140) uniform Fog { vec4 FogColor; float FogEnvironmentalStart; float FogEnvironmentalEnd; float FogRenderDistanceStart; float FogRenderDistanceEnd; float FogSkyEnd; float FogCloudsEnd; };
        layout(std140) uniform Projection { mat4 ProjMat; };
        layout(std140) uniform ChunkSection { mat4 ModelViewMat; float ChunkVisibility; ivec2 TextureSize; ivec3 ChunkPosition; };
        uniform sampler2D Sampler0;
        uniform sampler2D Sampler2;
        """;

    private ShaderPackTerrainTransformer() {
    }

    public static Result transform(final String vertexSource, final String fragmentSource, final AlphaMode alphaMode) {
        if (vertexSource == null || vertexSource.isBlank() || fragmentSource == null || fragmentSource.isBlank()) {
            throw new IllegalArgumentException("Terrain program requires vertex and fragment GLSL");
        }

        String rawVertex = normalize(vertexSource);
        String rawFragment = normalize(fragmentSource);
        validateTargets(rawFragment);
        BuiltIns builtIns = BuiltIns.detect(rawVertex, rawFragment);
        String vertex = transformStage(rawVertex, true, builtIns);
        String fragment = transformStage(rawFragment, false, builtIns);
        FragmentOutput output = resolveOutput(fragment);
        fragment = renameMain(output.source(), "sigma_pack_fragment_main")
            + "\nvoid main(){ sigma_pack_fragment_main(); " + alphaMode.discard(output.name()) + " }\n";
        vertex = renameMain(vertex, "sigma_pack_vertex_main") + builtIns.vertexWrapper();

        String vertexResult = assemble(vertex, VERTEX_HEADER + builtIns.vertexDeclarations());
        String fragmentResult = assemble(fragment, FRAGMENT_HEADER + builtIns.fragmentDeclarations());
        validateResult(vertexResult, true);
        validateResult(fragmentResult, false);
        return new Result(vertexResult, fragmentResult);
    }

    private static String transformStage(final String raw, final boolean vertexStage, final BuiltIns builtIns) {
        String source = raw;
        String masked = maskComments(source);
        if (!MAIN.matcher(masked).find()) {
            throw new IllegalArgumentException((vertexStage ? "vertex" : "fragment") + " shader has no main()");
        }

        List<Edit> edits = new ArrayList<>();
        Map<String, String> replacements = new HashMap<>();
        Matcher uniforms = UNIFORM.matcher(masked);
        while (uniforms.find()) {
            String replacement = mapUniform(uniforms.group(1), uniforms.group(2));
            if (replacement == null) {
                throw new IllegalArgumentException("Unsupported terrain uniform: " + uniforms.group(1) + " " + uniforms.group(2));
            }
            edits.add(new Edit(uniforms.start(), uniforms.end(), ""));
            replacements.put(uniforms.group(2), replacement);
        }

        if (vertexStage) {
            Matcher inputs = VERTEX_INPUT.matcher(masked);
            while (inputs.find()) {
                String replacement = mapVertexInput(inputs.group(1), inputs.group(2));
                if (replacement == null) {
                    throw new IllegalArgumentException("Unsupported terrain vertex input: " + inputs.group(1) + " " + inputs.group(2));
                }
                edits.add(new Edit(inputs.start(), inputs.end(), ""));
                replacements.put(inputs.group(2), replacement);
            }
        }

        Matcher varyings = VARYING.matcher(masked);
        while (varyings.find()) {
            edits.add(new Edit(varyings.start(), varyings.end(), (vertexStage ? "out " : "in ") + varyings.group(1) + " " + varyings.group(2) + ";"));
        }
        source = applyEdits(source, edits);
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            source = replaceMappedIdentifier(source, entry.getKey(), entry.getValue());
        }

        if (vertexStage) {
            source = source.replaceAll("\\bftransform\\s*\\(\\s*\\)", Matcher.quoteReplacement("(ProjMat * ModelViewMat * " + TERRAIN_VERTEX + ")"));
            source = replaceIdentifier(source, "gl_ModelViewProjectionMatrix", "(ProjMat * ModelViewMat)");
            source = replaceIdentifier(source, "gl_ModelViewMatrix", "ModelViewMat");
            source = replaceIdentifier(source, "gl_ProjectionMatrix", "ProjMat");
            source = source.replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*0\\s*]", "mat4(1.0)");
            source = source.replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*[12]\\s*]", Matcher.quoteReplacement(LIGHTMAP_MATRIX));
            source = replaceIdentifier(source, "gl_Vertex", TERRAIN_VERTEX);
            source = replaceIdentifier(source, "gl_Color", "Color");
            source = replaceIdentifier(source, "gl_MultiTexCoord0", "vec4(UV0,0,1)");
            source = replaceIdentifier(source, "gl_MultiTexCoord1", "vec4(vec2(UV2),0,1)");
            source = replaceIdentifier(source, "gl_MultiTexCoord2", "vec4(vec2(UV2),0,1)");
            source = builtIns.replaceVertex(source);
        } else {
            source = builtIns.replaceFragment(source);
        }

        source = source.replaceAll("\\btexture2DLod\\b", "textureLod");
        source = source.replaceAll("\\btexture2DProj\\b", "textureProj");
        source = source.replaceAll("\\btexture2DGrad\\b", "textureGrad");
        source = source.replaceAll("\\btexture2D\\b", "texture");
        return source;
    }

    private static String mapUniform(final String type, final String name) {
        if ("sampler2D".equals(type)) {
            return switch (name) {
                case "texture", "gtexture" -> "Sampler0";
                case "lightmap" -> "Sampler2";
                default -> null;
            };
        }
        return switch (name) {
            case "viewWidth" -> "float".equals(type) ? "ScreenSize.x" : null;
            case "viewHeight" -> "float".equals(type) ? "ScreenSize.y" : null;
            case "aspectRatio" -> "float".equals(type) ? "(ScreenSize.x / ScreenSize.y)" : null;
            case "cameraPosition" -> "vec3".equals(type) ? "(vec3(CameraBlockPos) - CameraOffset)" : null;
            case "gbufferModelView", "modelViewMatrix" -> "mat4".equals(type) ? "ModelViewMat" : null;
            case "gbufferModelViewInverse", "modelViewMatrixInverse" -> "mat4".equals(type) ? "inverse(ModelViewMat)" : null;
            case "gbufferProjection", "projectionMatrix" -> "mat4".equals(type) ? "ProjMat" : null;
            case "gbufferProjectionInverse", "projectionMatrixInverse" -> "mat4".equals(type) ? "inverse(ProjMat)" : null;
            default -> null;
        };
    }

    private static String mapVertexInput(final String type, final String name) {
        return switch (name) {
            case "vaPosition" -> "vec3".equals(type) ? "Position" : "vec4".equals(type) ? "vec4(Position,1)" : null;
            case "vaColor" -> "vec4".equals(type) ? "Color" : null;
            case "vaUV0" -> "vec2".equals(type) ? "UV0" : null;
            case "vaUV2" -> "vec2".equals(type) ? "vec2(UV2)" : "ivec2".equals(type) ? "UV2" : null;
            default -> null;
        };
    }

    private static FragmentOutput resolveOutput(String source) {
        String masked = maskComments(source);
        Matcher fragData = FRAG_DATA.matcher(masked);
        while (fragData.find()) {
            if (!"0".equals(fragData.group(1))) {
                throw new IllegalArgumentException("Terrain shader writes gl_FragData[" + fragData.group(1) + "]; only target 0 is available");
            }
        }
        Matcher outputs = FRAGMENT_OUTPUT.matcher(masked);
        String outputName = null;
        while (outputs.find()) {
            if (!"vec4".equals(outputs.group(1)) || outputName != null) {
                throw new IllegalArgumentException("Terrain shader must have at most one vec4 color output");
            }
            outputName = outputs.group(2);
        }
        if (outputName == null) {
            outputName = "sigmaFragColor";
            source = "out vec4 sigmaFragColor;\n" + source;
        }
        source = replaceIdentifier(source, "gl_FragColor", outputName);
        source = source.replaceAll("\\bgl_FragData\\s*\\[\\s*0\\s*]", Matcher.quoteReplacement(outputName));
        return new FragmentOutput(source, outputName);
    }

    private static void validateTargets(final String fragment) {
        Matcher drawBuffers = DRAWBUFFERS.matcher(fragment);
        while (drawBuffers.find()) {
            if (!drawBuffers.group(1).chars().allMatch(c -> c == '0')) {
                throw new IllegalArgumentException("Unsupported DRAWBUFFERS:" + drawBuffers.group(1));
            }
        }
        Matcher renderTargets = RENDERTARGETS.matcher(fragment);
        while (renderTargets.find()) {
            for (String target : renderTargets.group(1).split(",")) {
                if (!target.isBlank() && !"0".equals(target.strip())) {
                    throw new IllegalArgumentException("Unsupported RENDERTARGETS:" + renderTargets.group(1).strip());
                }
            }
        }
    }

    private static String assemble(final String source, final String header) {
        StringBuilder extensions = new StringBuilder();
        Matcher matcher = EXTENSION.matcher(source);
        while (matcher.find()) {
            extensions.append(matcher.group()).append('\n');
        }
        String body = EXTENSION.matcher(VERSION.matcher(source).replaceAll("")).replaceAll("");
        return "#version 330\n" + extensions + header + body.stripLeading();
    }

    private static void validateResult(final String source, final boolean vertexStage) {
        String masked = maskComments(source);
        if (Pattern.compile("\\battribute\\b").matcher(masked).find()) {
            throw new IllegalArgumentException("Legacy attribute remains after terrain transformation");
        }
        if (Pattern.compile("\\b(?:texture1D|texture2D|texture3D|textureCube)\\b").matcher(masked).find()) {
            throw new IllegalArgumentException("Legacy texture function remains after terrain transformation");
        }
        Matcher gl = Pattern.compile("\\bgl_[A-Za-z_][A-Za-z0-9_]*\\b").matcher(masked);
        while (gl.find()) {
            if (!ALLOWED_GL.contains(gl.group())) {
                throw new IllegalArgumentException("Unsupported legacy GLSL symbol: " + gl.group());
            }
        }
        if (vertexStage && Pattern.compile("\\b(?:mc_Entity|mc_midTexCoord|at_tangent|at_midBlock|vaNormal|gl_Normal)\\b").matcher(masked).find()) {
            throw new IllegalArgumentException("Shader requires extended terrain vertex attributes");
        }
    }

    private static String renameMain(final String source, final String replacement) {
        String masked = maskComments(source);
        Matcher matcher = MAIN.matcher(masked);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Shader has no main()");
        }
        int start = masked.indexOf("main", matcher.start());
        return source.substring(0, start) + replacement + source.substring(start + 4);
    }

    private static String replaceMappedIdentifier(final String source, final String identifier, final String replacement) {
        if ("texture".equals(identifier)) {
            return Pattern.compile("\\btexture\\b(?!\\s*\\()")
                .matcher(source)
                .replaceAll(Matcher.quoteReplacement(replacement));
        }
        return replaceIdentifier(source, identifier, replacement);
    }

    private static String replaceIdentifier(final String source, final String identifier, final String replacement) {
        return Pattern.compile("\\b" + Pattern.quote(identifier) + "\\b").matcher(source).replaceAll(Matcher.quoteReplacement(replacement));
    }

    private static String normalize(final String source) {
        return source.replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "");
    }

    private static String applyEdits(final String source, final List<Edit> edits) {
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder builder = new StringBuilder(source);
        for (Edit edit : edits) {
            builder.replace(edit.start(), edit.end(), edit.replacement());
        }
        return builder.toString();
    }

    private static String maskComments(final String source) {
        StringBuilder result = new StringBuilder(source);
        boolean line = false;
        boolean block = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (line) {
                if (c == '\n') line = false; else result.setCharAt(i, ' ');
            } else if (block) {
                if (c == '*' && next == '/') { result.setCharAt(i, ' '); result.setCharAt(++i, ' '); block = false; }
                else if (c != '\n') result.setCharAt(i, ' ');
            } else if (c == '/' && next == '/') {
                result.setCharAt(i, ' '); result.setCharAt(++i, ' '); line = true;
            } else if (c == '/' && next == '*') {
                result.setCharAt(i, ' '); result.setCharAt(++i, ' '); block = true;
            }
        }
        return result.toString();
    }

    public enum AlphaMode {
        SOLID, CUTOUT, TRANSLUCENT;

        private String discard(final String output) {
            return switch (this) {
                case SOLID -> "";
                case CUTOUT -> "if (" + output + ".a < 0.5) discard;";
                case TRANSLUCENT -> "if (" + output + ".a < 0.1) discard;";
            };
        }
    }

    public record Result(String vertexSource, String fragmentSource) {
    }

    private record Edit(int start, int end, String replacement) {
    }

    private record FragmentOutput(String source, String name) {
    }

    private static final class BuiltIns {
        private final Set<Integer> texCoords;
        private final boolean frontColor;

        private BuiltIns(final Set<Integer> texCoords, final boolean frontColor) {
            this.texCoords = Set.copyOf(texCoords);
            this.frontColor = frontColor;
        }

        static BuiltIns detect(final String vertex, final String fragment) {
            Set<Integer> texCoords = new HashSet<>();
            String combined = maskComments(vertex) + "\n" + maskComments(fragment);
            Matcher matcher = Pattern.compile("\\bgl_TexCoord\\s*\\[\\s*([0-9]+)\\s*]").matcher(combined);
            while (matcher.find()) {
                int index = Integer.parseInt(matcher.group(1));
                if (index > 2) {
                    throw new IllegalArgumentException("Unsupported gl_TexCoord index " + index);
                }
                texCoords.add(index);
            }
            boolean color = Pattern.compile("\\bgl_FrontColor\\b").matcher(combined).find()
                || Pattern.compile("\\bgl_Color\\b").matcher(maskComments(fragment)).find();
            return new BuiltIns(texCoords, color);
        }

        String replaceVertex(String source) {
            for (int index : this.texCoords) {
                source = source.replaceAll("\\bgl_TexCoord\\s*\\[\\s*" + index + "\\s*]", "sigmaTexCoord" + index);
            }
            return this.frontColor ? replaceIdentifier(source, "gl_FrontColor", "sigmaFrontColor") : source;
        }

        String replaceFragment(String source) {
            for (int index : this.texCoords) {
                source = source.replaceAll("\\bgl_TexCoord\\s*\\[\\s*" + index + "\\s*]", "sigmaTexCoord" + index);
            }
            return this.frontColor ? replaceIdentifier(source, "gl_Color", "sigmaFrontColor") : source;
        }

        String vertexDeclarations() {
            StringBuilder result = new StringBuilder();
            this.texCoords.stream().sorted().forEach(index -> result.append("out vec4 sigmaTexCoord").append(index).append(";\n"));
            if (this.frontColor) result.append("out vec4 sigmaFrontColor;\n");
            return result.toString();
        }

        String fragmentDeclarations() {
            StringBuilder result = new StringBuilder();
            this.texCoords.stream().sorted().forEach(index -> result.append("in vec4 sigmaTexCoord").append(index).append(";\n"));
            if (this.frontColor) result.append("in vec4 sigmaFrontColor;\n");
            return result.toString();
        }

        String vertexWrapper() {
            StringBuilder result = new StringBuilder("\nvoid main(){\n");
            for (int index : this.texCoords.stream().sorted().toList()) {
                result.append("sigmaTexCoord").append(index).append(" = ")
                    .append(index == 0 ? "vec4(UV0,0,1)" : "vec4(vec2(UV2),0,1)").append(";\n");
            }
            if (this.frontColor) result.append("sigmaFrontColor = Color;\n");
            return result.append("sigma_pack_vertex_main();\n}\n").toString();
        }
    }
}
