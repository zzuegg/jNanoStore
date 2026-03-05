package io.github.zzuegg.jbinary.accessor;

import io.github.zzuegg.jbinary.DataStore;

/**
 * Allocation-free accessor for a character field stored as a bit-packed unsigned integer,
 * exposing values as {@code char}.
 *
 * <p>Use {@link io.github.zzuegg.jbinary.annotation.BitField} to annotate the corresponding
 * field with the desired range (e.g. {@code @BitField(min = 0, max = 65535)} for the full
 * Unicode BMP, or a narrower range such as {@code @BitField(min = 32, max = 126)} for printable
 * ASCII).
 */
public final class CharAccessor {
    private final int bitOffset;
    private final int bitWidth;
    private final long minRaw;

    public CharAccessor(int bitOffset, int bitWidth, long minRaw) {
        this.bitOffset = bitOffset;
        this.bitWidth  = bitWidth;
        this.minRaw    = minRaw;
    }

    public char get(DataStore<?> store, int index) {
        return (char) (store.readBits(index, bitOffset, bitWidth) + minRaw);
    }

    public void set(DataStore<?> store, int index, char value) {
        store.writeBits(index, bitOffset, bitWidth, (long) value - minRaw);
    }
}
