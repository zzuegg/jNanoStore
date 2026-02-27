package io.github.zzuegg.jbinary.accessor;

import io.github.zzuegg.jbinary.DataStore;

/**
 * Allocation-free accessor for a 1-bit boolean field.
 */
public final class BoolAccessor {
    private final int bitOffset;

    public BoolAccessor(int bitOffset) {
        this.bitOffset = bitOffset;
    }

    public boolean get(DataStore store, int index) {
        return store.readBits(index, bitOffset, 1) != 0;
    }

    public void set(DataStore store, int index, boolean value) {
        store.writeBits(index, bitOffset, 1, value ? 1L : 0L);
    }
}
