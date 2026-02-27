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
 * A sparse {@link DataStore} that allocates per-row storage on demand.
 *
 * <p>Rows that have never been written to are treated as all-zeros, which maps to:
 * <ul>
 *   <li>The minimum value for {@code @BitField} and {@code @DecimalField} fields</li>
 *   <li>{@code false} for {@code @BoolField} fields</li>
 *   <li>The enum constant with ordinal / explicit-code {@code 0} for {@code @EnumField}</li>
 * </ul>
 *
 * <p>This is useful when only a small fraction of the declared {@link #capacity()} rows
 * will actually be populated; no memory is reserved for unwritten rows.</p>
 *
 * <p><strong>Thread safety:</strong> This class is <em>not</em> thread-safe.  Concurrent
 * reads are safe as long as no writes are in progress, but concurrent writes — or
 * a concurrent read and write — require external synchronisation.</p>
 *
 * <p>Use {@link DataStore#sparse} to create instances.</p>
 */
public final class SparseDataStore implements DataStore {

    private final int capacity;
    private final int rowStrideLongs;

    // per-component absolute bit offset within a row
    private final Map<Class<?>, Integer> componentBitOffsets;

    // lazily-allocated per-row storage: row index → long[rowStrideLongs]
    private final HashMap<Integer, long[]> rows;

    // -----------------------------------------------------------------------

    static DataStore create(int capacity, Class<?>... componentClasses) {
        if (capacity < 0) throw new IllegalArgumentException(
                "capacity must be >= 0, got " + capacity);
        if (componentClasses == null || componentClasses.length == 0) {
            throw new IllegalArgumentException("At least one component class is required");
        }
        Map<Class<?>, ComponentLayout> layouts = new LinkedHashMap<>();
        for (Class<?> cls : componentClasses) {
            layouts.put(cls, LayoutBuilder.layout(cls));
        }

        int bitCursor = 0;
        Map<Class<?>, Integer> offsets = new LinkedHashMap<>();
        for (Map.Entry<Class<?>, ComponentLayout> e : layouts.entrySet()) {
            offsets.put(e.getKey(), bitCursor);
            bitCursor += e.getValue().totalBits();
        }
        int rowBits = bitCursor;
        int rowStrideLongs = (rowBits + 63) / 64;
        if (rowStrideLongs == 0) rowStrideLongs = 1;

        return new SparseDataStore(capacity, rowStrideLongs,
                Collections.unmodifiableMap(offsets));
    }

    private SparseDataStore(int capacity, int rowStrideLongs,
                             Map<Class<?>, Integer> componentBitOffsets) {
        this.capacity = capacity;
        this.rowStrideLongs = rowStrideLongs;
        this.componentBitOffsets = componentBitOffsets;
        this.rows = new HashMap<>();
    }

    // -----------------------------------------------------------------------
    // DataStore implementation

    @Override
    public long readBits(int row, int bitOffset, int bitWidth) {
        long[] rowData = rows.get(row);
        if (rowData == null) return 0L;  // unwritten row — all zeros

        int wordIndex = bitOffset >>> 6;
        int shift     = bitOffset & 63;
        long word = rowData[wordIndex];
        if (shift + bitWidth <= 64) {
            long mask = bitWidth == 64 ? -1L : (1L << bitWidth) - 1L;
            return (word >>> shift) & mask;
        } else {
            int bitsInFirst = 64 - shift;
            long lo = word >>> shift;
            long hi = rowData[wordIndex + 1] & ((1L << (bitWidth - bitsInFirst)) - 1L);
            return lo | (hi << bitsInFirst);
        }
    }

    @Override
    public void writeBits(int row, int bitOffset, int bitWidth, long value) {
        if (row < 0 || row >= capacity) throw new IndexOutOfBoundsException(
                "row " + row + " out of bounds for capacity " + capacity);
        long[] rowData = rows.computeIfAbsent(row, k -> new long[rowStrideLongs]);

        int wordIndex = bitOffset >>> 6;
        int shift     = bitOffset & 63;
        long mask = bitWidth == 64 ? -1L : (1L << bitWidth) - 1L;
        long bits = value & mask;

        if (shift + bitWidth <= 64) {
            long shiftedMask = mask << shift;
            rowData[wordIndex] = (rowData[wordIndex] & ~shiftedMask) | (bits << shift);
        } else {
            int bitsInFirst = 64 - shift;
            long maskLo = (1L << bitsInFirst) - 1L;
            rowData[wordIndex] =
                    (rowData[wordIndex] & ~(maskLo << shift)) | ((bits & maskLo) << shift);
            int bitsInSecond = bitWidth - bitsInFirst;
            long maskHi = (1L << bitsInSecond) - 1L;
            rowData[wordIndex + 1] =
                    (rowData[wordIndex + 1] & ~maskHi) | (bits >>> bitsInFirst);
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

    /** Returns the number of rows that have been allocated (written at least once). */
    public int allocatedRowCount() {
        return rows.size();
    }

    // -----------------------------------------------------------------------
    // Serialization (type tag = 1)

    private static final int  MAGIC       = 0x4A42494E; // "JBIN"
    private static final byte TYPE_SPARSE = 1;

    @Override
    public void write(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(MAGIC);
        dos.writeByte(TYPE_SPARSE);
        dos.writeInt(capacity);
        dos.writeInt(rowStrideLongs);
        dos.writeInt(rows.size());
        for (Map.Entry<Integer, long[]> entry : rows.entrySet()) {
            dos.writeInt(entry.getKey());
            for (long word : entry.getValue()) {
                dos.writeLong(word);
            }
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
        if (type != TYPE_SPARSE) throw new IOException(
                "Expected sparse store (type 1), got type " + type);
        int cap    = dis.readInt();
        int stride = dis.readInt();
        if (cap != capacity || stride != rowStrideLongs) throw new IllegalArgumentException(
                "Store metadata mismatch: stream has capacity=" + cap +
                " rowStride=" + stride + " but store has capacity=" + capacity +
                " rowStride=" + rowStrideLongs);
        rows.clear();
        int rowCount = dis.readInt();
        for (int i = 0; i < rowCount; i++) {
            int rowIndex = dis.readInt();
            long[] rowData = new long[rowStrideLongs];
            for (int j = 0; j < rowStrideLongs; j++) {
                rowData[j] = dis.readLong();
            }
            rows.put(rowIndex, rowData);
        }
    }
}
