# BitKit Collections

The `io.github.zzuegg.jbinary.collections` package provides standard Java collection
implementations backed by bit-packed storage.

---

## PackedList

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

### PackedList benchmark results (ns/op)

| Operation | ns/op |
|-----------|------:|
| Bulk read (1 000) | 129,011 |
| Bulk write (1 000) | 200,284 |
| Random read (1 000) | 129,011 |
| Random write (1 000) | 196,144 |

---

## PackedIntMap

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

### PackedIntMap benchmark results (ns/op)

| Operation | ns/op |
|-----------|------:|
| Bulk read (1 000) | 147,123 |
| Bulk write (1 000) | 276,556 |
| Random read (1 000) | 154,217 |
| Random write (1 000) | 281,963 |

---

## Memory comparison

For a `Point` record with two 10-bit fields (20 bits total → 1 `long` per row):

| Structure | Memory per 10 000 elements |
|-----------|---------------------------|
| `ArrayList<Point>` | ~400 KB (object headers + references) |
| `PackedList<Point>` | **80 KB** (single `long[]`) |
| `PackedIntMap<Point>` | **~80 KB** (sparse — only populated entries) |

---

## See also

- [STORES.md](STORES.md) — DataStore variants
- [BENCHMARKS.md](../BENCHMARKS.md) — Full benchmark results
