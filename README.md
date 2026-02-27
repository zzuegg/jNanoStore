# jBinary

**jBinary** is a Java 25 library for type-safe, high-performance, memory-efficient
datastores that pack annotated record fields into a shared `long[]` backing store using
bit-level packing.

## Quickstart

### 1. Define component types

```java
import io.github.zzuegg.jbinary.annotation.*;

// Terrain: 8-bit height (0–255), fixed-point temperature (−50…50, 2 d.p.), 1-bit active flag
public record Terrain(
        @BitField(min = 0, max = 255)          int height,
        @DecimalField(min = -50.0, max = 50.0, precision = 2) double temperature,
        @BoolField                              boolean active
) {}

// Water: 4-decimal-place salinity (0–1), frozen flag
public record Water(
        @DecimalField(min = 0.0, max = 1.0, precision = 4) double salinity,
        @BoolField                                          boolean frozen
) {}
```

### 2. Create the shared DataStore

```java
import io.github.zzuegg.jbinary.DataStore;

DataStore store = DataStore.of(10_000, Terrain.class, Water.class);
```

Both `Terrain` and `Water` share the same `long[]` – each row holds the packed bits for
**all** registered component types.

### 3. Create pre-computed accessors (do this once, store as static fields)

```java
import io.github.zzuegg.jbinary.Accessors;
import io.github.zzuegg.jbinary.accessor.*;

// Terrain accessors
IntAccessor    terrainHeight = Accessors.intFieldInStore(store, Terrain.class, "height");
DoubleAccessor terrainTemp   = Accessors.doubleFieldInStore(store, Terrain.class, "temperature");
BoolAccessor   terrainActive = Accessors.boolFieldInStore(store, Terrain.class, "active");

// Water accessors
DoubleAccessor waterSalinity = Accessors.doubleFieldInStore(store, Water.class, "salinity");
BoolAccessor   waterFrozen   = Accessors.boolFieldInStore(store, Water.class, "frozen");
```

### 4. Read and write – array-like, allocation-free

```java
int index = 42;

// Write
terrainHeight.set(store, index, 200);
terrainTemp.set(store, index, -12.5);
terrainActive.set(store, index, true);

waterSalinity.set(store, index, 0.035);
waterFrozen.set(store, index, false);

// Read
int    h = terrainHeight.get(store, index);   // → 200
double t = terrainTemp.get(store, index);     // → −12.50
boolean a = terrainActive.get(store, index);  // → true
```

### 5. Enums

```java
public enum Biome { PLAINS, FOREST, DESERT, OCEAN }

public record BiomeData(
        @EnumField Biome biome,
        @BitField(min = 0, max = 100) int fertility
) {}

DataStore biomeStore = DataStore.of(1000, BiomeData.class);
EnumAccessor<Biome> biomeAcc =
        Accessors.enumFieldInStore(biomeStore, BiomeData.class, "biome");

biomeAcc.set(biomeStore, 0, Biome.FOREST);
Biome b = biomeAcc.get(biomeStore, 0); // → FOREST
```

## Supported Field Types

| Annotation          | Java type      | Storage                                          |
|---------------------|----------------|--------------------------------------------------|
| `@BitField(min,max)`| `int` / `long` | ⌈log₂(max−min+1)⌉ bits, offset from min         |
| `@DecimalField`     | `double`/`float` | fixed-point scaled to long, same bit calc      |
| `@BoolField`        | `boolean`      | 1 bit                                            |
| `@EnumField`        | any `enum`     | ⌈log₂(N)⌉ bits by ordinal (or explicit codes)   |

## DataStore variants

jBinary provides three `DataStore` implementations that all use the same bit-packing
logic and work with the same accessor API.

### `PackedDataStore` (default / dense)

Pre-allocates a single contiguous `long[]` for all rows at construction time.
Best when most rows will be written.

```java
DataStore store = DataStore.packed(10_000, Terrain.class, Water.class);
// or equivalently:
DataStore store = DataStore.of(10_000, Terrain.class, Water.class);
```

### `SparseDataStore` (lazy row allocation)

Allocates each row's `long[]` on the first write; unwritten rows read back as
all-zeros (field minimum for int/decimal, `false` for bool, ordinal-0 for enum).
Best when only a fraction of rows will ever be populated.

```java
DataStore store = DataStore.sparse(10_000, Terrain.class, Water.class);
int writtenRows = ((SparseDataStore) store).allocatedRowCount();
```

### `OctreeDataStore` (sparse 3-D with automatic collapsing)

Organises a `sideLength × sideLength × sideLength` voxel space (sideLength = 2^maxDepth)
as a sparse octree.  On each write, the store checks whether all 8 siblings satisfy every
registered `CollapsingFunction`; if so, those 8 child nodes are merged into a single parent
node.  Reading traverses from the finest level upward and returns the first matching node.

```java
import io.github.zzuegg.jbinary.octree.*;

record Voxel(
    @BitField(min = 0, max = 15)  int material,
    @BitField(min = 0, max = 255) int density
) {}

// maxDepth=6 → 64 × 64 × 64 voxel space
OctreeDataStore store = OctreeDataStore.builder(6)
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

**`CollapsingFunction` factories:**

| Factory | Behaviour |
|---------|-----------|
| `CollapsingFunction.equalBits()` | Collapse when all 8 children are bit-identical (default) |
| `CollapsingFunction.never()` | Never collapse |
| `CollapsingFunction.always()` | Always collapse regardless of values |
| Custom lambda | Full control via `canCollapse(offset, bits, stride, children[8])` |

## Memory savings

### Packed field encoding

jBinary stores each field in the minimum number of bits needed to represent its range,
using a stride of ⌈totalBits/64⌉ `long` words per row.  For example:

| Field                               | Java type | Naive JVM | jBinary storage |
|-------------------------------------|-----------|-----------|-----------------|
| `@BitField(min=0, max=255)`         | `int`     | 32 bits   | **8 bits**      |
| `@DecimalField(min=-50, max=50, precision=2)` | `double` | 64 bits | **14 bits** (range 10 000 → 14 bits) |
| `@BoolField`                        | `boolean` | 8–32 bits | **1 bit**       |
| `@EnumField` (4-constant enum)      | `enum`    | 32 bits   | **2 bits**      |

**`Terrain` example** (height + temperature + active):

| Layout | Bits/row | 10 000-row memory |
|--------|----------|-------------------|
| Naive JVM (`int` + `double` + `boolean`) | ≥ 128 bits | ≥ 160 KB |
| jBinary `PackedDataStore` | **23 bits** → 1 `long`/row | **80 KB** (~50% of naive) |

That is a **2× reduction** before any sparsity optimisation.

### Sparsity savings (`SparseDataStore`)

`SparseDataStore` only allocates a row-array when the row is first written.  For a
10 000-row store where only 10 % of rows are ever populated, heap usage drops to roughly
10 % of the packed store (plus a small `HashMap` overhead per allocated row).

### Octree savings (`OctreeDataStore`)

`OctreeDataStore` stores a 3-D voxel space (side = 2^maxDepth).  Whenever all 8 children
of an octree node are identical (or satisfy the registered `CollapsingFunction`), those
8 nodes are replaced by 1.  In the best case (uniform space) the entire volume collapses
to a single root node — **O(1) memory regardless of capacity**.  In typical voxel worlds
with large homogeneous regions (air, stone, water), node counts stay orders of magnitude
below the theoretical maximum.

| Scenario | Nodes stored | Memory |
|----------|-------------|--------|
| Completely uniform (e.g. all-air) | 1 | 1 × `long[stride]` |
| 50 % uniform surface world (depth 6, 64³) | hundreds | kB range |
| Fully heterogeneous (worst case) | 2^(3×maxDepth) | same as `SparseDataStore` |

## Benchmarks

The benchmark suite (`DataStoreBenchmark`) compares all three `DataStore` implementations
against a **baseline** of plain parallel primitive arrays (`int[]` + `double[]` +
`boolean[]`).  All numbers are estimated average time per operation (ns/op) on JDK 25
with JMH 1.37, 1 024-row dataset:

### Throughput (bulk operations over 1 024 rows)

| Benchmark | ~ns/op | vs Baseline |
|-----------|--------|-------------|
| `baselineReadAll` | ~388 | 1× (reference) |
| `baselineWriteAll` | ~402 | 1× (reference) |
| `packedReadAll` | ~1 432 | ~3.7× slower |
| `packedWriteAll` | ~2 016 | ~5.0× slower |
| `sparseReadAll` | ~1 650 | ~4.3× slower |
| `sparseWriteAll` | ~2 400 | ~6.0× slower |
| `octreeReadAll` | ~4 800 | ~12× slower |
| `octreeWriteAll` | ~9 500 | ~24× slower |

### Throughput (single-element operations)

| Benchmark | ~ns/op | vs Baseline |
|-----------|--------|-------------|
| `baselineReadSingle` | ~1.1 | 1× (reference) |
| `packedReadSingle` | ~4.8 | ~4.4× slower |
| `sparseReadSingle` | ~6.5 | ~5.9× slower |
| `octreeReadSingle` | ~18 | ~16× slower |

**Why the differences?**

| Store | Extra overhead per access | When it wins |
|-------|--------------------------|--------------|
| `PackedDataStore` | Bit-shift + mask on a contiguous `long[]` | Large dense datasets where ~82 % smaller working set improves cache hit rates |
| `SparseDataStore` | Same bit ops + `HashMap.get()` to locate the row array | Large sparse datasets; heap ≫ L3 cache, so unallocated rows cost nothing |
| `OctreeDataStore` | Bit ops + Morton decode + tree traversal (up to `maxDepth` hops) | 3-D voxel worlds with large uniform regions that collapse; memory savings dominate |

See [BENCHMARKS.md](BENCHMARKS.md) for full numbers, environment details, and
reproduction instructions.

```bash
./gradlew jmhRun
```

## Building and testing

```bash
./gradlew build         # compiles + tests
./gradlew test          # unit tests only
./gradlew jmhRun        # JMH benchmarks
```
