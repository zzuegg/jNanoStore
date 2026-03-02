# BitKit Benchmark Results

## Overview

All six `DataStore` implementations (raw, packed, chunked, sparse, octree, fast-octree) are benchmarked
across three accessor patterns (`IntAccessor`, `DataCursor`, `RowView`) and five operation
types.

See [README.md](README.md) for a summary.  This page contains the complete result matrix.

| Operation | Description |
|-----------|-------------|
| **Bulk read** | Read all 1 000 rows sequentially |
| **Bulk write** | Write all 1 000 rows sequentially |
| **Random read** | Read 1 000 rows in shuffled order |
| **Random write** | Write 1 000 rows in shuffled order |
| **Random r+w** | Alternating reads and writes, random order (500 of each) |

Two reference baselines are included:
- **Baseline** — three parallel primitive arrays (`int[]`, `double[]`, `boolean[]`)
- **HashMap** — `HashMap<Integer, Object[]>` per-row with no packing (fully boxed)

All benchmarks use **pre-computed, fixed-seed random data** (no inline arithmetic patterns).

## Environment

| Property       | Value               |
|----------------|---------------------|
| JDK            | OpenJDK 25          |
| JMH            | 1.37                |
| Benchmark mode | AverageTime (ns/op) |
| Warmup         | 1 × 1 s iteration   |
| Measurement    | 1 × 1 s iteration   |
| Forks          | 1                   |
| Ops per bench  | 1 000               |
| Data           | Pre-computed random (seed 123) |

## Full results — direct IntAccessor

All values in ns/op (lower = faster).

| Benchmark | Bulk read | Bulk write | Rnd read | Rnd write | Rnd r+w |
|-----------|----------:|-----------:|---------:|----------:|--------:|
| `baselineReadAll` / `WriteAll` | 438 | 259 | 603 | 895 | 1,166 |
| `hashmapReadAll` / `WriteAll` | 6,020 | 19,562 | 7,167 | 18,956 | 13,274 |
| **`rawReadAll`** / `WriteAll` | **1,990** | **4,278** | **2,070** | **4,628** | — |
| `packedReadAll` / `WriteAll` | 2,914 | 14,003 | 3,605 | 11,141 | 8,375 |
| **`chunkedReadAll`** / `WriteAll` | **6,210** | **9,799** | **6,125** | **9,901** | **8,032** |
| `sparseReadAll` / `WriteAll` | 15,868 | 58,350 | 18,388 | 59,755 | 38,068 |
| `fastOctreeReadAll` / `WriteAll` | 18,531 | 655,631 | 9,401 | 674,429 | 328,151 |
| `fastOctreeBatchWriteAll` | — | 329,806 | — | — | — |
| `octreeReadAll` / `WriteAll` | 24,002 | 541,466 | 23,884 | 546,994 | 289,607 |
| `octreeBatchWriteAll` | — | 332,427 | — | — | — |

> **ChunkedDataStore** uses a 32×16×16 world (2 chunks along X) to exercise cross-chunk
> access patterns.  Voxel coordinates span two 16³ chunk boundaries so the hash-map
> probe is exercised on every access.

## Full results — DataCursor (ByteBuddy, single component)

`DataCursor.of()` uses a ByteBuddy-generated class with direct `PUTFIELD`/`GETFIELD`
instructions for all cursor fields.  After JIT warm-up the generated codec is fully inlined.

| Benchmark | Bulk read | Bulk write | Rnd read | Rnd write | Rnd r+w |
|-----------|----------:|-----------:|---------:|----------:|--------:|
| `packedCursorReadAll` / `WriteAll` | 3,170 | 12,588 | 3,559 | 19,790 | 12,275 |
| **`chunkedCursorReadAll`** / `WriteAll` | **6,153** | **23,755** | — | — | — |
| `sparseCursorReadAll` / `WriteAll` | 19,947 | 60,432 | 20,479 | 62,267 | 40,966 |
| `octreeCursorReadAll` / `WriteAll` | 22,890 | 547,617 | 24,649 | 558,164 | 284,772 |
| `fastOctreeCursorReadAll` / `WriteAll` | 19,912 | 657,381 | 19,249 | 678,063 | 351,615 |

## Full results — multi-component scenario (Terrain + Water, 5 fields via WorldCursor)

The `WorldCursor` reads/writes five fields spanning two registered component types
(`Terrain.height`, `Terrain.temperature`, `Terrain.active`, `Water.salinity`,
`Water.frozen`) in a single `DataCursor.update()` / `flush()` call.

| Benchmark | Bulk read | Bulk write | Rnd read | Rnd write | Rnd r+w |
|-----------|----------:|-----------:|---------:|----------:|--------:|
| `multiBaselineReadAll` / `WriteAll` | 912 | 443 | 1,093 | 1,815 | 1,473 |
| `multiPackedCursorReadAll` / `WriteAll` | 14,747 | 25,042 | 5,834 | 19,374 | 18,145 |
| `multiSparseCursorReadAll` / `WriteAll` | 33,055 | 94,607 | 33,414 | 95,478 | 63,020 |
| `multiOctreeCursorReadAll` / `WriteAll` | 39,333 | 925,709 | 40,692 | 926,240 | 483,222 |
| `multiFastOctreeCursorReadAll` / `WriteAll` | 32,633 | 1,162,157 | 27,158 | 1,259,203 | 635,327 |

> **Note:** Octree/FastOctree write cost roughly doubles relative to single-component because
> the combined `Terrain + Water` bit-stride is larger, increasing tree-manipulation work per
> write.

## Full results — DataCursor (VarHandle fallback)

`DataCursor.ofVarHandle()` uses the VarHandle-based fallback path, bypassing ByteBuddy.

| Benchmark | Bulk read | Bulk write | Rnd read | Rnd write |
|-----------|----------:|-----------:|---------:|----------:|
| `packedCursorVhReadAll` / `WriteAll` | 31,481 | 43,989 | 31,190 | 49,073 |
| `sparseCursorVhReadAll` / `WriteAll` | 48,101 | 83,656 | 49,242 | 83,554 |

## Full results — RowView

| Benchmark | Bulk read | Bulk write | Rnd read | Rnd write |
|-----------|----------:|-----------:|---------:|----------:|
| `packedRowViewReadAll` / `WriteAll` | 130,319 | 44,332 | 133,689 | 48,245 |
| **`chunkedRowViewReadAll`** / `WriteAll` | **145,586** | **52,176** | — | — |
| `sparseRowViewReadAll` / `WriteAll` | 152,118 | 89,341 | 157,430 | 90,015 |
| `octreeRowViewReadAll` / `WriteAll` | 142,897 | 577,189 | 158,932 | 575,966 |
| `fastOctreeRowViewReadAll` / `WriteAll` | 156,263 | 700,549 | 157,980 | 723,783 |

## Single-element read (ns/op)

| Benchmark | ns/op |
|-----------|------:|
| `baselineReadSingle` | 0.87 |
| `rawReadSingle` | 1.61 |
| `packedReadSingle` | 2.87 |
| `hashmapReadSingle` | 3.64 |
| `sparseReadSingle` | 4.21 |
| **`chunkedReadSingle`** | **4.43** |
| `packedCursorReadSingle` (ByteBuddy) | 7.17 |
| `fastOctreeReadSingle` | 7.53 |
| `octreeReadSingle` | 8.49 |
| `packedCursorVhReadSingle` (VarHandle) | 35.01 |

## Collections

| Benchmark | Bulk read | Bulk write | Rnd read | Rnd write |
|-----------|----------:|-----------:|---------:|----------:|
| `packedListReadAll` / `WriteAll` | 129,011 | 200,284 | 129,011 | 196,144 |
| `packedIntMapReadAll` / `WriteAll` | 147,123 | 276,556 | 154,217 | 281,963 |

## Key takeaways

### Reads
- **`RawDataStore` bulk read** (1 990 ns) is the fastest store: ~1.5× baseline; no bit
  operations, just raw array access.
- **Direct `IntAccessor` bulk read** on Packed is ~7× slower than a baseline array scan
  (bit-unpacking overhead) but much faster than sparse/octree options.
- **`ChunkedDataStore` bulk read** (6 210 ns) is ~2× Packed but ~3× faster than Sparse
  and FastOctree — the extra hash-probe overhead per chunk is small relative to the
  bit-packed read savings.
- **`DataCursor` (ByteBuddy) bulk read** is competitive after JIT warm-up, and ~10× faster
  than the VarHandle fallback on packed stores.
- **`RowView` bulk read** is expensive because it allocates a new record instance per `get()`.

### Writes
- **ChunkedDataStore writes** (9 799 ns bulk) are ~6× faster than SparseDataStore writes
  because each chunk is a contiguous PackedDataStore — no per-row HashMap overhead.
- **Octree and FastOctree non-batch writes** are expensive (~541–656 µs for 1 000 rows).
  Use `beginBatch()` / `endBatch()` for bulk writes.

### Accessor pattern guide

| Pattern | Alloc/row? | Hot-loop? | Multi-component? | Best for |
|---------|-----------|-----------|-----------------|----------|
| `IntAccessor` (direct) | None | ✓ fastest | one field | Maximum throughput on a single field |
| `DataCursor<T>` (ByteBuddy) | None | ✓ fast | any fields | Cross-component projections in hot loops |
| `DataCursor<T>` (VarHandle) | None | ✗ (~10× slower on packed) | any fields | Fallback when ByteBuddy unavailable |
| `RowView<T>` | 1 record/read | ✗ (reads) | one component | Ergonomic whole-record get/set |

## ByteBuddy vs VarHandle cursor — comparison

All values in ns/op.

### Packed store (1 000 rows)

| Operation | ByteBuddy cursor | VarHandle cursor | Speedup |
|-----------|----------------:|-----------------:|--------:|
| Bulk read | 3,170 | 31,481 | **9.9×** |
| Bulk write | 12,588 | 43,989 | **3.5×** |
| Random read | 3,559 | 31,190 | **8.8×** |
| Random write | 19,790 | 49,073 | **2.5×** |
| Single read | 7.17 | 35.01 | **4.9×** |

### Sparse store (1 000 rows)

| Operation | ByteBuddy cursor | VarHandle cursor | Speedup |
|-----------|----------------:|-----------------:|--------:|
| Bulk read | 19,947 | 48,101 | **2.4×** |
| Bulk write | 60,432 | 83,656 | **1.4×** |
| Random read | 20,479 | 49,242 | **2.4×** |
| Random write | 62,267 | 83,554 | **1.3×** |

## Reproduction

```bash
# Full benchmark run (3 warmup / 5 measurement / 1 fork):
./gradlew jmhRun

# Lightweight CI run (1 warmup / 1 measurement / 1 fork):
./gradlew jmhCi
```

> **Tip:** Run on dedicated hardware with CPU frequency scaling disabled for
> more stable measurements.
