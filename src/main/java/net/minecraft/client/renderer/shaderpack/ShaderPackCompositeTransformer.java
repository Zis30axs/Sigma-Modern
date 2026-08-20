package net.minecraft.client.renderer.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShaderPackCompositeTransformer {
    private static final Pattern VERSION = Pattern.compile("(?m)^[\\t ]*#\\s*version\\s+[^\\n]*$");
    private static final Pattern EXTENSION = Pattern.compile("(?m)^[\\t ]*#\\s*extension\\s+[^\\n]*$");
    private static final Pattern UNIFORM = Pattern.compile("(?m)^[\\t ]*uniform[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*;");
    private static final Pattern VARYING = Pattern.compile("(?m)^[\\t ]*varying[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*;");
    private static final Pattern INPUT = Pattern.compile("(?m)^[\\t ]*in[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*;");
    private static final Pattern OUTPUT = Pattern.compile("(?m)^[\\t ]*(?:layout[\\t ]*\\([^;\\n]*\\)[\\t ]*)?out[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*;");
    private static final Pattern FRAG_DATA = Pattern.compile("\\bgl_FragData\\s*\\[\\s*([0-9]+)\\s*]");
    private static final Pattern DRAWBUFFERS = Pattern.compile("(?i)DRAWBUFFERS\\s*:\\s*([0-7]+)");
    private static final Pattern RENDERTARGETS = Pattern.compile("(?i)RENDERTARGETS\\s*:\\s*([0-7](?:[\\t ]*,[\\t ]*[0-7])*)");
    private static final Pattern TEXCOORD = Pattern.compile("\\bgl_TexCoord\\s*\\[\\s*0\\s*]");

    private ShaderPackCompositeTransformer() {
    }

    public static Result transform(final String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Composite fragment source is empty");
        }
        String source = normalize(input);
        String code = maskComments(source);
        if (!Pattern.compile("\\bvoid\\s+main\\s*\\(").matcher(code).find()) {
            throw new IllegalArgumentException("Composite fragment source does not define main()");
        }
        reject(code, "gl_FragDepth", "Composite pass writes gl_FragDepth");
        reject(code, "attribute", "Composite pass contains a legacy attribute");
        reject(code, "gl_Color", "Composite pass uses unsupported gl_Color");
        reject(code, "gl_SecondaryColor", "Composite pass uses unsupported gl_SecondaryColor");
        reject(code, "gl_FogFragCoord", "Composite pass uses unsupported gl_FogFragCoord");

        List<Integer> colorTargets = parseColorTargets(source);
        Set<Integer> colorSamplers = new TreeSet<>();
        Set<Integer> depthSamplers = new TreeSet<>();
        List<Replacement> replacements = new ArrayList<>();
        List<Range> removals = new ArrayList<>();
        boolean viewWidth = false;
        boolean viewHeight = false;
        boolean aspectRatio = false;

        Matcher uniforms = UNIFORM.matcher(code);
        while (uniforms.find()) {
            String type = uniforms.group(1);
            String name = uniforms.group(2);
            Integer color = "sampler2D".equals(type) ? colorSampler(name) : null;
            Integer depth = "sampler2D".equals(type) ? depthSampler(name) : null;
            if (color != null) {
                colorSamplers.add(color);
                replacements.add(new Replacement(name, "colortex" + color));
            } else if (depth != null) {
                depthSamplers.add(depth);
                replacements.add(new Replacement(name, "depthtex" + depth));
            } else if ("float".equals(type) && "viewWidth".equals(name)) {
                viewWidth = true;
                colorSamplers.add(0);
            } else if ("float".equals(type) && "viewHeight".equals(name)) {
                viewHeight = true;
                colorSamplers.add(0);
            } else if ("float".equals(type) && "aspectRatio".equals(name)) {
                aspectRatio = true;
                colorSamplers.add(0);
            } else {
                throw new IllegalArgumentException("Unsupported composite uniform: " + type + " " + name);
            }
            removals.add(new Range(uniforms.start(), uniforms.end()));
        }

        String texCoord = null;
        Matcher varyings = VARYING.matcher(code);
        while (varyings.find()) {
            if (!"vec2".equals(varyings.group(1)) || texCoord != null && !texCoord.equals(varyings.group(2))) {
                throw new IllegalArgumentException("Composite subset supports one vec2 fragment input");
            }
            texCoord = varyings.group(2);
        }
        Matcher modernInputs = INPUT.matcher(code);
        while (modernInputs.find()) {
            if (!"vec2".equals(modernInputs.group(1)) || texCoord != null && !texCoord.equals(modernInputs.group(2))) {
                throw new IllegalArgumentException("Composite subset supports one vec2 fragment input");
            }
            texCoord = modernInputs.group(2);
        }
        if (texCoord == null && TEXCOORD.matcher(code).find()) {
            texCoord = "sigmaTexCoord";
        }

        String explicitOutput = null;
        Matcher outputs = OUTPUT.matcher(code);
        while (outputs.find()) {
            if (!"vec4".equals(outputs.group(1)) || explicitOutput != null && !explicitOutput.equals(outputs.group(2))) {
                throw new IllegalArgumentException("Composite subset supports at most one explicit vec4 output");
            }
            explicitOutput = outputs.group(2);
        }
        if (explicitOutput != null && colorTargets.size() != 1) {
            throw new IllegalArgumentException("Explicit composite output requires one draw buffer");
        }

        Set<Integer> outputLocations = new LinkedHashSet<>();
        Matcher fragData = FRAG_DATA.matcher(code);
        while (fragData.find()) {
            int location = Integer.parseInt(fragData.group(1));
            if (location >= colorTargets.size()) {
                throw new IllegalArgumentException("gl_FragData[" + location + "] exceeds configured draw buffers");
            }
            outputLocations.add(location);
        }
        boolean fragColor = Pattern.compile("\\bgl_FragColor\\b").matcher(code).find();
        if (fragColor) {
            if (colorTargets.size() != 1) {
                throw new IllegalArgumentException("gl_FragColor requires one draw buffer");
            }
            outputLocations.add(0);
        }
        if (explicitOutput != null) {
            outputLocations.add(0);
        }
        if (outputLocations.isEmpty()) {
            throw new IllegalArgumentException("Composite pass has no supported color output");
        }

        source = removeRanges(source, removals);
        if (texCoord != null) {
            source = VARYING.matcher(source).replaceAll(Matcher.quoteReplacement("in vec2 " + texCoord + ";"));
        }
        source = VERSION.matcher(source).replaceAll("");
        List<String> extensions = new ArrayList<>();
        Matcher ext = EXTENSION.matcher(source);
        while (ext.find()) {
            extensions.add(ext.group().trim());
        }
        source = EXTENSION.matcher(source).replaceAll("");
        for (Replacement replacement : replacements) {
            source = replaceWord(source, replacement.from(), replacement.to());
        }
        source = source.replaceAll("\\btexture2DLod\\b", "textureLod")
            .replaceAll("\\btexture2DProj\\b", "textureProj")
            .replaceAll("\\btexture2DGrad\\b", "textureGrad")
            .replaceAll("\\btexture2D\\b", "texture");
        if (texCoord != null) {
            source = Pattern.compile("\\bgl_TexCoord\\s*\\[\\s*0\\s*]\\s*\\.(?:st|xy)\\b").matcher(source).replaceAll(Matcher.quoteReplacement(texCoord));
            source = TEXCOORD.matcher(source).replaceAll(Matcher.quoteReplacement("vec4(" + texCoord + ",0.0,1.0)"));
        }
        if (aspectRatio) {
            source = replaceWord(source, "aspectRatio", "(float(textureSize(colortex0,0).x)/float(textureSize(colortex0,0).y))");
        }
        if (viewWidth) {
            source = replaceWord(source, "viewWidth", "float(textureSize(colortex0,0).x)");
        }
        if (viewHeight) {
            source = replaceWord(source, "viewHeight", "float(textureSize(colortex0,0).y)");
        }
        if (explicitOutput == null) {
            if (fragColor) {
                source = source.replaceAll("\\bgl_FragColor\\b", "sigmaFragColor0");
            }
            Matcher matcher = FRAG_DATA.matcher(source);
            StringBuffer rewritten = new StringBuffer();
            while (matcher.find()) {
                matcher.appendReplacement(rewritten, "sigmaFragColor" + matcher.group(1));
            }
            matcher.appendTail(rewritten);
            source = rewritten.toString();
        }

        String after = maskComments(source);
        if (Pattern.compile("\\buniform\\b|\\bvarying\\b|\\battribute\\b|\\bgl_Frag(?:Color|Data)\\b|\\bgl_TexCoord\\b|\\btexture2D[A-Za-z0-9_]*\\b").matcher(after).find()) {
            throw new IllegalArgumentException("Composite pass still contains unsupported legacy GLSL after transformation");
        }

        StringBuilder declarations = new StringBuilder();
        for (int sampler : colorSamplers) {
            declarations.append("uniform sampler2D colortex").append(sampler).append(";\n");
        }
        for (int sampler : depthSamplers) {
            declarations.append("uniform sampler2D depthtex").append(sampler).append(";\n");
        }
        if (texCoord != null && !Pattern.compile("(?m)^\\s*in\\s+vec2\\s+" + Pattern.quote(texCoord) + "\\s*;").matcher(after).find()) {
            declarations.append("in vec2 ").append(texCoord).append(";\n");
        }
        if (explicitOutput == null) {
            for (int location : outputLocations) {
                declarations.append("layout(location=").append(location).append(") out vec4 sigmaFragColor").append(location).append(";\n");
            }
        }

        String fragment = "#version 330\n" + (extensions.isEmpty() ? "" : String.join("\n", extensions) + "\n") + declarations + source.stripLeading();
        StringBuilder vertex = new StringBuilder("#version 330\n");
        if (texCoord != null) {
            vertex.append("out vec2 ").append(texCoord).append(";\n");
        }
        vertex.append("void main(){vec2 u=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(u*2.0-1.0,0.0,1.0);");
        if (texCoord != null) {
            vertex.append(texCoord).append("=u;");
        }
        vertex.append("}\n");
        return new Result(vertex.toString(), fragment, colorTargets, List.copyOf(colorSamplers), List.copyOf(depthSamplers));
    }

    static List<Integer> parseColorTargets(final String source) {
        Matcher draw = DRAWBUFFERS.matcher(source);
        int drawPos = -1;
        String drawValue = null;
        while (draw.find()) {
            drawPos = draw.start();
            drawValue = draw.group(1);
        }
        Matcher render = RENDERTARGETS.matcher(source);
        int renderPos = -1;
        String renderValue = null;
        while (render.find()) {
            renderPos = render.start();
            renderValue = render.group(1);
        }
        List<Integer> result = new ArrayList<>();
        if (renderPos > drawPos && renderValue != null) {
            for (String value : renderValue.split(",")) {
                result.add(Integer.parseInt(value.strip()));
            }
        } else if (drawValue != null) {
            for (char value : drawValue.toCharArray()) {
                result.add(Character.digit(value, 10));
            }
        } else {
            result.add(0);
        }
        if (new LinkedHashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException("Composite draw buffers contain duplicate logical targets");
        }
        return List.copyOf(result);
    }

    private static Integer colorSampler(final String name) {
        if (name.startsWith("colortex")) {
            try {
                int index = Integer.parseInt(name.substring(8));
                return index >= 0 && index < 8 ? index : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return switch (name) {
            case "gcolor" -> 0;
            case "gdepth" -> 1;
            case "gnormal" -> 2;
            case "composite" -> 3;
            case "gaux1" -> 4;
            case "gaux2" -> 5;
            case "gaux3" -> 6;
            case "gaux4" -> 7;
            default -> null;
        };
    }

    private static Integer depthSampler(final String name) {
        return switch (name) {
            case "depthtex0", "gdepthtex" -> 0;
            case "depthtex1" -> 1;
            case "depthtex2" -> 2;
            default -> null;
        };
    }

    private static void reject(final String code, final String symbol, final String message) {
        if (Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b").matcher(code).find()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String removeRanges(final String source, final List<Range> ranges) {
        StringBuilder result = new StringBuilder(source);
        ranges.sort((a, b) -> Integer.compare(b.start(), a.start()));
        for (Range range : ranges) {
            result.delete(range.start(), range.end());
        }
        return result.toString();
    }

    private static String replaceWord(final String source, final String from, final String to) {
        if (from.equals(to)) {
            return source;
        }
        return Pattern.compile("\\b" + Pattern.quote(from) + "\\b").matcher(source).replaceAll(Matcher.quoteReplacement(to));
    }

    private static String normalize(final String source) {
        return source.replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "");
    }

    private static String maskComments(final String source) {
        StringBuilder masked = new StringBuilder(source);
        boolean line = false;
        boolean block = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char n = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (line) {
                if (c == '\n') line = false; else masked.setCharAt(i, ' ');
            } else if (block) {
                if (c == '*' && n == '/') {
                    masked.setCharAt(i, ' ');
                    masked.setCharAt(++i, ' ');
                    block = false;
                } else if (c != '\n') {
                    masked.setCharAt(i, ' ');
                }
            } else if (c == '/' && n == '/') {
                masked.setCharAt(i, ' ');
                masked.setCharAt(++i, ' ');
                line = true;
            } else if (c == '/' && n == '*') {
                masked.setCharAt(i, ' ');
                masked.setCharAt(++i, ' ');
                block = true;
            }
        }
        return masked.toString();
    }

    public record Result(String vertexSource, String fragmentSource, List<Integer> colorTargets, List<Integer> colorSamplers, List<Integer> depthSamplers) {}
    private record Replacement(String from, String to) {}
    private record Range(int start, int end) {}
}
