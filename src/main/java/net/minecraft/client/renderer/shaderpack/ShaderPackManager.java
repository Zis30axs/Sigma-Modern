package net.minecraft.client.renderer.shaderpack;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
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
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class ShaderPackManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CONFIG_FILE = "optionsshaders.txt";
    private static final String SELECTED_PACK_KEY = "shaderPack";
    private static final String OPENGL_BACKEND = "OpenGL";
    private static final String SHADER_ROOT = "shaders/";
    private final Path shaderPackDirectory;
    private final Path configFile;
    private @Nullable String selectedPack;

    public ShaderPackManager(final Path gameDirectory) {
        this.shaderPackDirectory = gameDirectory.resolve("shaderpacks");
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

        Path selected = this.shaderPackDirectory.resolve(this.selectedPack);
        return this.isValidShaderPack(selected) ? Optional.of(selected) : Optional.empty();
    }

    public boolean isSelected(final String packName) {
        return packName.equals(this.selectedPack);
    }

    public boolean canUseShaders() {
        GpuDevice device = RenderSystem.tryGetDevice();
        return device != null && OPENGL_BACKEND.equals(device.getDeviceInfo().backendName());
    }

    public String backendName() {
        GpuDevice device = RenderSystem.tryGetDevice();
        return device == null ? "Unavailable" : device.getDeviceInfo().backendName();
    }

    public boolean isValidShaderPack(final String packName) {
        return this.isValidShaderPack(this.shaderPackDirectory.resolve(packName));
    }

    public boolean select(final @Nullable String packName) {
        if (packName == null || packName.isBlank()) {
            this.selectedPack = null;
            this.save();
            return true;
        }

        if (!this.canUseShaders()) {
            return false;
        }

        Path packPath = this.shaderPackDirectory.resolve(packName);
        if (!this.isValidShaderPack(packPath)) {
            return false;
        }

        this.selectedPack = packName;
        this.save();
        return true;
    }

    public void ensureDirectory() {
        try {
            Files.createDirectories(this.shaderPackDirectory);
        } catch (IOException exception) {
            LOGGER.warn("Failed to create shader pack directory {}", this.shaderPackDirectory, exception);
        }
    }

    private boolean isValidShaderPack(final Path path) {
        if (Files.isDirectory(path)) {
            return Files.isDirectory(path.resolve("shaders"));
        }

        if (!Files.isRegularFile(path) || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return false;
        }

        try (ZipFile zip = new ZipFile(path.toFile())) {
            return zip.stream().anyMatch(entry -> entry.getName().startsWith(SHADER_ROOT) && entry.getName().length() > SHADER_ROOT.length());
        } catch (IOException exception) {
            LOGGER.warn("Failed to inspect shader pack {}", path, exception);
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
