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
 * A high-performance sparse, hierarchical {@link DataStore} backed by an octree structure.
 *
 * <p>Functionally identical to {@link OctreeDataStore} but uses:
 * <ol>
 *   <li>A hand-rolled open-addressing (linear probing) hash map with primitive {@code long}
 *       keys — eliminates boxing overhead of {@code HashMap<Long, long[]>}.</li>
 *   <li>A flat arena {@code long[]} for all node row data — improves cache locality.</li>
 *   <li>Iterative (non-recursive) {@code tryCollapse}.</li>
 *   <li>Full batch-mode support via {@link #beginBatch()} / {@link #endBatch()}.</li>
 * </ol>
 *
 * <h2>Creation</h2>
 * Use the {@link Builder} returned by {@link #builder(int)}:
 * <pre>{@code
 * FastOctreeDataStore store = FastOctreeDataStore.builder(6)
 *     .component(Terrain.class)
 *     .component(Water.class, CollapsingFunction.never())
 *     .build();
 * }</pre>
 *
 * <p><strong>Thread safety:</strong> not thread-safe.</p>
 * <p><strong>maxDepth limit:</strong> 1–10.</p>
 */
public final class FastOctreeDataStore<T> implements DataStore<T> {

    // -----------------------------------------------------------------------
    // Hash map sentinels and constants

    private static final long KEY_EMPTY = Long.MIN_VALUE;       // empty bucket
    private static final long KEY_TOMB  = Long.MIN_VALUE + 1L;  // tombstone
    private static final int  NO_SLOT   = -1;                   // "not found"
    // Rehash when (live+dead+1)/capacity > 75%
    private static final int  LOAD_NUMERATOR   = 3;
    private static final int  LOAD_DENOMINATOR = 4;

    // -----------------------------------------------------------------------
    // Fields

    private final int maxDepth;
    private final int sideLength;   // 1 << maxDepth  (internal Morton grid resolution)
    private final int widthX;       // actual user-facing X bound
    private final int widthY;       // actual user-facing Y bound
    private final int widthZ;       // actual user-facing Z bound
    private final int capacity;     // widthX * widthY * widthZ
    private final int rowStrideLongs;

    private final Map<Class<?>, Integer> componentBitOffsets;
    private final Map<Class<?>, Integer> componentBitWidths;
    private final Map<Class<?>, CollapsingFunction> collapsingFunctions;

    // Open-addressing hash map: primitive long key → int arena-slot index
    private long[] mapKeys;
    private int[]  mapVals;
    private int    mapMask;
    private int    mapLive;
    private int    mapDead;

    // Arena allocator: slot s occupies arena[s*rowStrideLongs .. (s+1)*rowStrideLongs-1]
    private long[] arena;
    private int    arenaLive;
    private int[]  freeStack;
    private int    freeTop;

    // Reusable collapse buffer (avoids per-collapse allocation)
    private final long[][] collapseBuffer;

    // Batch mode
    private boolean inBatch = false;

    // -----------------------------------------------------------------------
    // Builder

    /** Returns a {@link Builder} for a uniform fast octree with the given maximum depth. */
    public static Builder builder(int maxDepth) {
        return new Builder(maxDepth);
    }

    /**
     * Returns a {@link Builder} for a non-uniform fast octree with independent per-axis sizes.
     *
     * @param widthX  voxel count along the X axis; must be ≥ 1
     * @param widthY  voxel count along the Y axis; must be ≥ 1
     * @param widthZ  voxel count along the Z axis; must be ≥ 1
     */
    public static Builder builder(int widthX, int widthY, int widthZ) {
        return new Builder(widthX, widthY, widthZ);
    }

    /** Builder for {@link FastOctreeDataStore}. */
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

        /** Registers a component with the default {@link CollapsingFunction#equalBits()} function. */
        public Builder component(Class<?> cls) {
            return component(cls, CollapsingFunction.equalBits());
        }

        /** Registers a component with an explicit {@link CollapsingFunction}. */
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

        /** Builds and returns the configured {@link FastOctreeDataStore}. */
        public FastOctreeDataStore<?> build() {
            if (components.isEmpty()) {
                throw new IllegalArgumentException("At least one component class is required");
            }
            return FastOctreeDataStore.create(maxDepth, widthX, widthY, widthZ, components, functions);
        }
    }

    // -----------------------------------------------------------------------
    // Internal factory

    @SuppressWarnings("unchecked")
    private static <T> FastOctreeDataStore<T> create(int maxDepth, int widthX, int widthY, int widthZ,
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

        return new FastOctreeDataStore<>(maxDepth, widthX, widthY, widthZ, rowStride,
                Collections.unmodifiableMap(offsets),
                Collections.unmodifiableMap(widths),
                Collections.unmodifiableMap(new LinkedHashMap<>(functions)));
    }

    private FastOctreeDataStore(int maxDepth, int widthX, int widthY, int widthZ,
                                 int rowStrideLongs,
                                 Map<Class<?>, Integer> offsets,
                                 Map<Class<?>, Integer> widths,
                                 Map<Class<?>, CollapsingFunction> functions) {
        this.maxDepth = maxDepth;
        this.sideLength = 1 << maxDepth;
        this.widthX = widthX;
        this.widthY = widthY;
        this.widthZ = widthZ;
        this.capacity = widthX * widthY * widthZ;
        this.rowStrideLongs = rowStrideLongs;
        this.componentBitOffsets = offsets;
        this.componentBitWidths = widths;
        this.collapsingFunctions = functions;
        this.collapseBuffer = new long[8][rowStrideLongs];
        hashInit(64);
        this.arena = new long[32 * rowStrideLongs];
        this.arenaLive = 0;
        this.freeStack = new int[16];
        this.freeTop = 0;
    }

    // -----------------------------------------------------------------------
    // DataStore interface

    @Override public int capacity()       { return capacity; }
    @Override public int rowStrideLongs() { return rowStrideLongs; }

    @Override
    public int componentBitOffset(Class<?> cls) {
        Integer off = componentBitOffsets.get(cls);
        if (off == null) throw new IllegalArgumentException(
                "Component " + cls.getSimpleName() + " not registered in this FastOctreeDataStore");
        return off;
    }

    @Override
    public long readBits(int row, int bitOffset, int bitWidth) {
        long morton = (long) row;   // row IS the 30-bit Morton code; always non-negative
        for (int level = maxDepth, shift = 0; level >= 0; level--, shift += 3) {
            int s = hashGet(((long) level << 30) | (morton >>> shift));
            if (s != NO_SLOT) {
                return readBitsFromArena(s, bitOffset, bitWidth);
            }
        }
        return 0L;
    }

    @Override
    public void writeBits(int row, int bitOffset, int bitWidth, long value) {
        long morton = (long) row;
        int x = unspread3(morton);
        int y = unspread3(morton >>> 1);
        int z = unspread3(morton >>> 2);
        writeBitsAt(x, y, z, bitOffset, bitWidth, value);
    }

    // -----------------------------------------------------------------------
    // 3D read/write

    /**
     * Reads {@code bitWidth} bits for the voxel at {@code (x, y, z)}.
     * Searches from leaf level upward; returns 0 if no node found.
     *
     * <p>Computes the full-resolution Morton code once, then shifts it right by 3 bits
     * per level-up, exploiting the identity
     * {@code morton3D(x>>>k, y>>>k, z>>>k) == morton3D(x,y,z) >>> (3*k)}.
     */
    public long readBitsAt(int x, int y, int z, int bitOffset, int bitWidth) {
        long morton = morton3D(x, y, z);
        for (int level = maxDepth, shift = 0; level >= 0; level--, shift += 3) {
            int s = hashGet(((long) level << 30) | (morton >>> shift));
            if (s != NO_SLOT) {
                return readBitsFromArena(s, bitOffset, bitWidth);
            }
        }
        return 0L;
    }

    /**
     * Writes {@code bitWidth} bits to the leaf voxel at {@code (x, y, z)}.
     *
     * @throws IllegalArgumentException if any coordinate is out of bounds
     */
    public void writeBitsAt(int x, int y, int z, int bitOffset, int bitWidth, long value) {
        checkCoords(x, y, z);
        expandToLeaf(x, y, z);
        long id = nodeId(maxDepth, x, y, z);
        int s = hashGet(id);
        if (s == NO_SLOT) {
            s = arenaAlloc();
            hashPut(id, s);
        }
        writeBitsToArena(s, bitOffset, bitWidth, value);
        if (!inBatch) tryCollapse(maxDepth, x, y, z);
    }

    // -----------------------------------------------------------------------
    // Metadata

    /**
     * Encodes 3D voxel coordinates into a Morton-code row index.
     *
     * @throws IllegalArgumentException if any coordinate is out of bounds
     */
    public int row(int x, int y, int z) {
        checkCoords(x, y, z);
        return (int) morton3D(x, y, z);
    }

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

    /** Returns the number of nodes currently allocated. */
    public int nodeCount() { return mapLive; }

    // -----------------------------------------------------------------------
    // Batch mode

    @Override
    public void beginBatch() {
        inBatch = true;
    }

    @Override
    public void endBatch() {
        if (!inBatch) return;
        inBatch = false;
        // Snapshot all leaf-level keys
        long[] snapshot = new long[mapLive];
        int count = 0;
        for (long k : mapKeys) {
            if (k != KEY_EMPTY && k != KEY_TOMB && levelFromKey(k) == maxDepth) {
                snapshot[count++] = k;
            }
        }
        for (int i = 0; i < count; i++) {
            // Only collapse if the leaf still exists (not collapsed by an earlier iteration)
            if (hashGet(snapshot[i]) != NO_SLOT) {
                long morton = snapshot[i] & 0x3FFFFFFFL;
                int x = unspread3(morton);
                int y = unspread3(morton >>> 1);
                int z = unspread3(morton >>> 2);
                tryCollapse(maxDepth, x, y, z);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Serialization (type tag = 3)

    private static final int  MAGIC         = 0x4A42494E; // "JBIN"
    private static final byte TYPE_FAST_OCT = 3;

    @Override
    public void write(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(MAGIC);
        dos.writeByte(TYPE_FAST_OCT);
        dos.writeInt(widthX);
        dos.writeInt(widthY);
        dos.writeInt(widthZ);
        dos.writeInt(rowStrideLongs);
        dos.writeInt(mapLive);
        for (int i = 0; i < mapKeys.length; i++) {
            long k = mapKeys[i];
            if (k != KEY_EMPTY && k != KEY_TOMB) {
                int s = mapVals[i];
                dos.writeLong(k);
                for (int j = 0; j < rowStrideLongs; j++) {
                    dos.writeLong(arena[s * rowStrideLongs + j]);
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
        if (type != TYPE_FAST_OCT) throw new IOException(
                "Expected fast octree store (type 3), got type " + type);
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
        // Reset
        hashInit(64);
        this.arena = new long[32 * rowStrideLongs];
        this.arenaLive = 0;
        this.freeStack = new int[16];
        this.freeTop = 0;
        int nodeCount = dis.readInt();
        for (int i = 0; i < nodeCount; i++) {
            long nodeId = dis.readLong();
            int s = arenaAlloc();
            for (int j = 0; j < rowStrideLongs; j++) {
                arena[s * rowStrideLongs + j] = dis.readLong();
            }
            hashPut(nodeId, s);
        }
    }

    // -----------------------------------------------------------------------
    // Ancestor expansion

    private void expandToLeaf(int x, int y, int z) {
        for (int level = 0; level < maxDepth; level++) {
            long id = nodeId(level,
                             x >>> (maxDepth - level),
                             y >>> (maxDepth - level),
                             z >>> (maxDepth - level));
            int s = hashGet(id);
            if (s != NO_SLOT) {
                expandNode(level,
                           x >>> (maxDepth - level),
                           y >>> (maxDepth - level),
                           z >>> (maxDepth - level),
                           s);
            }
        }
    }

    private void expandNode(int level, int cx, int cy, int cz, int parentSlot) {
        hashRemove(nodeId(level, cx, cy, cz));
        for (int i = 0; i < 8; i++) {
            int dx = i & 1, dy = (i >> 1) & 1, dz = (i >> 2) & 1;
            int childSlot = arenaAlloc();
            System.arraycopy(arena, parentSlot * rowStrideLongs,
                             arena, childSlot * rowStrideLongs,
                             rowStrideLongs);
            hashPut(nodeId(level + 1, cx * 2 + dx, cy * 2 + dy, cz * 2 + dz), childSlot);
        }
        arenaFree(parentSlot);
    }

    // -----------------------------------------------------------------------
    // Iterative bottom-up collapse

    private void tryCollapse(int startLevel, int x, int y, int z) {
        int level = startLevel;
        while (level > 0) {
            int parentLevel = level - 1;
            int pcx = x >>> (maxDepth - parentLevel);
            int pcy = y >>> (maxDepth - parentLevel);
            int pcz = z >>> (maxDepth - parentLevel);

            // Gather 8 children into collapseBuffer and track their slots
            int repSlot = NO_SLOT;
            int[] childSlots = new int[8];
            for (int i = 0; i < 8; i++) {
                int dx = i & 1, dy = (i >> 1) & 1, dz = (i >> 2) & 1;
                int s = hashGet(nodeId(level, pcx * 2 + dx, pcy * 2 + dy, pcz * 2 + dz));
                childSlots[i] = s;
                if (s != NO_SLOT) {
                    System.arraycopy(arena, s * rowStrideLongs, collapseBuffer[i], 0, rowStrideLongs);
                    if (repSlot == NO_SLOT) repSlot = s;
                } else {
                    Arrays.fill(collapseBuffer[i], 0L);
                }
            }

            // Every registered component must agree to collapse
            boolean canCollapse = true;
            for (Map.Entry<Class<?>, CollapsingFunction> e : collapsingFunctions.entrySet()) {
                if (!e.getValue().canCollapse(
                        componentBitOffsets.get(e.getKey()),
                        componentBitWidths.get(e.getKey()),
                        rowStrideLongs, collapseBuffer)) {
                    canCollapse = false;
                    break;
                }
            }
            if (!canCollapse) break;

            // Allocate parent slot and copy representative data
            int parentSlot = arenaAlloc();
            if (repSlot != NO_SLOT) {
                System.arraycopy(arena, repSlot * rowStrideLongs,
                                 arena, parentSlot * rowStrideLongs,
                                 rowStrideLongs);
            }

            // Remove 8 children (free their arena slots)
            for (int i = 0; i < 8; i++) {
                int dx = i & 1, dy = (i >> 1) & 1, dz = (i >> 2) & 1;
                hashRemove(nodeId(level, pcx * 2 + dx, pcy * 2 + dy, pcz * 2 + dz));
                if (childSlots[i] != NO_SLOT) arenaFree(childSlots[i]);
            }

            // Promote representative to parent
            hashPut(nodeId(parentLevel, pcx, pcy, pcz), parentSlot);

            level--;
        }
    }

    // -----------------------------------------------------------------------
    // Arena allocator

    private int arenaAlloc() {
        if (freeTop > 0) {
            return freeStack[--freeTop];
        }
        int needed = (arenaLive + 1) * rowStrideLongs;
        if (needed > arena.length) {
            arena = Arrays.copyOf(arena, Math.max(arena.length * 2, needed));
        }
        return arenaLive++;
    }

    private void arenaFree(int slot) {
        if (freeTop >= freeStack.length) {
            freeStack = Arrays.copyOf(freeStack, freeStack.length * 2);
        }
        freeStack[freeTop++] = slot;
        Arrays.fill(arena, slot * rowStrideLongs, (slot + 1) * rowStrideLongs, 0L);
    }

    // -----------------------------------------------------------------------
    // Low-level bit read/write on arena

    private long readBitsFromArena(int slot, int bitOffset, int bitWidth) {
        int base = slot * rowStrideLongs;
        int wordIndex = bitOffset >>> 6;
        int shift     = bitOffset & 63;
        long word = arena[base + wordIndex];
        if (shift + bitWidth <= 64) {
            long mask = bitWidth == 64 ? -1L : (1L << bitWidth) - 1L;
            return (word >>> shift) & mask;
        } else {
            int bitsInFirst = 64 - shift;
            long lo = word >>> shift;
            long hi = arena[base + wordIndex + 1] & ((1L << (bitWidth - bitsInFirst)) - 1L);
            return lo | (hi << bitsInFirst);
        }
    }

    private void writeBitsToArena(int slot, int bitOffset, int bitWidth, long value) {
        int base = slot * rowStrideLongs;
        int wordIndex = bitOffset >>> 6;
        int shift     = bitOffset & 63;
        long mask = bitWidth == 64 ? -1L : (1L << bitWidth) - 1L;
        long bits = value & mask;
        if (shift + bitWidth <= 64) {
            long shiftedMask = mask << shift;
            arena[base + wordIndex] = (arena[base + wordIndex] & ~shiftedMask) | (bits << shift);
        } else {
            int bitsInFirst = 64 - shift;
            long maskLo = (1L << bitsInFirst) - 1L;
            arena[base + wordIndex] =
                    (arena[base + wordIndex] & ~(maskLo << shift)) | ((bits & maskLo) << shift);
            int bitsInSecond = bitWidth - bitsInFirst;
            long maskHi = (1L << bitsInSecond) - 1L;
            arena[base + wordIndex + 1] =
                    (arena[base + wordIndex + 1] & ~maskHi) | (bits >>> bitsInFirst);
        }
    }

    // -----------------------------------------------------------------------
    // Open-addressing hash map (primitive long key → int slot)

    private void hashInit(int cap) {
        mapKeys = new long[cap];
        mapVals = new int[cap];
        Arrays.fill(mapKeys, KEY_EMPTY);
        mapMask = cap - 1;
        mapLive = 0;
        mapDead = 0;
    }

    private int hashGet(long key) {
        int i = (int)(mix(key) & mapMask);
        while (true) {
            long k = mapKeys[i];
            if (k == KEY_EMPTY) return NO_SLOT;
            if (k == key) return mapVals[i];
            i = (i + 1) & mapMask;
        }
    }

    private void hashPut(long key, int slot) {
        if ((mapLive + mapDead + 1) * LOAD_DENOMINATOR > mapKeys.length * LOAD_NUMERATOR) {
            hashRehash(mapKeys.length * 2);
        }
        int i = (int)(mix(key) & mapMask);
        int tombIndex = -1;
        while (true) {
            long k = mapKeys[i];
            if (k == KEY_EMPTY) {
                if (tombIndex >= 0) {
                    i = tombIndex;
                    mapDead--;
                }
                mapKeys[i] = key;
                mapVals[i] = slot;
                mapLive++;
                return;
            }
            if (k == KEY_TOMB) {
                if (tombIndex < 0) tombIndex = i;
            } else if (k == key) {
                mapVals[i] = slot;
                return;
            }
            i = (i + 1) & mapMask;
        }
    }

    private int hashRemove(long key) {
        int i = (int)(mix(key) & mapMask);
        while (true) {
            long k = mapKeys[i];
            if (k == KEY_EMPTY) return NO_SLOT;
            if (k == key) {
                int val = mapVals[i];
                mapKeys[i] = KEY_TOMB;
                mapVals[i] = NO_SLOT;
                mapLive--;
                mapDead++;
                return val;
            }
            i = (i + 1) & mapMask;
        }
    }

    private void hashRehash(int newCap) {
        long[] oldKeys = mapKeys;
        int[]  oldVals = mapVals;
        hashInit(newCap);
        for (int i = 0; i < oldKeys.length; i++) {
            long k = oldKeys[i];
            if (k != KEY_EMPTY && k != KEY_TOMB) {
                hashPut(k, oldVals[i]);
            }
        }
    }

    private static long mix(long v) {
        v ^= v >>> 33;
        v *= 0xff51afd7ed558ccdL;
        v ^= v >>> 33;
        v *= 0xc4ceb9fe1a85ec53L;
        v ^= v >>> 33;
        return v;
    }

    // -----------------------------------------------------------------------
    // Morton-code helpers (same as OctreeDataStore)

    /** Extracts the level component from a nodeId key (bits 30–34). */
    private static int levelFromKey(long key) {
        return (int)(key >>> 30);
    }

    private static long nodeId(int level, int cx, int cy, int cz) {
        return ((long) level << 30) | morton3D(cx, cy, cz);
    }

    private static long morton3D(int x, int y, int z) {
        return spread3(x) | (spread3(y) << 1) | (spread3(z) << 2);
    }

    private static long spread3(int v) {
        long x = v & 0x3FFL;
        x = (x ^ (x << 16)) & 0xFF0000FFL;
        x = (x ^ (x <<  8)) & 0x0F00F00FL;
        x = (x ^ (x <<  4)) & 0xC30C30C3L;
        x = (x ^ (x <<  2)) & 0x49249249L;
        return x;
    }

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
