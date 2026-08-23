package net.minecraft.world.level.block.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import malte0811.ferritecore.ducks.FastMapStateHolder; // MODIFIED for porting
import malte0811.ferritecore.fastmap.FastMap; // MODIFIED for porting
import malte0811.ferritecore.impl.FastMapStateHolderImpl; // MODIFIED for porting
import malte0811.ferritecore.mixin.accessors.StateHolderAccess; // MODIFIED for porting
import malte0811.ferritecore.mixin.config.FerriteConfig; // MODIFIED for porting
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

// MODIFIED for porting: FerriteCore replaces the per-state neighbor table (S[][] neighbors, one array per property plus
// one array per state) with a single FastMap shared by all states of a StateDefinition, and - when
// FerriteConfig.PROPERTY_MAP is enabled - drops the explicit propertyValues array in favour of reading the values back
// out of that FastMap.
public abstract class StateHolder<O, S> implements FastMapStateHolder<S>, StateHolderAccess {
    private static final int VALUE_NOT_FOUND = -1;
    public static final String NAME_TAG = "Name";
    public static final String PROPERTIES_TAG = "Properties";
    protected final O owner;
    private final Property<?>[] propertyKeys;
    // MODIFIED for porting: no longer final, it is nulled out once the FastMap is initialized
    private @Nullable Comparable<?>[] propertyValues;
    // MODIFIED for porting: replaces the vanilla `private S[][] neighbors` field
    private int ferritecore_globalTableIndex;
    private FastMap<S> ferritecore_globalTable;

    protected StateHolder(final O owner, final Property<?>[] propertyKeys, final Comparable<?>[] propertyValues) {
        assert propertyKeys.length == propertyValues.length;
        this.owner = owner;
        this.propertyKeys = propertyKeys;
        this.propertyValues = propertyValues;
    }

    // MODIFIED for porting: was FerriteCore's StateHolderAccess accessor Mixin
    @Override
    public Property<?>[] getPropertyKeys() {
        return this.propertyKeys;
    }

    // MODIFIED for porting: was FastMapStateHolderMixin#ferritecore_setStateMap
    @Override
    public void ferritecore_setStateMap(final FastMap<S> stateMap, final int tableIndex) {
        this.ferritecore_globalTable = stateMap;
        this.ferritecore_globalTableIndex = tableIndex;
        if (FerriteConfig.PROPERTY_MAP.isEnabled()) {
            this.propertyValues = null;
        }
    }

    public <T extends Comparable<T>> S cycle(final Property<T> property) {
        return this.setValue(property, findNextInCollection(property.getPossibleValues(), this.getValue(property)));
    }

    protected static <T> T findNextInCollection(final List<T> values, final T current) {
        int nextIndex = values.indexOf(current) + 1;
        return nextIndex == values.size() ? values.getFirst() : values.get(nextIndex);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(this.owner);
        if (!this.isSingletonState()) {
            builder.append('[');
            builder.append(this.getValues().map(Property.Value::toString).collect(Collectors.joining(",")));
            builder.append(']');
        }

        return builder.toString();
    }

    @Override
    public final boolean equals(final Object obj) {
        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    public Collection<Property<?>> getProperties() {
        return List.of(this.propertyKeys);
    }

    private int valueIndex(final Property<?> property) {
        for (int i = 0; i < this.propertyKeys.length; i++) {
            if (this.propertyKeys[i] == property) {
                return i;
            }
        }

        return -1;
    }

    public boolean hasProperty(final Property<?> property) {
        return this.valueIndex(property) != -1;
    }

    private <T extends Comparable<T>> @Nullable T getNullableValue(final Property<T> property) {
        int index = this.valueIndex(property);
        // MODIFIED for porting: FerriteCore redirects the propertyValues array read so the value can be recovered from
        // the shared FastMap once the explicit array has been dropped.
        return index == -1
            ? null
            : property
                .getValueClass()
                .cast(
                    FastMapStateHolderImpl.getPropertyValue(
                        this.propertyValues, this.ferritecore_globalTable, this.ferritecore_globalTableIndex, index
                    )
                );
    }

    public <T extends Comparable<T>> T getValue(final Property<T> property) {
        T value = this.getNullableValue(property);
        if (value == null) {
            throw new IllegalArgumentException("Cannot get property " + property + " as it does not exist in " + this.owner);
        } else {
            return value;
        }
    }

    public <T extends Comparable<T>> Optional<T> getOptionalValue(final Property<T> property) {
        return Optional.ofNullable(this.getNullableValue(property));
    }

    public <T extends Comparable<T>> T getValueOrElse(final Property<T> property, final T defaultValue) {
        return Objects.requireNonNullElse(this.getNullableValue(property), defaultValue);
    }

    public <T extends Comparable<T>, V extends T> S setValue(final Property<T> property, final V value) {
        int index = this.valueIndex(property);
        if (index == -1) {
            throw new IllegalArgumentException("Cannot set property " + property + " as it does not exist in " + this.owner);
        } else {
            return this.setValueInternal(property, index, value);
        }
    }

    public <T extends Comparable<T>, V extends T> S trySetValue(final Property<T> property, final V value) {
        int index = this.valueIndex(property);
        return (S)(index == -1 ? this : this.setValueInternal(property, index, value));
    }

    private <T extends Comparable<T>, V extends T> S setValueInternal(final Property<T> property, final int propertyIndex, final V value) {
        int valueIndex = property.getInternalIndex((T)value);
        if (valueIndex < 0) {
            throw new IllegalArgumentException("Cannot set property " + property + " to " + value + " on " + this.owner + ", it is not an allowed value");
        } else {
            // MODIFIED for porting: FerriteCore replaces the neighbor array lookup by a FastMap lookup
            return this.ferritecore_globalTable.with(this.ferritecore_globalTableIndex, propertyIndex, valueIndex);
        }
    }

    // MODIFIED for porting: FerriteCore replaces the neighbor data structure entirely, so the only states that still go
    // through this method are singleton states (which have no neighbors at all).
    void initializeNeighbors(final S[][] neighbors) {
        if (!this.isSingletonState()) {
            throw new UnsupportedOperationException(
                "Neighbor arrays are replaced by FerriteCore. This function should only be called for singleton states."
            );
        }
    }

    public boolean isSingletonState() {
        return this.propertyKeys.length == 0;
    }

    public Stream<Property.Value<?>> getValues() {
        int length = this.propertyKeys.length;
        // MODIFIED for porting: FerriteCore redirects the propertyValues array read (see getNullableValue)
        return length == 0
            ? Stream.empty()
            : IntStream.range(0, length)
                .mapToObj(
                    i -> createValue(
                        this.propertyKeys[i],
                        FastMapStateHolderImpl.getPropertyValue(
                            this.propertyValues, this.ferritecore_globalTable, this.ferritecore_globalTableIndex, i
                        )
                    )
                );
    }

    private static <T extends Comparable<T>> Property.Value<T> createValue(final Property<T> propertyKey, final Comparable<?> propertyValue) {
        return new Property.Value<>(propertyKey, (T)propertyValue);
    }

    protected static <O, S extends StateHolder<O, S>> Codec<S> codec(
        final Codec<O> ownerCodec, final Function<O, S> defaultState, final Function<O, StateDefinition<O, S>> stateDefinition
    ) {
        return ownerCodec.dispatch(
            "Name",
            s -> s.owner,
            o -> {
                StateDefinition<O, S> definition = stateDefinition.apply((O)o);
                S defaultValue = defaultState.apply((O)o);
                return definition.isSingletonState()
                    ? MapCodec.unit(defaultValue)
                    : definition.propertiesCodec().codec().lenientOptionalFieldOf("Properties").xmap(oo -> oo.orElse(defaultValue), Optional::of);
            }
        );
    }
}