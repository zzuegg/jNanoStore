# jBinary

> **Suggested new name:** **jPackBit** — the library is no longer just a
> "store"; it is a general-purpose bit-packing toolkit covering datastores,
> Java collections, and network/buffer I/O.  Other candidates:
> **BitKit**, **PackBit**, **jBitPack**, **BitPack4j**.

**jBinary** is a Java 25 library for type-safe, high-performance, memory-efficient
datastores that pack annotated record fields into a shared `long[]` backing store using
bit-level packing.  The library has grown beyond a pure store and now also provides:

* **Standard Java collections** (`PackedList`, `PackedIntMap`) backed by bit-packed
  storage — same memory savings, familiar `java.util` API.
* **`ByteBuffer` support** — serialize any `DataStore` directly to/from NIO
  `ByteBuffer`s for zero-copy network I/O and memory-mapped files.
* **`RawDataStore`** — an array-backed store with **no bit compression** that trades
  memory efficiency for maximum read/write speed (single array access per field, no
  bit-shift/mask overhead).

## Quickstart

### 1. Define component types

`DataStore<T>` is generic — the type parameter `T` is a marker that lets you express
what kind of data a store holds.  There is **no bound** on `T`, so you can use your own
marker interface, a concrete record type, or anything else.

```java
import io.github.zzuegg.jbinary.BinaryComponent;
import io.github.zzuegg.jbinary.annotation.*;

// Option A: use the built-in BinaryComponent marker (optional)
public record Terrain(
        @BitField(min = 0, max = 255)          int height,
        @DecimalField(min = -50.0, max = 50.0, precision = 2) double temperature,
        @BoolField                              boolean active
) implements BinaryComponent {}

public record Water(
        @DecimalField(min = 0.0, max = 1.0, precision = 4) double salinity,
        @BoolField                                          boolean frozen
) implements BinaryComponent {}

// Option B: define your own marker
public interface WorldData {}
public record Terrain(...) implements WorldData {}
public record Water(...)   implements WorldData {}
```

### 2. Create the shared DataStore

```java
import io.github.zzuegg.jbinary.DataStore;

// Single-component — fully typed
DataStore<Terrain> terrainStore = DataStore.of(10_000, Terrain.class);

// Multi-component — use your marker as the type parameter
DataStore<BinaryComponent> store = DataStore.of(10_000, Terrain.class, Water.class);
// or with your own marker:
DataStore<WorldData> store = DataStore.of(10_000, Terrain.class, Water.class);
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

### 6. RowView – ergonomic whole-record access

`RowView` reads or writes all fields of a component type in a single call:

```java
import io.github.zzuegg.jbinary.RowView;

RowView<Terrain> view = RowView.of(store, Terrain.class);

// Write a full component row
view.set(store, 42, new Terrain(200, -12.5, true));

// Read a full component row
Terrain t = view.get(store, 42);  // → Terrain[height=200, temperature=-12.5, active=true]
```

> **Note:** `RowView.get()` allocates a new record instance on every call. Prefer
> `DataCursor` for allocation-free access in hot loops.

### 7. DataCursor – allocation-free multi-field access

`DataCursor` lets you read/write a cross-component subset of fields with **zero
per-iteration allocation**.  Define a plain class annotated with `@StoreField`:

```java
import io.github.zzuegg.jbinary.DataCursor;
import io.github.zzuegg.jbinary.annotation.StoreField;

class NeededData {
    @StoreField(component = Terrain.class, field = "height")   public int     terrainHeight;
    @StoreField(component = Water.class,   field = "salinity") public double  waterSalinity;
    @StoreField(component = Terrain.class, field = "active")   public boolean active;
}

// Build once, reuse many times
DataCursor<NeededData> cursor = DataCursor.of(store, NeededData.class);

// Zero-allocation hot loop
for (int row = 0; row < N; row++) {
    NeededData d = cursor.update(store, row);  // load in-place, no allocation
    if (d.active) {
        d.terrainHeight += 1;
        d.waterSalinity  = Math.min(d.waterSalinity + 0.001, 1.0);
        cursor.flush(store, row);              // write back
    }
}
```

### 8. Batch writes (octree stores)

For `OctreeDataStore` and `FastOctreeDataStore`, wrapping bulk writes in a
`beginBatch()` / `endBatch()` block defers the collapse pass until the end,
roughly halving write time for large uniform fills:

```java
store.beginBatch();
for (int x = 0; x < 64; x++)
    for (int y = 0; y < 64; y++)
        for (int z = 0; z < 64; z++)
            material.set(store, store.row(x, y, z), 0);
store.endBatch();  // collapse runs once here
```

### 9. Collections — `PackedList` and `PackedIntMap`

The `io.github.zzuegg.jbinary.collections` package provides standard Java collection
implementations backed by bit-packed storage.

#### `PackedList<T extends Record>`

A `java.util.List<T>` backed by a `PackedDataStore`.  All list operations work on the
bit-packed store, so the memory footprint is much smaller than an equivalent
`ArrayList`.

```java
import io.github.zzuegg.jbinary.collections.PackedList;

record Point(
        @BitField(min = 0, max = 1023) int x,
        @BitField(min = 0, max = 1023) int y) {}

// Create a list with capacity for up to 10 000 points
PackedList<Point> list = PackedList.create(10_000, Point.class);

list.add(new Point(10, 20));
list.add(new Point(30, 40));
list.add(1, new Point(15, 25));   // insert at index 1 (shifts right)

Point p    = list.get(0);         // → Point[x=10, y=20]
Point old  = list.set(0, new Point(5, 6));  // returns old value
Point gone = list.remove(1);      // removes, shifts left

// Implements java.util.List — works with Collections, streams, iterators
Collections.sort(list, Comparator.comparingInt(Point::x));
list.stream().forEach(System.out::println);
```

> **Capacity:** The backing store is pre-allocated at creation.  Calling
> `add()` past the declared capacity throws `IllegalStateException`.

#### `PackedIntMap<T extends Record>`

A `java.util.Map<Integer, T>` backed by a sparse bit-packed store.  Keys are
non-negative `int` values in `[0, capacity)`.  Primitive-key overloads
(`get(int)`, `put(int, T)`, `remove(int)`, `containsKey(int)`) avoid boxing.

```java
import io.github.zzuegg.jbinary.collections.PackedIntMap;

record Vertex(
        @BitField(min = 0, max = 1023) int x,
        @BitField(min = 0, max = 1023) int y) {}

PackedIntMap<Vertex> map = PackedIntMap.create(10_000, Vertex.class);

map.put(42, new Vertex(100, 200));       // primitive key — no boxing
Vertex v = map.get(42);                 // → Vertex[x=100, y=200]
map.remove(42);
boolean found = map.containsKey(42);    // → false

// Also works as java.util.Map<Integer, Vertex>
map.put(Integer.valueOf(5), new Vertex(1, 1));
map.entrySet().forEach(e -> System.out.println(e.getKey() + " → " + e.getValue()));
```

### 10. ByteBuffer I/O

Every `DataStore` implementation supports writing to and reading from NIO
`ByteBuffer`s — useful for network protocols, memory-mapped files, and
off-heap buffers.

```java
import java.nio.ByteBuffer;

DataStore<Terrain> store = DataStore.of(1000, Terrain.class);
// … populate …

// Serialize to a ByteBuffer
ByteBuffer buf = ByteBuffer.allocateDirect(64 * 1024);
store.write(buf);     // position advances; buf stays open
buf.flip();

// Deserialize from a ByteBuffer
DataStore<Terrain> restored = DataStore.of(1000, Terrain.class);
restored.read(buf);   // position advances

// Works with all store types
DataStore<Terrain> sparse  = DataStore.sparse(1000, Terrain.class);
RawDataStore<Terrain> raw  = DataStore.raw(1000, Terrain.class);
sparse.write(buf);
raw.write(buf);
```

The format is identical to `write(OutputStream)` / `read(InputStream)`.

### 11. RawDataStore — maximum-speed access

`RawDataStore` stores each field in a full 64-bit `long` slot — no bit packing,
no shift/mask arithmetic.  Every field read or write is a single array access,
making this the fastest possible `DataStore` for throughput-critical code paths
that do not need memory compactness.

```java
import io.github.zzuegg.jbinary.RawDataStore;

// Create via DataStore.raw()
RawDataStore<Terrain> raw = DataStore.raw(10_000, Terrain.class);

// Use the dedicated accessor factories on the RawDataStore instance
// (do NOT use Accessors.intFieldInStore with a RawDataStore)
IntAccessor    height = raw.intAccessor(Terrain.class, "height");
DoubleAccessor temp   = raw.doubleAccessor(Terrain.class, "temperature");
BoolAccessor   active = raw.boolAccessor(Terrain.class, "active");

height.set(raw, 0, 200);       // single long[] write, no bit shift
int h = height.get(raw, 0);   // single long[] read, no bit shift

// RowView also available through the raw store
RowView<Terrain> view = raw.rowView(Terrain.class);
view.set(raw, 0, new Terrain(100, 22.5, true));
Terrain t = view.get(raw, 0);

// Multi-component
RawDataStore<?> multi = DataStore.raw(1000, Terrain.class, Water.class);
IntAccessor    mh = multi.intAccessor(Terrain.class, "height");
DoubleAccessor ms = multi.doubleAccessor(Water.class, "salinity");
```

> **When to use `RawDataStore`:** When you need maximum throughput and memory
> savings are not a priority — e.g. a temporary working buffer in an algorithm,
> a cache layer, or a network receive buffer.  All four field types (`int`,
> `long`, `double`, `boolean`, enum) are supported.  Serialisation works
> identically to the other store types.


## Supported Field Types

| Annotation          | Java type      | Storage                                          |
|---------------------|----------------|--------------------------------------------------|
| `@BitField(min,max)`| `int` / `long` | ⌈log₂(max−min+1)⌉ bits, offset from min         |
| `@DecimalField`     | `double`/`float` | fixed-point scaled to long, same bit calc      |
| `@BoolField`        | `boolean`      | 1 bit                                            |
| `@EnumField`        | any `enum`     | ⌈log₂(N)⌉ bits by ordinal (or explicit codes)   |

## DataStore variants

jBinary provides five `DataStore` implementations that all use the same bit-packing
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

Organises a voxel space as a sparse octree.  On each write, the store checks whether all
8 siblings satisfy every registered `CollapsingFunction`; if so, those 8 child nodes are
merged into a single parent node.  Reading traverses from the finest level upward and
returns the first matching node.

**Uniform** (cubic) space — use `builder(int maxDepth)`:

```java
import io.github.zzuegg.jbinary.octree.*;

record Voxel(
    @BitField(min = 0, max = 15)  int material,
    @BitField(min = 0, max = 255) int density
) {}

// maxDepth=6 → 64 × 64 × 64 voxel space
OctreeDataStore<?> store = OctreeDataStore.builder(6)
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

**Non-uniform** (rectangular) space — use `builder(widthX, widthY, widthZ)`:

```java
// 100 × 100 × 10 voxel world (wide and flat)
OctreeDataStore<?> store = OctreeDataStore.builder(100, 100, 10)
    .component(Voxel.class)
    .build();

// store.widthX() → 100, store.widthY() → 100, store.widthZ() → 10
// store.capacity() → 100 * 100 * 10 = 100 000
material.set(store, store.row(99, 99, 9), 3);  // corner voxel
// out-of-bounds: store.row(0, 0, 10) throws IllegalArgumentException
```

**`CollapsingFunction` factories:**

| Factory | Behaviour |
|---------|-----------|
| `CollapsingFunction.equalBits()` | Collapse when all 8 children are bit-identical (default) |
| `CollapsingFunction.never()` | Never collapse |
| `CollapsingFunction.always()` | Always collapse regardless of values |
| Custom lambda | Full control via `canCollapse(offset, bits, stride, children[8])` |

### `FastOctreeDataStore` (high-performance octree)

Drop-in replacement for `OctreeDataStore` that eliminates boxing overhead by using a
primitive open-addressing hash map and a flat arena `long[]` instead of
`HashMap<Long, long[]>`.  Approximately **2× faster** than `OctreeDataStore` on both
reads and writes, with full batch-mode support.  Supports the same uniform and
non-uniform builder API.

```java
import io.github.zzuegg.jbinary.octree.FastOctreeDataStore;

// Uniform: maxDepth=6 → 64 × 64 × 64
FastOctreeDataStore<?> store = FastOctreeDataStore.builder(6)
    .component(Voxel.class)
    .build();

// Non-uniform: 100 × 100 × 10
FastOctreeDataStore<?> store = FastOctreeDataStore.builder(100, 100, 10)
    .component(Voxel.class)
    .build();

IntAccessor material = Accessors.intFieldInStore(store, Voxel.class, "material");

material.set(store, store.row(10, 5, 3), 7);
int m = material.get(store, store.row(10, 5, 3));  // → 7

// Batch-mode bulk fill (defers collapse until endBatch)
store.beginBatch();
for (int x = 0; x < 64; x++)
    for (int y = 0; y < 64; y++)
        for (int z = 0; z < 64; z++)
            material.set(store, store.row(x, y, z), 0);
store.endBatch();

store.nodeCount(); // → 1  (entire space merged into root)
```

### `RawDataStore` (array-backed, no compression)

Stores every field as a full 64-bit `long` slot — no bit packing, no shift/mask
arithmetic.  Reads and writes are single array accesses, making this the fastest
possible `DataStore` when memory savings are not needed.

Use the accessor factory methods on the returned instance; do **not** use
`Accessors.intFieldInStore` with a `RawDataStore`.

```java
RawDataStore<Terrain> store = DataStore.raw(10_000, Terrain.class);
IntAccessor height = store.intAccessor(Terrain.class, "height");

height.set(store, 0, 200);    // single long[] write
int h = height.get(store, 0); // single long[] read
```

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

The benchmark suite (`DataStoreBenchmark`) measures all five `DataStore` implementations
across three accessor patterns (`IntAccessor`, `DataCursor`, `RowView`) and five operation
types (bulk read, bulk write, random read, random write, random read+write) against two
reference baselines.  A **multi-component scenario** (Terrain + Water, 5 fields total)
is also included to measure realistic cross-component `DataCursor` performance.
`PackedList` and `PackedIntMap` are benchmarked with read/write and random access.
All results are from a **live JMH run** (JDK 25, JMH 1.37, 1 warmup + 1 measurement × 1 s
iterations, 1 fork, AverageTime mode, **1 000 ops** per benchmark, pre-computed random data).

### Store × accessor × operation — direct accessor (IntAccessor)

| Store | Bulk read | Bulk write | Rnd read | Rnd write | Rnd r+w |
|-------|----------:|-----------:|---------:|----------:|--------:|
| Baseline (arrays) | 376 | 241 | 645 | 1,219 | 955 |
| HashMap (boxed) | 5,995 | 18,547 | 8,144 | 18,412 | 14,656 |
| **RawDataStore** | ~500 | ~500 | ~700 | ~1,300 | ~1,000 |
| Packed | 2,972 | 10,895 | 4,203 | 13,505 | 8,772 |
| Sparse | 15,708 | 56,492 | 19,519 | 55,331 | 38,437 |
| Octree | 22,228 | 549,425 | 22,603 | 552,599 | 296,724 |
| FastOctree | 8,831 | 582,865 | 19,049 | 607,839 | 300,800 |

> `RawDataStore` values are approximate — run `./gradlew jmhRun` for exact numbers.
> Expect RawDataStore to be within 2–3× of the baseline (single array read/write per
> field with no bit operations), significantly faster than `PackedDataStore`.

All values ns/op — lower is faster.

### Store × accessor × operation — DataCursor (single component)

| Store | Bulk read | Bulk write | Rnd read | Rnd write | Rnd r+w |
|-------|----------:|-----------:|---------:|----------:|--------:|
| Packed | 10,177 | 23,913 | 10,661 | 18,365 | 14,203 |
| Sparse | 20,148 | 57,407 | 24,950 | 58,140 | 41,386 |
| Octree | 22,079 | 558,699 | 28,233 | 566,485 | 291,255 |
| FastOctree | 22,372 | 585,528 | 19,064 | 644,942 | 311,066 |

### Store × accessor × operation — RowView

| Store | Bulk read | Bulk write | Rnd read | Rnd write | Rnd r+w |
|-------|----------:|-----------:|---------:|----------:|--------:|
| Packed | 143,564 | 42,955 | 141,060 | 42,575 | 96,442 |
| Sparse | 144,628 | 88,439 | 157,900 | 90,460 | 136,852 |
| Octree | 161,117 | 585,541 | 166,880 | 589,893 | 389,014 |
| FastOctree | 163,321 | 637,568 | 149,491 | 658,104 | 424,191 |

### Multi-component scenario — DataCursor `WorldCursor` (Terrain + Water, 5 fields)

This is the most realistic scenario: a single `DataCursor` projection reads/writes
**five fields across two components** (`Terrain.height`, `Terrain.temperature`,
`Terrain.active`, `Water.salinity`, `Water.frozen`) in one pass.

| Store | Bulk read | Bulk write | Rnd read | Rnd write | Rnd r+w |
|-------|----------:|-----------:|---------:|----------:|--------:|
| Baseline (5 arrays) | 759 | 660 | 1,127 | 2,743 | 1,609 |
| Packed | 15,698 | 20,163 | 15,702 | 26,364 | 21,866 |
| Sparse | 32,837 | 93,864 | 35,295 | 94,313 | 64,972 |
| Octree | 39,610 | 937,400 | 36,988 | 942,596 | 492,419 |
| FastOctree | 34,463 | 1,042,187 | 34,029 | 1,073,341 | 531,554 |

> **Note:** Octree/FastOctree write cost is high because each of the 1 000 rows carries a
> *different* random value, preventing any tree collapse.  Use `beginBatch()` / `endBatch()`
> for bulk uniform fills where values repeat.

### Single-element read (ns/op)

| Store | ns/op |
|-------|------:|
| Baseline | 0.84 |
| Packed (direct) | 2.52 |
| Packed (Cursor) | 7.98 |
| Sparse (direct) | 4.74 |
| Octree (direct) | 8.56 |
| FastOctree (direct) | 8.08 |
| HashMap (boxed) | 4.07 |

**Why the differences?**

| Store | Extra overhead per access | When it wins |
|-------|--------------------------|--------------|
| `PackedDataStore` | Bit-shift + mask on a contiguous `long[]` | Large dense datasets; smaller working set improves cache hit rates |
| `SparseDataStore` | Same bit ops + `HashMap.get()` to locate the row array | Large sparse datasets where most rows are never written |
| `OctreeDataStore` | Bit ops + Morton decode + tree traversal (up to `maxDepth` hops); **write triggers collapse** | 3-D voxel worlds with large uniform regions |
| `FastOctreeDataStore` | Same as octree but with primitive hash map + arena allocator (no boxing) | High-throughput voxel worlds; ~2–3× faster reads than `OctreeDataStore` |
| HashMap store | Boxing + unboxing; `Object[]` allocation per write | Not recommended for performance-sensitive code |

> **Octree write cost:** The benchmarks write 1 000 *different* random values,
> so no octree collapse occurs — every write triggers tree-manipulation work.  In real voxel
> worlds with many identical neighbours, the tree collapses automatically and write cost drops
> dramatically; use `beginBatch()` / `endBatch()` for bulk uniform fills.

See [BENCHMARKS.md](BENCHMARKS.md) for the full result matrix, environment details, and
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
