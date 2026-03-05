package io.github.zzuegg.jbinary.accessor;

import io.github.zzuegg.jbinary.DataStore;

/**
 * Allocation-free accessor for a fixed-point decimal field stored as a scaled long,
 * exposing values as {@code float}.
 *
 * <p>Internally uses the same fixed-point representation as {@link DoubleAccessor};
 * the stored precision is governed by the {@link io.github.zzuegg.jbinary.annotation.DecimalField}
 * annotation.  Values are narrowed to {@code float} on read and widened from {@code float}
 * to {@code double} on write.
 */
public final class FloatAccessor {
    private final int bitOffset;
    private final int bitWidth;
    private final long minRaw;     // minRaw = round(min * scale)
    private final long scale;      // 10^precision
    private final float invScale;  // 1.0f / scale (precomputed)

    public FloatAccessor(int bitOffset, int bitWidth, long minRaw, long scale) {
        this.bitOffset = bitOffset;
        this.bitWidth  = bitWidth;
        this.minRaw    = minRaw;
        this.scale     = scale;
        this.invScale  = 1.0f / scale;
    }

    public float get(DataStore<?> store, int index) {
        long raw = store.readBits(index, bitOffset, bitWidth) + minRaw;
        return raw * invScale;
    }

    public void set(DataStore<?> store, int index, float value) {
        long raw = Math.round((double) value * scale);
        store.writeBits(index, bitOffset, bitWidth, raw - minRaw);
    }
}
