package io.github.zzuegg.jbinary;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Base interface for a bit-packed datastore.
 *
 * <p>A DataStore holds {@link #capacity()} rows; each row can contain data from one or
 * more annotated component types packed together in a fixed bit-stride.  Three concrete
 * implementations are provided:
 * <ul>
 *   <li>{@link PackedDataStore} — dense, pre-allocates a single {@code long[]} for all rows.</li>
 *   <li>{@link SparseDataStore} — sparse, allocates storage only for rows that are written to;
 *       unwritten rows read back as all-zeros.</li>
 *   <li>{@link io.github.zzuegg.jbinary.octree.FastOctreeDataStore} — high-performance octree
 *       store using a primitive open-addressing hash map and a flat arena allocator.</li>
 * </ul>
 *
 * <p>Use the static factory methods to create instances:
 * <pre>{@code
 * DataStore packed = DataStore.of(10_000, Terrain.class, Water.class);
 * DataStore sparse = DataStore.sparse(10_000, Terrain.class, Water.class);
 * }</pre>
 */
public interface DataStore {

    /** Returns the maximum number of rows this store was created with. */
    int capacity();

    /** Returns the number of {@code long} words used per row. */
    int rowStrideLongs();

    /**
     * Returns the absolute bit offset (within a row) at which the given component type starts.
     *
     * @throws IllegalArgumentException if {@code cls} was not registered when the store was created
     */
    int componentBitOffset(Class<?> cls);

    /**
     * Reads {@code bitWidth} bits from row {@code row}, starting at {@code bitOffset}
     * within the row.
     */
    long readBits(int row, int bitOffset, int bitWidth);

    /**
     * Writes the low {@code bitWidth} bits of {@code value} into row {@code row},
     * starting at {@code bitOffset} within the row.
     */
    void writeBits(int row, int bitOffset, int bitWidth, long value);

    /**
     * Serializes the raw bit data of this store to the given stream.
     *
     * <p>The output stream is <em>not</em> closed by this method.  The binary format is
     * self-describing (magic bytes, type tag, capacity, rowStride, then the raw longs) and
     * is compatible with {@link #read(InputStream)}.
     *
     * @param out  destination stream; must not be {@code null}
     * @throws IOException if an I/O error occurs during writing
     */
    void write(OutputStream out) throws IOException;

    /**
     * Loads raw bit data from the given stream into this store, replacing its current contents.
     *
     * <p>The stream must have been written by {@link #write(OutputStream)} of a store of the
     * <em>same type</em>, with the same {@link #capacity()} and {@link #rowStrideLongs()}.
     * The input stream is <em>not</em> closed by this method.
     *
     * @param in  source stream; must not be {@code null}
     * @throws IOException              if an I/O error occurs during reading
     * @throws IllegalArgumentException if the stream metadata does not match this store
     */
    void read(InputStream in) throws IOException;

    // -----------------------------------------------------------------------
    // Batch write support

    /**
     * Signals the start of a batch write operation.
     *
     * <p>Implementations may defer expensive post-write operations (e.g. octree collapse)
     * until {@link #endBatch()} is called.  For flat stores ({@link PackedDataStore},
     * {@link SparseDataStore}) this is a no-op.</p>
     */
    default void beginBatch() {}

    /**
     * Signals the end of a batch write operation and flushes any deferred work.
     *
     * <p>Must be called after every {@link #beginBatch()} to ensure correctness.
     * For flat stores this is a no-op.</p>
     */
    default void endBatch() {}

    // -----------------------------------------------------------------------
    // Static factories

    /**
     * Creates a dense (packed) DataStore backed by a contiguous {@code long[]} for all rows.
     *
     * @param capacity         maximum number of rows
     * @param componentClasses one or more annotated component types to register
     * @throws IllegalArgumentException if no component classes are supplied, if any class
     *         has no annotated fields, or if the resulting array would overflow
     */
    static DataStore of(int capacity, Class<?>... componentClasses) {
        return PackedDataStore.create(capacity, componentClasses);
    }

    /**
     * Creates a dense (packed) DataStore; equivalent to {@link #of}.
     */
    static DataStore packed(int capacity, Class<?>... componentClasses) {
        return PackedDataStore.create(capacity, componentClasses);
    }

    /**
     * Creates a sparse DataStore that allocates storage only for rows that are written.
     * Rows that have never been written read back as all-zeros (i.e. the minimum value
     * for integer/decimal fields, {@code false} for booleans, ordinal 0 for enums).
     *
     * @param capacity         maximum number of rows
     * @param componentClasses one or more annotated component types to register
     * @throws IllegalArgumentException if no component classes are supplied, if any class
     *         has no annotated fields, or if {@code capacity} is negative
     */
    static DataStore sparse(int capacity, Class<?>... componentClasses) {
        return SparseDataStore.create(capacity, componentClasses);
    }
}
