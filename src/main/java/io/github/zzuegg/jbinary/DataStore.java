package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.schema.ComponentLayout;
import io.github.zzuegg.jbinary.schema.LayoutBuilder;

import java.util.*;

/**
 * A fixed-capacity datastore that packs multiple component types into a shared
 * {@code long[]} backing store using bit-level packing.
 *
 * <p>One row per entry; each component type occupies a contiguous bit-block
 * within the row. The row stride (in longs) is computed so that all component
 * types fit.
 */
public final class DataStore {

    private final long[] data;
    private final int capacity;
    private final int rowStrideLongs;   // number of longs per row

    // per-component absolute bit offset within a row
    private final Map<Class<?>, Integer> componentBitOffsets;

    // -----------------------------------------------------------------------

    /**
     * Creates a DataStore for {@code capacity} rows, accommodating all given
     * component types in the same row.
     */
    public static DataStore of(int capacity, Class<?>... componentClasses) {
        Map<Class<?>, ComponentLayout> layouts = new LinkedHashMap<>();
        for (Class<?> cls : componentClasses) {
            layouts.put(cls, LayoutBuilder.layout(cls));
        }

        // Assign bit offsets per component (packed sequentially)
        int bitCursor = 0;
        Map<Class<?>, Integer> offsets = new LinkedHashMap<>();
        for (Map.Entry<Class<?>, ComponentLayout> e : layouts.entrySet()) {
            offsets.put(e.getKey(), bitCursor);
            bitCursor += e.getValue().totalBits();
        }
        int rowBits = bitCursor;
        int rowStrideLongs = (rowBits + 63) / 64;
        if (rowStrideLongs == 0) rowStrideLongs = 1;

        return new DataStore(capacity, rowStrideLongs,
                Collections.unmodifiableMap(offsets));
    }

    private DataStore(int capacity, int rowStrideLongs,
                      Map<Class<?>, Integer> componentBitOffsets) {
        this.capacity = capacity;
        this.rowStrideLongs = rowStrideLongs;
        this.componentBitOffsets = componentBitOffsets;
        this.data = new long[(long) capacity * rowStrideLongs > Integer.MAX_VALUE
                ? throwCapacityOverflow(capacity, rowStrideLongs)
                : capacity * rowStrideLongs];
    }

    private static int throwCapacityOverflow(int cap, int stride) {
        throw new IllegalArgumentException(
                "DataStore too large: capacity=" + cap + " rowStride=" + stride);
    }

    // -----------------------------------------------------------------------
    // Low-level bit accessors (public so accessor classes in sub-packages can use them)

    /**
     * Reads {@code bitWidth} bits from row {@code row}, starting at
     * {@code bitOffset} within the row.
     */
    public long readBits(int row, int bitOffset, int bitWidth) {
        int base = row * rowStrideLongs;
        int wordIndex = bitOffset >>> 6;          // bitOffset / 64
        int shift     = bitOffset & 63;            // bitOffset % 64
        long word = data[base + wordIndex];
        if (shift + bitWidth <= 64) {
            // fits in a single long
            long mask = bitWidth == 64 ? -1L : (1L << bitWidth) - 1L;
            return (word >>> shift) & mask;
        } else {
            // spans two longs
            int bitsInFirst = 64 - shift;
            long lo = word >>> shift;
            long hi = data[base + wordIndex + 1] & ((1L << (bitWidth - bitsInFirst)) - 1L);
            return lo | (hi << bitsInFirst);
        }
    }

    /**
     * Writes {@code bitWidth} bits (the low bits of {@code value}) into row {@code row},
     * starting at {@code bitOffset} within the row.
     */
    public void writeBits(int row, int bitOffset, int bitWidth, long value) {
        int base = row * rowStrideLongs;
        int wordIndex = bitOffset >>> 6;
        int shift     = bitOffset & 63;
        long mask = bitWidth == 64 ? -1L : (1L << bitWidth) - 1L;
        long bits = value & mask;

        if (shift + bitWidth <= 64) {
            long shiftedMask = mask << shift;
            data[base + wordIndex] = (data[base + wordIndex] & ~shiftedMask) | (bits << shift);
        } else {
            int bitsInFirst = 64 - shift;
            long maskLo = (1L << bitsInFirst) - 1L;
            // first word
            data[base + wordIndex] =
                    (data[base + wordIndex] & ~(maskLo << shift)) | ((bits & maskLo) << shift);
            // second word
            int bitsInSecond = bitWidth - bitsInFirst;
            long maskHi = (1L << bitsInSecond) - 1L;
            data[base + wordIndex + 1] =
                    (data[base + wordIndex + 1] & ~maskHi) | (bits >>> bitsInFirst);
        }
    }

    // -----------------------------------------------------------------------
    // Public query helpers

    public int capacity() { return capacity; }
    public int rowStrideLongs() { return rowStrideLongs; }

    /** Returns the absolute bit offset of the given component type within a row. */
    public int componentBitOffset(Class<?> cls) {
        Integer off = componentBitOffsets.get(cls);
        if (off == null) throw new IllegalArgumentException(
                "Component " + cls.getSimpleName() + " not registered in this DataStore");
        return off;
    }
}
