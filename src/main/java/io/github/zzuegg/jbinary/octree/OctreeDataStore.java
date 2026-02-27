package io.github.zzuegg.jbinary.octree;

import io.github.zzuegg.jbinary.DataStore;
import io.github.zzuegg.jbinary.schema.ComponentLayout;
import io.github.zzuegg.jbinary.schema.LayoutBuilder;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

/**
 * A sparse, hierarchical {@link DataStore} backed by an octree structure.
 *
 * <h2>How it works</h2>
 * <p>The datastore represents a {@code sideLength × sideLength × sideLength} voxel space
 * where {@code sideLength = 1 << maxDepth}.  Internally, nodes are stored in a
 * {@link HashMap} keyed by a Morton-code node ID; only nodes that have been written
 * (or that represent collapsed regions) are allocated.</p>
 *
 * <p>On each write:
 * <ol>
 *   <li>Any collapsed ancestor node that covers the target voxel is <em>expanded</em>
 *       into its 8 children first, preserving existing values.</li>
 *   <li>The leaf node is written.</li>
 *   <li>The tree is <em>collapsed</em> bottom-up: if all 8 siblings of the modified
 *       node satisfy every registered {@link CollapsingFunction}, the 8 children are
 *       removed and a single representative node is stored at the parent level.</li>
 * </ol>
 *
 * <h2>Reading</h2>
 * <p>A read traverses from the finest (leaf) level upward until a node covering
 * {@code (x, y, z)} is found.  If none exists, 0 is returned (equivalent to the
 * minimum value for each field type).</p>
 *
 * <h2>Row index</h2>
 * <p>This class implements {@link DataStore}, so all existing
 * {@link io.github.zzuegg.jbinary.Accessors} factory methods work unchanged.  The
 * {@code row} parameter used by those accessors is a Morton-code (Z-order curve)
 * encoding of {@code (x, y, z)}.  Obtain it via {@link #row(int, int, int)}:</p>
 * <pre>{@code
 * IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");
 * height.set(store, store.row(10, 5, 3), 128);
 * int h = height.get(store, store.row(10, 5, 3));  // → 128
 * }</pre>
 *
 * <h2>Creation</h2>
 * Use the {@link Builder} returned by {@link #builder(int)}.  Every component must be
 * registered exactly once; a collapsing function may be supplied per component
 * (default: {@link CollapsingFunction#equalBits()}).
 * <pre>{@code
 * OctreeDataStore store = OctreeDataStore.builder(6)   // 64 × 64 × 64 space
 *     .component(Terrain.class)                        // collapse on bit-equality
 *     .component(Water.class, CollapsingFunction.never())
 *     .build();
 * }</pre>
 *
 * <p><strong>Thread safety:</strong> not thread-safe; external synchronisation is required
 * for concurrent access.</p>
 *
 * <p><strong>maxDepth limit:</strong> 1–10 (sideLength 2–1024; capacity 8–2^30).</p>
 */
public final class OctreeDataStore<T> implements DataStore<T> {

    private final int maxDepth;
    private final int sideLength;   // 1 << maxDepth  (internal Morton grid resolution)
    private final int widthX;       // actual user-facing X bound
    private final int widthY;       // actual user-facing Y bound
    private final int widthZ;       // actual user-facing Z bound
    private final int capacity;     // widthX * widthY * widthZ
    private final int rowStrideLongs;

    // per-component metadata (insertion order preserved)
    private final Map<Class<?>, Integer> componentBitOffsets;
    private final Map<Class<?>, Integer> componentBitWidths;
    private final Map<Class<?>, CollapsingFunction> collapsingFunctions;

    // sparse node storage: nodeId → long[rowStrideLongs] row data
    private final HashMap<Long, long[]> nodes;

    // batch mode: when true, writeBitsAt defers tryCollapse until endBatch()
    private boolean inBatch = false;

    // -----------------------------------------------------------------------
    // Builder

    /**
     * Returns a {@link Builder} for a uniform octree with the given maximum depth.
     *
     * @param maxDepth  tree depth; must be 1–10.  Determines the voxel resolution:
     *                  {@code sideLength = 1 << maxDepth} voxels per axis.
     */
    public static Builder builder(int maxDepth) {
        return new Builder(maxDepth);
    }

    /**
     * Returns a {@link Builder} for a non-uniform octree with independent per-axis sizes.
     *
     * <p>Each dimension is rounded up to the next power of two internally (the Morton
     * grid resolution), but coordinate bounds checking uses the exact values given here.
     *
     * @param widthX  voxel count along the X axis; must be ≥ 1
     * @param widthY  voxel count along the Y axis; must be ≥ 1
     * @param widthZ  voxel count along the Z axis; must be ≥ 1
     */
    public static Builder builder(int widthX, int widthY, int widthZ) {
        return new Builder(widthX, widthY, widthZ);
    }

    /**
     * Builder for {@link OctreeDataStore}.
     *
     * <p>Register each component type with {@link #component(Class)} (uses default
     * {@link CollapsingFunction#equalBits()}) or
     * {@link #component(Class, CollapsingFunction)} for a custom function.  Call
     * {@link #build()} when done.</p>
     */
    public static final class Builder {

        private final int maxDepth;
        private final int widthX, widthY, widthZ;
        private final List<Class<?>> components = new ArrayList<>();
        private final Map<Class<?>, CollapsingFunction> functions = new LinkedHashMap<>();

        /** Uniform builder: widthX = widthY = widthZ = 1 << maxDepth. */
        private Builder(int maxDepth) {
            if (maxDepth < 1 || maxDepth > 10) {
                throw new IllegalArgumentException(
                        "maxDepth must be between 1 and 10, got " + maxDepth);
            }
            this.maxDepth = maxDepth;
            this.widthX = this.widthY = this.widthZ = 1 << maxDepth;
        }

        /** Non-uniform builder: each axis has its own size. */
        private Builder(int widthX, int widthY, int widthZ) {
            if (widthX < 1 || widthY < 1 || widthZ < 1) {
                throw new IllegalArgumentException(
                        "All dimensions must be >= 1, got widthX=" + widthX
                        + " widthY=" + widthY + " widthZ=" + widthZ);
            }
            int depthX = bitsForSize(widthX);
            int depthY = bitsForSize(widthY);
            int depthZ = bitsForSize(widthZ);
            int depth  = Math.max(depthX, Math.max(depthY, depthZ));
            if (depth > 10) {
                throw new IllegalArgumentException(
                        "Dimensions too large: resulting maxDepth=" + depth + " exceeds 10");
            }
            this.maxDepth = depth;
            this.widthX = widthX;
            this.widthY = widthY;
            this.widthZ = widthZ;
        }

        private static int bitsForSize(int n) {
            return Math.max(1, 64 - Long.numberOfLeadingZeros(n - 1));
        }

        /**
         * Registers a component type with the default {@link CollapsingFunction#equalBits()}
         * collapsing function (collapse when all 8 children are bit-identical).
         */
        public Builder component(Class<?> cls) {
            return component(cls, CollapsingFunction.equalBits());
        }

        /**
         * Registers a component type with an explicit {@link CollapsingFunction}.
         *
         * @param cls  annotated record or class to register
         * @param fn   collapsing function to use for this component type
         */
        public Builder component(Class<?> cls, CollapsingFunction fn) {
            Objects.requireNonNull(cls, "component class must not be null");
            Objects.requireNonNull(fn, "collapsing function must not be null");
            if (functions.containsKey(cls)) {
                throw new IllegalArgumentException(
                        "Component " + cls.getSimpleName() + " is already registered");
            }
            components.add(cls);
            functions.put(cls, fn);
            return this;
        }

        /** Builds and returns the configured {@link OctreeDataStore}. */
        public OctreeDataStore<?> build() {
            if (components.isEmpty()) {
                throw new IllegalArgumentException("At least one component class is required");
            }
            return OctreeDataStore.create(maxDepth, widthX, widthY, widthZ, components, functions);
        }
    }

    // -----------------------------------------------------------------------
    // Internal factory

    @SuppressWarnings("unchecked")
    private static <T> OctreeDataStore<T> create(int maxDepth, int widthX, int widthY, int widthZ,
                                                   List<Class<?>> components,
                                                   Map<Class<?>, CollapsingFunction> functions) {
        Map<Class<?>, ComponentLayout> layouts = new LinkedHashMap<>();
        for (Class<?> cls : components) {
            layouts.put(cls, LayoutBuilder.layout(cls));
        }

        int bitCursor = 0;
        Map<Class<?>, Integer> offsets = new LinkedHashMap<>();
        Map<Class<?>, Integer> widths  = new LinkedHashMap<>();
        for (Map.Entry<Class<?>, ComponentLayout> e : layouts.entrySet()) {
            offsets.put(e.getKey(), bitCursor);
            widths.put(e.getKey(), e.getValue().totalBits());
            bitCursor += e.getValue().totalBits();
        }
        int rowStride = Math.max(1, (bitCursor + 63) / 64);

        return new OctreeDataStore<>(maxDepth, widthX, widthY, widthZ, rowStride,
                Collections.unmodifiableMap(offsets),
                Collections.unmodifiableMap(widths),
                Collections.unmodifiableMap(new LinkedHashMap<>(functions)));
    }

    private OctreeDataStore(int maxDepth, int widthX, int widthY, int widthZ,
                             int rowStrideLongs,
                             Map<Class<?>, Integer> componentBitOffsets,
                             Map<Class<?>, Integer> componentBitWidths,
                             Map<Class<?>, CollapsingFunction> collapsingFunctions) {
        this.maxDepth = maxDepth;
        this.sideLength = 1 << maxDepth;
        this.widthX = widthX;
        this.widthY = widthY;
        this.widthZ = widthZ;
        this.capacity = widthX * widthY * widthZ;
        this.rowStrideLongs = rowStrideLongs;
        this.componentBitOffsets = componentBitOffsets;
        this.componentBitWidths = componentBitWidths;
        this.collapsingFunctions = collapsingFunctions;
        this.nodes = new HashMap<>();
    }

    // -----------------------------------------------------------------------
    // Coordinate helpers

    /**
     * Encodes 3D voxel coordinates into a Morton-code (Z-order) row index.
     * Pass the result as the {@code row} argument to accessor {@code get}/{@code set}.
     *
     * @param x  X coordinate, {@code [0, sideLength-1]}
     * @param y  Y coordinate, {@code [0, sideLength-1]}
     * @param z  Z coordinate, {@code [0, sideLength-1]}
     * @return   Morton-code row index in {@code [0, capacity-1]}
     * @throws IllegalArgumentException if any coordinate is out of bounds
     */
    public int row(int x, int y, int z) {
        checkCoords(x, y, z);
        return (int) morton3D(x, y, z);
    }

    // -----------------------------------------------------------------------
    // DataStore interface

    @Override public int capacity()        { return capacity; }
    @Override public int rowStrideLongs()  { return rowStrideLongs; }

    @Override
    public int componentBitOffset(Class<?> cls) {
        Integer off = componentBitOffsets.get(cls);
        if (off == null) throw new IllegalArgumentException(
                "Component " + cls.getSimpleName() + " not registered in this OctreeDataStore");
        return off;
    }

    /**
     * Reads {@code bitWidth} bits from the voxel whose row index equals {@code row}
     * (obtain via {@link #row(int, int, int)}).  Searches from leaf level upward for
     * the finest existing node covering the position; returns 0 if none is found.
     *
     * <p>The {@code row} value is itself the full-resolution Morton code, so this
     * method avoids decoding it to {@code (x,y,z)} and re-encoding per level.
     * Instead it shifts the Morton code right by 3 bits per level-up, exploiting
     * the identity {@code morton3D(x>>>k, y>>>k, z>>>k) == morton3D(x,y,z) >>> (3*k)}.
     */
    @Override
    public long readBits(int row, int bitOffset, int bitWidth) {
        long morton = (long) row;   // row IS the 30-bit Morton code; always non-negative
        for (int level = maxDepth, shift = 0; level >= 0; level--, shift += 3) {
            long[] rowData = nodes.get(((long) level << 30) | (morton >>> shift));
            if (rowData != null) {
                return readBitsFromRow(rowData, bitOffset, bitWidth);
            }
        }
        return 0L;
    }

    /**
     * Writes {@code bitWidth} bits to the leaf voxel whose row index equals {@code row}.
     * Expands any coarser collapsed ancestor before writing, then collapses the tree
     * upward as far as the registered collapsing functions allow.
     */
    @Override
    public void writeBits(int row, int bitOffset, int bitWidth, long value) {
        long morton = (long) row;
        int x = unspread3(morton);
        int y = unspread3(morton >>> 1);
        int z = unspread3(morton >>> 2);
        writeBitsAt(x, y, z, bitOffset, bitWidth, value);
    }

    // -----------------------------------------------------------------------
    // 3D read/write (public for direct use without Morton-code conversion)

    /**
     * Reads {@code bitWidth} bits for the voxel at 3D coordinates {@code (x, y, z)}.
     * The finest existing octree node covering the position is used; returns 0 if none.
     *
     * <p>Computes the full-resolution Morton code once, then shifts it right by 3 bits
     * per level-up, exploiting the identity
     * {@code morton3D(x>>>k, y>>>k, z>>>k) == morton3D(x,y,z) >>> (3*k)}.
     */
    public long readBitsAt(int x, int y, int z, int bitOffset, int bitWidth) {
        long morton = morton3D(x, y, z);
        for (int level = maxDepth, shift = 0; level >= 0; level--, shift += 3) {
            long[] rowData = nodes.get(((long) level << 30) | (morton >>> shift));
            if (rowData != null) {
                return readBitsFromRow(rowData, bitOffset, bitWidth);
            }
        }
        return 0L;
    }

    /**
     * Writes {@code bitWidth} bits to the leaf voxel at {@code (x, y, z)}.
     * Expands collapsed ancestors if needed, writes the leaf, then collapses upward.
     *
     * @throws IllegalArgumentException if any coordinate is out of bounds
     */
    public void writeBitsAt(int x, int y, int z, int bitOffset, int bitWidth, long value) {
        checkCoords(x, y, z);
        expandToLeaf(x, y, z);
        long[] row = nodes.computeIfAbsent(nodeId(maxDepth, x, y, z),
                                           k -> new long[rowStrideLongs]);
        writeBitsToRow(row, bitOffset, bitWidth, value);
        if (!inBatch) tryCollapse(maxDepth, x, y, z);
    }

    // -----------------------------------------------------------------------
    // Octree-specific metadata

    /** Returns the maximum depth of this octree (1–10). */
    public int maxDepth() { return maxDepth; }

    /**
     * Returns the number of voxels along each axis for uniform stores
     * ({@code 1 << maxDepth}).  For non-uniform stores use
     * {@link #widthX()}, {@link #widthY()}, {@link #widthZ()}.
     */
    public int sideLength() { return sideLength; }

    /** Returns the actual user-facing voxel count along the X axis. */
    public int widthX() { return widthX; }

    /** Returns the actual user-facing voxel count along the Y axis. */
    public int widthY() { return widthY; }

    /** Returns the actual user-facing voxel count along the Z axis. */
    public int widthZ() { return widthZ; }

    /**
     * Returns the number of nodes currently allocated (both leaf nodes and collapsed
     * interior nodes).  Useful for monitoring compression effectiveness.
     */
    public int nodeCount() { return nodes.size(); }

    // -----------------------------------------------------------------------
    // Batch mode

    @Override
    public void beginBatch() {
        this.inBatch = true;
    }

    @Override
    public void endBatch() {
        if (!inBatch) return;
        inBatch = false;
        // Snapshot all leaf-level keys to avoid ConcurrentModificationException
        List<Long> snapshot = new ArrayList<>(nodes.keySet());
        for (long key : snapshot) {
            // Check containsKey because an earlier tryCollapse in this loop may have
            // removed this leaf as part of collapsing its sibling group.
            if ((int)(key >>> 30) == maxDepth && nodes.containsKey(key)) {
                long morton = key & 0x3FFFFFFFL;
                int x = unspread3(morton);
                int y = unspread3(morton >>> 1);
                int z = unspread3(morton >>> 2);
                tryCollapse(maxDepth, x, y, z);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Serialization (type tag = 2)

    private static final int  MAGIC       = 0x4A42494E; // "JBIN"
    private static final byte TYPE_OCTREE = 2;

    @Override
    public void write(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(MAGIC);
        dos.writeByte(TYPE_OCTREE);
        dos.writeInt(widthX);
        dos.writeInt(widthY);
        dos.writeInt(widthZ);
        dos.writeInt(rowStrideLongs);
        dos.writeInt(nodes.size());
        for (Map.Entry<Long, long[]> entry : nodes.entrySet()) {
            dos.writeLong(entry.getKey());
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
        if (type != TYPE_OCTREE) throw new IOException(
                "Expected octree store (type 2), got type " + type);
        int wx     = dis.readInt();
        int wy     = dis.readInt();
        int wz     = dis.readInt();
        int stride = dis.readInt();
        if (wx != widthX || wy != widthY || wz != widthZ || stride != rowStrideLongs) {
            throw new IllegalArgumentException(
                    "Store metadata mismatch: stream has widthX=" + wx + " widthY=" + wy
                    + " widthZ=" + wz + " rowStride=" + stride
                    + " but store has widthX=" + widthX + " widthY=" + widthY
                    + " widthZ=" + widthZ + " rowStride=" + rowStrideLongs);
        }
        nodes.clear();
        int nodeCount = dis.readInt();
        for (int i = 0; i < nodeCount; i++) {
            long nodeId = dis.readLong();
            long[] data = new long[rowStrideLongs];
            for (int j = 0; j < rowStrideLongs; j++) {
                data[j] = dis.readLong();
            }
            nodes.put(nodeId, data);
        }
    }

    // -----------------------------------------------------------------------
    // Ancestor expansion

    /**
     * Ensures no collapsed ancestor covers {@code (x,y,z)}.  If one is found it is
     * expanded into 8 children, each cloning the ancestor's data.  The process is
     * repeated level-by-level until the leaf level is reached.
     */
    private void expandToLeaf(int x, int y, int z) {
        // Walk from root down to the level just above leaves.
        // If a node is found at level L, expand it to 8 children at L+1;
        // those children are then checked in subsequent iterations.
        for (int level = 0; level < maxDepth; level++) {
            long id = nodeId(level,
                             x >>> (maxDepth - level),
                             y >>> (maxDepth - level),
                             z >>> (maxDepth - level));
            long[] data = nodes.get(id);
            if (data != null) {
                expandNode(level,
                           x >>> (maxDepth - level),
                           y >>> (maxDepth - level),
                           z >>> (maxDepth - level),
                           data);
            }
        }
    }

    /** Replaces node {@code (level, cx, cy, cz)} with 8 cloned children at {@code level+1}. */
    private void expandNode(int level, int cx, int cy, int cz, long[] data) {
        nodes.remove(nodeId(level, cx, cy, cz));
        for (int i = 0; i < 8; i++) {
            int dx = i & 1, dy = (i >> 1) & 1, dz = (i >> 2) & 1;
            nodes.put(nodeId(level + 1, cx * 2 + dx, cy * 2 + dy, cz * 2 + dz),
                      data.clone());
        }
    }

    // -----------------------------------------------------------------------
    // Bottom-up collapse

    /**
     * Starting from {@code level}, checks whether the 8 nodes at {@code level} that
     * share a parent can be collapsed.  If so, removes them, stores the representative
     * at {@code level-1}, and recurses upward.
     *
     * @param level  the level of the just-modified node (maxDepth on first call)
     * @param x,y,z  full-resolution leaf coordinates (used to identify the subtree)
     */
    private void tryCollapse(int level, int x, int y, int z) {
        if (level == 0) return;

        int parentLevel = level - 1;
        int pcx = x >>> (maxDepth - parentLevel);
        int pcy = y >>> (maxDepth - parentLevel);
        int pcz = z >>> (maxDepth - parentLevel);

        // Gather the 8 children of the parent
        long[][] children = new long[8][];
        for (int i = 0; i < 8; i++) {
            int dx = i & 1, dy = (i >> 1) & 1, dz = (i >> 2) & 1;
            children[i] = nodes.get(nodeId(level, pcx * 2 + dx, pcy * 2 + dy, pcz * 2 + dz));
        }

        // Every registered component must agree to collapse
        for (Map.Entry<Class<?>, CollapsingFunction> e : collapsingFunctions.entrySet()) {
            Class<?> cls = e.getKey();
            int offset = componentBitOffsets.get(cls);
            int width  = componentBitWidths.get(cls);
            if (!e.getValue().canCollapse(offset, width, rowStrideLongs, children)) return;
        }

        // Pick the first non-null child as the representative value
        long[] rep = null;
        for (long[] c : children) {
            if (c != null) { rep = c.clone(); break; }
        }
        if (rep == null) rep = new long[rowStrideLongs];  // all null = all-zeros default

        // Remove the 8 children and promote the representative to the parent
        for (int i = 0; i < 8; i++) {
            int dx = i & 1, dy = (i >> 1) & 1, dz = (i >> 2) & 1;
            nodes.remove(nodeId(level, pcx * 2 + dx, pcy * 2 + dy, pcz * 2 + dz));
        }
        nodes.put(nodeId(parentLevel, pcx, pcy, pcz), rep);

        // Recurse upward
        tryCollapse(parentLevel, x, y, z);
    }

    // -----------------------------------------------------------------------
    // Low-level bit read/write (identical to PackedDataStore)

    private long readBitsFromRow(long[] row, int bitOffset, int bitWidth) {
        int wordIndex = bitOffset >>> 6;
        int shift     = bitOffset & 63;
        long word = row[wordIndex];
        if (shift + bitWidth <= 64) {
            long mask = bitWidth == 64 ? -1L : (1L << bitWidth) - 1L;
            return (word >>> shift) & mask;
        } else {
            int bitsInFirst = 64 - shift;
            long lo = word >>> shift;
            long hi = row[wordIndex + 1] & ((1L << (bitWidth - bitsInFirst)) - 1L);
            return lo | (hi << bitsInFirst);
        }
    }

    private void writeBitsToRow(long[] row, int bitOffset, int bitWidth, long value) {
        int wordIndex = bitOffset >>> 6;
        int shift     = bitOffset & 63;
        long mask = bitWidth == 64 ? -1L : (1L << bitWidth) - 1L;
        long bits = value & mask;
        if (shift + bitWidth <= 64) {
            long shiftedMask = mask << shift;
            row[wordIndex] = (row[wordIndex] & ~shiftedMask) | (bits << shift);
        } else {
            int bitsInFirst = 64 - shift;
            long maskLo = (1L << bitsInFirst) - 1L;
            row[wordIndex] = (row[wordIndex] & ~(maskLo << shift)) | ((bits & maskLo) << shift);
            int bitsInSecond = bitWidth - bitsInFirst;
            long maskHi = (1L << bitsInSecond) - 1L;
            row[wordIndex + 1] = (row[wordIndex + 1] & ~maskHi) | (bits >>> bitsInFirst);
        }
    }

    // -----------------------------------------------------------------------
    // Morton-code helpers (10-bit per axis, 30-bit total; maxDepth ≤ 10)

    /**
     * Encodes a node: level uses the top 5 bits (bits 30–34), Morton code the lower 30.
     * Supports level 0–31 and coordinates up to 10 bits each.
     */
    private static long nodeId(int level, int cx, int cy, int cz) {
        return ((long) level << 30) | morton3D(cx, cy, cz);
    }

    /** 3D Morton code: interleaves bits of x, y, z (each ≤ 10 bits). */
    private static long morton3D(int x, int y, int z) {
        return spread3(x) | (spread3(y) << 1) | (spread3(z) << 2);
    }

    /** Spreads a 10-bit integer so each bit occupies every 3rd position (30-bit output). */
    private static long spread3(int v) {
        long x = v & 0x3FFL;
        x = (x ^ (x << 16)) & 0xFF0000FFL;
        x = (x ^ (x <<  8)) & 0x0F00F00FL;
        x = (x ^ (x <<  4)) & 0xC30C30C3L;
        x = (x ^ (x <<  2)) & 0x49249249L;
        return x;
    }

    /** Inverse of {@link #spread3}: compacts every-3rd bit back into a 10-bit integer. */
    private static int unspread3(long v) {
        v &= 0x49249249L;
        v = (v ^ (v >>>  2)) & 0xC30C30C3L;
        v = (v ^ (v >>>  4)) & 0x0F00F00FL;
        v = (v ^ (v >>>  8)) & 0xFF0000FFL;
        v = (v ^ (v >>> 16)) & 0x3FFL;
        return (int) v;
    }

    private void checkCoords(int x, int y, int z) {
        if (x < 0 || x >= widthX || y < 0 || y >= widthY || z < 0 || z >= widthZ) {
            throw new IllegalArgumentException(
                    "Coordinates (" + x + "," + y + "," + z +
                    ") out of bounds for widthX=" + widthX
                    + " widthY=" + widthY + " widthZ=" + widthZ);
        }
    }
}
