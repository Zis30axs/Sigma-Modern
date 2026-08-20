package net.minecraft.client.renderer.shaderpack;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

public final class ShaderPackSource implements AutoCloseable {
    private static final String SHADER_ROOT = "shaders/";
    private final Path packPath;
    private final @Nullable Path directoryRoot;
    private final @Nullable ZipFile archive;

    private ShaderPackSource(final Path packPath, final @Nullable Path directoryRoot, final @Nullable ZipFile archive) {
        this.packPath = packPath;
        this.directoryRoot = directoryRoot;
        this.archive = archive;
    }

    public static ShaderPackSource open(final Path packPath) throws IOException {
        if (Files.isDirectory(packPath)) {
            Path root = packPath.resolve("shaders").normalize();
            if (!Files.isDirectory(root)) {
                throw new IOException("Shader pack has no shaders directory: " + packPath);
            }

            return new ShaderPackSource(packPath, root, null);
        }

        if (!Files.isRegularFile(packPath)) {
            throw new IOException("Shader pack does not exist: " + packPath);
        }

        ZipFile archive = new ZipFile(packPath.toFile());
        boolean containsShaders = archive.stream()
            .anyMatch(entry -> !entry.isDirectory() && entry.getName().startsWith(SHADER_ROOT) && entry.getName().length() > SHADER_ROOT.length());
        if (!containsShaders) {
            archive.close();
            throw new IOException("Shader pack has no shaders directory: " + packPath);
        }

        return new ShaderPackSource(packPath, null, archive);
    }

    public Path packPath() {
        return this.packPath;
    }

    public List<String> files() throws IOException {
        if (this.directoryRoot != null) {
            try (Stream<Path> files = Files.walk(this.directoryRoot)) {
                return files.filter(Files::isRegularFile)
                    .map(this.directoryRoot::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace(File.separatorChar, '/'))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            }
        }

        if (this.archive != null) {
            return this.archive.stream()
                .filter(entry -> !entry.isDirectory() && entry.getName().startsWith(SHADER_ROOT))
                .map(ZipEntry::getName)
                .map(name -> name.substring(SHADER_ROOT.length()))
                .filter(name -> !name.isEmpty())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        }

        return List.of();
    }

    public boolean contains(final String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        if (this.directoryRoot != null) {
            return Files.isRegularFile(this.directoryRoot.resolve(normalized).normalize());
        }

        return this.archive != null && this.archive.getEntry(SHADER_ROOT + normalized) != null;
    }

    public Optional<String> readText(final String relativePath) throws IOException {
        String normalized = normalizeRelativePath(relativePath);
        if (this.directoryRoot != null) {
            Path target = this.directoryRoot.resolve(normalized).normalize();
            if (!target.startsWith(this.directoryRoot) || !Files.isRegularFile(target)) {
                return Optional.empty();
            }

            return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
        }

        if (this.archive != null) {
            ZipEntry entry = this.archive.getEntry(SHADER_ROOT + normalized);
            if (entry == null || entry.isDirectory()) {
                return Optional.empty();
            }

            try (InputStream input = this.archive.getInputStream(entry)) {
                return Optional.of(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        return Optional.empty();
    }

    private static String normalizeRelativePath(final String relativePath) {
        String slashPath = relativePath.replace('\\', '/');
        Path normalizedPath = Path.of(slashPath).normalize();
        if (normalizedPath.isAbsolute() || normalizedPath.startsWith("..")) {
            throw new IllegalArgumentException("Shader path escapes pack root: " + relativePath);
        }

        String normalized = normalizedPath.toString().replace(File.separatorChar, '/');
        if (normalized.isEmpty() || normalized.equals(".")) {
            throw new IllegalArgumentException("Shader path must name a file");
        }

        return normalized;
    }

    @Override
    public void close() throws IOException {
        if (this.archive != null) {
            this.archive.close();
        }
    }
}
