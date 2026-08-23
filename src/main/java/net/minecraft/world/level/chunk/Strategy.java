package net.minecraft.world.level.chunk;

import net.minecraft.core.IdMap;
import net.minecraft.util.Mth;

public abstract class Strategy<T> implements net.caffeinemc.mods.lithium.mixin.util.accessors.StrategyAccessor { // MODIFIED for porting: lithium StrategyAccessor
    public static final Palette.Factory SINGLE_VALUE_PALETTE_FACTORY = SingleValuePalette::create; // MODIFIED for porting: lithium.accesswidener widened access
    public static final Palette.Factory LINEAR_PALETTE_FACTORY = LinearPalette::create; // MODIFIED for porting: lithium.accesswidener widened access
    private static final Palette.Factory HASHMAP_PALETTE_FACTORY = HashMapPalette::create;
    private static final Configuration ZERO_BITS = new Configuration.Simple(SINGLE_VALUE_PALETTE_FACTORY, 0);
    private static final Configuration ONE_BIT_LINEAR = new Configuration.Simple(LINEAR_PALETTE_FACTORY, 1);
    private static final Configuration TWO_BITS_LINEAR = new Configuration.Simple(LINEAR_PALETTE_FACTORY, 2);
    private static final Configuration THREE_BITS_LINEAR = new Configuration.Simple(LINEAR_PALETTE_FACTORY, 3);
    private static final Configuration FOUR_BITS_LINEAR = new Configuration.Simple(LINEAR_PALETTE_FACTORY, 4);
    private static final Configuration FIVE_BITS_HASHMAP = new Configuration.Simple(HASHMAP_PALETTE_FACTORY, 5);
    private static final Configuration SIX_BITS_HASHMAP = new Configuration.Simple(HASHMAP_PALETTE_FACTORY, 6);
    private static final Configuration SEVEN_BITS_HASHMAP = new Configuration.Simple(HASHMAP_PALETTE_FACTORY, 7);
    private static final Configuration EIGHT_BITS_HASHMAP = new Configuration.Simple(HASHMAP_PALETTE_FACTORY, 8);
    // MODIFIED for porting: lithium chunk.palette StrategyMixin replaces the vanilla hash palette with lithium's own and
    // lowers the threshold for using it to 3 bits, because that implementation performs better at small key ranges.
    private static final Palette.Factory LITHIUM_HASH = net.caffeinemc.mods.lithium.common.world.chunk.LithiumHashPalette::create;
    private static final Configuration LITHIUM_HASH_3BIT = new Configuration.Simple(LITHIUM_HASH, 3);
    private static final Configuration LITHIUM_HASH_4BIT = new Configuration.Simple(LITHIUM_HASH, 4);
    private static final Configuration LITHIUM_HASH_5BIT = new Configuration.Simple(LITHIUM_HASH, 5);
    private static final Configuration LITHIUM_HASH_6BIT = new Configuration.Simple(LITHIUM_HASH, 6);
    private static final Configuration LITHIUM_HASH_7BIT = new Configuration.Simple(LITHIUM_HASH, 7);
    private static final Configuration LITHIUM_HASH_8BIT = new Configuration.Simple(LITHIUM_HASH, 8);
    private final IdMap<T> globalMap;
    private final GlobalPalette<T> globalPalette;
    protected final int globalPaletteBitsInMemory;
    private final int bitsPerAxis;
    private final int entryCount;

    private Strategy(final IdMap<T> globalMap, final int bitsPerAxis) {
        this.globalMap = globalMap;
        this.globalPalette = new GlobalPalette<>(globalMap);
        this.globalPaletteBitsInMemory = minimumBitsRequiredForDistinctValues(globalMap.size());
        this.bitsPerAxis = bitsPerAxis;
        this.entryCount = 1 << bitsPerAxis * 3;
    }

    public static <T> Strategy<T> createForBlockStates(final IdMap<T> registry) {
        return new Strategy<T>(registry, 4) {
            @Override
            public Configuration getConfigurationForBitCount(final int entryBits) {
                // MODIFIED for porting: lithium chunk.palette StrategyMixin$Strategy1Mixin
                return switch (entryBits) {
                    case 0 -> Strategy.ZERO_BITS;
                    case 1, 2 -> Strategy.FOUR_BITS_LINEAR;
                    case 3, 4 -> Strategy.LITHIUM_HASH_4BIT;
                    case 5 -> Strategy.LITHIUM_HASH_5BIT;
                    case 6 -> Strategy.LITHIUM_HASH_6BIT;
                    case 7 -> Strategy.LITHIUM_HASH_7BIT;
                    case 8 -> Strategy.LITHIUM_HASH_8BIT;
                    default -> new Configuration.Global(this.globalPaletteBitsInMemory, entryBits);
                };
            }
        };
    }

    public static <T> Strategy<T> createForBiomes(final IdMap<T> registry) {
        return new Strategy<T>(registry, 2) {
            @Override
            public Configuration getConfigurationForBitCount(final int entryBits) {
                // MODIFIED for porting: lithium chunk.palette StrategyMixin$Strategy2Mixin
                return switch (entryBits) {
                    case 0 -> Strategy.ZERO_BITS;
                    case 1 -> Strategy.ONE_BIT_LINEAR;
                    case 2 -> Strategy.TWO_BITS_LINEAR;
                    case 3 -> Strategy.LITHIUM_HASH_3BIT;
                    default -> new Configuration.Global(this.globalPaletteBitsInMemory, entryBits);
                };
            }
        };
    }

    public int entryCount() {
        return this.entryCount;
    }

    public int getIndex(final int x, final int y, final int z) {
        return (y << this.bitsPerAxis | z) << this.bitsPerAxis | x;
    }

    public IdMap<T> globalMap() {
        return this.globalMap;
    }

    public GlobalPalette<T> globalPalette() {
        return this.globalPalette;
    }

    protected abstract Configuration getConfigurationForBitCount(int entryBits);

    protected Configuration getConfigurationForPaletteSize(final int paletteSize) {
        int bits = minimumBitsRequiredForDistinctValues(paletteSize);
        return this.getConfigurationForBitCount(bits);
    }

    // MODIFIED for porting: was lithium's StrategyAccessor @Invoker Mixin
    @Override
    public Configuration lithium$getConfigurationForPaletteSize(final int i) {
        return this.getConfigurationForPaletteSize(i);
    }

    private static int minimumBitsRequiredForDistinctValues(final int count) {
        return Mth.ceillog2(count);
    }
}