package io.github.zzuegg.jbinary.accessor;

import io.github.zzuegg.jbinary.DataStore;

/**
 * Allocation-free accessor for a fixed-point decimal field stored as a scaled long.
 */
public final class DoubleAccessor {
    private final int bitOffset;
    private final int bitWidth;
    private final long minRaw;     // minRaw = round(min * scale)
    private final long scale;      // 10^precision
    private final double invScale; // 1.0 / scale (precomputed)

    public DoubleAccessor(int bitOffset, int bitWidth, long minRaw, long scale) {
        this.bitOffset = bitOffset;
        this.bitWidth  = bitWidth;
        this.minRaw    = minRaw;
        this.scale     = scale;
        this.invScale  = 1.0 / scale;
    }

    public double get(DataStore store, int index) {
        long raw = store.readBits(index, bitOffset, bitWidth) + minRaw;
        return raw * invScale;
    }

    public void set(DataStore store, int index, double value) {
        long raw = Math.round(value * scale);
        store.writeBits(index, bitOffset, bitWidth, raw - minRaw);
    }
}
