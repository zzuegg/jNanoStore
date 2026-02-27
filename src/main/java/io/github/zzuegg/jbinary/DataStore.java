package io.github.zzuegg.jbinary;

/**
 * Base interface for a bit-packed datastore.
 *
 * <p>A DataStore holds {@link #capacity()} rows; each row can contain data from one or
 * more annotated component types packed together in a fixed bit-stride.  Two concrete
 * implementations are provided:
 * <ul>
 *   <li>{@link PackedDataStore} — dense, pre-allocates a single {@code long[]} for all rows.</li>
 *   <li>{@link SparseDataStore} — sparse, allocates storage only for rows that are written to;
 *       unwritten rows read back as all-zeros.</li>
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
