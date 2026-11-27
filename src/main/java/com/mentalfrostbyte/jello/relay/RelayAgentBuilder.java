package com.mentalfrostbyte.jello.relay;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import net.minecraft.client.Minecraft;

/**
 * Compiles and caches the standalone Java-8-compatible ({@code --release 8}, class version 52.0)
 * {@code mc-relay-agent.jar} from bundled classpath sources so {@code Sigma-Modern} can hot-inject
 * into any external vanilla / Forge / Fabric / NeoForge Minecraft JVM (Java 8 through Java 25)
 * without requiring any external {@code agent.jar} or {@code injector.jar}.
 */
public final class RelayAgentBuilder {

    private static final String RESOURCE_ROOT = "/assets/minecraft/sigma/relay-agent/";
    private static final String SOURCES_INDEX = RESOURCE_ROOT + "sources.txt";
    private static final String AGENT_MAIN_CLASS = "com.mcrelay.agent.RelayAgent";

    private static volatile Path cachedJar;

    private RelayAgentBuilder() {
    }

    /** Returns a ready-to-inject {@code mc-relay-agent.jar} on disk, compiling once if not yet cached. */
    public static synchronized Path ensureAgentJar() throws IOException {
        Path existing = cachedJar;
        if (existing != null && Files.isRegularFile(existing)) {
            return existing;
        }

        Map<String, String> sources = loadEmbeddedSources();
        String hash = computeHash(sources).substring(0, 12);
        Path cacheDir = resolveCacheDirectory();
        Files.createDirectories(cacheDir);

        Path targetJar = cacheDir.resolve("mc-relay-agent-" + hash + ".jar");
        if (Files.isRegularFile(targetJar) && Files.size(targetJar) > 1024L) {
            cachedJar = targetJar;
            return targetJar;
        }

        compileAndPackage(sources, targetJar);
        cachedJar = targetJar;
        return targetJar;
    }

    private static Map<String, String> loadEmbeddedSources() throws IOException {
        List<String> relativePaths = new ArrayList<>();
        try (InputStream indexStream = RelayAgentBuilder.class.getResourceAsStream(SOURCES_INDEX)) {
            if (indexStream == null) {
                throw new IOException("Missing bundled relay agent index: " + SOURCES_INDEX);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(indexStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        relativePaths.add(trimmed);
                    }
                }
            }
        }
        if (relativePaths.isEmpty()) {
            throw new IOException("Bundled relay agent source index is empty");
        }

        Map<String, String> sources = new LinkedHashMap<>();
        for (String rel : relativePaths) {
            String resourcePath = RESOURCE_ROOT + rel;
            try (InputStream in = RelayAgentBuilder.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new IOException("Missing bundled relay agent source: " + resourcePath);
                }
                sources.put(rel, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return sources;
    }

    private static void compileAndPackage(final Map<String, String> sources, final Path targetJar) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("JDK JavaCompiler is unavailable (ensure Sigma-Modern runs on a full JDK)");
        }

        Path tempClassesDir = Files.createTempDirectory("sigma-relay-agent-classes-");
        try {
            List<InMemorySourceFile> compilationUnits = new ArrayList<>(sources.size());
            for (Map.Entry<String, String> entry : sources.entrySet()) {
                String className = entry.getKey()
                    .replace('/', '.')
                    .replace('\\', '.');
                if (className.endsWith(".java")) {
                    className = className.substring(0, className.length() - 5);
                }
                compilationUnits.add(new InMemorySourceFile(className, entry.getValue()));
            }

            String nettyClasspath = resolveNettyCompileClasspath();
            StringWriter compilerDiagnostics = new StringWriter();
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
                fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(tempClassesDir.toFile()));
                List<String> options = new ArrayList<>(List.of(
                    "--release", "8",
                    "-encoding", "UTF-8",
                    "-nowarn",
                    "-proc:none"
                ));
                if (!nettyClasspath.isEmpty()) {
                    options.add("-classpath");
                    options.add(nettyClasspath);
                }

                boolean ok = Boolean.TRUE.equals(
                    compiler.getTask(compilerDiagnostics, fileManager, null, options, null, compilationUnits).call()
                );
                if (!ok) {
                    throw new IOException("Failed to compile bundled mc-relay agent:\n" + compilerDiagnostics);
                }
            }

            Manifest manifest = new Manifest();
            Attributes attrs = manifest.getMainAttributes();
            attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            attrs.put(new Attributes.Name("Agent-Class"), AGENT_MAIN_CLASS);
            attrs.put(new Attributes.Name("Premain-Class"), AGENT_MAIN_CLASS);
            attrs.put(new Attributes.Name("Can-Redefine-Classes"), "true");
            attrs.put(new Attributes.Name("Can-Retransform-Classes"), "true");
            attrs.put(new Attributes.Name("Implementation-Title"), "sigma-mc-relay-agent");

            Path tempJar = Files.createTempFile(targetJar.getParent(), "mc-relay-agent-", ".tmp");
            try {
                try (OutputStream out = Files.newOutputStream(tempJar);
                     JarOutputStream jarOut = new JarOutputStream(out, manifest);
                     Stream<Path> walk = Files.walk(tempClassesDir)) {
                    List<Path> classFiles = walk
                        .filter(Files::isRegularFile)
                        .sorted()
                        .toList();
                    for (Path classFile : classFiles) {
                        String entryName = tempClassesDir.relativize(classFile).toString().replace('\\', '/');
                        JarEntry jarEntry = new JarEntry(entryName);
                        jarEntry.setTime(0L);
                        jarOut.putNextEntry(jarEntry);
                        Files.copy(classFile, jarOut);
                        jarOut.closeEntry();
                    }
                }
                Files.move(tempJar, targetJar, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tempJar);
            }
        } finally {
            deleteRecursivelyQuietly(tempClassesDir);
        }
    }

    private static String resolveNettyCompileClasspath() {
        List<String> entries = new ArrayList<>();
        String rawCp = System.getProperty("java.class.path", "");
        for (String part : rawCp.split(File.pathSeparator)) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (lower.contains("netty")) {
                entries.add(part);
            }
        }
        if (entries.isEmpty()) {
            addCodeSourceLocation(entries, io.netty.channel.Channel.class);
            addCodeSourceLocation(entries, io.netty.buffer.ByteBuf.class);
            addCodeSourceLocation(entries, io.netty.handler.codec.ByteToMessageDecoder.class);
            addCodeSourceLocation(entries, io.netty.util.ReferenceCountUtil.class);
        }
        return String.join(File.pathSeparator, entries);
    }

    private static void addCodeSourceLocation(final List<String> entries, final Class<?> type) {
        try {
            if (type.getProtectionDomain() != null && type.getProtectionDomain().getCodeSource() != null) {
                URL url = type.getProtectionDomain().getCodeSource().getLocation();
                if (url != null) {
                    String path = Path.of(url.toURI()).toAbsolutePath().toString();
                    if (!entries.contains(path)) {
                        entries.add(path);
                    }
                }
            }
        } catch (final Exception ignored) {
        }
    }

    private static Path resolveCacheDirectory() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gameDirectory != null) {
            return mc.gameDirectory.toPath().resolve("sigma-jello").resolve("relay");
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "sigma-relay-agent");
    }

    private static String computeHash(final Map<String, String> sources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(AGENT_MAIN_CLASS.getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, String> entry : sources.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void deleteRecursivelyQuietly(final Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (final IOException ignored) {
                }
            });
        } catch (final IOException ignored) {
        }
    }

    private static final class InMemorySourceFile extends SimpleJavaFileObject {
        private final String content;

        InMemorySourceFile(final String className, final String content) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return this.content;
        }
    }
}
