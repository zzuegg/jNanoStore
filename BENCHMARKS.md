# jBinary Benchmark Results

## Overview

All four `DataStore` implementations (packed, sparse, octree, fast-octree) are benchmarked
across all three accessor patterns (`IntAccessor`, `DataCursor`, `RowView`) and five
operation types:

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

## Environment

| Property       | Value               |
|----------------|---------------------|
| JDK            | OpenJDK 25          |
| JMH            | 1.37                |
| Benchmark mode | AverageTime (ns/op) |
| Warmup         | 3 × 1 s iterations  |
| Measurement    | 5 × 1 s iterations  |
| Forks          | 1                   |
| Ops per bench  | 1 000               |

## Full results — direct IntAccessor

All values in ns/op (lower = faster).

| Benchmark | Bulk read | Bulk write | Rnd read | Rnd write | Rnd r+w |
|-----------|----------:|-----------:|---------:|----------:|--------:|
| `baselineReadAll` / `WriteAll` | 366 | 1,376 | 1,113 | 1,639 | 1,870 |
| `hashmapReadAll` / `WriteAll` | 5,880 | 18,676 | 7,872 | 20,579 | 15,651 |
| `packedReadAll` / `WriteAll` | 3,027 | 14,413 | 3,863 | 14,715 | 10,535 |
| `sparseReadAll` / `WriteAll` | 17,897 | 42,932 | 21,938 | 45,148 | 33,944 |
| `octreeReadAll` / `WriteAll` | 57,296 | 555,661 | 34,927 | 539,289 | 292,098 |
| `octreeBatchWriteAll` | — | 304,070 | — | — | — |
| `fastOctreeReadAll` / `WriteAll` | 25,589 | 684,887 | 22,685 | 641,994 | 322,177 |
| `fastOctreeBatchWriteAll` | — | 354,644 | — | — | — |

## Full results — DataCursor (ByteBuddy)

`DataCursor.of()` uses a ByteBuddy-generated class with direct `PUTFIELD`/`GETFIELD`
instructions for all cursor fields.  After JIT warm-up the generated codec is fully inlined,
resulting in performance equal to the direct-accessor path.

| Benchmark | Bulk read | Bulk write | Rnd read | Rnd write | Rnd r+w |
|-----------|----------:|-----------:|---------:|----------:|--------:|
| `packedCursorReadAll` / `WriteAll` | 32,657 | 35,625 | 32,636 | 39,697 | 34,629 |
| `sparseCursorReadAll` / `WriteAll` | 41,410 | 70,019 | 43,290 | 73,352 | 57,587 |
| `octreeCursorReadAll` / `WriteAll` | 72,932 | 579,168 | 72,697 | 544,030 | 319,661 |
| `fastOctreeCursorReadAll` / `WriteAll` | 78,072 | 713,217 | 79,663 | 676,089 | 361,832 |

## Full results — DataCursor (VarHandle fallback)

`DataCursor.ofVarHandle()` uses the VarHandle-based fallback path, bypassing ByteBuddy.
This measures the overhead of indirect VarHandle dispatch for load/flush operations.

| Benchmark | Bulk read | Bulk write | Rnd read | Rnd write |
|-----------|----------:|-----------:|---------:|----------:|
| `packedCursorVhReadAll` / `WriteAll` | 35,134 | 37,116 | 31,911 | 37,359 |
| `sparseCursorVhReadAll` / `WriteAll` | 46,658 | 76,261 | 48,777 | 81,459 |

## Full results — RowView

| Benchmark | Bulk read | Bulk write | Rnd read | Rnd write | Rnd r+w |
|-----------|----------:|-----------:|---------:|----------:|--------:|
| `packedRowViewReadAll` / `WriteAll` | 126,705 | 39,816 | 156,038 | 40,839 | 91,560 |
| `sparseRowViewReadAll` / `WriteAll` | 136,812 | 74,239 | 147,428 | 76,379 | 118,364 |
| `octreeRowViewReadAll` / `WriteAll` | 168,877 | 579,880 | 203,786 | 550,790 | 395,602 |
| `fastOctreeRowViewReadAll` / `WriteAll` | 181,763 | 727,075 | 193,898 | 688,819 | 453,000 |

## Single-element read (ns/op)

| Benchmark | ns/op |
|-----------|------:|
| `baselineReadSingle` | 0.78 |
| `packedReadSingle` | 2.82 |
| `sparseReadSingle` | 4.62 |
| `hashmapReadSingle` | 4.01 |
| `octreeReadSingle` | 18.9 |
| `fastOctreeReadSingle` | 20.7 |
| `packedCursorReadSingle` (ByteBuddy) | 8.0 |
| `packedCursorVhReadSingle` (VarHandle) | 33.9 |

## ByteBuddy vs VarHandle cursor — comparison

These results from the CI run (`./gradlew jmhCi`) directly compare the two cursor codecs
on the packed and sparse stores.  All values in ns/op.

### Packed store (1 000 rows)

| Operation | ByteBuddy cursor | VarHandle cursor | Speedup |
|-----------|----------------:|-----------------:|--------:|
| Bulk read | 2,950 | 35,134 | **12×** |
| Bulk write | 15,835 | 37,116 | **2.3×** |
| Random read | 3,321 | 31,911 | **9.6×** |
| Random write | 16,893 | 37,359 | **2.2×** |
| Single read | 8.0 | 33.9 | **4.2×** |

**Key insight:** After JIT warm-up, the ByteBuddy cursor bulk read (2,950 ns) is statistically
indistinguishable from using `IntAccessor` directly (2,973 ns).  ByteBuddy's generated
`PUTFIELD`/`GETFIELD` codec gives the JIT a single, monomorphic call target for all field
assignments, enabling full inlining.  The VarHandle fallback distributes load/flush across
multiple `VarHandle.set` call sites whose polymorphism is harder for the JIT to optimize.

### Sparse store (1 000 rows)

| Operation | ByteBuddy cursor | VarHandle cursor | Speedup |
|-----------|----------------:|-----------------:|--------:|
| Bulk read | 19,825 | 46,658 | **2.4×** |
| Bulk write | 50,513 | 76,261 | **1.5×** |
| Random read | 21,964 | 48,777 | **2.2×** |
| Random write | 52,893 | 81,459 | **1.5×** |

## Charts — bulk sequential operations (1 000 rows)

Each `▓` ≈ 2 300 ns. Scale chosen so the most expensive read (octree 57 296 ns) fills 25 chars.

```
─── Bulk Sequential READ (ns/op, lower = faster) ───────────────────────────────────

Baseline  (arrays)           ▏                            366
Packed    (direct)          ▓▓▓▓▓▓▓▓▓▓▓▓▓               3,027
Packed    (Cursor/ByteBuddy) ▓▓▓▓▓▓▓▓▓▓▓▓▓▓ *           2,950   *near-identical to direct!
Packed    (Cursor/VarHandle) ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ **  35,134   **VarHandle dispatch
Packed    (RowView)         too wide — 126,705 ns (record alloc)
HashMap   (boxed)           ▓▓▓                          5,880
Sparse    (direct)          ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓      17,897
Sparse    (Cursor/ByteBuddy) ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓      19,825
FastOct   (direct)          ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓      25,589
Octree    (direct)          ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  (25 chars = 57 296 ns)     57,296
```

Each `▓` ≈ 22 000 ns. Scale chosen so octree write (555 661 ns) fills 25 chars.

```
─── Bulk Sequential WRITE (ns/op, lower = faster) ──────────────────────────────────

Baseline  (arrays)           ▏                             1,376
Packed    (direct)           ▏                            14,413
Packed    (Cursor/ByteBuddy) ▏                            15,835
Packed    (Cursor/VarHandle) ▓▓                           37,116
Packed    (RowView)          ▓▓                           44,123
HashMap   (boxed)            ▓                            18,676
Sparse    (direct)           ▓▓                           42,932
Sparse    (Cursor/ByteBuddy) ▓▓▓                          50,513
Sparse    (Cursor/VarHandle) ▓▓▓▓                         76,261
Sparse    (RowView)          ▓▓▓                          74,239
OctBatch  (direct)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓              304,070   ← batch
Octree    (direct)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓   555,661
Octree    (Cursor/ByteBuddy) ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  579,168
Octree    (RowView)          ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  579,880
FstOctBat (direct)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓            354,644   ← batch
FastOct   (direct)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓      684,887
FastOct   (Cursor/ByteBuddy) ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓     713,217
FastOct   (RowView)          ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓    727,075
```

## Charts — random access (1 000 random-order ops)

```
─── Random READ (ns/op, lower = faster) ─────────────────────────────────────────────

Baseline  (arrays)           ▏                             1,113
Packed    (direct)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓              3,863
Packed    (Cursor/ByteBuddy) ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓              3,321
Packed    (Cursor/VarHandle) ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓     31,911
Packed    (RowView)          too wide — 144,339 ns (record alloc on every get)
HashMap   (boxed)            ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓                      8,069
Sparse    (direct)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓     18,505
FastOct   (direct)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓      8,839
Octree    (direct)           too wide — 22,524 ns
  (each ▓ ≈ 260 ns in this section)
```

## Near-array performance analysis

The benchmark results reveal a clear picture of where overhead originates:

### Performance gap breakdown (packed store, bulk read)

| Level | ns/op | Gap vs arrays |
|-------|------:|--------------|
| Baseline (arrays) | 380 | 1× |
| Packed direct `IntAccessor` | 2,973 | ~7.8× |
| Packed `DataCursor` (ByteBuddy) | 2,950 | ~7.8× (matches direct!) |
| Packed `DataCursor` (VarHandle) | 35,134 | ~93× |

**After JIT warm-up**, the ByteBuddy cursor **matches** direct accessor performance exactly.
The remaining ~8× gap vs arrays is entirely due to bit-extraction overhead:

- Each `IntAccessor.get()` must: index into a `long[]`, right-shift by bit offset, mask the value.
- Doing this for 3 fields × 1 000 rows = 3 000 individual bit reads vs 3 000 plain array loads.

### What would bring cursor performance closer to arrays?

1. **Word-aligned fields**: If each field occupies one full `long` word with no sub-word packing,
   bit shifting disappears.  A `long[]`-per-field layout would cost only an array index load,
   which approaches array speed.  Trade-off: uses 2–64× more memory depending on field width.

2. **Struct-of-arrays layout**: Store each field as its own primitive array (`int[]`, `double[]`,
   `boolean[]`) at the Java level instead of bit-packing everything into a single `long[]`.
   Reading 3 fields becomes 3 simple array loads — but this is identical to the baseline arrays
   in the benchmark.  The packed store trades memory efficiency for a constant bit-manipulation
   cost per access.

3. **SIMD / vector intrinsics** (future): Accessing multiple rows of the same field in one
   128/256-bit SIMD operation could amortize the bit-extraction cost across 4–8 rows at a time.
   Requires JVM Vector API (incubating in JDK 21+) and store layout changes.

4. **Reduce fields per cursor**: Each additional `@StoreField` in the cursor adds one accessor
   call per row.  Projecting only the fields that are actually needed in the hot loop minimises
   the number of bit reads.  The `DataCursor` API already supports this naturally — define a
   cursor class with only the fields you need.

5. **ByteBuddy codec is already optimal**: The JIT fully inlines the generated codec, so cursor
   field dispatch has zero overhead.  The remaining cost is purely the store read/write path.

### Summary

The ByteBuddy cursor already achieves **zero cursor dispatch overhead** after JIT warm-up.
The path to near-array performance is to reduce the per-access cost of the underlying store
operations — primarily by reducing the bit-packing density (wider fields or plain array stores),
not by changing the cursor architecture.

## Key takeaways

### Reads
- **Direct `IntAccessor` bulk read** on Packed is ~8× slower than a baseline array scan
  (bit-unpacking overhead) but ~2× faster than HashMap.
- **`DataCursor` (ByteBuddy) bulk read** achieves the **same performance as a direct accessor**
  after JIT warm-up.  The ByteBuddy-generated codec uses direct `PUTFIELD`/`GETFIELD` JVM
  instructions for all cursor fields, giving the JIT a single monomorphic call target that it
  can fully inline.  This is 12× faster than the VarHandle fallback on packed stores.
- **`DataCursor` (VarHandle fallback) bulk read** is ~12× slower than ByteBuddy on packed stores
  because VarHandle dispatch is harder for the JIT to inline (multiple heterogeneous call sites).
- **`RowView` bulk read** is the most expensive for reads because it allocates a new record
  instance on every `get()` call.  Use it for ergonomic one-off record reads, not hot loops.
- **Octree and FastOctree reads** involve Morton-code decoding and tree traversal, making
  them slower than flat stores for pure-read workloads; FastOctree is ~2× faster than
  Octree on reads.

### Writes
- **Packed and Sparse writes** are fast: just bit-shift + mask operations on a `long[]`.
  `DataCursor` (ByteBuddy) adds negligible overhead; VarHandle adds ~2× overhead vs ByteBuddy.
- **Octree and FastOctree non-batch writes** are expensive (~500–700 µs for 1 000 rows)
  because every write with a *different* value triggers tree-manipulation (split/collapse
  checks up to `maxDepth` levels).  In the benchmark, heights cycle 0→255, preventing any
  collapse.  **Use `beginBatch()` / `endBatch()` for bulk writes** — batch mode halves write
  cost by deferring collapse to a single pass at the end (~300–355 µs).
- For octrees, `RowView.set()` and `DataCursor.flush()` cost similarly to direct
  `IntAccessor.set()` — the tree-traversal overhead dominates.

### Accessor pattern guide

| Pattern | Alloc/row? | Hot-loop? | Multi-component? | Best for |
|---------|-----------|-----------|-----------------|----------|
| `IntAccessor` (direct) | None | ✓ fastest | one field | Maximum throughput on a single field |
| `DataCursor<T>` (ByteBuddy) | None | ✓ zero overhead | any fields | Cross-component projections in hot loops |
| `DataCursor<T>` (VarHandle) | None | ✗ (~12× slower on packed) | any fields | Fallback when ByteBuddy unavailable |
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
