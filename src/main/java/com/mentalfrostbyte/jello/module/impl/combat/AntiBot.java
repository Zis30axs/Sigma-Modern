package com.mentalfrostbyte.jello.module.impl.combat;

import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.misc.FakePlayer;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/** Read-only player classification. No packets are cancelled and no entities are removed. */
public final class AntiBot extends Module {
    static final int MISSING_PROFILE = 1;
    static final int INVALID_NAME = 2;
    static final int DUPLICATE_NAME = 4;

    private final BooleanSetting tabProfile = register(new BooleanSetting("Tab Profile",
            "Filters players whose complete server player-info entry stays missing; hidden Tab entries are allowed.", true));
    private final BooleanSetting invalidName = register(new BooleanSetting("Invalid Name",
            "Strict Java profile-name check. Leave off for Bedrock bridges and servers with custom names.", false));
    private final BooleanSetting duplicateName = register(new BooleanSetting("Duplicate Name",
            "Filters all loaded copies of a repeated profile name. May reject real players on nickname servers.", false));
    private final NumberSetting graceTicks = register(new NumberSetting("Grace Ticks",
            "Minimum ticks observed before classifying a player, including after joining or enabling AntiBot.", 40, 0, 200, 1));
    private final NumberSetting confirmTicks = register(new NumberSetting("Confirm Ticks",
            "One enabled check must remain suspicious for this many consecutive ticks.", 20, 1, 100, 1));

    private final Evidence evidence = new Evidence();
    private @Nullable ClientLevel level;
    private @Nullable LocalPlayer owner;
    private @Nullable ClientPacketListener connection;

    public AntiBot() {
        super(ModuleCategory.COMBAT, "AntiBot", "Filters suspected server bots from combat targets and radar after a confirmation period.");
    }

    /** Shared filter; the local FakePlayer remains controlled by Target's Bots setting. */
    public static boolean isBot(final LivingEntity entity) {
        if (!(entity instanceof Player)) return false;
        AntiBot module = Modules.enabled(AntiBot.class);
        return module != null && module.classifies(entity);
    }

    private boolean classifies(final LivingEntity entity) {
        if (level == null || owner == null || connection == null || level != mc.level || owner != mc.player
                || connection != mc.getConnection() || !connection.getConnection().isConnected()
                || entity == owner || entity.level() != level || entity.isRemoved() || !entity.isAlive()) return false;
        int checks = (tabProfile.get() ? MISSING_PROFILE : 0) | (invalidName.get() ? INVALID_NAME : 0)
                | (duplicateName.get() ? DUPLICATE_NAME : 0);
        return evidence.isBot(entity, checks, graceTicks.getInt(), confirmTicks.getInt());
    }

    /** Runs before combat selection, reading only already-applied client state on the game thread. */
    @EventTarget(EventPriority.HIGH)
    public void onTick(final EventTick event) {
        if (!event.isPre()) return;
        LocalPlayer player = mc.player;
        ClientLevel currentLevel = mc.level;
        ClientPacketListener currentConnection = mc.getConnection();
        if (level != currentLevel || owner != player || connection != currentConnection) {
            reset();
            level = currentLevel;
            owner = player;
            connection = currentConnection;
        }
        if (player == null || currentLevel == null || currentConnection == null || !player.isAlive()
                || !currentConnection.getConnection().isConnected()) {
            reset();
            return;
        }
        if (mc.isPaused()) return;

        FakePlayer fake = Modules.enabled(FakePlayer.class);
        Map<String, Integer> names = new HashMap<>();
        if (duplicateName.get()) {
            // Include our own name so a server-created copy cannot pretend to be the local player.
            for (Player candidate : currentLevel.players()) {
                if (candidate.isAlive() && !candidate.isRemoved() && (fake == null || candidate != fake.getEntity())) {
                    names.merge(candidate.getGameProfile().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
                }
            }
        }

        evidence.beginFrame();
        for (Player candidate : currentLevel.players()) {
            if (candidate == player || candidate.isRemoved() || !candidate.isAlive() || candidate.isSpectator()
                    || fake != null && candidate == fake.getEntity()) continue;
            String name = candidate.getGameProfile().name();
            // The complete profile map is different from getListedOnlinePlayers(): servers may hide real players.
            int flags = currentConnection.getPlayerInfo(candidate.getUUID()) == null ? MISSING_PROFILE : 0;
            if (!validProfileName(name)) flags |= INVALID_NAME;
            if (duplicateName.get() && names.getOrDefault(name.toLowerCase(Locale.ROOT), 0) > 1) flags |= DUPLICATE_NAME;
            evidence.sample(candidate, flags);
        }
        evidence.endFrame();
    }

    static boolean validProfileName(final @Nullable String name) {
        // Old accounts can legitimately have one or two characters. Display names/team formatting are not examined.
        if (name == null || name.isEmpty() || name.length() > 16) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && c != '_') return false;
        }
        return true;
    }

    @Override
    protected void onEnable() { reset(); }

    @Override
    protected void onDisable() { reset(); }

    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) { reset(); }

    private void reset() {
        evidence.clear();
        level = null;
        owner = null;
        connection = null;
    }

    /** Identity-bound, bounded evidence. An entity ID/UUID reused after respawn never inherits a verdict. */
    static final class Evidence {
        private static final int MAX_PLAYERS = 2048;
        private static final int MAX_TICKS = 201;
        private final Map<Object, Observation> observations = new IdentityHashMap<>();

        void beginFrame() {
            for (Observation observation : observations.values()) observation.seen = false;
        }

        void sample(final Object identity, final int flags) {
            Observation observation = observations.get(identity);
            if (observation == null) {
                if (observations.size() >= MAX_PLAYERS) return; // Unknown players are allowed when the budget is full.
                observation = new Observation();
                observations.put(identity, observation);
            }
            if (observation.seen) return;
            observation.seen = true;
            observation.age = Math.min(MAX_TICKS, observation.age + 1);
            for (int i = 0; i < observation.consecutive.length; i++) {
                observation.consecutive[i] = (flags & (1 << i)) == 0 ? 0
                        : Math.min(MAX_TICKS, observation.consecutive[i] + 1);
            }
        }

        void endFrame() { observations.values().removeIf(observation -> !observation.seen); }

        boolean isBot(final Object identity, final int enabledChecks, final int graceTicks, final int confirmTicks) {
            Observation observation = observations.get(identity);
            if (observation == null || !observation.seen || observation.age < Math.max(0, graceTicks)) return false;
            for (int i = 0; i < observation.consecutive.length; i++) {
                if ((enabledChecks & (1 << i)) != 0 && observation.consecutive[i] >= Math.max(1, confirmTicks)) return true;
            }
            return false;
        }

        void clear() { observations.clear(); }
        int size() { return observations.size(); }

        private static final class Observation {
            private int age;
            private final int[] consecutive = new int[3];
            private boolean seen;
        }
    }
}
