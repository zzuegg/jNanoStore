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

## Memory savings

For a `Terrain` row with 3 fields:
- `@BitField(0, 255)` → 8 bits
- `@DecimalField(-50, 50, 2)` → 14 bits (range 10 000, needs 14 bits)
- `@BoolField` → 1 bit

Total: **23 bits per row** vs. ≥ 128 bits for `int + double + boolean` (aligned JVM objects).

## Benchmarks

See [BENCHMARKS.md](BENCHMARKS.md) for full results and reproduction instructions.

```bash
./gradlew jmhRun
```

## Building and testing

```bash
./gradlew build         # compiles + tests
./gradlew test          # unit tests only
./gradlew jmhRun        # JMH benchmarks
```
