package io.github.zzuegg.jbinary.collections;

import io.github.zzuegg.jbinary.annotation.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PackedList}.
 */
class PackedListTest {

    record Point(
            @BitField(min = 0, max = 1023) int x,
            @BitField(min = 0, max = 1023) int y
    ) {}

    record Item(
            @BitField(min = 0, max = 255) int id,
            @DecimalField(min = 0.0, max = 100.0, precision = 2) double score,
            @BoolField boolean active
    ) {}

    // ------------------------------------------------------------------ basic operations

    @Test
    void create_isEmptyInitially() {
        PackedList<Point> list = PackedList.create(100, Point.class);
        assertTrue(list.isEmpty());
        assertEquals(0, list.size());
        assertEquals(100, list.capacity());
    }

    @Test
    void add_appendsElement() {
        PackedList<Point> list = PackedList.create(10, Point.class);
        list.add(new Point(1, 2));
        assertEquals(1, list.size());
        assertEquals(new Point(1, 2), list.get(0));
    }

    @Test
    void add_multipleElements() {
        PackedList<Point> list = PackedList.create(10, Point.class);
        list.add(new Point(10, 20));
        list.add(new Point(30, 40));
        list.add(new Point(50, 60));

        assertEquals(3, list.size());
        assertEquals(new Point(10, 20), list.get(0));
        assertEquals(new Point(30, 40), list.get(1));
        assertEquals(new Point(50, 60), list.get(2));
    }

    @Test
    void set_replaceElement() {
        PackedList<Point> list = PackedList.create(10, Point.class);
        list.add(new Point(1, 2));
        Point old = list.set(0, new Point(99, 88));
        assertEquals(new Point(1, 2), old);
        assertEquals(new Point(99, 88), list.get(0));
    }

    @Test
    void remove_shiftsElements() {
        PackedList<Point> list = PackedList.create(10, Point.class);
        list.add(new Point(1, 1));
        list.add(new Point(2, 2));
        list.add(new Point(3, 3));

        Point removed = list.remove(1);
        assertEquals(new Point(2, 2), removed);
        assertEquals(2, list.size());
        assertEquals(new Point(1, 1), list.get(0));
        assertEquals(new Point(3, 3), list.get(1));
    }

    @Test
    void addAtIndex_shiftsElements() {
        PackedList<Point> list = PackedList.create(10, Point.class);
        list.add(new Point(1, 1));
        list.add(new Point(3, 3));
        list.add(1, new Point(2, 2));  // insert at index 1

        assertEquals(3, list.size());
        assertEquals(new Point(1, 1), list.get(0));
        assertEquals(new Point(2, 2), list.get(1));
        assertEquals(new Point(3, 3), list.get(2));
    }

    // ------------------------------------------------------------------ capacity

    @Test
    void add_throwsWhenFull() {
        PackedList<Point> list = PackedList.create(2, Point.class);
        list.add(new Point(1, 1));
        list.add(new Point(2, 2));
        assertThrows(IllegalStateException.class, () -> list.add(new Point(3, 3)));
    }

    // ------------------------------------------------------------------ bounds

    @Test
    void get_throwsOutOfBounds() {
        PackedList<Point> list = PackedList.create(10, Point.class);
        list.add(new Point(1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(1));
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
    }

    // ------------------------------------------------------------------ mixed-type record

    @Test
    void mixedRecord_roundTrip() {
        PackedList<Item> list = PackedList.create(20, Item.class);
        for (int i = 0; i < 10; i++) {
            list.add(new Item(i, i * 7.5, i % 2 == 0));
        }
        assertEquals(10, list.size());
        for (int i = 0; i < 10; i++) {
            Item item = list.get(i);
            assertEquals(i, item.id());
            assertEquals(i * 7.5, item.score(), 0.01);
            assertEquals(i % 2 == 0, item.active());
        }
    }

    // ------------------------------------------------------------------ List contract

    @Test
    void iteration_viaIterator() {
        PackedList<Point> list = PackedList.create(5, Point.class);
        list.add(new Point(1, 1));
        list.add(new Point(2, 2));
        list.add(new Point(3, 3));

        List<Point> copy = new ArrayList<>(list);
        assertEquals(3, copy.size());
        assertEquals(new Point(1, 1), copy.get(0));
        assertEquals(new Point(3, 3), copy.get(2));
    }

    @Test
    void contains_findsElement() {
        PackedList<Point> list = PackedList.create(10, Point.class);
        list.add(new Point(5, 6));
        assertTrue(list.contains(new Point(5, 6)));
        assertFalse(list.contains(new Point(7, 8)));
    }

    @Test
    void indexOf_returnsCorrectIndex() {
        PackedList<Point> list = PackedList.create(10, Point.class);
        list.add(new Point(1, 1));
        list.add(new Point(2, 2));
        list.add(new Point(1, 1));
        assertEquals(0, list.indexOf(new Point(1, 1)));
        assertEquals(1, list.indexOf(new Point(2, 2)));
        assertEquals(-1, list.indexOf(new Point(9, 9)));
    }

    @Test
    void clear_emptiesTheList() {
        PackedList<Point> list = PackedList.create(10, Point.class);
        list.add(new Point(1, 1));
        list.add(new Point(2, 2));
        list.clear();
        assertEquals(0, list.size());
        assertTrue(list.isEmpty());
    }

    @Test
    void subList_returnsCorrectRange() {
        PackedList<Point> list = PackedList.create(10, Point.class);
        for (int i = 0; i < 5; i++) list.add(new Point(i, i));
        List<Point> sub = list.subList(1, 4);
        assertEquals(3, sub.size());
        assertEquals(new Point(1, 1), sub.get(0));
        assertEquals(new Point(3, 3), sub.get(2));
    }
}
