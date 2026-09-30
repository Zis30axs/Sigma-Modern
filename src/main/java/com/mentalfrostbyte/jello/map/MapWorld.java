package com.mentalfrostbyte.jello.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;

/**
 * Which world the maps are of: a singleplayer world by its name, a server by its address - the old client's way of
 * telling them apart. {@link #key} is the folder the world's maps and waypoints live in (under {@code local} or
 * {@code server}); {@link #name} is what the Maps page shows.
 */
public record MapWorld(String key, String name) {
    private static final int LONGEST = 64;

    public static MapWorld of(final Minecraft mc) {
        IntegratedServer local = mc.getSingleplayerServer();
        if (local != null) {
            String level = local.getWorldData().getLevelName();
            return new MapWorld("local/" + safe(level), "local - " + level);
        }

        ServerData server = mc.getCurrentServer();
        if (server != null) {
            return new MapWorld("server/" + safe(server.ip), "server - " + server.ip);
        }

        return new MapWorld("local/local", "local - local");
    }

    /**
     * {@code raw} as one folder name: letters, digits and {@code . _ -} stay (in any script, so a world called 新世界 keeps its
     * name), everything else - the {@code :} of an address, slashes, spaces - becomes {@code _}. Never empty, never a
     * folder's own {@code .} or {@code ..}, no trailing dot (Windows drops it), at most 64 characters.
     */
    static String safe(final String raw) {
        StringBuilder out = new StringBuilder();
        raw.codePoints().limit(LONGEST).forEach(cp -> {
            boolean plain = Character.isLetterOrDigit(cp) || cp == '.' || cp == '_' || cp == '-';
            out.appendCodePoint(plain ? cp : '_');
        });

        String name = out.toString();
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1) + "_";
        }

        return name.isEmpty() || name.startsWith(".") ? "_" + name : name;
    }

    /** A dimension's folder: {@code minecraft:the_nether} is {@code the_nether}; another namespace is kept in front. */
    static String dimensionFolder(final String dimension) {
        String plain = dimension.startsWith("minecraft:") ? dimension.substring("minecraft:".length()) : dimension;
        return safe(plain.replace(':', '_'));
    }
}
