package com.mentalfrostbyte.jello.cape;

import java.util.Locale;

/**
 * The rules for a cape PNG the user drops into {@code sigma5/capes/}, kept free of game classes so they can be
 * tested on their own.
 *
 * <p>A cape texture has the vanilla 64x32 layout: the outer face at (1, 1), the lining at (12, 1), the elytra
 * at (22, 0). The model reads it by fraction, so any image twice as wide as it is tall works - 128x64,
 * 512x256 and so on draw the same layout in more detail. Anything else would land the wrong part of the image
 * on the cape, so it is skipped rather than stretched.</p>
 */
public final class CapeFiles {

    /** Saved ids of user capes start with this; built-in capes have bare ids. */
    public static final String CUSTOM_PREFIX = "file:";

    private CapeFiles() {
    }

    /** True for a file name the library should try to load. */
    public static boolean isCapeFile(final String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".png") && fileName.length() > 4 && !fileName.startsWith(".");
    }

    /** The widest cape accepted; past this a stray photo would just eat video memory. */
    public static final int MAX_WIDTH = 4096;

    /** True when an image of this size has the cape layout: 2:1, at least the vanilla 64x32, at most {@link #MAX_WIDTH}. */
    public static boolean isValidSize(final int width, final int height) {
        return width >= 64 && width <= MAX_WIDTH && width == height * 2;
    }

    /** The id a user cape is saved under: its file name, so it survives the folder being re-read in another order. */
    public static String idFor(final String fileName) {
        return CUSTOM_PREFIX + fileName;
    }

    public static boolean isCustomId(final String id) {
        return id.startsWith(CUSTOM_PREFIX);
    }

    /** The name shown for a user cape: its file name without the extension. */
    public static String displayName(final String fileName) {
        int dot = fileName.lastIndexOf('.');
        String name = dot > 0 ? fileName.substring(0, dot) : fileName;
        return name.isBlank() ? fileName : name;
    }

    /**
     * A texture path for a user cape. Resource paths only allow {@code [a-z0-9/._-]}, and two files can sanitise
     * to the same text ({@code "Red Cape.png"} and {@code "red_cape.png"}), so the file's position in the list
     * is part of the path too.
     */
    public static String texturePath(final int index, final String fileName) {
        StringBuilder out = new StringBuilder("capes/custom/").append(index).append('_');
        String name = displayName(fileName).toLowerCase(Locale.ROOT);
        for (int i = 0; i < name.length() && i < 48; i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            out.append(ok ? c : '_');
        }
        return out.toString();
    }
}
