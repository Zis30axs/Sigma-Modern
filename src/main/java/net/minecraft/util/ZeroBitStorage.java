package net.minecraft.util;

import java.util.Arrays;
import java.util.function.IntConsumer;
import org.apache.commons.lang3.Validate;

// MODIFIED for porting: implements sodium's BitStorageExtension (core.world.chunk ZeroBitStorageMixin)
public class ZeroBitStorage implements BitStorage, net.caffeinemc.mods.sodium.client.world.BitStorageExtension {
    // MODIFIED for porting: was sodium's core.world.chunk ZeroBitStorageMixin
    @Override
    public <T> void sodium$unpack(final T[] out, final net.minecraft.world.level.chunk.Palette<T> palette) {
        if (this.size != out.length) {
            throw new IllegalArgumentException("Array has mismatched size");
        }

        java.util.Arrays.fill(out, java.util.Objects.requireNonNull(palette.valueFor(0), "Palette must have default entry"));
    }

    public static final long[] RAW = new long[0];
    private final int size;

    public ZeroBitStorage(final int size) {
        this.size = size;
    }

    @Override
    public int getAndSet(final int index, final int value) {
        // MODIFIED for porting: lithium chunk.no_validation ZeroBitStorageMixin#skipValidation
        return 0;
    }

    @Override
    public void set(final int index, final int value) {
        // MODIFIED for porting: lithium chunk.no_validation ZeroBitStorageMixin#skipValidation
    }

    @Override
    public int get(final int index) {
        // MODIFIED for porting: lithium chunk.no_validation ZeroBitStorageMixin#skipValidation
        return 0;
    }

    @Override
    public long[] getRaw() {
        return RAW;
    }

    @Override
    public int getSize() {
        return this.size;
    }

    @Override
    public int getBits() {
        return 0;
    }

    @Override
    public void getAll(final IntConsumer output) {
        for (int i = 0; i < this.size; i++) {
            output.accept(0);
        }
    }

    @Override
    public void unpack(final int[] output) {
        Arrays.fill(output, 0, this.size, 0);
    }

    @Override
    public BitStorage copy() {
        return this;
    }
}