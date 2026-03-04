# BitKit

**BitKit** is a Java 25 library for type-safe, high-performance, memory-efficient
datastores that pack annotated record fields into a shared `long[]` backing store using
bit-level packing.

## Pages

| Page | Description |
|------|-------------|
| **[README.md](README.md)** | Overview (this page) |
| **[docs/STORES.md](docs/STORES.md)** | All DataStore variants with full examples |
| **[docs/COLLECTIONS.md](docs/COLLECTIONS.md)** | PackedList and PackedIntMap |
| **[BENCHMARKS.md](BENCHMARKS.md)** | Full JMH benchmark results |
| **[VOXEL_WORLD_EVALUATION.md](VOXEL_WORLD_EVALUATION.md)** | Voxel-world design analysis & ChunkedDataStore rationale |

---

## Features

* **Bit-packed DataStore** — stores annotated record fields in the minimum bits needed.
  A `Terrain` record with `height` (0–255, 8 bits), `temperature` (14 bits), and `active`
  (1 bit) occupies **1 `long` per row** instead of 128+ bits.
* **Six DataStore implementations** covering every memory/speed trade-off:
  `RawDataStore`, `PackedDataStore`, **`ChunkedDataStore`** (new), `SparseDataStore`,
  `FastOctreeDataStore`, `OctreeDataStore`.
* **Standard Java collections** (`PackedList`, `PackedIntMap`) backed by bit-packed
  storage — same memory savings, familiar `java.util` API.
* **`ByteBuffer` support** — serialize any `DataStore` directly to/from NIO
  `ByteBuffer`s for zero-copy network I/O and memory-mapped files.
* **`DataCursor`** — allocation-free multi-field access using ByteBuddy-generated
  field accessors (up to ~10× faster than VarHandle fallback).
* **`RowView`** — ergonomic whole-record read/write (one allocation per read; use for
  one-off access, not hot loops).

---

## Quickstart

### 1. Define component types

```java
import io.github.zzuegg.jbinary.annotation.*;

public record Terrain(
        @BitField(min = 0, max = 255)                         int height,
        @DecimalField(min = -50.0, max = 50.0, precision = 2) double temperature,
        @BoolField                                            boolean active
) {}

public record Water(
        @DecimalField(min = 0.0, max = 1.0, precision = 4) double salinity,
        @BoolField                                          boolean frozen
) {}
```

### 2. Create a DataStore

```java
import io.github.zzuegg.jbinary.DataStore;

// Single-component
DataStore<Terrain> terrainStore = DataStore.of(10_000, Terrain.class);

// Multi-component — both Terrain and Water share the same long[]
DataStore<?> worldStore = DataStore.of(10_000, Terrain.class, Water.class);

// Large voxel world (chunk-based, memory-efficient)
ChunkedDataStore<Terrain> world = DataStore.chunked(1024, 256, 1024, Terrain.class);
```

### 3. Create pre-computed accessors (once; store as static fields)

```java
import io.github.zzuegg.jbinary.Accessors;
import io.github.zzuegg.jbinary.accessor.*;

IntAccessor    height = Accessors.intFieldInStore(terrainStore, Terrain.class, "height");
DoubleAccessor temp   = Accessors.doubleFieldInStore(terrainStore, Terrain.class, "temperature");
BoolAccessor   active = Accessors.boolFieldInStore(terrainStore, Terrain.class, "active");
```

### 4. Read and write

```java
int index = 42;

// Write
height.set(terrainStore, index, 200);
temp.set(terrainStore, index, -12.5);
active.set(terrainStore, index, true);

// Read
int    h = height.get(terrainStore, index);   // → 200
double t = temp.get(terrainStore, index);     // → −12.50
boolean a = active.get(terrainStore, index);  // → true
```

### 5. RowView — ergonomic whole-record access

```java
import io.github.zzuegg.jbinary.RowView;

RowView<Terrain> view = RowView.of(terrainStore, Terrain.class);

view.set(terrainStore, 42, new Terrain(200, -12.5, true));
Terrain t = view.get(terrainStore, 42);
// → Terrain[height=200, temperature=-12.5, active=true]
```

### 6. DataCursor — allocation-free multi-field access

```java
import io.github.zzuegg.jbinary.DataCursor;
import io.github.zzuegg.jbinary.annotation.StoreField;

class NeededData {
    @StoreField(component = Terrain.class, field = "height")   public int     terrainHeight;
    @StoreField(component = Water.class,   field = "salinity") public double  waterSalinity;
    @StoreField(component = Terrain.class, field = "active")   public boolean active;
}

DataCursor<NeededData> cursor = DataCursor.of(worldStore, NeededData.class);

// Zero-allocation hot loop
for (int row = 0; row < N; row++) {
    NeededData d = cursor.update(worldStore, row);  // load in-place, no allocation
    if (d.active) {
        d.terrainHeight += 1;
        d.waterSalinity  = Math.min(d.waterSalinity + 0.001, 1.0);
        cursor.flush(worldStore, row);              // write back
    }
}
```

### 7. ChunkedDataStore — large voxel worlds

```java
// 1 024 × 256 × 1 024 voxel world; only written chunks use memory
ChunkedDataStore<Terrain> world = DataStore.chunked(1024, 256, 1024, Terrain.class);
IntAccessor material = Accessors.intFieldInStore(world, Terrain.class, "height");

// row(x, y, z) converts 3-D coordinates to a Morton-encoded row index
material.set(world, world.row(100, 64, 200), 200);
int h = material.get(world, world.row(100, 64, 200));   // → 200

// Unwritten regions return 0 (field minimum) with zero memory allocation
int air = material.get(world, world.row(500, 200, 500)); // → 0
System.out.println(world.allocatedChunkCount());         // chunks written so far
```

### 8. Enums

```java
public enum Biome { PLAINS, FOREST, DESERT, OCEAN }

public record BiomeData(
        @EnumField Biome biome,
        @BitField(min = 0, max = 100) int fertility
) {}

DataStore<BiomeData> biomeStore = DataStore.of(1000, BiomeData.class);
EnumAccessor<Biome> biomeAcc =
        Accessors.enumFieldInStore(biomeStore, BiomeData.class, "biome");

biomeAcc.set(biomeStore, 0, Biome.FOREST);
Biome b = biomeAcc.get(biomeStore, 0); // → FOREST
```

### 9. Batch writes (octree stores)

```java
store.beginBatch();
for (int x = 0; x < 64; x++)
    for (int y = 0; y < 64; y++)
        for (int z = 0; z < 64; z++)
            material.set(store, store.row(x, y, z), 0);
store.endBatch();  // collapse runs once here — ~2× faster for uniform fills
```

### 10. Collections

```java
import io.github.zzuegg.jbinary.collections.*;

PackedList<Point>   list = PackedList.create(10_000, Point.class);
PackedIntMap<Point> map  = PackedIntMap.create(10_000, Point.class);

list.add(new Point(10, 20));
map.put(42, new Point(100, 200));
```

See [docs/COLLECTIONS.md](docs/COLLECTIONS.md) for full API details.

### 11. ByteBuffer I/O

```java
ByteBuffer buf = ByteBuffer.allocateDirect(64 * 1024);
store.write(buf);   // serialize — position advances
buf.flip();
store.read(buf);    // deserialize — works with all store types
```

---

## Supported Field Types

| Annotation          | Java type        | Storage                                        |
|---------------------|------------------|------------------------------------------------|
| `@BitField(min,max)`| `int` / `long`   | ⌈log₂(max−min+1)⌉ bits, offset from min       |
| `@DecimalField`     | `double`/`float` | fixed-point scaled to long, same bit calc      |
| `@BoolField`        | `boolean`        | 1 bit                                          |
| `@EnumField`        | any `enum`       | ⌈log₂(N)⌉ bits by ordinal (or explicit codes) |

---

## DataStore variants at a glance

| Store | Single read | Bulk read / 1k | Memory model | Voxel world? |
|-------|------------:|---------------:|-------------|:---:|
| `RawDataStore` | 1.61 ns | 1 990 ns | Dense, 1 slot/field | ✗ |
| `PackedDataStore` | 2.87 ns | 2 914 ns | Dense, bit-packed | ✗ |
| **`ChunkedDataStore`** | **4.43 ns** | **6 210 ns** | **Chunk-sparse, bit-packed** | **✓** |
| `SparseDataStore` | 4.21 ns | 15 868 ns | Row-sparse HashMap | ⚠ slow |
| `FastOctreeDataStore` | 7.53 ns | 18 531 ns | Collapsed octree | ⚠ slow |
| `OctreeDataStore` | 8.49 ns | 24 002 ns | Collapsed octree | ⚠ very slow |

See [docs/STORES.md](docs/STORES.md) for full details, examples, and selection guide.

---

## Benchmark summary

All values ns/op (lower = faster).  JMH, 1 000 ops/benchmark, JDK 25.

### Bulk read — 1 000 rows, IntAccessor

```
Baseline (arrays)    ▓                                                          438
RawDataStore         ▓▓▓▓                                                     1,990
PackedDataStore      ▓▓▓▓▓▓                                                   2,914
ChunkedDataStore     ▓▓▓▓▓▓▓▓▓▓▓▓▓                                            6,210
SparseDataStore      ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓                     15,868
FastOctreeDataStore  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓                 18,531
OctreeDataStore      ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓     24,002
```

### Single-element read (ns/op)

```
Baseline (array)     ▓                                                          0.87
RawDataStore         ▓▓                                                         1.61
PackedDataStore      ▓▓▓                                                        2.87
SparseDataStore      ▓▓▓▓▓                                                      4.21
ChunkedDataStore     ▓▓▓▓▓                                                      4.43
FastOctreeDataStore  ▓▓▓▓▓▓▓▓▓                                                  7.53
OctreeDataStore      ▓▓▓▓▓▓▓▓▓▓                                                 8.49
```

### Bulk write — 1 000 rows, IntAccessor

```
Baseline (arrays)    ▓                                                            259
RawDataStore         ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓                                        4,278
PackedDataStore      ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  14,003
ChunkedDataStore     ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓   9,799
SparseDataStore      (58,350 — off-chart)
FastOctreeDataStore  (655,631 — off-chart)
OctreeDataStore      (541,466 — off-chart)
```

> ChunkedDataStore writes are fast because each chunk is a dense `PackedDataStore` —
> no per-row HashMap allocation, no octree tree manipulation.

Full results → [BENCHMARKS.md](BENCHMARKS.md)

---

## Memory savings

### Packed field encoding

| Field                               | Java type | Naive JVM | BitKit storage |
|-------------------------------------|-----------|-----------|----------------|
| `@BitField(min=0, max=255)`         | `int`     | 32 bits   | **8 bits**     |
| `@DecimalField(min=-50, max=50, precision=2)` | `double` | 64 bits | **14 bits** |
| `@BoolField`                        | `boolean` | 8–32 bits | **1 bit**     |
| `@EnumField` (4-constant enum)      | `enum`    | 32 bits   | **2 bits**    |

**`Terrain` example** (height + temperature + active):

| Layout | Bits/row | 10 000-row memory |
|--------|----------|-------------------|
| Naive JVM (`int` + `double` + `boolean`) | ≥ 128 bits | ≥ 160 KB |
| BitKit `PackedDataStore` | **23 bits** → 1 `long`/row | **80 KB** (~50% of naive) |

### ChunkedDataStore for a voxel world

For a 1 024 × 256 × 1 024 world (~268 million voxels, 23-bit `Terrain` record):

| Scenario | Memory |
|----------|--------|
| All 268 M voxels allocated (`PackedDataStore`) | ~268 MB |
| Only surface chunks allocated (1 % of world) | **~2.7 MB** with `ChunkedDataStore` |
| Completely unwritten region | **0 bytes** (hash-map entry only after first write) |

---

## Installation

BitKit is published to [GitHub Packages](https://github.com/zzuegg/BitKit/packages).

### Gradle (Kotlin DSL)

Add the GitHub Packages repository and the dependency to your `build.gradle.kts`:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/zzuegg/BitKit")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("io.github.zzuegg:jbinary:0.1.0")
}
```

GitHub Packages requires authentication even for public packages.
You need a [personal access token](https://github.com/settings/tokens) with the `read:packages` scope.
Store it in `~/.gradle/gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_PERSONAL_ACCESS_TOKEN
```

### Gradle (Groovy DSL)

```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/zzuegg/BitKit")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation 'io.github.zzuegg:jbinary:0.1.0'
}
```

---

## Building and testing

```bash
./gradlew build         # compiles + tests
./gradlew test          # unit tests only
./gradlew jmhRun        # JMH benchmarks (full: 3 warmup / 5 measurement / 1 fork)
./gradlew jmhCi         # JMH benchmarks (fast: 1 warmup / 1 measurement / 1 fork)
```
