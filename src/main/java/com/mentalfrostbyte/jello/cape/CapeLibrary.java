package com.mentalfrostbyte.jello.cape;

import com.mentalfrostbyte.Client;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.logging.LogUtils;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Every cape the Cape module can put on: Sigma's painted ones first, then the PNGs in {@code sigma5/capes/}.
 *
 * <p>Textures are made the first time anything asks, on the render thread (both callers - the player renderer
 * and the ClickGUI panel - already run there), and stay until {@link #reload()} releases them and reads the
 * folder again. A design that fails to paint, or a file that isn't a cape, is logged and left out rather than
 * taking the whole list with it.</p>
 */
public final class CapeLibrary {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAMESPACE = "sigma";

    /**
     * One cape. {@code asset} is what goes into a {@code PlayerSkin}; {@code texture} is the same texture for
     * drawing it flat in a GUI.
     */
    public record Entry(String id, String name, boolean custom, Identifier texture, ClientAsset.Texture asset) {}

    private static List<Entry> entries = List.of();
    private static boolean loaded;

    private CapeLibrary() {
    }

    /** Where the user's own cape PNGs go. */
    public static Path folder() {
        return Client.getInstance().getDirectory().resolve("capes");
    }

    public static List<Entry> all() {
        ensureLoaded();
        return entries;
    }

    /** The cape saved as {@code id}, or the first cape when that one is gone (a deleted file, say). */
    public static @Nullable Entry get(final String id) {
        List<Entry> all = all();
        for (Entry entry : all) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return all.isEmpty() ? null : all.getFirst();
    }

    /** Drops every texture and reads the designs and the folder again. */
    public static void reload() {
        RenderSystem.assertOnRenderThread();
        for (Entry entry : entries) {
            Minecraft.getInstance().getTextureManager().release(entry.texture());
        }
        entries = List.of();
        loaded = false;
        ensureLoaded();
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        loaded = true;
        List<Entry> list = new ArrayList<>();
        loadBuiltIn(list);
        loadCustom(list);
        entries = List.copyOf(list);
        LOGGER.info("Loaded {} capes", entries.size());
    }

    private static void loadBuiltIn(final List<Entry> out) {
        for (CapeDesigns.Design design : CapeDesigns.ALL) {
            try {
                Identifier id = Identifier.fromNamespaceAndPath(NAMESPACE, "capes/builtin/" + design.id());
                register(id, paint(design), true);
                out.add(new Entry(design.id(), design.name(), false, id, new ClientAsset.ResourceTexture(id, id)));
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("Could not paint cape {}", design.id(), e);
            }
        }
    }

    private static void loadCustom(final List<Entry> out) {
        Path dir = folder();
        List<String> names;
        try {
            Files.createDirectories(dir);
            try (Stream<Path> files = Files.list(dir)) {
                names = files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(CapeFiles::isCapeFile)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read cape folder {}", dir, e);
            return;
        }

        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            NativeImage image = null;
            try (InputStream in = Files.newInputStream(dir.resolve(name))) {
                image = NativeImage.read(in);
                if (!CapeFiles.isValidSize(image.getWidth(), image.getHeight())) {
                    LOGGER.warn("Skipping cape {}: it is {}x{}, a cape must be twice as wide as tall (64x32, 128x64, ...)",
                        name, image.getWidth(), image.getHeight());
                    image.close();
                    continue;
                }
                Identifier id = Identifier.fromNamespaceAndPath(NAMESPACE, CapeFiles.texturePath(i, name));
                NativeImage pixels = image;
                image = null;
                register(id, pixels, false);
                out.add(new Entry(CapeFiles.idFor(name), CapeFiles.displayName(name), true, id, new ClientAsset.ResourceTexture(id, id)));
            } catch (IOException | RuntimeException e) {
                if (image != null) {
                    image.close();
                }
                LOGGER.warn("Could not load cape {}", name, e);
            }
        }
    }

    private static void register(final Identifier id, final NativeImage pixels, final boolean smooth) {
        Minecraft.getInstance().getTextureManager().register(id, new CapeTexture(id.toString(), pixels, smooth));
    }

    private static NativeImage paint(final CapeDesigns.Design design) {
        int s = CapeDesigns.SCALE, w = 64 * s, h = 32 * s;
        try (Surface surface = Surface.makeRaster(new ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL));
             Bitmap bitmap = new Bitmap()) {
            CapeDesigns.compose(surface.getCanvas(), design, s);
            ImageInfo info = new ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL);
            if (!bitmap.allocPixels(info) || !surface.readPixels(bitmap, 0, 0)) {
                throw new IllegalStateException("Cannot read cape surface");
            }
            byte[] px = bitmap.readPixels();
            // Skia's RGBA_8888 bytes are NativeImage's RGBA layout already.
            NativeImage image = new NativeImage(w, h, false);
            MemoryUtil.memByteBuffer(image.getPointer(), px.length).put(px);
            return image;
        }
    }

    /**
     * Painted capes are drawn at several texels per cape pixel and look best filtered; a user's pixel-art cape
     * keeps the vanilla nearest sampling so it stays crisp.
     */
    private static final class CapeTexture extends DynamicTexture {
        CapeTexture(final String label, final NativeImage pixels, final boolean smooth) {
            super(() -> "Sigma cape " + label, pixels);
            if (smooth) {
                this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            }
        }
    }
}
