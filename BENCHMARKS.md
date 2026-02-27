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

## Full results — DataCursor

| Benchmark | Bulk read | Bulk write | Rnd read | Rnd write | Rnd r+w |
|-----------|----------:|-----------:|---------:|----------:|--------:|
| `packedCursorReadAll` / `WriteAll` | 32,657 | 35,625 | 32,636 | 39,697 | 34,629 |
| `sparseCursorReadAll` / `WriteAll` | 41,410 | 70,019 | 43,290 | 73,352 | 57,587 |
| `octreeCursorReadAll` / `WriteAll` | 72,932 | 579,168 | 72,697 | 544,030 | 319,661 |
| `fastOctreeCursorReadAll` / `WriteAll` | 78,072 | 713,217 | 79,663 | 676,089 | 361,832 |

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
| `packedCursorReadSingle` | 35.4 |

## Charts — bulk sequential operations (1 000 rows)

Each `▓` ≈ 2 300 ns. Scale chosen so the most expensive read (octree 57 296 ns) fills 25 chars.

```
─── Bulk Sequential READ (ns/op, lower = faster) ───────────────────────────────────

Baseline  (arrays)           ▏                            366
Packed    (direct)          ▓▓▓▓▓▓▓▓▓▓▓▓▓               3,027
Packed    (Cursor)          ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ *          32,657   *VarHandle overhead
Packed    (RowView)         ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ **  126,705   **record alloc
HashMap   (boxed)           ▓▓▓                          5,880
Sparse    (direct)          ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓      17,897
Sparse    (Cursor)          ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓      41,410
FastOct   (direct)          ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓      25,589
Octree    (direct)          ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  (25 chars = 57 296 ns)     57,296
```

Each `▓` ≈ 22 000 ns. Scale chosen so octree write (555 661 ns) fills 25 chars.

```
─── Bulk Sequential WRITE (ns/op, lower = faster) ──────────────────────────────────

Baseline  (arrays)           ▏                             1,376
Packed    (direct)           ▏                            14,413
Packed    (Cursor)           ▓▓                           35,625
Packed    (RowView)          ▓▓                           39,816
HashMap   (boxed)            ▓                            18,676
Sparse    (direct)           ▓▓                           42,932
Sparse    (Cursor)           ▓▓▓                          70,019
Sparse    (RowView)          ▓▓▓                          74,239
OctBatch  (direct)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓              304,070   ← batch
Octree    (direct)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓   555,661
Octree    (Cursor)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  579,168
Octree    (RowView)          ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  579,880
FstOctBat (direct)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓            354,644   ← batch
FastOct   (direct)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓      684,887
FastOct   (Cursor)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓     713,217
FastOct   (RowView)          ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓    727,075
```

## Charts — random access (1 000 random-order ops)

```
─── Random READ (ns/op, lower = faster) ─────────────────────────────────────────────

Baseline  (arrays)           ▏                             1,113
Packed    (direct)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓              3,863
Packed    (Cursor)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓     32,636
Packed    (RowView)          too wide — 156,038 ns (record alloc on every get)
HashMap   (boxed)            ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓                      7,872
Sparse    (direct)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓     21,938
FastOct   (direct)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓      22,685
Octree    (direct)           ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓     34,927
  (each ▓ ≈ 260 ns in this section)
```

## Key takeaways

### Reads
- **Direct `IntAccessor` bulk read** on Packed is ~8× slower than a baseline array scan
  (bit-unpacking overhead) but ~2× faster than HashMap.
- **`DataCursor` bulk read** adds ~10× VarHandle overhead over direct IntAccessor on Packed,
  but provides allocation-free access to multiple fields across components — the right
  tool for hot inner loops that read/write a cross-component projection.
- **`RowView` bulk read** is the most expensive for reads because it allocates a new record
  instance on every `get()` call.  Use it for ergonomic one-off record reads, not hot loops.
- **Octree and FastOctree reads** involve Morton-code decoding and tree traversal, making
  them slower than flat stores for pure-read workloads; FastOctree is ~2× faster than
  Octree on reads.

### Writes
- **Packed and Sparse writes** are fast: just bit-shift + mask operations on a `long[]`.
  `DataCursor.flush()` and `RowView.set()` cost slightly more (VarHandle / reflective invoke)
  but remain in the same order of magnitude.
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
| `DataCursor<T>` | None | ✓ near-zero | any fields | Cross-component projections in hot loops |
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
