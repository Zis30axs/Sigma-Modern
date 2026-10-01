package com.mentalfrostbyte.jello.map;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What has been seen of one dimension of one world: the colour of every column of every chunk that was loaded while the
 * map was recording, kept in a folder of region files.
 *
 * <p>A region is 8 x 8 chunks and one file ({@code r.<x>.<z>.jmap}): a gzipped header - a magic number, the format and
 * which of the 64 chunks are in it - then 256 columns of 3 bytes for each chunk that is. The old client's files were Java
 * serialisation of its own classes and are not read; the map starts again from what is seen.</p>
 *
 * <p>Regions are read when first asked for and kept (a few dozen), and written when they are dropped, on {@link #flush()}
 * or when the owner decides to; a write goes to a temporary file first, so a crash cannot leave half a region. A file that
 * cannot be read counts as empty. Not thread-safe: the client thread owns it.</p>
 */
public final class ExploredMap {
    /** The colour of a column nobody has seen: the old client's light blue. */
    public static final int UNKNOWN = 0xFF8AB7FF;

    private static final Logger LOGGER = LoggerFactory.getLogger("Sigma/Map");
    private static final int SIDE = 8;
    private static final int MAGIC = 0x4A4D4150;
    private static final int FORMAT = 1;
    private static final int KEEP = 128;

    private static final class Region {
        final int x;
        final int z;
        long present;
        final int[][] chunks = new int[SIDE * SIDE][];
        boolean dirty;

        Region(final int x, final int z) {
            this.x = x;
            this.z = z;
        }
    }

    private final Path directory;
    private final LinkedHashMap<Long, Region> regions = new LinkedHashMap<>(32, 0.75F, true);
    private int version;

    public ExploredMap(final Path directory) {
        this.directory = directory;
    }

    /** Counts the changes made, so a picture built from the map can tell it has gone stale. */
    public int version() {
        return this.version;
    }

    public boolean has(final int chunkX, final int chunkZ) {
        return this.get(chunkX, chunkZ) != null;
    }

    /** The 256 colours of the chunk (row by row, north first, as ARGB), or {@code null} if it was never seen. Not a copy. */
    public int @Nullable [] get(final int chunkX, final int chunkZ) {
        return this.region(chunkX, chunkZ).chunks[index(chunkX, chunkZ)];
    }

    /** Records what a chunk looks like (opaque colours: the alpha is not kept). Saying what is already known changes nothing. */
    public void put(final int chunkX, final int chunkZ, final int[] colours) {
        if (colours.length != 256) {
            throw new IllegalArgumentException("a chunk is 256 columns, got " + colours.length);
        }

        int[] opaque = new int[256];
        for (int i = 0; i < 256; i++) {
            opaque[i] = 0xFF000000 | colours[i];
        }

        Region region = this.region(chunkX, chunkZ);
        int slot = index(chunkX, chunkZ);
        if (region.chunks[slot] != null && Arrays.equals(region.chunks[slot], opaque)) {
            return;
        }

        region.chunks[slot] = opaque;
        region.present |= 1L << slot;
        region.dirty = true;
        this.version++;
    }

    /**
     * Lays {@code chunksWide} x {@code chunksHigh} chunks out as pixels - the chunk at ({@code chunkX}, {@code chunkZ}) at
     * the top-left, one pixel a column, {@link #UNKNOWN} where nothing was seen - into {@code out}, which is
     * {@code chunksWide * 16} pixels to a row.
     */
    public void compose(final int chunkX, final int chunkZ, final int chunksWide, final int chunksHigh, final int[] out) {
        int stride = chunksWide * 16;
        if (out.length < stride * chunksHigh * 16) {
            throw new IllegalArgumentException("the picture needs " + stride * chunksHigh * 16 + " pixels, got " + out.length);
        }

        for (int cz = 0; cz < chunksHigh; cz++) {
            for (int cx = 0; cx < chunksWide; cx++) {
                int[] colours = this.get(chunkX + cx, chunkZ + cz);
                int base = cz * 16 * stride + cx * 16;
                for (int row = 0; row < 16; row++) {
                    int at = base + row * stride;
                    if (colours == null) {
                        Arrays.fill(out, at, at + 16, UNKNOWN);
                    } else {
                        System.arraycopy(colours, row * 16, out, at, 16);
                    }
                }
            }
        }
    }

    /** Writes every region that has changed. */
    public void flush() {
        for (Region region : this.regions.values()) {
            this.save(region);
        }
    }

    /** Whether something has changed since the last {@link #flush()}. */
    public boolean dirty() {
        for (Region region : this.regions.values()) {
            if (region.dirty) {
                return true;
            }
        }

        return false;
    }

    // ------------------------------------------------------------------ regions

    private static int index(final int chunkX, final int chunkZ) {
        return Math.floorMod(chunkZ, SIDE) * SIDE + Math.floorMod(chunkX, SIDE);
    }

    private Region region(final int chunkX, final int chunkZ) {
        int rx = Math.floorDiv(chunkX, SIDE);
        int rz = Math.floorDiv(chunkZ, SIDE);
        long key = (long) rx << 32 | rz & 0xFFFFFFFFL;
        Region region = this.regions.get(key);
        if (region == null) {
            region = this.load(rx, rz);
            this.regions.put(key, region);
            this.trim();
        }

        return region;
    }

    private void trim() {
        Iterator<Map.Entry<Long, Region>> eldest = this.regions.entrySet().iterator();
        while (this.regions.size() > KEEP && eldest.hasNext()) {
            this.save(eldest.next().getValue());
            eldest.remove();
        }
    }

    private Path fileOf(final Region region) {
        return this.directory.resolve("r." + region.x + "." + region.z + ".jmap");
    }

    private Region load(final int rx, final int rz) {
        Region region = new Region(rx, rz);
        Path file = this.fileOf(region);
        if (!Files.isRegularFile(file)) {
            return region;
        }

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != MAGIC || in.readUnsignedByte() != FORMAT) {
                LOGGER.warn("{} is not a map region this version reads, starting it again", file);
                return region;
            }

            long present = in.readLong();
            byte[] rgb = new byte[256 * 3];
            for (int slot = 0; slot < SIDE * SIDE; slot++) {
                if ((present >> slot & 1L) == 0L) {
                    continue;
                }

                in.readFully(rgb);
                int[] colours = new int[256];
                for (int i = 0; i < 256; i++) {
                    colours[i] = 0xFF000000 | (rgb[i * 3] & 0xFF) << 16 | (rgb[i * 3 + 1] & 0xFF) << 8 | rgb[i * 3 + 2] & 0xFF;
                }

                region.chunks[slot] = colours;
                region.present |= 1L << slot;
            }
        } catch (IOException | RuntimeException failure) {
            // Keep what was read before the damage; the rest is seen again.
            LOGGER.warn("Could not read the map region {}", file, failure);
        }

        return region;
    }

    private void save(final Region region) {
        if (!region.dirty) {
            return;
        }

        Path file = this.fileOf(region);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(this.directory);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary))))) {
                out.writeInt(MAGIC);
                out.writeByte(FORMAT);
                out.writeLong(region.present);
                byte[] rgb = new byte[256 * 3];
                for (int slot = 0; slot < SIDE * SIDE; slot++) {
                    int[] colours = region.chunks[slot];
                    if (colours == null) {
                        continue;
                    }

                    for (int i = 0; i < 256; i++) {
                        rgb[i * 3] = (byte) (colours[i] >> 16);
                        rgb[i * 3 + 1] = (byte) (colours[i] >> 8);
                        rgb[i * 3 + 2] = (byte) colours[i];
                    }

                    out.write(rgb);
                }
            }

            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            region.dirty = false;
        } catch (IOException failure) {
            LOGGER.warn("Could not save the map region {}", file, failure);
        }
    }
}
