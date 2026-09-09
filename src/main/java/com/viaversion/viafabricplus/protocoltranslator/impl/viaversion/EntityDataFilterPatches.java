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

import com.viaversion.viafabricplus.ViaFabricPlusImpl;
import com.viaversion.viafabricplus.features.entity.metadata.WolfHealthTracker1_14_4;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_15;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.protocols.v1_14_4to1_15.Protocol1_14_4To1_15;
import com.viaversion.viaversion.rewriter.EntityRewriter;
import com.viaversion.viaversion.rewriter.entitydata.EntityDataFilter;
import com.viaversion.viaversion.rewriter.entitydata.EntityDataHandler;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * MODIFIED for porting: replaces ViaFabricPlus mixins that {@code @Redirect} a call inside an
 * {@code EntityRewriter#registerRewrites()} body, i.e. that change what one already-registered
 * {@link EntityDataFilter} does. Sigma-Modern has no Mixin runtime and cannot edit the ViaVersion jar.
 *
 * <p>An entity data filter is an immutable record held in {@code EntityRewriter#entityDataFilters}, a
 * {@code protected final List} with no public accessor. Filters run in registration order and the first
 * one that cancels an entry ends the loop for that entry ({@code EntityRewriter#handleEntityData}), so a
 * filter appended through the public {@code filter()} builder can never see an entry that ViaVersion's
 * own earlier filter already dropped. The only route that keeps the hook at the same position in the
 * chain - which is what the upstream redirect does - is to swap the record in place. That list read is the
 * single reflective access here; it is resolved once, and every failure degrades to a warning that names
 * the hook, leaving ViaVersion's own filter untouched (the same policy as {@link LibraryFieldAccessPatches}).
 *
 * <p>{@code registerRewrites()} runs from {@code RewriterBase#register()} inside each protocol's
 * {@code registerPackets()}, so the swap has to wait on the mapping-loader future first; see
 * {@link ViaFabricPlusProtocolPatches} for the timing rules.
 */
public final class EntityDataFilterPatches {

    private static final @Nullable Field ENTITY_DATA_FILTERS = findFiltersField();

    private EntityDataFilterPatches() {
    }

    public static void apply() {
        final ProtocolManager protocolManager = Via.getManager().getProtocolManager();
        awaitMappings(protocolManager, Protocol1_14_4To1_15.class);
        applyWolfHealthSnapshot(protocolManager);
    }

    // was VFP features/entity/metadata/MixinEntityPacketRewriter1_15#removeAndTrackHealth (@Redirect of the one
    // EntityDataFilter.Builder#removeIndex(I) call in EntityPacketRewriter1_15#registerRewrites - the
    // filter().type(WOLF).removeIndex(18) at EntityPacketRewriter1_15.java:132).
    //
    // 1.15 dropped the wolf health entity data entry (index 18 after the LIVING_ENTITY addIndex(12) shift that
    // runs just before it). ViaVersion's replacement handler is exactly "cancel index 18, shift everything
    // above it down by one"; upstream keeps that renumbering and adds one line, storing the value being dropped
    // in WolfHealthTracker1_14_4 so Wolf#vfpGetHealth (tail angle, whine/pant selection and the client-side
    // feeding prediction) can read the server's 1.14.4 health instead of the attribute health on <= 1.14.4
    // targets. The tracker is put into the connection by ViaFabricPlusProtocol#init for exactly those targets.
    private static void applyWolfHealthSnapshot(final ProtocolManager protocolManager) {
        final Protocol1_14_4To1_15 protocol = protocolManager.getProtocol(Protocol1_14_4To1_15.class);
        if (protocol == null) {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocol1_14_4To1_15 is not registered, wolf health will not be tracked on <= 1.14.4 targets");
            return;
        }

        replaceFilter(protocol.getEntityRewriter(), "wolf health (MixinEntityPacketRewriter1_15)",
            filter -> filter.type() == EntityTypes1_15.WOLF && filter.index() == -1 && filter.dataType() == null,
            (event, data) -> {
                final int metaIndex = event.index();
                if (metaIndex == 18) {
                    final WolfHealthTracker1_14_4 tracker = event.user().get(WolfHealthTracker1_14_4.class);
                    if (tracker != null) { // upstream dereferences unconditionally; a connection without the storable just keeps the drop
                        tracker.setWolfHealth(event.entityId(), data.value());
                    }
                    event.cancel();
                } else if (metaIndex > 18) {
                    event.setIndex(metaIndex - 1);
                }
            });
    }

    /**
     * Swaps the handler of exactly one already-registered entity data filter, keeping its position, type,
     * family flag, data type and index. Does nothing (and warns) unless {@code match} selects exactly one filter.
     *
     * @return whether the filter was replaced
     */
    static boolean replaceFilter(final EntityRewriter<?, ?> rewriter, final String hook,
                                 final Predicate<EntityDataFilter> match, final EntityDataHandler handler) {
        final List<EntityDataFilter> filters = filtersOf(rewriter, hook);
        if (filters == null) {
            return false;
        }

        int found = -1;
        for (int i = 0; i < filters.size(); i++) {
            if (!match.test(filters.get(i))) {
                continue;
            }
            if (found != -1) {
                ViaFabricPlusImpl.INSTANCE.getLogger()
                    .warn("More than one entity data filter of {} matches the {} hook, leaving ViaVersion's filters untouched", rewriter.getClass().getSimpleName(), hook);
                return false;
            }
            found = i;
        }
        if (found == -1) {
            ViaFabricPlusImpl.INSTANCE.getLogger()
                .warn("No entity data filter of {} matches the {} hook, leaving ViaVersion's filters untouched", rewriter.getClass().getSimpleName(), hook);
            return false;
        }

        final EntityDataFilter original = filters.get(found);
        filters.set(found, new EntityDataFilter(original.type(), original.filterFamily(), original.dataType(), original.index(), handler));
        return true;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable List<EntityDataFilter> filtersOf(final EntityRewriter<?, ?> rewriter, final String hook) {
        if (ENTITY_DATA_FILTERS == null) {
            ViaFabricPlusImpl.INSTANCE.getLogger()
                .warn("EntityRewriter#entityDataFilters is not where this build expects it, the {} hook is not installed", hook);
            return null;
        }

        try {
            return (List<EntityDataFilter>) ENTITY_DATA_FILTERS.get(rewriter);
        } catch (final IllegalAccessException | RuntimeException e) {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Failed to read the entity data filters of {}, the {} hook is not installed", rewriter.getClass().getSimpleName(), hook, e);
            return null;
        }
    }

    private static @Nullable Field findFiltersField() {
        try {
            final Field field = EntityRewriter.class.getDeclaredField("entityDataFilters");
            field.setAccessible(true);
            return field;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return null; // reported per hook by filtersOf
        }
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

}
