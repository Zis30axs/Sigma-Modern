package net.minecraft.client.renderer.shaderpack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShaderPackFinalTransformer {
    private static final Pattern VERSION = Pattern.compile("(?m)^[\\t ]*#\\s*version\\s+[^\\n]*$");
    private static final Pattern EXTENSION = Pattern.compile("(?m)^[\\t ]*#\\s*extension\\s+[^\\n]*$");
    private static final Pattern UNIFORM = Pattern.compile(
        "(?m)^[\\t ]*uniform[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)(?:[\\t ]*\\[[^;\\n]*\\])?[\\t ]*;"
    );
    private static final Pattern VARYING = Pattern.compile(
        "(?m)^[\\t ]*varying[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*;"
    );
    private static final Pattern INPUT = Pattern.compile(
        "(?m)^[\\t ]*(?:layout[\\t ]*\\([^;\\n]*\\)[\\t ]*)?in[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*;"
    );
    private static final Pattern OUTPUT = Pattern.compile(
        "(?m)^[\\t ]*(?:layout[\\t ]*\\([^;\\n]*\\)[\\t ]*)?out[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*;"
    );
    private static final Pattern FRAG_DATA = Pattern.compile("\\bgl_FragData\\s*\\[\\s*([^]\\s]+)\\s*]");
    private static final Pattern GL_TEX_COORD_0_SWIZZLE = Pattern.compile("\\bgl_TexCoord\\s*\\[\\s*0\\s*]\\s*\\.(?:st|xy)\\b");
    private static final Pattern GL_TEX_COORD_0 = Pattern.compile("\\bgl_TexCoord\\s*\\[\\s*0\\s*]");
    private static final Pattern ANY_UNIFORM = Pattern.compile("\\buniform\\b");
    private static final Pattern ANY_VARYING = Pattern.compile("\\bvarying\\b");
    private static final Pattern ANY_ATTRIBUTE = Pattern.compile("\\battribute\\b");
    private static final Pattern ANY_GL_TEX_COORD = Pattern.compile("\\bgl_TexCoord\\b");
    private static final Pattern ANY_TEXTURE_2D = Pattern.compile("\\btexture2D[A-Za-z0-9_]*\\b");

    private ShaderPackFinalTransformer() {
    }

    public static Result transform(final String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Shader final source is empty");
        }

        String normalized = source.replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "");
        String masked = maskComments(normalized);
        if (!Pattern.compile("\\bvoid\\s+main\\s*\\(").matcher(masked).find()) {
            throw new IllegalArgumentException("Shader final source does not define main()");
        }
        if (Pattern.compile("\\bgl_FragDepth\\b").matcher(masked).find()) {
            throw new IllegalArgumentException("final.fsh writes gl_FragDepth, which is not supported by the Sigma final-pass subset");
        }
        if (Pattern.compile("\\b(?:gl_Color|gl_SecondaryColor|gl_FogFragCoord)\\b").matcher(masked).find()) {
            throw new IllegalArgumentException("final.fsh uses unsupported legacy fragment inputs");
        }

        Matcher fragDataMatcher = FRAG_DATA.matcher(masked);
        while (fragDataMatcher.find()) {
            if (!"0".equals(fragDataMatcher.group(1))) {
                throw new IllegalArgumentException("final.fsh writes gl_FragData[" + fragDataMatcher.group(1) + "]; only color target 0 is available");
            }
        }

        List<Edit> declarationEdits = new ArrayList<>();
        List<Replacement> samplerReplacements = new ArrayList<>();
        Set<Integer> colorSamplers = new TreeSet<>();
        boolean depthSampler = false;
        boolean replaceViewWidth = false;
        boolean replaceViewHeight = false;
        boolean replaceAspectRatio = false;

        Matcher uniformMatcher = UNIFORM.matcher(masked);
        while (uniformMatcher.find()) {
            String type = uniformMatcher.group(1);
            String name = uniformMatcher.group(2);
            Integer colorTarget = "sampler2D".equals(type) ? colorSamplerTarget(name) : null;
            if (colorTarget != null) {
                colorSamplers.add(colorTarget);
                declarationEdits.add(new Edit(uniformMatcher.start(), uniformMatcher.end(), ""));
                String canonical = "colortex" + colorTarget;
                if (!canonical.equals(name)) {
                    samplerReplacements.add(new Replacement(name, canonical));
                }
            } else if ("sampler2D".equals(type) && ("depthtex0".equals(name) || "gdepthtex".equals(name))) {
                depthSampler = true;
                declarationEdits.add(new Edit(uniformMatcher.start(), uniformMatcher.end(), ""));
                if (!"depthtex0".equals(name)) {
                    samplerReplacements.add(new Replacement(name, "depthtex0"));
                }
            } else if ("float".equals(type) && "viewWidth".equals(name)) {
                replaceViewWidth = true;
                colorSamplers.add(0);
                declarationEdits.add(new Edit(uniformMatcher.start(), uniformMatcher.end(), ""));
            } else if ("float".equals(type) && "viewHeight".equals(name)) {
                replaceViewHeight = true;
                colorSamplers.add(0);
                declarationEdits.add(new Edit(uniformMatcher.start(), uniformMatcher.end(), ""));
            } else if ("float".equals(type) && "aspectRatio".equals(name)) {
                replaceAspectRatio = true;
                colorSamplers.add(0);
                declarationEdits.add(new Edit(uniformMatcher.start(), uniformMatcher.end(), ""));
            } else {
                throw new IllegalArgumentException("Unsupported final.fsh uniform: " + type + " " + name);
            }
        }

        String inputName = null;
        boolean inputDeclarationInjected = false;
        Matcher varyingMatcher = VARYING.matcher(masked);
        while (varyingMatcher.find()) {
            if (!"vec2".equals(varyingMatcher.group(1))) {
                throw new IllegalArgumentException("Unsupported final.fsh varying type: " + varyingMatcher.group(1));
            }
            if (inputName != null && !inputName.equals(varyingMatcher.group(2))) {
                throw new IllegalArgumentException("final.fsh uses more than one fragment varying; the final-pass subset supports one vec2 input");
            }
            inputName = varyingMatcher.group(2);
            declarationEdits.add(new Edit(varyingMatcher.start(), varyingMatcher.end(), "in vec2 " + inputName + ";"));
        }

        Matcher inputMatcher = INPUT.matcher(masked);
        while (inputMatcher.find()) {
            if (!"vec2".equals(inputMatcher.group(1))) {
                throw new IllegalArgumentException("Unsupported final.fsh input type: " + inputMatcher.group(1));
            }
            if (inputName != null && !inputName.equals(inputMatcher.group(2))) {
                throw new IllegalArgumentException("final.fsh uses more than one fragment input; the final-pass subset supports one vec2 input");
            }
            inputName = inputMatcher.group(2);
        }

        String outputName = null;
        Matcher outputMatcher = OUTPUT.matcher(masked);
        while (outputMatcher.find()) {
            if (!"vec4".equals(outputMatcher.group(1))) {
                throw new IllegalArgumentException("Unsupported final.fsh output type: " + outputMatcher.group(1));
            }
            if (outputName != null && !outputName.equals(outputMatcher.group(2))) {
                throw new IllegalArgumentException("final.fsh declares more than one color output; only color target 0 is available");
            }
            outputName = outputMatcher.group(2);
        }

        boolean usesLegacyTexCoord = GL_TEX_COORD_0.matcher(masked).find();
        if (inputName == null && usesLegacyTexCoord) {
            inputName = "sigmaTexCoord";
            inputDeclarationInjected = true;
        }
        if (outputName == null) {
            outputName = "sigmaFragColor";
        }

        String body = applyEdits(normalized, declarationEdits);
        body = VERSION.matcher(body).replaceAll("");
        List<String> extensions = new ArrayList<>();
        Matcher extensionMatcher = EXTENSION.matcher(body);
        while (extensionMatcher.find()) {
            extensions.add(extensionMatcher.group().trim());
        }
        body = EXTENSION.matcher(body).replaceAll("");
        for (Replacement replacement : samplerReplacements) {
            body = replaceWord(body, replacement.from(), replacement.to());
        }
        body = body.replaceAll("\\btexture2DLod\\b", "textureLod");
        body = body.replaceAll("\\btexture2D\\b", "texture");
        body = body.replaceAll("\\bgl_FragColor\\b", Matcher.quoteReplacement(outputName));
        body = body.replaceAll("\\bgl_FragData\\s*\\[\\s*0\\s*]", Matcher.quoteReplacement(outputName));

        if (inputName != null) {
            body = GL_TEX_COORD_0_SWIZZLE.matcher(body).replaceAll(Matcher.quoteReplacement(inputName));
            body = GL_TEX_COORD_0.matcher(body).replaceAll(Matcher.quoteReplacement("vec4(" + inputName + ", 0.0, 1.0)"));
        }
        if (replaceAspectRatio) {
            body = replaceWord(body, "aspectRatio", "(float(textureSize(colortex0, 0).x) / float(textureSize(colortex0, 0).y))");
        }
        if (replaceViewWidth) {
            body = replaceWord(body, "viewWidth", "float(textureSize(colortex0, 0).x)");
        }
        if (replaceViewHeight) {
            body = replaceWord(body, "viewHeight", "float(textureSize(colortex0, 0).y)");
        }

        String transformedMasked = maskComments(body);
        if (ANY_UNIFORM.matcher(transformedMasked).find()) {
            throw new IllegalArgumentException("final.fsh contains a uniform declaration outside the supported subset");
        }
        if (ANY_VARYING.matcher(transformedMasked).find()) {
            throw new IllegalArgumentException("final.fsh contains an unsupported varying declaration");
        }
        if (ANY_ATTRIBUTE.matcher(transformedMasked).find()) {
            throw new IllegalArgumentException("final.fsh contains an attribute declaration, which is not valid for the final fragment stage");
        }
        if (ANY_GL_TEX_COORD.matcher(transformedMasked).find()) {
            throw new IllegalArgumentException("final.fsh uses an unsupported gl_TexCoord form");
        }
        Matcher oldTextureFunction = ANY_TEXTURE_2D.matcher(transformedMasked);
        if (oldTextureFunction.find()) {
            throw new IllegalArgumentException("Unsupported legacy texture function in final.fsh: " + oldTextureFunction.group());
        }
        if (Pattern.compile("\\bgl_FragData\\b").matcher(transformedMasked).find()) {
            throw new IllegalArgumentException("final.fsh contains an unsupported gl_FragData access");
        }

        StringBuilder declarations = new StringBuilder();
        for (int colorSampler : colorSamplers) {
            declarations.append("uniform sampler2D colortex").append(colorSampler).append(";\n");
        }
        if (depthSampler) {
            declarations.append("uniform sampler2D depthtex0;\n");
        }
        if (inputDeclarationInjected) {
            declarations.append("in vec2 ").append(inputName).append(";\n");
        }
        if (!Pattern.compile("(?m)^[\\t ]*(?:layout[\\t ]*\\([^;\\n]*\\)[\\t ]*)?out[\\t ]+vec4[\\t ]+" + Pattern.quote(outputName) + "[\\t ]*;")
            .matcher(maskComments(body)).find()) {
            declarations.append("out vec4 ").append(outputName).append(";\n");
        }

        String extensionPreamble = extensions.isEmpty() ? "" : String.join("\n", extensions) + "\n";
        String fragment = "#version 330\n" + extensionPreamble + declarations + body.stripLeading();
        StringBuilder vertex = new StringBuilder("#version 330\n");
        if (inputName != null) {
            vertex.append("out vec2 ").append(inputName).append(";\n");
        }
        vertex.append("void main() {\n")
            .append("    vec2 sigmaUv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);\n")
            .append("    gl_Position = vec4(sigmaUv * vec2(2.0) + vec2(-1.0), 0.0, 1.0);\n");
        if (inputName != null) {
            vertex.append("    ").append(inputName).append(" = sigmaUv;\n");
        }
        vertex.append("}\n");

        return new Result(vertex.toString(), fragment, List.copyOf(colorSamplers), depthSampler);
    }

    private static Integer colorSamplerTarget(final String name) {
        if (name.startsWith("colortex")) {
            try {
                int target = Integer.parseInt(name.substring("colortex".length()));
                return target >= 0 && target < 8 ? target : null;
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

    private static String replaceWord(final String source, final String word, final String replacement) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(source).replaceAll(Matcher.quoteReplacement(replacement));
    }

    private static String applyEdits(final String source, final List<Edit> edits) {
        if (edits.isEmpty()) {
            return source;
        }
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder builder = new StringBuilder(source);
        for (Edit edit : edits) {
            builder.replace(edit.start(), edit.end(), edit.replacement());
        }
        return builder.toString();
    }

    private static String maskComments(final String source) {
        StringBuilder masked = new StringBuilder(source);
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                } else {
                    masked.setCharAt(i, ' ');
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    masked.setCharAt(i, ' ');
                    masked.setCharAt(i + 1, ' ');
                    i++;
                    blockComment = false;
                } else if (c != '\n') {
                    masked.setCharAt(i, ' ');
                }
                continue;
            }
            if (c == '/' && next == '/') {
                masked.setCharAt(i, ' ');
                masked.setCharAt(i + 1, ' ');
                i++;
                lineComment = true;
            } else if (c == '/' && next == '*') {
                masked.setCharAt(i, ' ');
                masked.setCharAt(i + 1, ' ');
                i++;
                blockComment = true;
            }
        }
        return masked.toString();
    }

    public record Result(String vertexSource, String fragmentSource, List<Integer> colorSamplers, boolean depthSampler) {
    }

    private record Edit(int start, int end, String replacement) {
    }

    private record Replacement(String from, String to) {
    }
}
