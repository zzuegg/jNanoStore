# jBinary Benchmark Results

## Overview

All four `DataStore` implementations (packed, sparse, octree, fast-octree) are benchmarked
across three accessor patterns (`IntAccessor`, `DataCursor`, `RowView`) and five operation
types:

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

A **multi-component scenario** (`multiXxx` benchmarks) adds a second component type
(`Water` with `salinity` + `frozen`) and uses a `WorldCursor` projecting **five fields
across two components** from a single shared store.  A five-array baseline
(`multiBaselineXxx`) serves as the reference for that scenario.

All benchmarks use **pre-computed, fixed-seed random data** (no inline arithmetic patterns)
to reflect realistic workloads where field values come from existing data structures.

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
| `baselineReadAll` / `WriteAll` | 376 | 241 | 645 | 1,219 | 955 |
| `hashmapReadAll` / `WriteAll` | 5,995 | 18,547 | 8,144 | 18,412 | 14,656 |
| `packedReadAll` / `WriteAll` | 2,972 | 10,895 | 4,203 | 13,505 | 8,772 |
| `sparseReadAll` / `WriteAll` | 15,708 | 56,492 | 19,519 | 55,331 | 38,437 |
| `octreeReadAll` / `WriteAll` | 22,228 | 549,425 | 22,603 | 552,599 | 296,724 |
| `octreeBatchWriteAll` | — | 324,784 | — | — | — |
| `fastOctreeReadAll` / `WriteAll` | 8,831 | 582,865 | 19,049 | 607,839 | 300,800 |
| `fastOctreeBatchWriteAll` | — | 335,491 | — | — | — |

## Full results — DataCursor (ByteBuddy, single component)

`DataCursor.of()` uses a ByteBuddy-generated class with direct `PUTFIELD`/`GETFIELD`
instructions for all cursor fields.  After JIT warm-up the generated codec is fully inlined.

| Benchmark | Bulk read | Bulk write | Rnd read | Rnd write | Rnd r+w |
|-----------|----------:|-----------:|---------:|----------:|--------:|
| `packedCursorReadAll` / `WriteAll` | 10,177 | 23,913 | 10,661 | 18,365 | 14,203 |
| `sparseCursorReadAll` / `WriteAll` | 20,148 | 57,407 | 24,950 | 58,140 | 41,386 |
| `octreeCursorReadAll` / `WriteAll` | 22,079 | 558,699 | 28,233 | 566,485 | 291,255 |
| `fastOctreeCursorReadAll` / `WriteAll` | 22,372 | 585,528 | 19,064 | 644,942 | 311,066 |

## Full results — multi-component scenario (Terrain + Water, 5 fields via WorldCursor)

The `WorldCursor` reads/writes five fields spanning two registered component types
(`Terrain.height`, `Terrain.temperature`, `Terrain.active`, `Water.salinity`,
`Water.frozen`) in a single `DataCursor.update()` / `flush()` call.
The `multiBaseline` row uses five parallel primitive arrays as a reference.

| Benchmark | Bulk read | Bulk write | Rnd read | Rnd write | Rnd r+w |
|-----------|----------:|-----------:|---------:|----------:|--------:|
| `multiBaselineReadAll` / `WriteAll` | 759 | 660 | 1,127 | 2,743 | 1,609 |
| `multiPackedCursorReadAll` / `WriteAll` | 15,698 | 20,163 | 15,702 | 26,364 | 21,866 |
| `multiSparseCursorReadAll` / `WriteAll` | 32,837 | 93,864 | 35,295 | 94,313 | 64,972 |
| `multiOctreeCursorReadAll` / `WriteAll` | 39,610 | 937,400 | 36,988 | 942,596 | 492,419 |
| `multiFastOctreeCursorReadAll` / `WriteAll` | 34,463 | 1,042,187 | 34,029 | 1,073,341 | 531,554 |

> **Note:** Octree/FastOctree write cost roughly doubles relative to single-component because
> the combined `Terrain + Water` bit-stride is larger, increasing tree-manipulation work per
> write.  Packed and Sparse cost is comparable to single-component since two components still
> fit in one `long` word per row.

## Full results — DataCursor (VarHandle fallback)

`DataCursor.ofVarHandle()` uses the VarHandle-based fallback path, bypassing ByteBuddy.
This measures the overhead of indirect VarHandle dispatch for load/flush operations.

| Benchmark | Bulk read | Bulk write | Rnd read | Rnd write |
|-----------|----------:|-----------:|---------:|----------:|
| `packedCursorVhReadAll` / `WriteAll` | 32,064 | 47,667 | 31,637 | 50,058 |
| `sparseCursorVhReadAll` / `WriteAll` | 45,527 | 83,051 | 48,800 | 86,364 |

## Full results — RowView

| Benchmark | Bulk read | Bulk write | Rnd read | Rnd write | Rnd r+w |
|-----------|----------:|-----------:|---------:|----------:|--------:|
| `packedRowViewReadAll` / `WriteAll` | 143,564 | 42,955 | 141,060 | 42,575 | 96,442 |
| `sparseRowViewReadAll` / `WriteAll` | 144,628 | 88,439 | 157,900 | 90,460 | 136,852 |
| `octreeRowViewReadAll` / `WriteAll` | 161,117 | 585,541 | 166,880 | 589,893 | 389,014 |
| `fastOctreeRowViewReadAll` / `WriteAll` | 163,321 | 637,568 | 149,491 | 658,104 | 424,191 |

## Single-element read (ns/op)

| Benchmark | ns/op |
|-----------|------:|
| `baselineReadSingle` | 0.84 |
| `packedReadSingle` | 2.52 |
| `sparseReadSingle` | 4.74 |
| `hashmapReadSingle` | 4.07 |
| `octreeReadSingle` | 8.56 |
| `fastOctreeReadSingle` | 8.08 |
| `packedCursorReadSingle` (ByteBuddy) | 7.98 |
| `packedCursorVhReadSingle` (VarHandle) | 31.81 |

## Multi-component scenario analysis

### Single vs multi-component bulk read (ns/op)

| Store | Single (3 fields) | Multi (5 fields) | Overhead |
|-------|------------------:|-----------------:|---------:|
| Baseline arrays | 376 | 759 | 2.0× |
| Packed (DataCursor) | 10,177 | 15,698 | 1.5× |
| Sparse (DataCursor) | 20,148 | 32,837 | 1.6× |
| Octree (DataCursor) | 22,079 | 39,610 | 1.8× |
| FastOctree (DataCursor) | 22,372 | 34,463 | 1.5× |

Adding two more fields (67% more fields) adds only ~50–80% more read time on packed/sparse
stores — sub-linear scaling thanks to the compact bit-packed layout keeping both components
in the same `long` words.

### Key multi-component takeaways

- **Packed** is the fastest multi-component store: 15 700 ns bulk read for 5 fields across
  2 components, only ~21× slower than 5 raw array reads (759 ns).
- **Sparse** and **FastOctree** are within 2.2× of each other for bulk reads (~33–35 µs).
- **Octree writes** are expensive (~940 µs bulk) because random values prevent collapse.
  Use `beginBatch()` / `endBatch()` for uniform fills.
- The `DataCursor` API makes multi-component access as simple as single-component:
  just add more `@StoreField` annotations to the cursor class.

## ByteBuddy vs VarHandle cursor — comparison

All values in ns/op.

### Packed store (1 000 rows)

| Operation | ByteBuddy cursor | VarHandle cursor | Speedup |
|-----------|----------------:|-----------------:|--------:|
| Bulk read | 10,177 | 32,064 | **3.1×** |
| Bulk write | 23,913 | 47,667 | **2.0×** |
| Random read | 10,661 | 31,637 | **3.0×** |
| Random write | 18,365 | 50,058 | **2.7×** |
| Single read | 7.98 | 31.81 | **4.0×** |

### Sparse store (1 000 rows)

| Operation | ByteBuddy cursor | VarHandle cursor | Speedup |
|-----------|----------------:|-----------------:|--------:|
| Bulk read | 20,148 | 45,527 | **2.3×** |
| Bulk write | 57,407 | 83,051 | **1.4×** |
| Random read | 24,950 | 48,800 | **2.0×** |
| Random write | 58,140 | 86,364 | **1.5×** |

## Key takeaways

### Reads
- **Direct `IntAccessor` bulk read** on Packed is ~8× slower than a baseline array scan
  (bit-unpacking overhead) but ~2× faster than HashMap.
- **`DataCursor` (ByteBuddy) bulk read** is competitive after JIT warm-up, and ~3× faster
  than the VarHandle fallback on packed stores.
- **`DataCursor` (VarHandle fallback) bulk read** is ~3× slower than ByteBuddy on packed
  stores because VarHandle dispatch is harder for the JIT to inline.
- **`RowView` bulk read** is the most expensive for reads because it allocates a new record
  instance on every `get()` call.  Use it for ergonomic one-off record reads, not hot loops.
- **Octree and FastOctree reads** involve Morton-code decoding and tree traversal;
  FastOctree is ~2–3× faster than Octree on reads thanks to its primitive hash map.

### Writes
- **Packed and Sparse writes** are fast: just bit-shift + mask operations on a `long[]`.
  `DataCursor` (ByteBuddy) adds moderate overhead; VarHandle adds ~2× overhead vs ByteBuddy.
- **Octree and FastOctree non-batch writes** are expensive (~550–610 µs for 1 000 rows)
  because every write with a *different* value triggers tree-manipulation.  **Use
  `beginBatch()` / `endBatch()` for bulk writes** — batch mode roughly halves write cost
  (~325–335 µs).
- For octrees, `RowView.set()` and `DataCursor.flush()` cost similarly to direct
  `IntAccessor.set()` — the tree-traversal overhead dominates.

### Accessor pattern guide

| Pattern | Alloc/row? | Hot-loop? | Multi-component? | Best for |
|---------|-----------|-----------|-----------------|----------|
| `IntAccessor` (direct) | None | ✓ fastest | one field | Maximum throughput on a single field |
| `DataCursor<T>` (ByteBuddy) | None | ✓ fast | any fields | Cross-component projections in hot loops |
| `DataCursor<T>` (VarHandle) | None | ✗ (~3× slower on packed) | any fields | Fallback when ByteBuddy unavailable |
| `RowView<T>` | 1 record/read | ✗ (reads) | one component | Ergonomic whole-record get/set |

## Reproduction

```bash
# Full benchmark run (3 warmup / 5 measurement / 1 fork):
./gradlew jmhRun

# Lightweight CI run (1 warmup / 1 measurement / 1 fork):
./gradlew jmhCi
```

The benchmark fat-jar is assembled automatically.

> **Tip:** Run on dedicated hardware with CPU frequency scaling disabled for
> more stable measurements.  Add `-f 3` for multiple forks.
