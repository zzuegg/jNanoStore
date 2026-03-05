package io.github.zzuegg.jbinary.accessor;

import io.github.zzuegg.jbinary.DataStore;

/**
 * Allocation-free accessor for an integer-valued field stored as a bit-packed value,
 * exposing values as {@code byte}.
 *
 * <p>Use {@link io.github.zzuegg.jbinary.annotation.BitField} to annotate the corresponding
 * field with the desired range (e.g. {@code @BitField(min = -128, max = 127)} for a signed byte
 * or {@code @BitField(min = 0, max = 255)} for an unsigned byte).
 */
public final class ByteAccessor {
    private final int bitOffset;
    private final int bitWidth;
    private final long minRaw;

    public ByteAccessor(int bitOffset, int bitWidth, long minRaw) {
        this.bitOffset = bitOffset;
        this.bitWidth  = bitWidth;
        this.minRaw    = minRaw;
    }

    public byte get(DataStore<?> store, int index) {
        return (byte) (store.readBits(index, bitOffset, bitWidth) + minRaw);
    }

    public void set(DataStore<?> store, int index, byte value) {
        store.writeBits(index, bitOffset, bitWidth, (long) value - minRaw);
    }
}
