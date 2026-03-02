# Voxel World DataStore Evaluation

## Objective

Identify the best `DataStore` strategy for a **large voxel world** that must satisfy
two competing constraints simultaneously:

1. **Very fast read access** — voxel reads happen in every render frame and in the
   game-logic hot loop; latency of a single-voxel lookup must stay in the low
   nanoseconds range.
2. **Low memory footprint** — a typical Minecraft-scale world (1 024 × 256 × 1 024)
   contains ~268 million voxels.  Allocating memory for every voxel up-front is not
   viable.

---

## Characteristics of a Large Voxel World

Before evaluating each store, it is important to note what makes voxel worlds special:

| Property | Implication |
|----------|-------------|
| Enormous total capacity (100 M – 1 B voxels) | No store can pre-allocate everything |
| **Large homogeneous regions** (air, solid stone, ocean) | Compression / collapsing can save orders-of-magnitude memory |
| Highly **spatial locality** in the access pattern | Cache-friendly layouts (contiguous arrays per region) matter |
| Reads outnumber writes by 10–100× (render / physics) | Optimising read latency is the primary goal |
| Writes are bursty (player edits, terrain generation) | Amortised write cost is acceptable |

---

## Evaluation of Existing DataStore Implementations

All read timings below are taken from live JMH benchmark results in [BENCHMARKS.md](BENCHMARKS.md)
using the `IntAccessor` (direct) path on 1 000 rows, which is the closest proxy for
per-voxel access in a hot loop.

### 1. `PackedDataStore` (dense)

| Attribute | Assessment |
|-----------|-----------|
| Bulk read (1 000 rows) | **2 914 ns** — fastest of all bit-packed stores |
| Single-element read | **2.87 ns** |
| Memory (dense) | Pre-allocates `capacity × rowStrideLongs × 8` bytes at construction |
| Memory for 268 M voxels (23-bit Terrain row) | **≈ 268 MB** — entirely up-front |
| Handles sparse / lazy loading | ✗ — all rows allocated at construction |

**Verdict for large voxel worlds:** Memory model is incompatible with world-scale use.
A 268 M-voxel world requires the full allocation before a single voxel is placed.
Partial loading (only the player's visible chunks) is impossible with a single
`PackedDataStore`.  **Eliminated on memory grounds.**

---

### 2. `SparseDataStore` (lazy per-row allocation)

| Attribute | Assessment |
|-----------|-----------|
| Bulk read (1 000 rows) | **15 868 ns** — ~5× slower than `PackedDataStore` |
| Single-element read | **4.21 ns** |
| Memory | Allocates one `long[rowStrideLongs]` per written row, plus a `HashMap<Integer, long[]>` entry |
| HashMap overhead per read | One `HashMap.get(Integer)` call — involves integer boxing and hash-bucket traversal |
| Handles sparse / lazy loading | ✓ — unwritten rows cost zero memory |

**Verdict for large voxel worlds:** Memory behaviour is correct (only pay for written
voxels), but the `HashMap.get()` per read is expensive.  For the render-loop use case,
where thousands to millions of voxel reads happen per frame, this overhead is
significant.  **Acceptable memory model, but reads too slow for the primary hot path.**

---

### 3. `OctreeDataStore` (3-D sparse octree)

| Attribute | Assessment |
|-----------|-----------|
| Bulk read (1 000 rows) | **24 002 ns** — ~8× slower than `PackedDataStore` |
| Single-element read | **8.49 ns** |
| Memory | Only stores nodes; uniform regions collapse to a single ancestor node |
| Memory for a typical surface world | Hundreds to low thousands of nodes — kB range |
| Write cost (non-batch) | Very high; every distinct write may trigger collapse checks up to `maxDepth` levels |
| Write cost (batch mode) | ~2× better; collapse deferred to `endBatch()` |

**Verdict for large voxel worlds:** Excellent memory efficiency — the tree collapses
large uniform volumes (air above ground, bedrock below) to a single node.  However,
reads require Morton-code decoding plus a tree traversal of up to `maxDepth` steps,
making per-voxel latency too high for real-time rendering.  **Best memory, but reads
too slow for the primary hot path.**

---

### 4. `FastOctreeDataStore` (primitive hash map + arena allocator)

| Attribute | Assessment |
|-----------|-----------|
| Bulk read (1 000 rows) | **18 531 ns** — ~6× slower than `PackedDataStore` |
| Single-element read | **7.53 ns** |
| Memory | Same octree collapsing as `OctreeDataStore`; arena `long[]` eliminates per-node object overhead |
| vs. `OctreeDataStore` reads | ~1.3× faster |
| Write cost | Higher than `OctreeDataStore` due to open-address-hash bookkeeping |

**Verdict for large voxel worlds:** The best available option among the four existing
stores — combines good memory savings with the fastest octree read speed.  Still ~6×
slower than `PackedDataStore` reads because every access traverses the hash map and
the octree levels.  **Best compromise of the existing stores, but a better
approach now exists.**

---

## Summary Table — Existing Stores

| Store | Read (ns/1 000) | Single read (ns) | Memory model | Large-world viable? |
|-------|----------------:|------------------:|-------------|---------------------|
| `PackedDataStore` | 2 914 | 2.87 | Dense — all upfront | ✗ memory |
| `SparseDataStore` | 15 868 | 4.21 | Per-row HashMap | ⚠ slow reads |
| `OctreeDataStore` | 24 002 | 8.49 | Collapsed octree | ⚠ very slow reads |
| `FastOctreeDataStore` | 18 531 | 7.53 | Collapsed octree (arena) | ⚠ slow reads |

No existing store satisfies **both** constraints simultaneously:
- The fast stores allocate too much memory.
- The memory-efficient stores read too slowly.

---

## Implemented Design: `ChunkedDataStore`

The fundamental insight is that the tension between fast reads and low memory can be
broken by introducing **a second level of indirection at chunk granularity** rather
than at voxel granularity.

### Core idea

Divide the voxel world into **fixed-size cubic chunks** (16 × 16 × 16 = 4 096
voxels per chunk).  Each chunk, when non-empty, is backed by its own `PackedDataStore`
of exactly 4 096 rows.  The world-level store only holds a **sparse index** from
chunk coordinates to chunk stores; chunks that have never been written are not
allocated.

```
World read(x, y, z):
  1. Compute in-chunk row:  inChunkRow = mortonEncode(x&15, y&15, z&15)
  2. Compute chunk key:     chunkKey   = fullMortonCode(x,y,z) >>> 12
  3. Look up chunk in open-addressing hash map (no boxing, ~4 ns).
  3a. Chunk not found → return 0 (air / default value).  Cost: O(1).
  3b. Chunk found → delegate to chunk's PackedDataStore.readBits().  Cost: ~3 ns.
Total measured read latency: 4.43 ns per voxel (single-element benchmark).
```

### Why this solves both constraints

| Constraint | How the design addresses it |
|------------|----------------------------|
| **Fast reads** | Within an allocated chunk, reads are identical to `PackedDataStore`: one array index + bit-shift + mask.  The chunk lookup adds one more hash-map probe (~2 ns).  Total latency ≈ 4.43 ns (measured). |
| **Low memory** | Only written chunks allocate a `PackedDataStore`.  For a world that is 99 % air (16-chunk columns above the surface), almost no memory is used.  The chunk index itself is a sparse array proportional to the number of non-empty chunks, not the total voxel count. |
| **Cache locality** | All voxels within a 16³ chunk are stored in a single contiguous `long[]`, which fits in L1/L2 cache.  Sequential access patterns (render loop scanning a chunk) incur zero cache misses after the first chunk load. |
| **Morton-code decomposition** | The full voxel Morton code splits cleanly at bit 12 into chunk key (upper bits) and in-chunk row (lower 12 bits) — no extra coordinate arithmetic required. |

### Measured performance vs. existing stores

| Store | Single read (ns) | Bulk read / 1 000 (ns) | Bulk write / 1 000 (ns) | Notes |
|-------|----------------:|-----------------------:|------------------------:|-------|
| `PackedDataStore` (baseline) | 2.87 | 2 914 | 14 003 | all upfront |
| `SparseDataStore` | 4.21 | 15 868 | 58 350 | HashMap per row |
| `FastOctreeDataStore` | 7.53 | 18 531 | 655 631 | octree traversal |
| **`ChunkedDataStore`** | **4.43** | **6 210** | **9 799** | **2 chunks exercised** |

Key observations:

- **Single-element read: 4.43 ns** — only ~54% slower than `PackedDataStore` (2.87 ns),
  and faster than both octree variants (7.53 / 8.49 ns).
- **Bulk read: 6 210 ns** — ~2× `PackedDataStore` but ~3× faster than `SparseDataStore`
  and `FastOctreeDataStore`.
- **Bulk write: 9 799 ns** — ~6× faster than `SparseDataStore` (58 350 ns) and ~67×
  faster than `FastOctreeDataStore` (655 631 ns), because each chunk is a dense
  `PackedDataStore` (no tree-manipulation, no per-row HashMap allocation).

### Usage

```java
// Create a 1 024 × 256 × 1 024 voxel world
ChunkedDataStore<Voxel> world = DataStore.chunked(1024, 256, 1024, Voxel.class);
IntAccessor material = Accessors.intFieldInStore(world, Voxel.class, "material");

// Write a voxel
material.set(world, world.row(100, 64, 200), 5);

// Read a voxel — ~4.4 ns including chunk lookup
int m = material.get(world, world.row(100, 64, 200));  // → 5

// Unwritten regions cost zero memory (return 0 / field minimum)
int air = material.get(world, world.row(0, 200, 0));   // → 0, no allocation
```

### Tradeoffs

| Concern | Notes |
|---------|-------|
| Chunk size | Fixed at 16³ (4 096 voxels).  All voxels in a chunk share one contiguous `long[]`. |
| Chunk index structure | Open-addressing hash map keyed on Morton-encoded chunk coordinates.  No boxing.  Resizes automatically at 50% load. |
| Uniform-chunk flyweight | Completely empty chunks return 0 without any allocation; only the first write to a chunk triggers `PackedDataStore` creation. |
| Serialisation | `write(OutputStream)` / `read(InputStream)` and `write(ByteBuffer)` / `read(ByteBuffer)` write each chunk as a contiguous block — straightforward per-chunk streaming. |
| Thread safety | Not thread-safe; external synchronisation required for concurrent access. |
| vs. OctreeDataStore | Unlike octrees, `ChunkedDataStore` does not automatically collapse uniform regions (e.g. all-air chunks still allocate a `PackedDataStore` on first write).  If automatic collapsing of large homogeneous volumes is critical, prefer `FastOctreeDataStore`. |

---

## Conclusion

The **`ChunkedDataStore`** has been implemented and benchmarked.  It achieves:

- **Sub-5 ns single-voxel reads** — 4.43 ns, within 54% of the theoretical minimum
  (`PackedDataStore` at 2.87 ns) and faster than any octree variant.
- **Fast bulk writes** — 9 799 ns / 1 000 rows, ~6× faster than `SparseDataStore`
  and ~67× faster than `FastOctreeDataStore`.
- **Memory proportional to written chunks** — unwritten regions (e.g. empty air)
  consume zero memory beyond a small hash-map entry.

This satisfies **both** primary constraints — fast reads and low memory — simultaneously,
making it the recommended store for large voxel worlds.

See [BENCHMARKS.md](BENCHMARKS.md) for the full result matrix and reproduction instructions.
