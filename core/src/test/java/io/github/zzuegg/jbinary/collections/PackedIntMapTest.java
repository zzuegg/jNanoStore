package io.github.zzuegg.jbinary.collections;

import io.github.zzuegg.jbinary.annotation.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PackedIntMap}.
 */
class PackedIntMapTest {

    record Vertex(
            @BitField(min = 0, max = 1023) int x,
            @BitField(min = 0, max = 1023) int y
    ) {}

    record Data(
            @BitField(min = 0, max = 255) int id,
            @DecimalField(min = 0.0, max = 100.0, precision = 2) double score,
            @BoolField boolean active
    ) {}

    // ------------------------------------------------------------------ basic operations

    @Test
    void create_isEmptyInitially() {
        PackedIntMap<Vertex> map = PackedIntMap.create(100, Vertex.class);
        assertTrue(map.isEmpty());
        assertEquals(0, map.size());
        assertEquals(100, map.capacity());
    }

    @Test
    void put_andGet_primitiveKey() {
        PackedIntMap<Vertex> map = PackedIntMap.create(100, Vertex.class);
        map.put(42, new Vertex(10, 20));
        assertEquals(new Vertex(10, 20), map.get(42));
    }

    @Test
    void get_returnsNullForMissingKey() {
        PackedIntMap<Vertex> map = PackedIntMap.create(100, Vertex.class);
        assertNull(map.get(5));
        assertNull(map.get((Object) 5));
    }

    @Test
    void put_returnsOldValue() {
        PackedIntMap<Vertex> map = PackedIntMap.create(100, Vertex.class);
        assertNull(map.put(1, new Vertex(1, 2)));
        Vertex old = map.put(1, new Vertex(9, 8));
        assertEquals(new Vertex(1, 2), old);
        assertEquals(new Vertex(9, 8), map.get(1));
    }

    @Test
    void remove_primitiveKey() {
        PackedIntMap<Vertex> map = PackedIntMap.create(100, Vertex.class);
        map.put(7, new Vertex(3, 4));
        Vertex removed = map.remove(7);
        assertEquals(new Vertex(3, 4), removed);
        assertNull(map.get(7));
        assertEquals(0, map.size());
    }

    @Test
    void remove_nonExistentKey_returnsNull() {
        PackedIntMap<Vertex> map = PackedIntMap.create(100, Vertex.class);
        assertNull(map.remove(99));
    }

    @Test
    void containsKey_primitiveKey() {
        PackedIntMap<Vertex> map = PackedIntMap.create(100, Vertex.class);
        map.put(10, new Vertex(1, 2));
        assertTrue(map.containsKey(10));
        assertFalse(map.containsKey(11));
    }

    // ------------------------------------------------------------------ multiple entries

    @Test
    void multipleEntries_independent() {
        PackedIntMap<Vertex> map = PackedIntMap.create(1000, Vertex.class);
        for (int i = 0; i < 50; i++) map.put(i * 10, new Vertex(i, i * 2));

        assertEquals(50, map.size());
        for (int i = 0; i < 50; i++) {
            assertEquals(new Vertex(i, i * 2), map.get(i * 10));
        }
    }

    // ------------------------------------------------------------------ mixed record

    @Test
    void mixedRecord_roundTrip() {
        PackedIntMap<Data> map = PackedIntMap.create(100, Data.class);
        map.put(1, new Data(10, 55.5, true));
        map.put(2, new Data(20, 33.3, false));

        Data d1 = map.get(1);
        assertEquals(10, d1.id());
        assertEquals(55.5, d1.score(), 0.01);
        assertTrue(d1.active());

        Data d2 = map.get(2);
        assertEquals(20, d2.id());
        assertEquals(33.3, d2.score(), 0.01);
        assertFalse(d2.active());
    }

    // ------------------------------------------------------------------ Map contract (boxed)

    @Test
    void boxedGet_andPut() {
        PackedIntMap<Vertex> map = PackedIntMap.create(100, Vertex.class);
        map.put(Integer.valueOf(5), new Vertex(1, 1));
        assertEquals(new Vertex(1, 1), map.get(Integer.valueOf(5)));
    }

    @Test
    void boxedRemove() {
        PackedIntMap<Vertex> map = PackedIntMap.create(100, Vertex.class);
        map.put(3, new Vertex(2, 2));
        map.remove(Integer.valueOf(3));
        assertNull(map.get(3));
    }

    @Test
    void entrySet_containsAllEntries() {
        PackedIntMap<Vertex> map = PackedIntMap.create(100, Vertex.class);
        map.put(1, new Vertex(10, 20));
        map.put(2, new Vertex(30, 40));

        Set<Map.Entry<Integer, Vertex>> entries = map.entrySet();
        assertEquals(2, entries.size());
        Map<Integer, Vertex> copy = new HashMap<>();
        for (Map.Entry<Integer, Vertex> e : entries) copy.put(e.getKey(), e.getValue());
        assertEquals(new Vertex(10, 20), copy.get(1));
        assertEquals(new Vertex(30, 40), copy.get(2));
    }

    @Test
    void keySet_andValues() {
        PackedIntMap<Vertex> map = PackedIntMap.create(100, Vertex.class);
        map.put(5, new Vertex(1, 1));
        map.put(10, new Vertex(2, 2));
        assertTrue(map.keySet().contains(5));
        assertTrue(map.keySet().contains(10));
        assertEquals(2, map.values().size());
    }

    // ------------------------------------------------------------------ bounds

    @Test
    void put_throwsOnNegativeKey() {
        PackedIntMap<Vertex> map = PackedIntMap.create(100, Vertex.class);
        assertThrows(IndexOutOfBoundsException.class, () -> map.put(-1, new Vertex(1, 1)));
    }

    @Test
    void put_throwsOnKeyAtOrAboveCapacity() {
        PackedIntMap<Vertex> map = PackedIntMap.create(10, Vertex.class);
        assertThrows(IndexOutOfBoundsException.class, () -> map.put(10, new Vertex(1, 1)));
    }

    // ------------------------------------------------------------------ edge cases

    @Test
    void get_nonIntegerKeyReturnsNull() {
        PackedIntMap<Vertex> map = PackedIntMap.create(10, Vertex.class);
        assertNull(map.get("hello"));
        assertNull(map.get(null));
    }

    @Test
    void remove_nonIntegerKeyReturnsNull() {
        PackedIntMap<Vertex> map = PackedIntMap.create(10, Vertex.class);
        assertNull(map.remove("hello"));
    }

    @Test
    void containsKey_nonIntegerReturnsFalse() {
        PackedIntMap<Vertex> map = PackedIntMap.create(10, Vertex.class);
        assertFalse(map.containsKey("hello"));
    }
}
