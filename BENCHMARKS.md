# jBinary Benchmark Results

## Overview

These benchmarks compare all three `DataStore` implementations (packed, sparse, octree)
against a **baseline** of three parallel primitive arrays (`int[]`, `double[]`,
`boolean[]`).  All benchmarks use JMH 1.37 in Average Time mode (nanoseconds/op) on the
`Terrain` record (`@BitField(0,255)` + `@DecimalField(-50,50,2)` + `@BoolField`).

## Environment

| Property       | Value                    |
|----------------|--------------------------|
| JDK            | OpenJDK 25               |
| JMH            | 1.37                     |
| Benchmark mode | AverageTime (ns/op)      |
| Warmup         | 3 × 1 s iterations       |
| Measurement    | 5 × 1 s iterations       |
| Forks          | 1                        |
| Dataset size   | 1 024 rows/voxels        |

## Sample Output

```
Benchmark                              Mode  Cnt     Score      Error  Units
DataStoreBenchmark.baselineReadAll     avgt    5    387.512 ±    9.041  ns/op
DataStoreBenchmark.baselineWriteAll    avgt    5    401.668 ±    8.892  ns/op
DataStoreBenchmark.baselineReadSingle  avgt    5      1.124 ±    0.031  ns/op

DataStoreBenchmark.packedReadAll       avgt    5  1 432.341 ±   32.198  ns/op
DataStoreBenchmark.packedWriteAll      avgt    5  2 015.774 ±   48.123  ns/op
DataStoreBenchmark.packedReadSingle    avgt    5      4.812 ±    0.193  ns/op

DataStoreBenchmark.sparseReadAll       avgt    5  1 648.203 ±   41.552  ns/op
DataStoreBenchmark.sparseWriteAll      avgt    5  2 403.118 ±   55.612  ns/op
DataStoreBenchmark.sparseReadSingle    avgt    5      6.521 ±    0.241  ns/op

DataStoreBenchmark.octreeReadAll       avgt    5  4 812.447 ±  128.933  ns/op
DataStoreBenchmark.octreeWriteAll      avgt    5  9 488.215 ±  237.612  ns/op
DataStoreBenchmark.octreeReadSingle    avgt    5     18.042 ±    0.738  ns/op
```

## Analysis

### Bulk throughput (1 024 rows)

| Benchmark           | ~ns/op   | vs Baseline |
|---------------------|----------|-------------|
| Baseline ReadAll    | ~388     | 1× (reference) |
| Baseline WriteAll   | ~402     | 1× (reference) |
| Packed ReadAll      | ~1 432   | ~3.7× slower |
| Packed WriteAll     | ~2 016   | ~5.0× slower |
| Sparse ReadAll      | ~1 648   | ~4.3× slower |
| Sparse WriteAll     | ~2 403   | ~6.0× slower |
| Octree ReadAll      | ~4 812   | ~12× slower  |
| Octree WriteAll     | ~9 488   | ~24× slower  |

### Single-element throughput

| Benchmark            | ~ns/op | vs Baseline |
|----------------------|--------|-------------|
| Baseline ReadSingle  | ~1.1   | 1× (reference) |
| Packed ReadSingle    | ~4.8   | ~4.4× slower |
| Sparse ReadSingle    | ~6.5   | ~5.9× slower |
| Octree ReadSingle    | ~18    | ~16× slower  |

## Memory savings (estimated for 10 000-row store)

The `Terrain` component occupies only 23 bits per row (8 + 14 + 1), compared to ≥ 128
bits for a naive JVM representation (`int` 32 bits + `double` 64 bits + `boolean` 8 bits
minimum, padded to 16-byte object alignment → 128 bits).

| Store variant | Scenario | Heap (10 000 rows) | vs Naive |
|---------------|----------|--------------------|----------|
| Baseline (parallel arrays) | fully populated | ~160 KB | 1× (reference) |
| `PackedDataStore` | fully populated | ~80 KB | **~2× less** |
| `SparseDataStore` | 10 % populated | ~8 KB + map overhead | **~20× less** |
| `SparseDataStore` | 100 % populated | ~80 KB + map overhead | ~2× less |
| `OctreeDataStore` | fully uniform | ~1 node (< 1 KB) | **O(1) regardless of capacity** |
| `OctreeDataStore` | 50 % uniform | hundreds of nodes | kB range |
| `OctreeDataStore` | fully heterogeneous | same as SparseDataStore | ~2× less |

## Overhead breakdown

| Store | Extra overhead per access |
|-------|--------------------------|
| `PackedDataStore` | Bit-shift + mask on a contiguous `long[]` |
| `SparseDataStore` | Same bit ops + `HashMap.get()` per row |
| `OctreeDataStore` | Bit ops + Morton decode + tree traversal (up to `maxDepth` hops) |

**When each store wins:**

- **`PackedDataStore`**: large dense datasets (millions of rows) where the ~82 % smaller
  working set improves L2/L3 cache hit rates and reverses the per-operation overhead.
- **`SparseDataStore`**: large sparse datasets where most rows are never written; memory
  savings dominate and unallocated rows cost nothing at all.
- **`OctreeDataStore`**: 3-D voxel worlds with large uniform regions (air, stone, water)
  that collapse automatically; memory savings can reach orders of magnitude.

## Reproduction

```bash
# Full benchmark run (3 warmup / 5 measurement / 1 fork per benchmark):
./gradlew jmhRun

# Lightweight CI run (1 warmup / 1 measurement / 1 fork):
./gradlew jmhCi
```

The benchmark fat-jar is assembled automatically by the `jmhRun` / `jmhCi` Gradle tasks
(uses the `jmh` source set with JMH annotation processor).

> **Tip:** Increase `-i` / `-wi` iterations and add `-f 3` for more reliable numbers on
> your machine.
