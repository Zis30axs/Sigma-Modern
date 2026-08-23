package net.minecraft.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

// MODIFIED for porting: lithium util.entity_collection_replacement ClassInstanceMultiMapMixin
public class ClassInstanceMultiMap<T> extends AbstractCollection<T>
    implements net.caffeinemc.mods.lithium.common.entity.TypeFilterableListInternalAccess<T>,
    net.caffeinemc.mods.lithium.mixin.alloc.entity_iteration.ClassInstanceMultiMapAccessor<T>, // MODIFIED for porting: lithium alloc.entity_iteration
    net.caffeinemc.mods.lithium.common.world.chunk.ClassGroupFilterableList<T> { // MODIFIED for porting: lithium chunk.entity_class_groups
    // MODIFIED for porting: lithium collections.entity_by_type ClassInstanceMultiMapMixin - the keys are Class objects, so a reference map is enough
    private final Map<Class<?>, List<T>> byClass = new it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap<>();
    private final Class<T> baseClass;
    private final List<T> allInstances = Lists.newArrayList();

    public ClassInstanceMultiMap(final Class<T> baseClass) {
        this.baseClass = baseClass;
        this.byClass.put(baseClass, this.allInstances);
    }

    // MODIFIED for porting: lithium chunk.entity_class_groups ClassInstanceMultiMapMixin lets entities be grouped by an
    // arbitrary set of classes (EntityClassGroup) instead of a single class.
    private final it.unimi.dsi.fastutil.objects.Reference2ReferenceArrayMap<net.caffeinemc.mods.lithium.common.entity.EntityClassGroup, it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet<T>> lithium$entitiesByGroup =
        new it.unimi.dsi.fastutil.objects.Reference2ReferenceArrayMap<>();

    @Override
    public Collection<T> lithium$getAllOfGroupType(final net.caffeinemc.mods.lithium.common.entity.EntityClassGroup type) {
        Collection<T> collection = this.lithium$entitiesByGroup.get(type);
        if (collection == null) {
            collection = this.lithium$createAllOfGroupType(type);
        }

        return collection;
    }

    private Collection<T> lithium$createAllOfGroupType(final net.caffeinemc.mods.lithium.common.entity.EntityClassGroup type) {
        it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet<T> allOfType = new it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet<>();

        for (T entity : this.allInstances) {
            if (type.contains((net.minecraft.world.entity.Entity)entity)) {
                allOfType.add(entity);
            }
        }

        this.lithium$entitiesByGroup.put(type, allOfType);
        return allOfType;
    }

    @Override
    public boolean add(final T instance) {
        // MODIFIED for porting: lithium chunk.entity_class_groups ClassInstanceMultiMapMixin#add (HEAD)
        for (Entry<net.caffeinemc.mods.lithium.common.entity.EntityClassGroup, it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet<T>> entityGroupAndSet : this.lithium$entitiesByGroup.entrySet()) {
            if (entityGroupAndSet.getKey().contains((net.minecraft.world.entity.Entity)instance)) {
                entityGroupAndSet.getValue().add(instance);
            }
        }

        boolean success = false;

        for (Entry<Class<?>, List<T>> entry : this.byClass.entrySet()) {
            if (entry.getKey().isInstance(instance)) {
                success |= entry.getValue().add(instance);
            }
        }

        return success;
    }

    @Override
    public boolean remove(final Object object) {
        // MODIFIED for porting: lithium chunk.entity_class_groups ClassInstanceMultiMapMixin#remove (HEAD)
        for (it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet<T> entitySet : this.lithium$entitiesByGroup.values()) {
            entitySet.remove(object);
        }

        boolean success = false;

        for (Entry<Class<?>, List<T>> entry : this.byClass.entrySet()) {
            if (entry.getKey().isInstance(object)) {
                List<T> list = entry.getValue();
                success |= list.remove(object);
            }
        }

        return success;
    }

    @Override
    public boolean contains(final Object o) {
        return this.find(o.getClass()).contains(o);
    }

    // MODIFIED for porting: lithium collections.entity_filtering ClassInstanceMultiMapMixin - only perform the slow
    // Class#isAssignableFrom check when no list exists for the type yet; otherwise the type is already known to be valid.
    // The slow path lives in its own method so the JVM can inline this one.
    // (lithium's block.hopper ClassInstanceMultiMapMixin redirects the isAssignableFrom call inside the *vanilla* body and
    // declares require = 0 / expect = 0 precisely because this overwrite removes that call; with entity_filtering enabled
    // - the default - it therefore has nothing left to do.)
    public <S> Collection<S> find(final Class<S> index) {
        Collection<T> collection = this.byClass.get(index);
        if (collection == null) {
            collection = this.createAllOfType(index);
        }

        return (Collection<S>)Collections.unmodifiableCollection(collection);
    }

    private <S> Collection<T> createAllOfType(final Class<S> type) {
        List<T> list = new java.util.ArrayList<>();

        for (T instance : this.allInstances) {
            if (type.isInstance(instance)) {
                list.add(instance);
            }
        }

        this.byClass.put(type, list);
        return list;
    }

    // MODIFIED for porting: the following three methods were lithium's ClassInstanceMultiMapMixin
    @Override
    public <S extends T> List<S> lithium$getOrCreateAllOfTypeRaw(final Class<S> type) {
        List<S> s = (List<S>)this.byClass.get(type);
        if (s == null) {
            this.find(type);
            s = (List<S>)this.byClass.get(type);
        }

        return s;
    }

    @Override
    public <S extends T> List<S> lithium$replaceCollectionAndGet(final Class<S> type, final java.util.function.Function<java.util.ArrayList<S>, List<S>> listCtor) {
        List<T> oldList = this.byClass.get(type);
        List<S> newList = listCtor.apply((java.util.ArrayList<S>)oldList);
        this.byClass.put(type, (List<T>)newList);
        return newList;
    }

    @Override
    public <S extends T> List<S> lithium$replaceCollectionAndGet(final Class<S> type, final java.util.ArrayList<S> list) {
        this.byClass.put(type, (List<T>)list);
        return list;
    }

    @Override
    public Iterator<T> iterator() {
        return this.allInstances.isEmpty() ? Collections.emptyIterator() : Iterators.unmodifiableIterator(this.allInstances.iterator());
    }

    // MODIFIED for porting: was lithium's alloc.entity_iteration ClassInstanceMultiMapAccessor Mixin, which exposes the
    // backing list itself so EntitySection#getEntities can iterate it without the unmodifiable wrapper. The vanilla
    // method with this exact name and signature has no callers anywhere in 26.2, so the accessor takes it over.
    @Override
    public List<T> getAllInstances() {
        return this.allInstances;
    }

    @Override
    public int size() {
        return this.allInstances.size();
    }
}