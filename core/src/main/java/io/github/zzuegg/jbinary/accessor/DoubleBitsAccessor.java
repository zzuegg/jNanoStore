package io.github.zzuegg.jbinary.accessor;

import io.github.zzuegg.jbinary.DataStore;

/**
 * Allocation-free accessor for a {@code double} field stored as its raw
 * <a href="https://en.wikipedia.org/wiki/IEEE_754">IEEE 754</a> 64-bit
 * bit pattern.
 *
 * <p>Unlike {@link DoubleAccessor} — which stores a fixed-point scaled integer —
 * this accessor preserves the full double precision without range or precision
 * constraints.  It is used automatically by {@link io.github.zzuegg.jbinary.RowView}
 * and {@link io.github.zzuegg.jbinary.schema.LayoutBuilder} for unannotated
 * {@code double} fields.</p>
 *
 * <p>Storage: 64 bits per value.
 * Write: {@code Double.doubleToRawLongBits(value)}.
 * Read: {@code Double.longBitsToDouble(rawBits)}.</p>
 */
public final class DoubleBitsAccessor {

    private final int bitOffset;

    public DoubleBitsAccessor(int bitOffset) {
        this.bitOffset = bitOffset;
    }

    public double get(DataStore<?> store, int index) {
        return Double.longBitsToDouble(store.readBits(index, bitOffset, 64));
    }

    public void set(DataStore<?> store, int index, double value) {
        store.writeBits(index, bitOffset, 64, Double.doubleToRawLongBits(value));
    }
}
