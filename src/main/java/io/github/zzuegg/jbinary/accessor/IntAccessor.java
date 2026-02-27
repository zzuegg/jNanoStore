package io.github.zzuegg.jbinary.accessor;

import io.github.zzuegg.jbinary.DataStore;

/**
 * Allocation-free accessor for a single int-typed bit-packed field.
 */
public final class IntAccessor {
    private final int bitOffset;   // absolute bit offset within a row
    private final int bitWidth;
    private final long minRaw;

    public IntAccessor(int bitOffset, int bitWidth, long minRaw) {
        this.bitOffset = bitOffset;
        this.bitWidth  = bitWidth;
        this.minRaw    = minRaw;
    }

    public int get(DataStore store, int index) {
        return (int) (store.readBits(index, bitOffset, bitWidth) + minRaw);
    }

    public void set(DataStore store, int index, int value) {
        store.writeBits(index, bitOffset, bitWidth, (long) value - minRaw);
    }
}
