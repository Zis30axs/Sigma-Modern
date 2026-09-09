/*
 * This file is part of ViaFabricPlus - https://github.com/ViaVersion/ViaFabricPlus
 * Copyright (C) 2021-2026 the original authors
 *                         - Florian Reuth <git@florianreuth.de>
 *                         - RK_01/RaphiMC
 * Copyright (C) 2023-2026 ViaVersion and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.viaversion.viafabricplus.protocoltranslator.impl.viaversion;

import com.google.common.base.Preconditions;
import com.google.common.collect.MapMaker;
import com.viaversion.viafabricplus.ViaFabricPlusImpl;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.configuration.ViaVersionConfig;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.legacy.bossbar.BossBar;
import com.viaversion.viaversion.api.legacy.bossbar.BossColor;
import com.viaversion.viaversion.api.legacy.bossbar.BossFlag;
import com.viaversion.viaversion.api.legacy.bossbar.BossStyle;
import com.viaversion.viaversion.api.minecraft.entities.EntityType;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_9;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.gson.JsonParser;
import com.viaversion.viaversion.protocols.v1_8to1_9.Protocol1_8To1_9;
import com.viaversion.viaversion.protocols.v1_8to1_9.packet.ClientboundPackets1_8;
import com.viaversion.viaversion.protocols.v1_8to1_9.packet.ClientboundPackets1_9;
import com.viaversion.viaversion.protocols.v1_8to1_9.provider.BossBarProvider;
import com.viaversion.viaversion.protocols.v1_8to1_9.storage.EntityTracker1_9;
import com.viaversion.viaversion.util.ComponentUtil;
import com.viaversion.viaversion.util.UUIDUtil;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

/**
 * MODIFIED for porting: replaces the ViaFabricPlus mixins in {@code features/entity/metadata} that redirect
 * calls inside ViaVersion's <= 1.8 boss-bar emulation - {@code MixinEntityTracker1_9} (three redirects in
 * {@code EntityTracker1_9#handleEntityData}) and {@code MixinCommonBoss} (one redirect in {@code CommonBoss}).
 * Sigma-Modern has no Mixin runtime and cannot edit the ViaVersion jar.
 *
 * <p>Both mixins together change one thing: the health a 1.8 ender dragon or wither reports through entity data
 * index 6 reaches the 1.9+ boss bar as the raw {@code health / 200 (dragon) or / 300 (wither)} ratio -
 * {@code NaN} becomes 0, nothing is clamped to {@code [0, 1]}, and {@code CommonBoss} no longer rejects values
 * outside that range. Without them, a server whose boss has a modified max health shows a bar pinned at full,
 * a negative health is pinned at empty, and a {@code NaN} health throws {@code IllegalArgumentException} out of
 * the packet handler (the clamps propagate NaN into the precondition), which kills the connection.
 *
 * <p>The boss-bar branch of {@code EntityTracker1_9#handleEntityData} cannot be altered from outside, and its
 * bars are {@code CommonBoss} instances whose precondition cannot be lifted either. So ViaVersion's branch is
 * switched off through {@link ViaFabricPlusConfig#isBossbarPatch()} and rebuilt here, appended to the same
 * packets that call {@code tracker.handleEntityData} for boss mobs (SET_ENTITY_DATA in EntityPacketRewriter1_9
 * and ADD_MOB in SpawnPacketRewriter1_9; ADD_PLAYER also calls it but a player is never a boss). The bars are
 * {@link UnclampedLegacyBossBar}s - a copy of {@code CommonBoss} minus the two preconditions - kept in the
 * tracker's own {@code getBossBarMap()}, so {@code EntityTracker1_9#removeEntity} still hides them and notifies
 * the {@link BossBarProvider} exactly as it does for ViaVersion's bars. The gate on the user's
 * {@code bossbar-patch} setting and the {@code bossbar-anti-flicker} check are preserved verbatim.
 *
 * <p>Runs after the mapping-loader future of {@link Protocol1_8To1_9}; see {@link ViaFabricPlusProtocolPatches}.
 */
public final class LegacyBossBarPatches {

    private LegacyBossBarPatches() {
    }

    public static void apply() {
        final ProtocolManager protocolManager = Via.getManager().getProtocolManager();
        awaitMappings(protocolManager, Protocol1_8To1_9.class);

        final Protocol1_8To1_9 protocol = protocolManager.getProtocol(Protocol1_8To1_9.class);
        if (protocol == null) {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocol1_8To1_9 is not registered, <= 1.8 boss bars will not be shown");
            return;
        }
        // appendClientbound falls back to a first registration when the packet has none, which would then collide
        // with ViaVersion's own; both packets are registered by registerPackets(), so this only fails if that never ran.
        if (!protocol.hasRegisteredClientbound(ClientboundPackets1_8.SET_ENTITY_DATA) || !protocol.hasRegisteredClientbound(ClientboundPackets1_8.ADD_MOB)) {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocol1_8To1_9 has no packet handlers, <= 1.8 boss bars will not be shown");
            return;
        }

        // Both packets store the entity id as VAR_INT #0 and the (already 1.9-typed) entity data list as
        // ENTITY_DATA_LIST1_9 #0, which is also what ViaVersion's own second handler reads for tracker.handleEntityData.
        final PacketHandler handler = wrapper -> {
            final EntityTracker1_9 tracker = wrapper.user().getEntityTracker(Protocol1_8To1_9.class);
            if (tracker == null) {
                return;
            }
            final int entityId = wrapper.get(Types.VAR_INT, 0);
            final List<EntityData> entityDataList = wrapper.get(Types.ENTITY_DATA_LIST1_9, 0);
            handleBossEntityData(wrapper.user(), tracker, entityId, entityDataList);
        };
        protocol.appendClientbound(ClientboundPackets1_8.SET_ENTITY_DATA, handler);
        protocol.appendClientbound(ClientboundPackets1_8.ADD_MOB, handler);
    }

    // EntityTracker1_9#handleEntityData, boss-bar block only (EntityTracker1_9.java:242-281), with the four upstream
    // redirects applied where marked. Iterates a copy like the original; the list itself is never modified here.
    private static void handleBossEntityData(final UserConnection user, final EntityTracker1_9 tracker, final int entityId, final List<EntityData> entityDataList) {
        if (!isBossbarPatchRequested()) {
            return;
        }
        final EntityType type = tracker.entityType(entityId);
        if (type != EntityTypes1_9.EntityType.ENDER_DRAGON && type != EntityTypes1_9.EntityType.WITHER) {
            return;
        }

        final Int2ObjectMap<BossBar> bossBarMap = tracker.getBossBarMap();
        final String defaultTitle = type == EntityTypes1_9.EntityType.ENDER_DRAGON ? EntityTracker1_9.DRAGON_TRANSLATABLE : EntityTracker1_9.WITHER_TRANSLATABLE;
        for (final EntityData entityData : new ArrayList<>(entityDataList)) {
            if (entityData.id() == 2) {
                BossBar bar = bossBarMap.get(entityId);
                String title = (String) entityData.getValue();
                if (title.isEmpty()) {
                    title = defaultTitle;
                } else {
                    title = ComponentUtil.plainToJson(title).toString();
                }
                if (bar == null) {
                    bar = new UnclampedLegacyBossBar(title, 1F, BossColor.PINK, BossStyle.SOLID);
                    bossBarMap.put(entityId, bar);
                    bar.addConnection(user);
                    bar.show();

                    // Send to provider
                    Via.getManager().getProviders().get(BossBarProvider.class).handleAdd(user, bar.getId());
                } else {
                    bar.setTitle(title);
                }
            } else if (entityData.id() == 6 && !Via.getConfig().isBossbarAntiflicker()) { // If anti flicker is enabled, don't update health
                BossBar bar = bossBarMap.get(entityId);
                final float maxHealth = type == EntityTypes1_9.EntityType.ENDER_DRAGON ? 200.0f : 300.0f;
                // MixinEntityTracker1_9#remapNaNToZero: a NaN health reads as 0 instead of poisoning the ratio.
                final Object rawValue = entityData.getValue();
                final float value = rawValue instanceof Float f && f.isNaN() ? 0F : (float) rawValue;
                // MixinEntityTracker1_9#removeMin / #removeMax: the ratio is passed on unclamped instead of
                // Math.max(0.0f, Math.min(value / maxHealth, 1.0f)).
                final float health = value / maxHealth;
                if (bar == null) {
                    bar = new UnclampedLegacyBossBar(defaultTitle, health, BossColor.PINK, BossStyle.SOLID);
                    bossBarMap.put(entityId, bar);
                    bar.addConnection(user);
                    bar.show();

                    // Send to provider
                    Via.getManager().getProviders().get(BossBarProvider.class).handleAdd(user, bar.getId());
                } else {
                    bar.setHealth(health);
                }
            }
        }
    }

    // Via.getConfig().isBossbarPatch() is forced to false to switch ViaVersion's block off; the user's setting is
    // exposed separately so this rebuild obeys it the way the original did.
    private static boolean isBossbarPatchRequested() {
        final ViaVersionConfig config = Via.getConfig();
        return config instanceof ViaFabricPlusConfig vfpConfig ? vfpConfig.isBossbarPatchRequested() : config.isBossbarPatch();
    }

    @SafeVarargs
    private static void awaitMappings(final ProtocolManager protocolManager, final Class<? extends Protocol>... protocols) {
        for (final Class<? extends Protocol> protocol : protocols) {
            final CompletableFuture<Void> future = protocolManager.getMappingLoaderFuture(protocol);
            if (future == null) {
                continue;
            }

            try {
                future.join();
            } catch (final CompletionException | CancellationException e) {
                ViaFabricPlusImpl.INSTANCE.getLogger().error("Failed to load mappings for {}, its ViaFabricPlus patches may not apply", protocol.getSimpleName(), e);
            }
        }
    }

    /**
     * {@code com.viaversion.viaversion.legacy.bossbar.CommonBoss} with the upstream {@code MixinCommonBoss#ignoreHealthCheck}
     * redirect applied: the {@code Preconditions.checkArgument(health >= 0 && health <= 1, ...)} in the constructor and
     * in {@link #setHealth(float)} are gone, everything else is a verbatim copy. It has to be a copy rather than a
     * subclass because {@code CommonBoss} keeps its state and its packet builders private, and the legacy API factory
     * ({@code LegacyViaAPI#createLegacyBossBar}) can only ever hand out the checked class.
     */
    static final class UnclampedLegacyBossBar implements BossBar {

        private final UUID uuid;
        private final Map<UUID, UserConnection> connections;
        private final Set<BossFlag> flags;
        private String title;
        private float health;
        private BossColor color;
        private BossStyle style;
        private boolean visible;

        UnclampedLegacyBossBar(final String title, final float health, final BossColor color, final BossStyle style) {
            Preconditions.checkNotNull(title, "Title cannot be null");
            // MixinCommonBoss#ignoreHealthCheck: no range check on health

            this.uuid = UUIDUtil.randomUUID();
            this.title = title;
            this.health = health;
            this.color = color == null ? BossColor.PURPLE : color;
            this.style = style == null ? BossStyle.SOLID : style;
            this.connections = new MapMaker().weakValues().makeMap();
            this.flags = EnumSet.noneOf(BossFlag.class);
            this.visible = true;
        }

        @Override
        public BossBar setTitle(final String title) {
            Preconditions.checkNotNull(title);
            this.title = title;
            sendPacket(UpdateAction.UPDATE_TITLE);
            return this;
        }

        @Override
        public BossBar setHealth(final float health) {
            // MixinCommonBoss#ignoreHealthCheck: no range check on health
            this.health = health;
            sendPacket(UpdateAction.UPDATE_HEALTH);
            return this;
        }

        @Override
        public BossColor getColor() {
            return color;
        }

        @Override
        public BossBar setColor(final BossColor color) {
            Preconditions.checkNotNull(color);
            this.color = color;
            sendPacket(UpdateAction.UPDATE_STYLE);
            return this;
        }

        @Override
        public BossBar setStyle(final BossStyle style) {
            Preconditions.checkNotNull(style);
            this.style = style;
            sendPacket(UpdateAction.UPDATE_STYLE);
            return this;
        }

        @Override
        public BossBar addPlayer(final UUID player) {
            final UserConnection client = Via.getManager().getConnectionManager().getServerConnection(player);
            if (client != null) {
                addConnection(client);
            }
            return this;
        }

        @Override
        public BossBar addConnection(final UserConnection conn) {
            if (connections.put(conn.getProtocolInfo().getUuid(), conn) == null && visible) {
                sendPacketConnection(conn, getPacket(UpdateAction.ADD, conn));
            }
            return this;
        }

        @Override
        public BossBar removePlayer(final UUID uuid) {
            final UserConnection client = connections.remove(uuid);
            if (client != null) {
                sendPacketConnection(client, getPacket(UpdateAction.REMOVE, client));
            }
            return this;
        }

        @Override
        public BossBar removeConnection(final UserConnection conn) {
            removePlayer(conn.getProtocolInfo().getUuid());
            return this;
        }

        @Override
        public BossBar addFlag(final BossFlag flag) {
            Preconditions.checkNotNull(flag);
            if (!hasFlag(flag)) {
                flags.add(flag);
            }
            sendPacket(UpdateAction.UPDATE_FLAGS);
            return this;
        }

        @Override
        public BossBar removeFlag(final BossFlag flag) {
            Preconditions.checkNotNull(flag);
            if (hasFlag(flag)) {
                flags.remove(flag);
            }
            sendPacket(UpdateAction.UPDATE_FLAGS);
            return this;
        }

        @Override
        public boolean hasFlag(final BossFlag flag) {
            Preconditions.checkNotNull(flag);
            return flags.contains(flag);
        }

        @Override
        public Set<UUID> getPlayers() {
            return Collections.unmodifiableSet(connections.keySet());
        }

        @Override
        public Set<UserConnection> getConnections() {
            return Collections.unmodifiableSet(new HashSet<>(connections.values()));
        }

        @Override
        public BossBar show() {
            setVisible(true);
            return this;
        }

        @Override
        public BossBar hide() {
            setVisible(false);
            return this;
        }

        @Override
        public boolean isVisible() {
            return visible;
        }

        private void setVisible(final boolean value) {
            if (visible != value) {
                visible = value;
                sendPacket(value ? UpdateAction.ADD : UpdateAction.REMOVE);
            }
        }

        @Override
        public UUID getId() {
            return uuid;
        }

        @Override
        public String getTitle() {
            return title;
        }

        @Override
        public float getHealth() {
            return health;
        }

        @Override
        public BossStyle getStyle() {
            return style;
        }

        private void sendPacket(final UpdateAction action) {
            for (final UserConnection conn : new ArrayList<>(connections.values())) {
                final PacketWrapper wrapper = getPacket(action, conn);
                sendPacketConnection(conn, wrapper);
            }
        }

        private void sendPacketConnection(final UserConnection conn, final PacketWrapper wrapper) {
            if (conn.getProtocolInfo() == null || !conn.getProtocolInfo().getPipeline().contains(Protocol1_8To1_9.class)) {
                connections.remove(conn.getProtocolInfo().getUuid());
                return;
            }
            try {
                wrapper.scheduleSend(Protocol1_8To1_9.class);
            } catch (final Exception e) {
                Via.getPlatform().getLogger().log(Level.WARNING, "Failed to send bossbar packet", e);
            }
        }

        private PacketWrapper getPacket(final UpdateAction action, final UserConnection connection) {
            try {
                final PacketWrapper wrapper = PacketWrapper.create(ClientboundPackets1_9.BOSS_EVENT, null, connection);
                wrapper.write(Types.UUID, uuid);
                wrapper.write(Types.VAR_INT, action.getId());
                switch (action) {
                    case ADD -> {
                        try {
                            wrapper.write(Types.COMPONENT, JsonParser.parseString(this.title));
                        } catch (final Exception e) {
                            wrapper.write(Types.COMPONENT, ComponentUtil.plainToJson(this.title));
                        }
                        wrapper.write(Types.FLOAT, health);
                        wrapper.write(Types.VAR_INT, color.getId());
                        wrapper.write(Types.VAR_INT, style.getId());
                        wrapper.write(Types.BYTE, (byte) flagToBytes());
                    }
                    case REMOVE -> {
                    }
                    case UPDATE_HEALTH -> wrapper.write(Types.FLOAT, health);
                    case UPDATE_TITLE -> {
                        try {
                            wrapper.write(Types.COMPONENT, JsonParser.parseString(this.title));
                        } catch (final Exception e) {
                            wrapper.write(Types.COMPONENT, ComponentUtil.plainToJson(this.title));
                        }
                    }
                    case UPDATE_STYLE -> {
                        wrapper.write(Types.VAR_INT, color.getId());
                        wrapper.write(Types.VAR_INT, style.getId());
                    }
                    case UPDATE_FLAGS -> wrapper.write(Types.BYTE, (byte) flagToBytes());
                }

                return wrapper;
            } catch (final Exception e) {
                Via.getPlatform().getLogger().log(Level.WARNING, "Failed to create bossbar packet", e);
            }
            return null;
        }

        private int flagToBytes() {
            int bitmask = 0;
            for (final BossFlag flag : flags) {
                bitmask |= flag.getId();
            }
            return bitmask;
        }

        private enum UpdateAction {

            ADD(0),
            REMOVE(1),
            UPDATE_HEALTH(2),
            UPDATE_TITLE(3),
            UPDATE_STYLE(4),
            UPDATE_FLAGS(5);

            private final int id;

            UpdateAction(final int id) {
                this.id = id;
            }

            public int getId() {
                return id;
            }
        }
    }

}
