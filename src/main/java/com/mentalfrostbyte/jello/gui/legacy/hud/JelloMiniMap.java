package com.mentalfrostbyte.jello.gui.legacy.hud;

import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.map.ChunkColours;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Jello's minimap: the ground of the 10 x 10 chunks around you as a 150-pixel square in the corner under the TabGUI, turning
 * so the way you face is up, with an arrow for you in the middle (a second, fainter one under it shows the way you are
 * moving) and a soft edge all round.
 *
 * <p>Each column of a chunk is coloured by the block at the top of it: its map colour, white under snow, the lava colour
 * under lava, water's for anything waterlogged, and a shade lighter or darker where the column next to it to the north or south
 * is open air, which is what gives the ground its relief. A chunk is coloured once, when it is there and the chunks either
 * side of it (north and south, whose columns the shading looks at) are too; at most a few are done in a frame. The
 * picture is one 160 x 160 texture rebuilt about twenty times a second from the coloured chunks.</p>
 */
final class JelloMiniMap {
    static final int SIZE = 150;
    private static final int CHUNKS = 10;
    private static final int PIXELS = CHUNKS * 16;
    /** Pixels of map per block: the old client drew the 160 blocks across 225 pixels. */
    private static final float ZOOM = 225.0F / PIXELS;
    private static final int UNKNOWN = 0xFF8A8A8A;
    private static final int PER_PASS = 4;
    private static final long REBUILD_NANOS = 50_000_000L;
    private static final Identifier ID = Identifier.withDefaultNamespace("sigma/minimap");

    private static final Map<Long, int[]> COLOURS = new HashMap<>();
    private static final Map<Long, Boolean> SETTLED = new HashMap<>();
    private static @Nullable MapTexture texture;
    private static long lastBuild;
    private static int builtX = Integer.MIN_VALUE;
    private static int builtZ = Integer.MIN_VALUE;
    private static boolean dirty;
    private static @Nullable ClientLevel level;

    private JelloMiniMap() {}

    /** A texture the map can be smoothed across when it is turned. */
    private static final class MapTexture extends DynamicTexture {
        MapTexture() {
            super("SigmaModern minimap", PIXELS, PIXELS, true);
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }

    static void reset() {
        COLOURS.clear();
        SETTLED.clear();
        builtX = Integer.MIN_VALUE;
        level = null;
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(ID);
            texture = null;
        }
    }

    /** Draws the map with its top-left at {@code (x, y)}. */
    static void render(final LegacyCanvas c, final Minecraft mc, final int x, final int y) {
        LocalPlayer player = mc.player;
        ClientLevel world = mc.level;
        if (player == null || world == null) {
            return;
        }
        if (world != level) {
            reset();
            level = world;
        }

        ChunkPos at = player.chunkPosition();
        long now = System.nanoTime();
        boolean moved = at.x() != builtX || at.z() != builtZ;
        if (texture == null) {
            texture = new MapTexture();
            mc.getTextureManager().register(ID, texture);
            moved = true;
        }
        if (moved || now - lastBuild > REBUILD_NANOS) {
            lastBuild = now;
            update(world, at);
            if (moved || dirty) {
                compose(at);
                builtX = at.x();
                builtZ = at.z();
                dirty = false;
            }
        }

        // Where the player is on the picture, in map pixels from its top-left corner.
        float u = (float) (player.getX() - (at.x() - CHUNKS / 2) * 16) * ZOOM;
        float v = (float) (player.getZ() - (at.z() - CHUNKS / 2) * 16) * ZOOM;
        float centreX = x + SIZE / 2.0F;
        float centreY = y + SIZE / 2.0F;

        c.fill(x, y, x + SIZE, y + SIZE, UNKNOWN);
        c.scissor(x, y, x + SIZE, y + SIZE);
        try {
            c.push();
            try {
                c.translate(centreX, centreY);
                // The picture has +Z down and +X right; turned so that the way the player faces ends up at the top.
                c.graphics().pose().rotate((float) (Math.PI - Math.toRadians(player.getYRot())));
                c.translate(-u, -v);
                c.image(ID, PIXELS, PIXELS, 0, 0, PIXELS * ZOOM, PIXELS * ZOOM, 0xFFFFFFFF);
            } finally {
                c.pop();
            }
        } finally {
            c.unscissor();
        }

        // The player: an arrow for the way they are moving (fainter, and dropped a little, under the main one) and one for
        // the way they face - which is straight up, since the map turns with them.
        double vx = player.getDeltaMovement().x;
        double vz = player.getDeltaMovement().z;
        float moving = vx * vx + vz * vz > 1.0E-4 ? (float) Math.toDegrees(Math.atan2(-vx, vz)) - player.getYRot() : 0.0F;
        arrow(c, centreX, centreY + 3, moving, 0x1C000000);
        arrow(c, centreX, centreY, moving, 0xFFFEFEFE);

        c.innerFeather(x, y, SIZE, SIZE, 23.0F, 0.75F);
        c.outerGlow(x, y, SIZE, SIZE, 8.0F, 0.7F);
    }

    private static void arrow(final LegacyCanvas c, final float cx, final float cy, final float degrees, final int colour) {
        c.push();
        try {
            c.translate(cx, cy);
            c.graphics().pose().rotate((float) Math.toRadians(degrees));
            float w = c.textWidth(Face.JELLO_MEDIUM, 20.0F, "^");
            c.text(Face.JELLO_MEDIUM, 20.0F, "^", -w / 2.0F, -8.0F, colour);
        } finally {
            c.pop();
        }
    }

    // ------------------------------------------------------------------ colouring

    private static void update(final ClientLevel world, final ChunkPos at) {
        for (Iterator<Map.Entry<Long, int[]>> it = COLOURS.entrySet().iterator(); it.hasNext(); ) {
            ChunkPos pos = ChunkPos.unpack(it.next().getKey());
            if (Math.max(Math.abs(pos.x() - at.x()), Math.abs(pos.z() - at.z())) > 7) {
                SETTLED.remove(pos.pack());
                it.remove();
                dirty = true;
            }
        }

        int done = 0;
        for (int dx = -CHUNKS / 2; dx < CHUNKS / 2 && done < PER_PASS; dx++) {
            for (int dz = -CHUNKS / 2; dz < CHUNKS / 2 && done < PER_PASS; dz++) {
                int cx = at.x() + dx;
                int cz = at.z() + dz;
                long key = new ChunkPos(cx, cz).pack();
                boolean settled = Boolean.TRUE.equals(SETTLED.get(key));
                if (settled) {
                    continue;
                }

                LevelChunk chunk = world.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }

                // The shading reads the column north and south, which can lie in the next chunk: wait until those are in, then
                // colour once more to settle.
                boolean neighbours = world.getChunkSource().getChunkNow(cx, cz + 1) != null && world.getChunkSource().getChunkNow(cx, cz - 1) != null;
                if (COLOURS.containsKey(key) && !neighbours) {
                    continue;
                }

                COLOURS.put(key, ChunkColours.of(world, chunk));
                SETTLED.put(key, neighbours);
                dirty = true;
                done++;
            }
        }
    }

    /** Lays the coloured chunks out on the texture, the window's north-west chunk at its top-left. */
    private static void compose(final ChunkPos at) {
        MapTexture target = texture;
        if (target == null) {
            return;
        }

        for (int cz = 0; cz < CHUNKS; cz++) {
            for (int cx = 0; cx < CHUNKS; cx++) {
                long key = new ChunkPos(at.x() - CHUNKS / 2 + cx, at.z() - CHUNKS / 2 + cz).pack();
                int[] colours = COLOURS.get(key);
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int argb = colours == null ? UNKNOWN : colours[z * 16 + x];
                        target.getPixels().setPixel(cx * 16 + x, cz * 16 + z, argb);
                    }
                }
            }
        }

        target.upload();
    }
}
