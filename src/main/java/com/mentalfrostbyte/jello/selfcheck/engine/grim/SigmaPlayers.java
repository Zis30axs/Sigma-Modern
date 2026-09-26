package com.mentalfrostbyte.jello.selfcheck.engine.grim;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.platform.api.entity.GrimEntity;
import ac.grim.grimac.platform.api.player.AbstractPlatformPlayerFactory;
import ac.grim.grimac.platform.api.player.BlockTranslator;
import ac.grim.grimac.platform.api.player.OfflinePlatformPlayer;
import ac.grim.grimac.platform.api.player.PlatformInventory;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.platform.api.world.PlatformChunk;
import ac.grim.grimac.platform.api.world.PlatformWorld;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.math.Location;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * The one player a SelfDetection Grim ever sees: the local player.
 *
 * <p>On a server these objects answer with the server's authoritative state - its inventory, its world, the entity
 * the player rides. Here there is no server, and the client's own state must never stand in for it: a cheat can
 * change the client's inventory, world and flags, and would then be changing what the judge sees. So everything that
 * could feed a verdict answers neutrally (empty items, unloaded chunks, no vehicle), and the ported sources that used
 * to fall back to it now use Grim's packet-derived state instead (the {@code MODIFIED for porting} sites listed in
 * SELFCHECK_PORTING.md). Only identity - name and UUID, from the server's login packet - is real.</p>
 */
final class SigmaPlayers {

    private SigmaPlayers() {
    }

    /** The "native player object" Grim's platform layer passes around: who logged in. */
    record Native(UUID uuid, String name) {
    }

    /**
     * What the local player may do: run Grim's commands and toggle its alerts, verbose output and brand notices - and
     * nothing else. It is a whitelist on purpose: Grim also has permissions that exempt a player from checks, setbacks
     * or packet modification, or switch Grim off for them altogether ({@code grim.disabled}), and one that slips through
     * silences the judge. Anything not listed, including permissions a later Grim adds, is denied. Of the enable-on-join
     * toggles only alerts start on, as with a server's default permissions; verbose and brand notices are opt-in with
     * {@code .grim verbose} / {@code .grim brands}.
     */
    static final Set<String> GRANTED = Set.of(
            "grim.alerts", "grim.alerts.enable-on-join", "grim.verbose", "grim.brand",
            "grim.help", "grim.version", "grim.reload", "grim.profile", "grim.performance", "grim.log", "grim.list",
            "grim.history", "grim.history.repair", "grim.dump", "grim.debug", "grim.consoledebug", "grim.testwebhook",
            "grim.sendalert");

    static boolean hasPermission(final String permission) {
        return GRANTED.contains(permission.toLowerCase(Locale.ROOT));
    }

    static final class Factory extends AbstractPlatformPlayerFactory<Native> {

        private final GrimEngine engine;
        private volatile @Nullable Native local;

        Factory(final GrimEngine engine) {
            this.engine = engine;
        }

        void setLocal(final @Nullable Native player) {
            this.local = player;
        }

        @Nullable Native local() {
            return this.local;
        }

        @Override
        protected @Nullable Native getNativePlayer(final UUID uuid) {
            Native player = this.local;
            return player != null && player.uuid().equals(uuid) ? player : null;
        }

        @Override
        protected @Nullable Native getNativePlayer(final String name) {
            Native player = this.local;
            return player != null && player.name().equalsIgnoreCase(name) ? player : null;
        }

        @Override
        protected PlatformPlayer createPlatformPlayer(final Native player) {
            return new Player(player, this.engine);
        }

        @Override
        protected UUID getPlayerUUID(final Native player) {
            return player.uuid();
        }

        @Override
        protected Collection<Native> getNativeOnlinePlayers() {
            Native player = this.local;
            return player == null ? List.of() : List.of(player);
        }

        @Override
        public @Nullable OfflinePlatformPlayer getOfflineFromUUID(final UUID uuid) {
            return this.getFromUUID(uuid);
        }

        @Override
        public @Nullable OfflinePlatformPlayer getOfflineFromName(final String name) {
            return this.getFromName(name);
        }

        @Override
        public Collection<OfflinePlatformPlayer> getOfflinePlayers() {
            return new ArrayList<>(this.getOnlinePlayers());
        }
    }

    static final class Player implements PlatformPlayer {

        private final Native player;
        private final GrimEngine engine;

        Player(final Native player, final GrimEngine engine) {
            this.player = player;
            this.engine = engine;
        }

        private @Nullable GrimPlayer grim() {
            return GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(this.player.uuid());
        }

        @Override
        public UUID getUniqueId() {
            return this.player.uuid();
        }

        @Override
        public String getName() {
            return this.player.name();
        }

        @Override
        public boolean isOnline() {
            return !this.engine.isClosed();
        }

        @Override
        public void kickPlayer(final String reason) {
            SigmaGrim.wouldDisconnect(reason);
        }

        @Override
        public void resyncSharedFlags() {
            // would write to the client
        }

        @Override
        public boolean hasPermission(final String permission) {
            return SigmaPlayers.hasPermission(permission);
        }

        @Override
        public boolean hasPermission(final String permission, final boolean defaultIfUnset) {
            return SigmaPlayers.hasPermission(permission);
        }

        @Override
        public void sendMessage(final String message) {
            this.engine.chat().sendLegacy(message);
        }

        @Override
        public void sendMessage(final Component message) {
            this.engine.chat().send(message);
        }

        @Override
        public void updateInventory() {
            // would ask the server to resend the inventory
        }

        @Override
        public Vector3d getPosition() {
            GrimPlayer grim = this.grim();
            return grim == null ? new Vector3d() : new Vector3d(grim.x, grim.y, grim.z);
        }

        @Override
        public PlatformInventory getInventory() {
            return EMPTY_INVENTORY;
        }

        @Override
        public @Nullable GrimEntity getVehicle() {
            return null;
        }

        @Override
        public GameMode getGameMode() {
            GrimPlayer grim = this.grim();
            return grim == null ? GameMode.SURVIVAL : grim.gamemode;
        }

        @Override
        public void setGameMode(final GameMode gameMode) {
            // would change the server's state
        }

        @Override
        public boolean isExternalPlayer() {
            return false;
        }

        @Override
        public void sendPluginMessage(final String channel, final byte[] data) {
            // would write to the client
        }

        @Override
        public Sender getSender() {
            return this.engine.senders().wrap(this.player);
        }

        @Override
        public BlockTranslator getBlockTranslator() {
            return BlockTranslator.IDENTITY;
        }

        @Override
        public boolean eject() {
            return false;
        }

        @Override
        public CompletableFuture<Boolean> teleportAsync(final Location location) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public Object getNative() {
            return this.player;
        }

        @Override
        public boolean isDead() {
            return false;
        }

        @Override
        public PlatformWorld getWorld() {
            return WORLD;
        }

        @Override
        public Location getLocation() {
            GrimPlayer grim = this.grim();
            return grim == null
                    ? new Location(WORLD, 0, 0, 0)
                    : new Location(WORLD, grim.x, grim.y, grim.z, grim.yaw, grim.pitch);
        }

        @Override
        public double distanceSquared(final double x, final double y, final double z) {
            Vector3d position = this.getPosition();
            double dx = position.getX() - x;
            double dy = position.getY() - y;
            double dz = position.getZ() - z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** No items anywhere: nothing that decides a verdict may come from here. */
    static final PlatformInventory EMPTY_INVENTORY = new PlatformInventory() {
        @Override
        public ItemStack getItemInHand() {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack getItemInOffHand() {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack getStack(final int bukkitSlot, final int vanillaSlot) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack getHelmet() {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack getChestplate() {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack getLeggings() {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack getBoots() {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack[] getContents() {
            return new ItemStack[0];
        }

        @Override
        public String getOpenInventoryKey() {
            return "CRAFTING";
        }
    };

    /** A world with nothing loaded: the server's world is not available, and the client's is not a substitute. */
    static final PlatformWorld WORLD = new PlatformWorld() {
        private final UUID id = new UUID(0x5349474D41L, 0L);

        @Override
        public boolean isChunkLoaded(final int chunkX, final int chunkZ) {
            return false;
        }

        @Override
        public WrappedBlockState getBlockAt(final int x, final int y, final int z) {
            return WrappedBlockState.getDefaultState(StateTypes.AIR);
        }

        @Override
        public String getName() {
            return "world";
        }

        @Override
        public UUID getUID() {
            return this.id;
        }

        @Override
        public @Nullable PlatformChunk getChunkAt(final int chunkX, final int chunkZ) {
            return null;
        }

        @Override
        public boolean isLoaded() {
            return true;
        }
    };
}
