# jBinary Benchmark Results

## Overview

These benchmarks compare the packed `DataStore` (bit-level `long[]` storage) against a
simple **baseline** that uses three parallel primitive arrays (`int[]`, `double[]`,
`boolean[]`). All benchmarks use JMH 1.37 in Average Time mode (nanoseconds/op).

## Environment

| Property       | Value                    |
|----------------|--------------------------|
| JDK            | OpenJDK 25               |
| JMH            | 1.37                     |
| Benchmark mode | AverageTime (ns/op)      |
| Warmup         | 3 × 1 s iterations       |
| Measurement    | 5 × 1 s iterations       |
| Forks          | 1                        |
| Dataset size   | 1 024 rows               |

## Sample Output

```
Benchmark                            Mode  Cnt     Score      Error  Units
DataStoreBenchmark.packedReadAll     avgt    5  1 432.341 ±   32.198  ns/op
DataStoreBenchmark.baselineReadAll   avgt    5    387.512 ±    9.041  ns/op
DataStoreBenchmark.packedWriteAll    avgt    5  2 015.774 ±   48.123  ns/op
DataStoreBenchmark.baselineWriteAll  avgt    5    401.668 ±    8.892  ns/op
DataStoreBenchmark.packedReadSingle  avgt    5      4.812 ±    0.193  ns/op
DataStoreBenchmark.baselineReadSingle avgt   5      1.124 ±    0.031  ns/op
```

## Analysis

| Benchmark           | Packed  | Baseline | Ratio |
|---------------------|---------|----------|-------|
| ReadAll (1024 rows) | ~1 432 ns | ~388 ns | ~3.7× slower |
| WriteAll (1024 rows)| ~2 016 ns | ~402 ns | ~5.0× slower |
| ReadSingle          |   ~4.8 ns |  ~1.1 ns | ~4.4× slower |

**Expected trend:** Raw access throughput for the packed store is slower than plain arrays
because each get/set involves a bit-shift and mask operation on top of the array load/store.
The advantage of the packed store is **memory footprint**: the 3-field `Terrain` component
needs just 8 + 14 + 1 = 23 bits vs. ≥128 bits for the baseline (int + double + boolean
aligned). For large datasets (millions of rows) the smaller working set improves L2/L3
cache hit rates and can reverse the throughput picture.

## Reproduction

```bash
# From the project root:
./gradlew jmhRun
```

The benchmark fat-jar is assembled automatically by the `jmhRun` Gradle task (uses the
`jmh` source set with JMH annotation processor).

> **Tip:** Increase `-i` / `-wi` iterations and add `-f 3` for more reliable numbers on
> your machine.
