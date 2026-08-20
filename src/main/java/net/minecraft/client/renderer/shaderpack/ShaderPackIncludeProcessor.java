package net.minecraft.client.renderer.shaderpack;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShaderPackIncludeProcessor {
    private static final Pattern INCLUDE_DIRECTIVE = Pattern.compile(
        "^\\s*#\\s*include\\s+(?:\\\"([^\\\"]+)\\\"|<([^>]+)>|([^\\s]+))\\s*(?://.*)?$"
    );
    private final ShaderPackSource source;
    private final Map<String, String> expandedSources = new HashMap<>();

    public ShaderPackIncludeProcessor(final ShaderPackSource source) {
        this.source = source;
    }

    public String expand(final String shaderPath) throws IOException {
        return this.expand(this.normalizePath("", shaderPath), new ArrayDeque<>());
    }

    private String expand(final String shaderPath, final Deque<String> activeIncludes) throws IOException {
        String cached = this.expandedSources.get(shaderPath);
        if (cached != null) {
            return cached;
        }

        if (activeIncludes.contains(shaderPath)) {
            List<String> cycle = new ArrayList<>(activeIncludes);
            cycle.add(shaderPath);
            throw new IOException("Shader #include cycle detected: " + String.join(" -> ", cycle));
        }

        Optional<String> sourceText = this.source.readText(shaderPath);
        if (sourceText.isEmpty()) {
            throw new IOException("Shader include not found: " + shaderPath);
        }

        activeIncludes.addLast(shaderPath);
        try {
            String[] lines = sourceText.get().split("\\R", -1);
            StringBuilder expanded = new StringBuilder(sourceText.get().length());
            String parent = parentOf(shaderPath);

            for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
                String line = lines[lineNumber];
                Matcher matcher = INCLUDE_DIRECTIVE.matcher(line);
                if (matcher.matches()) {
                    String target = firstNonNull(matcher.group(1), matcher.group(2), matcher.group(3));
                    String includePath = target.startsWith("/") ? this.normalizePath("", target.substring(1)) : this.normalizePath(parent, target);
                    expanded.append(this.expand(includePath, activeIncludes));
                } else {
                    if (line.stripLeading().startsWith("#include")) {
                        throw new IOException("Malformed #include directive in " + shaderPath + ":" + (lineNumber + 1) + ": " + line.trim());
                    }

                    expanded.append(line);
                }

                if (lineNumber < lines.length - 1) {
                    expanded.append('\n');
                }
            }

            String result = expanded.toString();
            this.expandedSources.put(shaderPath, result);
            return result;
        } finally {
            activeIncludes.removeLast();
        }
    }

    private String normalizePath(final String baseDirectory, final String path) throws IOException {
        String candidate = path.replace('\\', '/');
        if (candidate.indexOf('\u0000') >= 0) {
            throw new IOException("Shader include path contains a NUL character");
        }

        String combined = baseDirectory.isEmpty() ? candidate : baseDirectory + "/" + candidate;
        String[] segments = combined.split("/");
        List<String> normalized = new ArrayList<>(segments.length);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }

            if (segment.equals("..")) {
                if (normalized.isEmpty()) {
                    throw new IOException("Shader include escapes shaders root: " + path);
                }
                normalized.removeLast();
            } else {
                normalized.add(segment);
            }
        }

        if (normalized.isEmpty()) {
            throw new IOException("Shader include does not name a file: " + path);
        }

        return String.join("/", normalized);
    }

    private static String parentOf(final String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String firstNonNull(final String first, final String second, final String third) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        return third;
    }
}
