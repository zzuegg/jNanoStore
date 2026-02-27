package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.schema.ComponentLayout;
import io.github.zzuegg.jbinary.schema.LayoutBuilder;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

/**
 * A dense, fixed-capacity {@link DataStore} that packs multiple component types into a
 * single contiguous {@code long[]} backing store using bit-level packing.
 *
 * <p>One row per entry; each component type occupies a contiguous bit-block within the
 * row. The row stride (in longs) is computed so that all component types fit.</p>
 *
 * <p>Use {@link DataStore#packed} or {@link DataStore#of} to create instances.</p>
 */
public final class PackedDataStore implements DataStore {

    private final long[] data;
    private final int capacity;
    private final int rowStrideLongs;   // number of longs per row

    // per-component absolute bit offset within a row
    private final Map<Class<?>, Integer> componentBitOffsets;

    // -----------------------------------------------------------------------

    static DataStore create(int capacity, Class<?>... componentClasses) {
        if (componentClasses == null || componentClasses.length == 0) {
            throw new IllegalArgumentException("At least one component class is required");
        }
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

        return new PackedDataStore(capacity, rowStrideLongs,
                Collections.unmodifiableMap(offsets));
    }

    private PackedDataStore(int capacity, int rowStrideLongs,
                            Map<Class<?>, Integer> componentBitOffsets) {
        this.capacity = capacity;
        this.rowStrideLongs = rowStrideLongs;
        this.componentBitOffsets = componentBitOffsets;
        long totalLongs = (long) capacity * rowStrideLongs;
        if (totalLongs > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "DataStore too large: capacity=" + capacity + " rowStride=" + rowStrideLongs);
        }
        this.data = new long[(int) totalLongs];
    }

    // -----------------------------------------------------------------------
    // DataStore implementation

    @Override
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

    @Override
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

    @Override
    public int capacity() { return capacity; }

    @Override
    public int rowStrideLongs() { return rowStrideLongs; }

    @Override
    public int componentBitOffset(Class<?> cls) {
        Integer off = componentBitOffsets.get(cls);
        if (off == null) throw new IllegalArgumentException(
                "Component " + cls.getSimpleName() + " not registered in this DataStore");
        return off;
    }

    // -----------------------------------------------------------------------
    // Serialization (type tag = 0)

    static final int MAGIC = 0x4A42494E; // "JBIN"
    static final byte TYPE_PACKED = 0;

    @Override
    public void write(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(MAGIC);
        dos.writeByte(TYPE_PACKED);
        dos.writeInt(capacity);
        dos.writeInt(rowStrideLongs);
        for (long word : data) {
            dos.writeLong(word);
        }
        dos.flush();
    }

    @Override
    public void read(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int magic = dis.readInt();
        if (magic != MAGIC) throw new IOException(
                "Invalid magic bytes: expected 0x" + Integer.toHexString(MAGIC));
        int type = dis.readByte();
        if (type != TYPE_PACKED) throw new IOException(
                "Expected packed store (type 0), got type " + type);
        int cap    = dis.readInt();
        int stride = dis.readInt();
        if (cap != capacity || stride != rowStrideLongs) throw new IllegalArgumentException(
                "Store metadata mismatch: stream has capacity=" + cap +
                " rowStride=" + stride + " but store has capacity=" + capacity +
                " rowStride=" + rowStrideLongs);
        for (int i = 0; i < data.length; i++) {
            data[i] = dis.readLong();
        }
    }
}
