package net.minecraft.client.renderer.shaderpack;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class ShaderPackManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CONFIG_FILE = "optionsshaders.txt";
    private static final String SELECTED_PACK_KEY = "shaderPack";
    private final Path shaderPackDirectory;
    private final Path configFile;
    private @Nullable String selectedPack;

    public ShaderPackManager(final Path gameDirectory) {
        this.shaderPackDirectory = gameDirectory.resolve("shaderpacks").normalize();
        this.configFile = gameDirectory.resolve(CONFIG_FILE);
        this.ensureDirectory();
        this.load();
    }

    public Path shaderPackDirectory() {
        return this.shaderPackDirectory;
    }

    public List<String> discoverPacks() {
        this.ensureDirectory();

        try (Stream<Path> files = Files.list(this.shaderPackDirectory)) {
            return files.filter(path -> Files.isDirectory(path) || path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                .map(path -> path.getFileName().toString())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        } catch (IOException exception) {
            LOGGER.warn("Failed to scan shader pack directory {}", this.shaderPackDirectory, exception);
            return List.of();
        }
    }

    public Optional<String> selectedPack() {
        return Optional.ofNullable(this.selectedPack);
    }

    public Optional<Path> selectedPackPath() {
        if (this.selectedPack == null) {
            return Optional.empty();
        }

        return this.resolvePackPath(this.selectedPack).filter(this::isValidShaderPack);
    }

    public Optional<ShaderPackSource> openSelectedPack() {
        if (this.selectedPack == null) {
            return Optional.empty();
        }

        return this.openPack(this.selectedPack);
    }

    public Optional<ShaderPackSource> openPack(final String packName) {
        Optional<Path> packPath = this.resolvePackPath(packName);
        if (packPath.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(ShaderPackSource.open(packPath.get()));
        } catch (IOException exception) {
            LOGGER.warn("Failed to open shader pack {}", packPath.get(), exception);
            return Optional.empty();
        }
    }

    public Optional<ShaderPackProgramSet> inspectPrograms(final String packName) {
        Optional<Path> packPath = this.resolvePackPath(packName);
        if (packPath.isEmpty()) {
            return Optional.empty();
        }

        try (ShaderPackSource source = ShaderPackSource.open(packPath.get())) {
            return Optional.of(ShaderPackProgramSet.discover(source));
        } catch (IOException exception) {
            LOGGER.warn("Failed to inspect shader programs in {}", packPath.get(), exception);
            return Optional.empty();
        }
    }

    public Optional<String> preprocessShader(final String packName, final String shaderPath) {
        Optional<Path> packPath = this.resolvePackPath(packName);
        if (packPath.isEmpty()) {
            return Optional.empty();
        }

        try (ShaderPackSource source = ShaderPackSource.open(packPath.get())) {
            return Optional.of(new ShaderPackIncludeProcessor(source).expand(shaderPath));
        } catch (IOException exception) {
            LOGGER.warn("Failed to preprocess shader {} from {}", shaderPath, packPath.get(), exception);
            return Optional.empty();
        }
    }

    public boolean isSelected(final String packName) {
        return packName.equals(this.selectedPack);
    }

    public ShaderPackBackend backend() {
        return ShaderPackBackend.current();
    }

    public boolean canUseShaders() {
        return this.backend().supportsCustomShaderPipelines();
    }

    public String backendName() {
        return this.backend().displayName();
    }

    public boolean isValidShaderPack(final String packName) {
        return this.resolvePackPath(packName).filter(this::isValidShaderPack).isPresent();
    }

    public boolean select(final @Nullable String packName) {
        if (packName == null || packName.isBlank()) {
            this.selectedPack = null;
            this.save();
            ShaderPackRuntime.invalidate();
            return true;
        }

        if (!this.canUseShaders()) {
            return false;
        }

        Optional<Path> packPath = this.resolvePackPath(packName);
        if (packPath.isEmpty() || !this.isValidShaderPack(packPath.get())) {
            return false;
        }

        this.selectedPack = packName;
        this.save();
        ShaderPackRuntime.invalidate();
        return true;
    }

    public void ensureDirectory() {
        try {
            Files.createDirectories(this.shaderPackDirectory);
        } catch (IOException exception) {
            LOGGER.warn("Failed to create shader pack directory {}", this.shaderPackDirectory, exception);
        }
    }

    private Optional<Path> resolvePackPath(final String packName) {
        Path resolved = this.shaderPackDirectory.resolve(packName).normalize();
        return resolved.startsWith(this.shaderPackDirectory) && !resolved.equals(this.shaderPackDirectory) ? Optional.of(resolved) : Optional.empty();
    }

    private boolean isValidShaderPack(final Path path) {
        try (ShaderPackSource ignored = ShaderPackSource.open(path)) {
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private void load() {
        if (!Files.isRegularFile(this.configFile)) {
            return;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(this.configFile)) {
            properties.load(input);
            String configuredPack = properties.getProperty(SELECTED_PACK_KEY, "").trim();
            this.selectedPack = configuredPack.isEmpty() ? null : configuredPack;
        } catch (IOException exception) {
            LOGGER.warn("Failed to read shader settings from {}", this.configFile, exception);
        }
    }

    private void save() {
        Properties properties = new Properties();
        properties.setProperty(SELECTED_PACK_KEY, this.selectedPack == null ? "" : this.selectedPack);

        try (OutputStream output = Files.newOutputStream(this.configFile)) {
            properties.store(output, "Sigma shader settings");
        } catch (IOException exception) {
            LOGGER.warn("Failed to save shader settings to {}", this.configFile, exception);
        }
    }
}
