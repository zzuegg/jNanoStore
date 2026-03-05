package io.github.zzuegg.jbinary.accessor;

import io.github.zzuegg.jbinary.DataStore;

/**
 * Allocation-free accessor for a {@code float} field stored as its raw
 * <a href="https://en.wikipedia.org/wiki/IEEE_754">IEEE 754</a> 32-bit
 * bit pattern.
 *
 * <p>Unlike {@link FloatAccessor} — which stores a fixed-point scaled integer —
 * this accessor preserves the full float precision without range or precision
 * constraints.  It is used automatically by {@link io.github.zzuegg.jbinary.RowView}
 * and {@link io.github.zzuegg.jbinary.schema.LayoutBuilder} for unannotated
 * {@code float} fields.</p>
 *
 * <p>Storage: 32 bits per value.
 * Write: {@code Float.floatToRawIntBits(value) & 0xFFFFFFFFL} (unsigned 32-bit).
 * Read: {@code Float.intBitsToFloat((int) rawBits)}.</p>
 */
public final class FloatBitsAccessor {

    private final int bitOffset;

    public FloatBitsAccessor(int bitOffset) {
        this.bitOffset = bitOffset;
    }

    public float get(DataStore<?> store, int index) {
        return Float.intBitsToFloat((int) store.readBits(index, bitOffset, 32));
    }

    public void set(DataStore<?> store, int index, float value) {
        store.writeBits(index, bitOffset, 32,
                Float.floatToRawIntBits(value) & 0xFFFFFFFFL);
    }
}
