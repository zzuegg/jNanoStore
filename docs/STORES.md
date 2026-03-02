# DataStore Variants

BitKit provides six `DataStore` implementations that all use the same bit-packing
schema and work with the same accessor API.

Jump to: [PackedDataStore](#packeddatastore) · [ChunkedDataStore](#chunkeddatastore) ·
[SparseDataStore](#sparsedatastore) · [OctreeDataStore](#octreedatastore) ·
[FastOctreeDataStore](#fastoctreedatastore) · [RawDataStore](#rawdatastore)

---

## PackedDataStore

Pre-allocates a single contiguous `long[]` for all rows at construction time.
Best when most rows will be written.

```java
DataStore<Terrain> store = DataStore.packed(10_000, Terrain.class);
// or equivalently:
DataStore<Terrain> store = DataStore.of(10_000, Terrain.class);
```

**Memory:** `capacity × rowStrideLongs × 8` bytes, allocated immediately.

**When to choose:** Dense datasets where most rows will be written; maximum read throughput
is the primary concern and memory is available up-front.

---

## ChunkedDataStore

A chunk-based store optimised for large 3-D voxel worlds.  The world is divided into
**16×16×16 = 4 096-voxel chunks**; each non-empty chunk is backed by a `PackedDataStore`.
A sparse open-addressing hash map (no boxing) indexes chunks by Morton-encoded chunk
coordinates.

```java
// Create a 1 024 × 256 × 1 024 voxel world
ChunkedDataStore<Voxel> world = DataStore.chunked(1024, 256, 1024, Voxel.class);
IntAccessor material = Accessors.intFieldInStore(world, Voxel.class, "material");

// Use row(x, y, z) to convert 3-D coordinates to a row index (Morton code)
material.set(world, world.row(100, 64, 200), 5);
int m = material.get(world, world.row(100, 64, 200));  // → 5

// Unwritten regions return the field minimum (e.g. 0) without any allocation
int air = material.get(world, world.row(500, 200, 500));  // → 0

System.out.println(world.allocatedChunkCount());  // chunks written so far
```

**Memory:** Only allocated chunks consume storage (~32 KB per 16³ chunk for a
23-bit-per-voxel record with one `long` per row).

**Measured performance:**
- Single-element read: **4.43 ns** (vs 2.87 ns for `PackedDataStore`)
- Bulk read (1 000 voxels): **6 210 ns** (~2× Packed, ~3× faster than Sparse/FastOctree)
- Bulk write (1 000 voxels): **9 799 ns** (~6× faster than `SparseDataStore`)

**When to choose:** Large 3-D voxel worlds where only a fraction of the space is
non-empty.  Provides near-PackedDataStore read speed with near-OctreeDataStore memory
efficiency.  See [VOXEL_WORLD_EVALUATION.md](../VOXEL_WORLD_EVALUATION.md) for a
detailed analysis.

---

## SparseDataStore

Allocates each row's `long[]` on the first write; unwritten rows read back as all-zeros.

```java
DataStore<Terrain> store = DataStore.sparse(10_000, Terrain.class);
int writtenRows = ((SparseDataStore<?>) store).allocatedRowCount();
```

**Memory:** One `long[rowStrideLongs]` per written row, plus a `HashMap<Integer, long[]>` entry.

**When to choose:** Datasets where only a small fraction of the declared capacity will
ever be populated, and 3-D spatial indexing is not required.

---

## OctreeDataStore

Organises a voxel space as a sparse octree.  On each write, the store checks whether all
8 siblings satisfy every registered `CollapsingFunction`; if so, those 8 child nodes are
merged into a single parent node.

**Uniform** (cubic) space — use `builder(int maxDepth)`:

```java
import io.github.zzuegg.jbinary.octree.*;

record Voxel(
    @BitField(min = 0, max = 15)  int material,
    @BitField(min = 0, max = 255) int density
) {}

// maxDepth=6 → 64 × 64 × 64 voxel space
OctreeDataStore<?> store = OctreeDataStore.builder(6)
    .component(Voxel.class)                              // default: collapse on bit-equality
    .component(Water.class, CollapsingFunction.never())  // custom per-component function
    .build();

IntAccessor material = Accessors.intFieldInStore(store, Voxel.class, "material");

// Write using 3D coordinates → store.row(x, y, z) gives the Morton-code row index
material.set(store, store.row(10, 5, 3), 7);
int m = material.get(store, store.row(10, 5, 3));       // → 7

// Uniform fill collapses automatically
for (int x = 0; x < 64; x++)
    for (int y = 0; y < 64; y++)
        for (int z = 0; z < 64; z++)
            material.set(store, store.row(x, y, z), 0); // all air

store.nodeCount(); // → 1  (entire space merged into root)
```

**Non-uniform** (rectangular) space — use `builder(widthX, widthY, widthZ)`:

```java
// 100 × 100 × 10 voxel world (wide and flat)
OctreeDataStore<?> store = OctreeDataStore.builder(100, 100, 10)
    .component(Voxel.class)
    .build();
```

**Batch writes** defer collapse until `endBatch()`, roughly halving write time:

```java
store.beginBatch();
for (int x = 0; x < 64; x++)
    for (int y = 0; y < 64; y++)
        for (int z = 0; z < 64; z++)
            material.set(store, store.row(x, y, z), 0);
store.endBatch();  // collapse runs once here
```

**`CollapsingFunction` factories:**

| Factory | Behaviour |
|---------|-----------|
| `CollapsingFunction.equalBits()` | Collapse when all 8 children are bit-identical (default) |
| `CollapsingFunction.never()` | Never collapse |
| `CollapsingFunction.always()` | Always collapse regardless of values |
| Custom lambda | Full control via `canCollapse(offset, bits, stride, children[8])` |

**When to choose:** 3-D voxel worlds where large homogeneous regions (air, stone, water)
should collapse to a single node for maximum memory efficiency.  Reads are slower than
`ChunkedDataStore` due to tree traversal.

---

## FastOctreeDataStore

Drop-in replacement for `OctreeDataStore` that eliminates boxing overhead by using a
primitive open-addressing hash map and a flat arena `long[]` instead of
`HashMap<Long, long[]>`.  Approximately **1.3–2× faster** than `OctreeDataStore` on
both reads and writes, with full batch-mode support.  Supports the same uniform and
non-uniform builder API.

```java
import io.github.zzuegg.jbinary.octree.FastOctreeDataStore;

// Uniform: maxDepth=6 → 64 × 64 × 64
FastOctreeDataStore<?> store = FastOctreeDataStore.builder(6)
    .component(Voxel.class)
    .build();

// Non-uniform: 100 × 100 × 10
FastOctreeDataStore<?> store = FastOctreeDataStore.builder(100, 100, 10)
    .component(Voxel.class)
    .build();

IntAccessor material = Accessors.intFieldInStore(store, Voxel.class, "material");

material.set(store, store.row(10, 5, 3), 7);
int m = material.get(store, store.row(10, 5, 3));  // → 7

// Batch-mode bulk fill (defers collapse until endBatch)
store.beginBatch();
for (int x = 0; x < 64; x++)
    for (int y = 0; y < 64; y++)
        for (int z = 0; z < 64; z++)
            material.set(store, store.row(x, y, z), 0);
store.endBatch();

store.nodeCount(); // → 1  (entire space merged into root)
```

**When to choose:** Same as `OctreeDataStore` but when you need the best available octree
read performance and accept higher write complexity.

---

## RawDataStore

Stores every field as a full 64-bit `long` slot — no bit packing, no shift/mask
arithmetic.  Every field read or write is a single array access, making this the fastest
possible `DataStore` for throughput-critical code paths that do not need memory compactness.

```java
import io.github.zzuegg.jbinary.RawDataStore;

// Create via DataStore.raw()
RawDataStore<Terrain> raw = DataStore.raw(10_000, Terrain.class);

// Use the dedicated accessor factories on the RawDataStore instance
// (do NOT use Accessors.intFieldInStore with a RawDataStore)
IntAccessor    height = raw.intAccessor(Terrain.class, "height");
DoubleAccessor temp   = raw.doubleAccessor(Terrain.class, "temperature");
BoolAccessor   active = raw.boolAccessor(Terrain.class, "active");

height.set(raw, 0, 200);       // single long[] write, no bit shift
int h = height.get(raw, 0);   // single long[] read, no bit shift

// RowView also available through the raw store
RowView<Terrain> view = raw.rowView(Terrain.class);
view.set(raw, 0, new Terrain(100, 22.5, true));
Terrain t = view.get(raw, 0);

// Multi-component
RawDataStore<?> multi = DataStore.raw(1000, Terrain.class, Water.class);
IntAccessor    mh = multi.intAccessor(Terrain.class, "height");
DoubleAccessor ms = multi.doubleAccessor(Water.class, "salinity");
```

**Measured performance:** Single-element read: **1.61 ns** — fastest store, ~1.5× baseline.

**When to use:** Maximum throughput when memory savings are not a priority — e.g. a
temporary working buffer in an algorithm, a cache layer, or a network receive buffer.

---

## Store selection guide

| Use case | Recommended store |
|----------|-------------------|
| Small dense dataset, max throughput, no memory constraint | `PackedDataStore` |
| Large 3-D voxel world, partial loading, fast reads needed | **`ChunkedDataStore`** |
| Sparse flat dataset (few rows populated) | `SparseDataStore` |
| 3-D voxel world, automatic region collapsing critical | `FastOctreeDataStore` |
| Temporary buffer / cache / max raw throughput | `RawDataStore` |

---

## ByteBuffer I/O

Every `DataStore` implementation supports writing to and reading from NIO
`ByteBuffer`s — useful for network protocols, memory-mapped files, and
off-heap buffers.

```java
DataStore<Terrain> store = DataStore.of(1000, Terrain.class);
// … populate …

// Serialize to a ByteBuffer
ByteBuffer buf = ByteBuffer.allocateDirect(64 * 1024);
store.write(buf);     // position advances; buf stays open
buf.flip();

// Deserialize from a ByteBuffer
DataStore<Terrain> restored = DataStore.of(1000, Terrain.class);
restored.read(buf);   // position advances

// Works with all store types
ChunkedDataStore<Terrain> chunked = DataStore.chunked(32, 32, 32, Terrain.class);
chunked.write(buf);
```

The format is identical to `write(OutputStream)` / `read(InputStream)`.
