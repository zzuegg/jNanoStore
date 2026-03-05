package io.github.zzuegg.jbinary.accessor;

import io.github.zzuegg.jbinary.DataStore;

/**
 * Allocation-free accessor for an integer-valued field stored as a bit-packed value,
 * exposing values as {@code short}.
 *
 * <p>Use {@link io.github.zzuegg.jbinary.annotation.BitField} to annotate the corresponding
 * field with the desired range (e.g. {@code @BitField(min = -32768, max = 32767)} for a signed
 * short or {@code @BitField(min = 0, max = 65535)} for an unsigned short).
 */
public final class ShortAccessor {
    private final int bitOffset;
    private final int bitWidth;
    private final long minRaw;

    public ShortAccessor(int bitOffset, int bitWidth, long minRaw) {
        this.bitOffset = bitOffset;
        this.bitWidth  = bitWidth;
        this.minRaw    = minRaw;
    }

    public short get(DataStore<?> store, int index) {
        return (short) (store.readBits(index, bitOffset, bitWidth) + minRaw);
    }

    public void set(DataStore<?> store, int index, short value) {
        store.writeBits(index, bitOffset, bitWidth, (long) value - minRaw);
    }
}
