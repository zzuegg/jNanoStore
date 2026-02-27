package io.github.zzuegg.jbinary.accessor;

import io.github.zzuegg.jbinary.DataStore;

/**
 * Allocation-free accessor for a single long-typed bit-packed field.
 */
public final class LongAccessor {
    private final int bitOffset;
    private final int bitWidth;
    private final long minRaw;

    public LongAccessor(int bitOffset, int bitWidth, long minRaw) {
        this.bitOffset = bitOffset;
        this.bitWidth  = bitWidth;
        this.minRaw    = minRaw;
    }

    public long get(DataStore store, int index) {
        return store.readBits(index, bitOffset, bitWidth) + minRaw;
    }

    public void set(DataStore store, int index, long value) {
        store.writeBits(index, bitOffset, bitWidth, value - minRaw);
    }
}
