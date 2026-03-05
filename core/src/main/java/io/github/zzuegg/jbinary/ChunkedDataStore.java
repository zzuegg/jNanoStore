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
 * A chunk-based {@link DataStore} optimised for large 3-D voxel worlds.
 *
 * <h2>Design</h2>
 * <p>The world is partitioned into fixed-size cubic chunks of
 * {@value #CHUNK_SIZE}×{@value #CHUNK_SIZE}×{@value #CHUNK_SIZE} = 4 096 voxels each.
 * Each non-empty chunk is backed by a compact {@link PackedDataStore} of exactly
 * {@value #CHUNK_VOXELS} rows.  Chunks are indexed by a sparse open-addressing hash map
 * keyed on the Morton-encoded chunk coordinate — no boxing, ~O(1) lookup.</p>
 *
 * <h2>Performance characteristics</h2>
 * <ul>
 *   <li><strong>Read path</strong>: one hash-map probe (≈2–5 ns) + one
 *       {@code PackedDataStore} bit-read (≈3 ns) ≈ 6–10 ns per voxel.</li>
 *   <li><strong>Memory</strong>: only allocated chunks consume storage.  Unwritten regions
 *       (e.g. all-air above the terrain surface) use zero memory beyond the hash map
 *       entry skeleton.</li>
 *   <li><strong>Cache locality</strong>: all 4 096 voxels of a 16³ chunk are in one
 *       contiguous {@code long[]}, fitting comfortably in L1/L2 cache.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ChunkedDataStore<Voxel> world = DataStore.chunked(1024, 256, 1024, Voxel.class);
 * IntAccessor material = Accessors.intFieldInStore(world, Voxel.class, "material");
 *
 * material.set(world, world.row(100, 64, 200), 5);
 * int m = material.get(world, world.row(100, 64, 200));  // → 5
 * }</pre>
 *
 * <p>Use {@link DataStore#chunked} to create instances.</p>
 *
 * @param <T> marker type for the stored component(s)
 */
public final class ChunkedDataStore<T> implements DataStore<T> {

    /** Number of voxels per axis in one chunk. */
    public static final int CHUNK_SIZE = 16;

    /** log₂(CHUNK_SIZE): bits used per axis in the in-chunk Morton code. */
    private static final int CHUNK_BITS = 4;

    /** Number of voxels per chunk. */
    public static final int CHUNK_VOXELS = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE; // 4096

    /**
     * Number of bits in the in-chunk Morton code (3 axes × 4 bits each = 12).
     * The full row Morton code is: {@code chunkMorton << 12 | inChunkMorton}.
     */
    private static final int IN_CHUNK_MORTON_BITS = 3 * CHUNK_BITS; // 12

    /** Mask to extract the in-chunk Morton code from a full row index. */
    private static final int IN_CHUNK_MORTON_MASK = CHUNK_VOXELS - 1; // 0xFFF

    // -----------------------------------------------------------------------
    // Schema fields (shared across all chunks)

    /** Per-component absolute bit offset within a row. */
    private final Map<Class<?>, Integer> componentBitOffsets;

    /** Number of {@code long} words per row in each chunk's backing store. */
    private final int rowStrideLongs;

    /** Component classes, kept in order for chunk allocation and serialisation. */
    private final Class<?>[] componentClasses;

    // -----------------------------------------------------------------------
    // World geometry

    private final int worldX;
    private final int worldY;
    private final int worldZ;
    private final int capacity;  // worldX * worldY * worldZ

    // -----------------------------------------------------------------------
    // Sparse chunk index — open-addressing hash map (no boxing)

    /** Sentinel for an empty hash slot. */
    private static final long EMPTY_KEY = Long.MIN_VALUE;

    private long[] hashKeys;
    @SuppressWarnings("unchecked")
    private PackedDataStore<T>[] hashValues;
    private int hashMask;       // capacity - 1
    private int hashSize;       // number of occupied slots
    private int hashThreshold;  // resize at hashThreshold occupied slots

    // -----------------------------------------------------------------------
    // Serialisation type tag

    private static final int  MAGIC        = 0x4A42494E; // "JBIN"
    private static final byte TYPE_CHUNKED = 4;

    // -----------------------------------------------------------------------
    // Construction

    @SuppressWarnings("unchecked")
    static <T> ChunkedDataStore<T> create(int worldX, int worldY, int worldZ,
                                          Class<?>... componentClasses) {
        if (worldX < 1 || worldY < 1 || worldZ < 1) {
            throw new IllegalArgumentException(
                    "World dimensions must each be >= 1, got ("
                    + worldX + "," + worldY + "," + worldZ + ")");
        }
        if (componentClasses == null || componentClasses.length == 0) {
            throw new IllegalArgumentException("At least one component class is required");
        }

        // Build shared schema
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

        long cap = (long) worldX * worldY * worldZ;
        if (cap > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "World too large: " + worldX + "×" + worldY + "×" + worldZ
                    + " = " + cap + " voxels exceeds Integer.MAX_VALUE");
        }

        return new ChunkedDataStore<>(worldX, worldY, worldZ, (int) cap,
                rowStrideLongs, Collections.unmodifiableMap(offsets),
                componentClasses.clone());
    }

    @SuppressWarnings("unchecked")
    private ChunkedDataStore(int worldX, int worldY, int worldZ, int capacity,
                             int rowStrideLongs,
                             Map<Class<?>, Integer> componentBitOffsets,
                             Class<?>[] componentClasses) {
        this.worldX = worldX;
        this.worldY = worldY;
        this.worldZ = worldZ;
        this.capacity = capacity;
        this.rowStrideLongs = rowStrideLongs;
        this.componentBitOffsets = componentBitOffsets;
        this.componentClasses = componentClasses;

        // Initial hash table: 16 slots
        int initCap = 16;
        this.hashKeys   = new long[initCap];
        this.hashValues = new PackedDataStore[initCap];
        Arrays.fill(this.hashKeys, EMPTY_KEY);
        this.hashMask      = initCap - 1;
        this.hashSize      = 0;
        this.hashThreshold = initCap / 2;
    }

    // -----------------------------------------------------------------------
    // 3-D coordinate → row index

    /**
     * Converts 3-D voxel coordinates to the flat row index expected by
     * {@link #readBits} and {@link #writeBits}.
     *
     * <p>The row index is the Morton-encoded (Z-order) interleaving of
     * {@code (x, y, z)}, which ensures that voxels within the same 16³ chunk
     * share the same top bits and are therefore found in the same
     * {@link PackedDataStore} chunk.
     *
     * @param x  X coordinate; must be in {@code [0, worldX)}
     * @param y  Y coordinate; must be in {@code [0, worldY)}
     * @param z  Z coordinate; must be in {@code [0, worldZ)}
     * @return   Morton-encoded row index
     */
    public int row(int x, int y, int z) {
        if (x < 0 || x >= worldX || y < 0 || y >= worldY || z < 0 || z >= worldZ) {
            throw new IllegalArgumentException(
                    "Coordinates (" + x + "," + y + "," + z
                    + ") out of bounds for world (" + worldX + "," + worldY + "," + worldZ + ")");
        }
        return (int) morton3D(x, y, z);
    }

    // -----------------------------------------------------------------------
    // DataStore implementation

    @Override
    public long readBits(int row, int bitOffset, int bitWidth) {
        long chunkKey   = Integer.toUnsignedLong(row) >>> IN_CHUNK_MORTON_BITS;
        int  inChunkRow = row & IN_CHUNK_MORTON_MASK;
        PackedDataStore<T> chunk = findChunk(chunkKey);
        if (chunk == null) return 0L;
        return chunk.readBits(inChunkRow, bitOffset, bitWidth);
    }

    @Override
    public void writeBits(int row, int bitOffset, int bitWidth, long value) {
        long chunkKey   = Integer.toUnsignedLong(row) >>> IN_CHUNK_MORTON_BITS;
        int  inChunkRow = row & IN_CHUNK_MORTON_MASK;
        PackedDataStore<T> chunk = getOrCreateChunk(chunkKey);
        chunk.writeBits(inChunkRow, bitOffset, bitWidth, value);
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

    /** Returns the number of chunks that have been allocated (written at least once). */
    public int allocatedChunkCount() { return hashSize; }

    /** Returns the configured chunk size (voxels per axis; always {@value #CHUNK_SIZE}). */
    public int chunkSize() { return CHUNK_SIZE; }

    // -----------------------------------------------------------------------
    // Chunk hash map helpers

    /** Finds the chunk for {@code key}, or {@code null} if not allocated. */
    private PackedDataStore<T> findChunk(long key) {
        int slot = hashSlot(key);
        while (true) {
            long k = hashKeys[slot];
            if (k == EMPTY_KEY) return null;
            if (k == key)       return hashValues[slot];
            slot = (slot + 1) & hashMask;
        }
    }

    /**
     * Returns the chunk for {@code key}, allocating a new
     * {@link PackedDataStore} if it does not yet exist.
     */
    @SuppressWarnings("unchecked")
    private PackedDataStore<T> getOrCreateChunk(long key) {
        // Fast path: try to find existing slot
        int slot = hashSlot(key);
        while (true) {
            long k = hashKeys[slot];
            if (k == key)       return hashValues[slot];
            if (k == EMPTY_KEY) break;
            slot = (slot + 1) & hashMask;
        }
        // Slot is empty — allocate new chunk
        PackedDataStore<T> chunk = newChunk();
        hashKeys[slot]   = key;
        hashValues[slot] = chunk;
        hashSize++;
        if (hashSize >= hashThreshold) rehash();
        return chunk;
    }

    @SuppressWarnings("unchecked")
    private PackedDataStore<T> newChunk() {
        return (PackedDataStore<T>) PackedDataStore.create(CHUNK_VOXELS, componentClasses);
    }

    /** Returns the initial probe slot for a chunk key. */
    private int hashSlot(long key) {
        // Fibonacci/Knuth multiplicative hash — good distribution for Morton codes
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> 32) & hashMask;
    }

    @SuppressWarnings("unchecked")
    private void rehash() {
        int newCap = hashKeys.length * 2;
        long[] newKeys = new long[newCap];
        PackedDataStore<T>[] newVals = new PackedDataStore[newCap];
        Arrays.fill(newKeys, EMPTY_KEY);
        int newMask = newCap - 1;
        for (int i = 0; i < hashKeys.length; i++) {
            long k = hashKeys[i];
            if (k == EMPTY_KEY) continue;
            int slot = (int) ((k * 0x9E3779B97F4A7C15L) >>> 32) & newMask;
            while (newKeys[slot] != EMPTY_KEY) slot = (slot + 1) & newMask;
            newKeys[slot]  = k;
            newVals[slot]  = hashValues[i];
        }
        hashKeys      = newKeys;
        hashValues    = newVals;
        hashMask      = newMask;
        hashThreshold = newCap / 2;
    }

    // -----------------------------------------------------------------------
    // Morton encoding / decoding

    /** 3-D Morton code: interleaves bits of x, y, z (each ≤ 10 bits). */
    private static long morton3D(int x, int y, int z) {
        return spread3(x) | (spread3(y) << 1) | (spread3(z) << 2);
    }

    /**
     * Spreads a value ≤ 10 bits wide so each source bit occupies every 3rd position
     * in the output (30-bit result).  Supports world coordinates up to 1 024 per axis.
     */
    private static long spread3(int v) {
        long x = v & 0x3FFL;
        x = (x ^ (x << 16)) & 0xFF0000FFL;
        x = (x ^ (x <<  8)) & 0x0F00F00FL;
        x = (x ^ (x <<  4)) & 0xC30C30C3L;
        x = (x ^ (x <<  2)) & 0x49249249L;
        return x;
    }

    // -----------------------------------------------------------------------
    // Serialisation (type tag = 4)

    @Override
    public void write(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(MAGIC);
        dos.writeByte(TYPE_CHUNKED);
        dos.writeInt(worldX);
        dos.writeInt(worldY);
        dos.writeInt(worldZ);
        dos.writeInt(rowStrideLongs);
        dos.writeInt(hashSize);  // number of allocated chunks
        for (int i = 0; i < hashKeys.length; i++) {
            if (hashKeys[i] == EMPTY_KEY) continue;
            dos.writeLong(hashKeys[i]);
            // write the chunk's raw data directly (without the chunk's own header)
            PackedDataStore<T> chunk = hashValues[i];
            for (int row = 0; row < CHUNK_VOXELS; row++) {
                for (int w = 0; w < chunk.rowStrideLongs(); w++) {
                    dos.writeLong(chunk.readBits(row, w * 64, 64));
                }
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
        if (type != TYPE_CHUNKED) throw new IOException(
                "Expected chunked store (type 4), got type " + type);
        int wx     = dis.readInt();
        int wy     = dis.readInt();
        int wz     = dis.readInt();
        int stride = dis.readInt();
        if (wx != worldX || wy != worldY || wz != worldZ || stride != rowStrideLongs) {
            throw new IllegalArgumentException(
                    "Store metadata mismatch: stream has world=(" + wx + "," + wy + "," + wz
                    + ") stride=" + stride + " but store has world=(" + worldX + "," + worldY
                    + "," + worldZ + ") stride=" + rowStrideLongs);
        }
        // clear existing chunks
        Arrays.fill(hashKeys, EMPTY_KEY);
        Arrays.fill(hashValues, null);
        hashSize = 0;

        int numChunks = dis.readInt();
        for (int c = 0; c < numChunks; c++) {
            long key = dis.readLong();
            PackedDataStore<T> chunk = getOrCreateChunk(key);
            for (int row = 0; row < CHUNK_VOXELS; row++) {
                for (int w = 0; w < rowStrideLongs; w++) {
                    long word = dis.readLong();
                    chunk.writeBits(row, w * 64, 64, word);
                }
            }
        }
    }
}
