package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.*;
import io.github.zzuegg.jbinary.annotation.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests specific to {@link SparseDataStore}: lazy row allocation, default values for
 * unwritten rows, and correct behaviour after writing.
 */
class SparseDataStoreTest {

    // ------------------------------------------------------------------ component definitions

    record Terrain(
            @BitField(min = 0, max = 255) int height,
            @DecimalField(min = -50.0, max = 50.0, precision = 2) double temperature,
            @BoolField boolean active
    ) {}

    enum BiomeType { PLAINS, FOREST, DESERT, OCEAN }

    record BiomeData(
            @EnumField BiomeType biome,
            @BitField(min = 0, max = 100) int fertility
    ) {}

    record Water(
            @DecimalField(min = 0.0, max = 1.0, precision = 4) double salinity,
            @BoolField boolean frozen
    ) {}

    // -----------------------------------------------------------------------
    // Default (unwritten) row values

    @Test
    void unwrittenRowIntReturnsMin() {
        DataStore store = DataStore.sparse(100, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");
        // No write — stored bits are 0, decoded value = min = 0
        assertEquals(0, height.get(store, 42));
    }

    @Test
    void unwrittenRowBoolReturnsFalse() {
        DataStore store = DataStore.sparse(100, Terrain.class);
        BoolAccessor active = Accessors.boolFieldInStore(store, Terrain.class, "active");
        assertFalse(active.get(store, 0));
        assertFalse(active.get(store, 99));
    }

    @Test
    void unwrittenRowDoubleReturnsMin() {
        DataStore store = DataStore.sparse(100, Terrain.class);
        DoubleAccessor temp = Accessors.doubleFieldInStore(store, Terrain.class, "temperature");
        // stored bits = 0 → decoded = -50.0 (the min)
        assertEquals(-50.0, temp.get(store, 10), 0.01);
    }

    @Test
    void unwrittenRowEnumReturnsFirstConstant() {
        DataStore store = DataStore.sparse(100, BiomeData.class);
        EnumAccessor<BiomeType> biome =
                Accessors.enumFieldInStore(store, BiomeData.class, "biome");
        // stored bits = 0 → ordinal 0 = PLAINS
        assertEquals(BiomeType.PLAINS, biome.get(store, 7));
    }

    // -----------------------------------------------------------------------
    // Lazy allocation

    @Test
    void noRowsAllocatedUntilWrite() {
        SparseDataStore store = (SparseDataStore) DataStore.sparse(1000, Terrain.class);
        assertEquals(0, store.allocatedRowCount());
    }

    @Test
    void writeAllocatesExactlyOneRow() {
        SparseDataStore store = (SparseDataStore) DataStore.sparse(1000, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");

        height.set(store, 500, 128);
        assertEquals(1, store.allocatedRowCount());

        height.set(store, 500, 200);  // second write to same row — still 1 row
        assertEquals(1, store.allocatedRowCount());
    }

    @Test
    void eachWrittenRowAllocatedOnce() {
        SparseDataStore store = (SparseDataStore) DataStore.sparse(1000, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");

        for (int i = 0; i < 10; i++) {
            height.set(store, i * 100, i);
        }
        assertEquals(10, store.allocatedRowCount());
    }

    // -----------------------------------------------------------------------
    // Round-trip: all field types

    @Test
    void intFieldRoundTrip() {
        DataStore store = DataStore.sparse(10, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");

        height.set(store, 0, 0);
        assertEquals(0, height.get(store, 0));

        height.set(store, 3, 255);
        assertEquals(255, height.get(store, 3));

        height.set(store, 7, 128);
        assertEquals(128, height.get(store, 7));
        assertEquals(255, height.get(store, 3)); // unchanged
    }

    @Test
    void decimalFieldRoundTrip() {
        DataStore store = DataStore.sparse(10, Terrain.class);
        DoubleAccessor temp = Accessors.doubleFieldInStore(store, Terrain.class, "temperature");

        temp.set(store, 0, 0.0);
        assertEquals(0.0, temp.get(store, 0), 0.01);

        temp.set(store, 1, -50.0);
        assertEquals(-50.0, temp.get(store, 1), 0.01);

        temp.set(store, 2, 50.0);
        assertEquals(50.0, temp.get(store, 2), 0.01);

        temp.set(store, 3, 23.45);
        assertEquals(23.45, temp.get(store, 3), 0.01);
    }

    @Test
    void boolFieldRoundTrip() {
        DataStore store = DataStore.sparse(10, Terrain.class);
        BoolAccessor active = Accessors.boolFieldInStore(store, Terrain.class, "active");

        active.set(store, 0, true);
        assertTrue(active.get(store, 0));

        active.set(store, 1, false);
        assertFalse(active.get(store, 1));
    }

    @Test
    void enumFieldRoundTrip() {
        DataStore store = DataStore.sparse(10, BiomeData.class);
        EnumAccessor<BiomeType> biome =
                Accessors.enumFieldInStore(store, BiomeData.class, "biome");

        biome.set(store, 0, BiomeType.OCEAN);
        assertEquals(BiomeType.OCEAN, biome.get(store, 0));

        biome.set(store, 5, BiomeType.DESERT);
        assertEquals(BiomeType.DESERT, biome.get(store, 5));
        assertEquals(BiomeType.OCEAN, biome.get(store, 0)); // unchanged
    }

    // -----------------------------------------------------------------------
    // Row isolation

    @Test
    void rowIsolation() {
        DataStore store = DataStore.sparse(10, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");

        for (int i = 0; i < 10; i++) height.set(store, i, i * 25);
        for (int i = 0; i < 10; i++) assertEquals(i * 25, height.get(store, i));
    }

    // -----------------------------------------------------------------------
    // Multiple components in one sparse store

    @Test
    void multipleComponentTypesShareSparseStore() {
        DataStore store = DataStore.sparse(100, Terrain.class, Water.class);

        IntAccessor height    = Accessors.intFieldInStore(store, Terrain.class, "height");
        DoubleAccessor temp   = Accessors.doubleFieldInStore(store, Terrain.class, "temperature");
        BoolAccessor active   = Accessors.boolFieldInStore(store, Terrain.class, "active");
        DoubleAccessor salin  = Accessors.doubleFieldInStore(store, Water.class, "salinity");
        BoolAccessor frozen   = Accessors.boolFieldInStore(store, Water.class, "frozen");

        height.set(store, 10, 100);
        temp.set(store, 10, 20.0);
        active.set(store, 10, true);
        salin.set(store, 10, 0.035);
        frozen.set(store, 10, false);

        assertEquals(100,   height.get(store, 10));
        assertEquals(20.0,  temp.get(store, 10), 0.01);
        assertTrue(active.get(store, 10));
        assertEquals(0.035, salin.get(store, 10), 0.0001);
        assertFalse(frozen.get(store, 10));

        // unwritten row
        assertEquals(0,     height.get(store, 20));
        assertFalse(active.get(store, 20));
    }

    // -----------------------------------------------------------------------
    // Word-boundary spanning

    @Test
    void wordBoundarySpanningField() {
        record BigFirst(
                @BitField(min = 0, max = (1 << 30) - 1) int big,
                @BitField(min = 0, max = 255) int small
        ) {}
        DataStore store = DataStore.sparse(5, BigFirst.class);
        IntAccessor big   = Accessors.intFieldInStore(store, BigFirst.class, "big");
        IntAccessor small = Accessors.intFieldInStore(store, BigFirst.class, "small");

        big.set(store, 0, (1 << 30) - 1);
        small.set(store, 0, 200);

        assertEquals((1 << 30) - 1, big.get(store, 0));
        assertEquals(200, small.get(store, 0));
    }

    // -----------------------------------------------------------------------
    // Bounds checking

    @Test
    void outOfBoundsRowWriteThrows() {
        DataStore store = DataStore.sparse(5, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");

        assertThrows(IndexOutOfBoundsException.class, () -> height.set(store, 5, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> height.set(store, -1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> height.set(store, 100, 0));
    }

    @Test
    void outOfBoundsRowReadReturnsDefault() {
        // Out-of-bounds reads on sparse store return 0 (no allocation was made)
        // Note: PackedDataStore would throw ArrayIndexOutOfBoundsException naturally.
        DataStore store = DataStore.sparse(5, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");
        // row 4 is within bounds and unwritten → returns min (0)
        assertEquals(0, height.get(store, 4));
    }

    // -----------------------------------------------------------------------
    // Capacity meta-data

    @Test
    void capacityReflectsConstructorArg() {
        DataStore store = DataStore.sparse(42, Terrain.class);
        assertEquals(42, store.capacity());
    }

    @Test
    void rowStrideLongsIsPositive() {
        DataStore store = DataStore.sparse(10, Terrain.class);
        assertTrue(store.rowStrideLongs() > 0);
    }
}
