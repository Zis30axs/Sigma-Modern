package com.mentalfrostbyte.jello.gui.modern;

import java.io.IOException;
import java.io.InputStream;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The bundled warning SVG as a reusable white coverage texture for world name tags. */
public final class SuspectNameTagTexture {
    public static final String RESOURCE = "/assets/minecraft/sigma/icons/nametags/suspect.svg";
    private static final Logger LOGGER = LoggerFactory.getLogger("Sigma/NameTagIcon");
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("sigma/nametags/suspect");
    private static boolean attempted;
    private static boolean available;

    private SuspectNameTagTexture() {}

    /** Called on the render thread. One 36px raster serves every 9px world label, in all client modes. */
    public static @Nullable Identifier texture() {
        if (!attempted) {
            attempted = true;
            // Only this trusted classpath asset is accepted; SVG locations never come from player or server text.
            try (InputStream input = SuspectNameTagTexture.class.getResourceAsStream(RESOURCE)) {
                if (input == null) {
                    LOGGER.warn("Missing bundled name tag icon {}", RESOURCE);
                } else {
                    ModernSkiaRaster.upload(TEXTURE, ModernSvg.rasterize(input.readAllBytes(), 36, 36));
                    available = true;
                }
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("Could not load bundled name tag icon {}", RESOURCE, failure);
            }
        }
        return available ? TEXTURE : null;
    }
}
