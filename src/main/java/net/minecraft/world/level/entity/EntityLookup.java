package net.minecraft.world.level.entity;

import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.Map;
import java.util.UUID;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.minecraft.util.AbortableIterationConsumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class EntityLookup<T extends EntityAccess> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Int2ObjectMap<T> byId = new Int2ObjectLinkedOpenHashMap<>();
    private final Map<UUID, T> byUuid = Maps.newHashMap();

    public <U extends T> void getEntities(final EntityTypeTest<T, U> type, final AbortableIterationConsumer<U> consumer) {
        for (T entity : this.byId.values()) {
            U maybeEntity = (U)type.tryCast(entity);
            if (maybeEntity != null && consumer.accept(maybeEntity).shouldAbort()) {
                return;
            }
        }
    }

    public Iterable<T> getAllEntities() {
        return Iterables.unmodifiableIterable(this.byId.values());
    }

    public void add(final T entity) {
        UUID uuid = entity.getUUID();
        // MODIFIED for porting: was VFP entity.allow_duplicated_uuid MixinEntityLookup#allowDuplicateUuid
        // (@Redirect Map#containsKey). Targets <=1.16.4 legitimately reuse entity UUIDs, so the duplicate test is
        // forced false there and the entity still goes into both maps instead of being dropped with a warning.
        if (this.byUuid.containsKey(uuid) && ProtocolTranslator.getTargetVersion().newerThan(ProtocolVersion.v1_16_4)) {
            LOGGER.warn("Duplicate entity UUID {}: {}", uuid, entity);
        } else {
            this.byUuid.put(uuid, entity);
            this.byId.put(entity.getId(), entity);
        }
    }

    public void remove(final T entity) {
        this.byUuid.remove(entity.getUUID());
        this.byId.remove(entity.getId());
    }

    public @Nullable T getEntity(final int id) {
        return this.byId.get(id);
    }

    public @Nullable T getEntity(final UUID id) {
        return this.byUuid.get(id);
    }

    public int count() {
        // MODIFIED for porting: was VFP entity.allow_duplicated_uuid MixinEntityLookup#returnRealSize
        // (@Inject HEAD cancellable). With duplicate UUIDs allowed on <=1.16.4, byUuid under-counts.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_16_4)) {
            return this.byId.size();
        }

        return this.byUuid.size();
    }
}