package net.minecraft.world.level.entity;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry;
import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

public class EntityTickList {
    private Int2ObjectMap<Entity> active = new Int2ObjectLinkedOpenHashMap<>();
    private Int2ObjectMap<Entity> passive = new Int2ObjectLinkedOpenHashMap<>();
    private @Nullable Int2ObjectMap<Entity> iterated;

    // MODIFIED for porting: lithium collections.entity_ticking EntityTickListMixin - cloning the map is much cheaper
    // than clearing the other one and re-inserting every entry.
    private void ensureActiveIsNotIterated() {
        if (this.iterated == this.active) {
            this.passive = this.active;
            this.active = ((Int2ObjectLinkedOpenHashMap<Entity>)this.active).clone();
        }
    }

    public void add(final Entity entity) {
        this.ensureActiveIsNotIterated();
        this.active.put(entity.getId(), entity);
    }

    public void remove(final Entity entity) {
        this.ensureActiveIsNotIterated();
        this.active.remove(entity.getId());
    }

    public boolean contains(final Entity entity) {
        return this.active.containsKey(entity.getId());
    }

    public void forEach(final Consumer<Entity> output) {
        if (this.iterated != null) {
            throw new UnsupportedOperationException("Only one concurrent iteration supported");
        }

        this.iterated = this.active;

        try {
            for (Entity entity : this.active.values()) {
                output.accept(entity);
            }
        } finally {
            this.iterated = null;
        }
    }
}