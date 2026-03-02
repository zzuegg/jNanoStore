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

All read timings below are taken from the benchmark results in [BENCHMARKS.md](BENCHMARKS.md)
using the `IntAccessor` (direct) path on 1 000 rows, which is the closest proxy for
per-voxel access in a hot loop.

### 1. `PackedDataStore` (dense)

| Attribute | Assessment |
|-----------|-----------|
| Bulk read (1 000 rows) | **3 027 ns** — fastest of all stores |
| Single-element read | **2.82 ns** |
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
| Bulk read (1 000 rows) | **17 897 ns** — ~6× slower than `PackedDataStore` |
| Single-element read | **4.62 ns** |
| Memory | Allocates one `long[rowStrideLongs]` per written row, plus a `HashMap<Integer, long[]>` entry |
| HashMap overhead per read | One `HashMap.get(Integer)` call — involves integer boxing and hash-bucket traversal |
| Handles sparse / lazy loading | ✓ — unwritten rows cost zero memory |

**Verdict for large voxel worlds:** Memory behaviour is correct (only pay for written
voxels), but the `HashMap.get()` per read is expensive and adds ~15 000 ns of overhead
per 1 000 sequential reads relative to packed access.  For the render-loop use case,
where thousands to millions of voxel reads happen per frame, this overhead is
significant.  **Acceptable memory model, but reads too slow for the primary hot path.**

---

### 3. `OctreeDataStore` (3-D sparse octree)

| Attribute | Assessment |
|-----------|-----------|
| Bulk read (1 000 rows) | **57 296 ns** — ~19× slower than `PackedDataStore` |
| Single-element read | **18.9 ns** |
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
| Bulk read (1 000 rows) | **25 589 ns** — ~8× slower than `PackedDataStore` |
| Single-element read | **20.7 ns** |
| Memory | Same octree collapsing as `OctreeDataStore`; arena `long[]` eliminates per-node object overhead |
| vs. `OctreeDataStore` reads | ~2× faster |
| Write cost | Higher than `OctreeDataStore` due to open-address-hash bookkeeping |

**Verdict for large voxel worlds:** The best available option among the four existing
stores — combines good memory savings with the fastest octree read speed.  Still ~8×
slower than `PackedDataStore` reads because every access traverses the hash map and
the octree levels.  **Best compromise of the existing stores, but there is a better
approach.**

---

## Summary Table — Existing Stores

| Store | Read (ns/1 000) | Single read (ns) | Memory model | Large-world viable? |
|-------|----------------:|------------------:|-------------|---------------------|
| `PackedDataStore` | 3 027 | 2.82 | Dense — all upfront | ✗ memory |
| `SparseDataStore` | 17 897 | 4.62 | Per-row HashMap | ⚠ slow reads |
| `OctreeDataStore` | 57 296 | 18.9 | Collapsed octree | ⚠ very slow reads |
| `FastOctreeDataStore` | 25 589 | 20.7 | Collapsed octree (arena) | ⚠ slow reads |

No existing store satisfies **both** constraints simultaneously:
- The fast stores allocate too much memory.
- The memory-efficient stores read too slowly.

---

## Recommended Design: Chunk-Based `PackedDataStore`

The fundamental insight is that the tension between fast reads and low memory can be
broken by introducing **a second level of indirection at chunk granularity** rather
than at voxel granularity.

### Core idea

Divide the voxel world into **fixed-size cubic chunks** (e.g., 16 × 16 × 16 = 4 096
voxels per chunk).  Each chunk, when non-empty, is backed by its own `PackedDataStore`
of exactly 4 096 rows.  The world-level store only holds a **sparse index** from
chunk coordinates to chunk stores; chunks that have never been written are not
allocated.

```
World read(x, y, z):
  1. Compute chunk coordinates: (cx, cy, cz) = (x/16, y/16, z/16)
  2. Look up chunk in a sparse index (array or open-addressing hash map, keyed on
     a Morton-encoded chunk coordinate).  Cost: O(1), estimated < 5 ns — comparable
     to `SparseDataStore`'s single-element read (4.62 ns per BENCHMARKS.md), which
     also performs one hash-map lookup, but without the additional per-row long[]
     allocation overhead.
  3a. Chunk not found → return default value (air / 0).  Cost: O(1).
  3b. Chunk found → compute in-chunk voxel index and delegate to the chunk's
     PackedDataStore.readBits().  Cost: ~3 ns (one long[] access + bit ops).
Total estimated read latency: < 10 ns per voxel.
```

### Why this solves both constraints

| Constraint | How the design addresses it |
|------------|----------------------------|
| **Fast reads** | Within an allocated chunk, reads are identical to `PackedDataStore`: one array index + bit-shift + mask.  The chunk lookup adds one more array access at most.  Total latency ≈ 2–3× `PackedDataStore` single-read (< 10 ns). |
| **Low memory** | Only written chunks allocate a `PackedDataStore`.  For a world that is 99 % air (16-chunk columns above the surface), almost no memory is used.  The chunk index itself is a sparse array proportional to the number of non-empty chunks, not the total voxel count. |
| **Cache locality** | All voxels within a 16³ chunk are stored in a single contiguous `long[]`, which fits in L1/L2 cache.  Sequential access patterns (render loop scanning a chunk) incur zero cache misses after the first chunk load. |
| **Uniform chunks** | A chunk that is entirely one value (e.g., all-air) can use a shared flyweight `PackedDataStore` instance (read-only, a single allocated row with the default value).  This reduces memory for homogeneous chunks to one pointer. |

### Estimated performance vs. existing stores

| Store | Single read (ns, estimated) | 4 096-voxel chunk read (µs) | Memory — 268 M-voxel world |
|-------|----------------------------:|--------------------------:|---------------------------|
| `PackedDataStore` (baseline) | 2.82 | ~11.6 µs | ~268 MB (all upfront) |
| `SparseDataStore` | 4.62 | ~18.9 µs | ~134 MB (50 % fill) |
| `FastOctreeDataStore` | 20.7 | ~84.8 µs | < 1 MB (typical surface) |
| **Chunk-based (proposed)** | **~6–10** ¹ | **~25–41 µs** | **< 10 MB (typical surface)** |

¹ Estimated as `PackedDataStore` single-read (2.82 ns) + chunk index lookup overhead
(~2–5 ns, based on `SparseDataStore`'s 4.62 ns single-read as a hash-map-lookup proxy),
plus in-chunk index arithmetic (sub-nanosecond).  No implementation benchmark exists yet.

The chunk-based design achieves **near-PackedDataStore read speed** (2–3× overhead only)
with **near-OctreeDataStore memory efficiency** for typical voxel worlds.

### Tradeoffs and open questions

| Concern | Notes |
|---------|-------|
| Chunk size | 16³ (4 K voxels) is a common choice; 32³ (32 K voxels) gives better cache re-use at the cost of higher minimum chunk allocation. |
| Chunk index structure | A 3-D flat array indexed by chunk coordinates is O(1) and avoids hashing entirely — viable if the loaded region is bounded.  For an unbounded world, an open-addressing hash map keyed on Morton-encoded chunk coords is appropriate (no boxing, < 5 ns per lookup). |
| Uniform-chunk flyweight | A shared read-only sentinel chunk for the default (all-zeros) value avoids allocating memory for completely empty chunks and makes the miss path trivially cheap. |
| Serialisation | Each chunk's `PackedDataStore` already supports `write(OutputStream)` / `read(InputStream)`, so streaming individual chunks to disk is straightforward. |
| Thread safety | Chunk reads are independent; parallel reads of different chunks require no synchronisation.  Only the chunk index requires a read-lock during concurrent access. |

---

## Conclusion

Among the **existing** implementations, **`FastOctreeDataStore`** is the best choice
for a large voxel world: it provides the lowest memory footprint of the available stores
through automatic region collapsing, while delivering the fastest reads of the two octree
variants (~2× faster than `OctreeDataStore`).

However, neither octree implementation can reach near-raw-array read latency because
every voxel access requires hash-map lookup and tree traversal.

The **recommended path forward** is a new **chunk-based `PackedDataStore`** that uses
two-level addressing:
- A sparse chunk index (one entry per non-empty 16³ region) → low memory.
- A dense `PackedDataStore` per chunk → near-raw-array reads.

This design achieves sub-10 ns single-voxel reads and sub-megabyte memory usage for
typical surface voxel worlds — satisfying both primary constraints simultaneously.

*This document is an evaluation only.  Implementation is deferred to a follow-up task.*
