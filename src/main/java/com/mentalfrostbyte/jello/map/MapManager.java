package com.mentalfrostbyte.jello.map;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.event.EventState;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.gui.ClientMode;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the maps of the world being played: while Jello is the presentation it colours the chunks around the player as
 * they load and files them in the world's {@link ExploredMap}, and it holds the world's {@link WaypointStore}. The Maps
 * page ({@code JelloMapsScreen}) reads both through {@link #session()}.
 *
 * <p>A world's maps are kept under {@code sigma5/maps/<local|server>/<name>/}: {@code waypoints.json} for the world and one
 * folder of region files for each dimension. Dimensions with a ceiling (the Nether) are not recorded - their top is
 * bedrock, and a map of that says nothing.</p>
 */
public final class MapManager {
    private static final int RADIUS = 12;
    private static final int PER_PASS = 6;
    private static final int EVERY_TICKS = 4;
    private static final int SAVE_TICKS = 400;

    /** The maps of one world in one dimension. */
    public record Session(MapWorld world, String dimension, WaypointStore waypoints, ExploredMap explored, boolean recordable) {}

    private final Path root;
    private @Nullable Session session;
    // Chunks filed this session; one that unloads is forgotten, so it is filed again when it is back.
    private final Set<Long> filed = new HashSet<>();
    private int ticks;

    public MapManager(final Path root) {
        this.root = root;
    }

    /** The maps of the level the player is in, or {@code null} with no level. Opens (and switches) them as needed. */
    public @Nullable Session session() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return null;
        }

        MapWorld world = MapWorld.of(mc);
        String dimension = level.dimension().identifier().toString();
        Session open = this.session;
        if (open != null && open.world().equals(world) && open.dimension().equals(dimension)) {
            return open;
        }

        this.leave();
        Path worldFolder = this.root.resolve(world.key());
        WaypointStore waypoints = open != null && open.world().equals(world)
            ? open.waypoints()
            : new WaypointStore(worldFolder.resolve("waypoints.json")).load();
        ExploredMap explored = new ExploredMap(worldFolder.resolve(MapWorld.dimensionFolder(dimension)));
        this.session = new Session(world, dimension, waypoints, explored, !level.dimensionType().hasCeiling());
        return this.session;
    }

    /** Writes what has been seen since the last time. */
    public void flush() {
        Session open = this.session;
        if (open != null) {
            open.explored().flush();
        }
    }

    private void leave() {
        this.flush();
        this.session = null;
        this.filed.clear();
    }

    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) {
        this.leave();
    }

    @EventTarget
    public void onTick(final EventTick event) {
        if (event.getState() != EventState.POST) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (this.session != null) {
                this.leave();
            }

            return;
        }

        this.ticks++;
        if (this.ticks % EVERY_TICKS == 0 && Client.getInstance().getClientModeManager().get() == ClientMode.JELLO) {
            this.record(PER_PASS);
        }

        if (this.ticks % SAVE_TICKS == 0) {
            this.flush();
        }
    }

    /**
     * Colours up to {@code budget} of the loaded chunks around the player that have not been, nearest first, and files
     * them. A chunk waits until the ones north and south of it are loaded too - its shading reads their columns, and one
     * coloured before would keep a dark line along its edge - and is filed again after it has unloaded and come back,
     * which is how the map keeps up with what was built while one was away.
     */
    public void record(final int budget) {
        Minecraft mc = Minecraft.getInstance();
        Session open = this.session();
        if (open == null || !open.recordable() || mc.player == null) {
            return;
        }

        ClientLevel level = mc.level;
        ClientChunkCache cache = level.getChunkSource();
        this.filed.removeIf(key -> {
            ChunkPos pos = ChunkPos.unpack(key);
            return cache.getChunkNow(pos.x(), pos.z()) == null;
        });

        ChunkPos at = mc.player.chunkPosition();
        int[] done = {0};
        for (int ring = 0; ring <= RADIUS && done[0] < budget; ring++) {
            for (int d = -ring; d <= ring && done[0] < budget; d++) {
                this.visit(level, cache, open, at.x() + d, at.z() - ring, done);
                if (ring > 0) {
                    this.visit(level, cache, open, at.x() + d, at.z() + ring, done);
                }
            }

            for (int d = -ring + 1; d <= ring - 1 && done[0] < budget; d++) {
                this.visit(level, cache, open, at.x() - ring, at.z() + d, done);
                this.visit(level, cache, open, at.x() + ring, at.z() + d, done);
            }
        }
    }

    private void visit(final ClientLevel level, final ClientChunkCache cache, final Session open, final int cx, final int cz, final int[] done) {
        if (this.filed.contains(new ChunkPos(cx, cz).pack())) {
            return;
        }

        LevelChunk chunk = cache.getChunkNow(cx, cz);
        if (chunk == null || cache.getChunkNow(cx, cz + 1) == null || cache.getChunkNow(cx, cz - 1) == null) {
            return;
        }

        open.explored().put(cx, cz, ChunkColours.of(level, chunk));
        this.filed.add(new ChunkPos(cx, cz).pack());
        done[0]++;
    }
}
