package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.*;
import io.github.zzuegg.jbinary.annotation.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataStoreTest {

    // ------------------------------------------------------------------ records
    record Terrain(
            @BitField(min = 0, max = 255) int height,
            @DecimalField(min = -50.0, max = 50.0, precision = 2) double temperature,
            @BoolField boolean active
    ) {}

    enum BiomeType {
        PLAINS, FOREST, DESERT, OCEAN;
    }

    record BiomeData(
            @EnumField BiomeType biome,
            @BitField(min = 0, max = 100) int fertility
    ) {}

    enum Priority {
        LOW, MEDIUM, HIGH;
    }

    enum Priority2 {
        @EnumCode(1) LOW,
        @EnumCode(5) MEDIUM,
        @EnumCode(9) HIGH;
    }

    record TaskRecord(
            @EnumField(useExplicitCodes = true) Priority2 priority,
            @BitField(min = 0, max = 1000) int score
    ) {}

    record Water(
            @DecimalField(min = 0.0, max = 1.0, precision = 4) double salinity,
            @BoolField boolean frozen
    ) {}

    // ------------------------------------------------------------------ tests

    @Test
    void intFieldRoundTrip() {
        DataStore store = DataStore.of(10, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");

        height.set(store, 0, 0);
        assertEquals(0, height.get(store, 0));

        height.set(store, 0, 255);
        assertEquals(255, height.get(store, 0));

        height.set(store, 5, 128);
        assertEquals(128, height.get(store, 5));
        assertEquals(255, height.get(store, 0)); // other row unchanged
    }

    @Test
    void intFieldBoundary() {
        DataStore store = DataStore.of(5, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");
        height.set(store, 0, 0);
        height.set(store, 1, 255);
        assertEquals(0,   height.get(store, 0));
        assertEquals(255, height.get(store, 1));
    }

    @Test
    void decimalFieldRoundTrip() {
        DataStore store = DataStore.of(10, Terrain.class);
        DoubleAccessor temp = Accessors.doubleFieldInStore(store, Terrain.class, "temperature");

        temp.set(store, 0, 0.0);
        assertEquals(0.0, temp.get(store, 0), 0.01);

        temp.set(store, 1, -50.0);
        assertEquals(-50.0, temp.get(store, 1), 0.01);

        temp.set(store, 2, 50.0);
        assertEquals(50.0, temp.get(store, 2), 0.01);

        temp.set(store, 3, 23.45);
        assertEquals(23.45, temp.get(store, 3), 0.01);

        temp.set(store, 4, -12.34);
        assertEquals(-12.34, temp.get(store, 4), 0.01);
    }

    @Test
    void boolFieldRoundTrip() {
        DataStore store = DataStore.of(10, Terrain.class);
        BoolAccessor active = Accessors.boolFieldInStore(store, Terrain.class, "active");

        active.set(store, 0, true);
        assertTrue(active.get(store, 0));

        active.set(store, 1, false);
        assertFalse(active.get(store, 1));

        active.set(store, 0, false);
        assertFalse(active.get(store, 0));
    }

    @Test
    void enumFieldOrdinal() {
        DataStore store = DataStore.of(10, BiomeData.class);
        EnumAccessor<BiomeType> biome = Accessors.enumFieldInStore(store, BiomeData.class, "biome");

        biome.set(store, 0, BiomeType.PLAINS);
        assertEquals(BiomeType.PLAINS, biome.get(store, 0));

        biome.set(store, 1, BiomeType.OCEAN);
        assertEquals(BiomeType.OCEAN, biome.get(store, 1));

        biome.set(store, 2, BiomeType.DESERT);
        assertEquals(BiomeType.DESERT, biome.get(store, 2));
    }

    @Test
    void enumFieldExplicitCodes() {
        DataStore store = DataStore.of(5, TaskRecord.class);
        EnumAccessor<Priority2> priority = Accessors.enumFieldInStore(store, TaskRecord.class, "priority");

        priority.set(store, 0, Priority2.LOW);
        assertEquals(Priority2.LOW, priority.get(store, 0));

        priority.set(store, 1, Priority2.HIGH);
        assertEquals(Priority2.HIGH, priority.get(store, 1));

        priority.set(store, 2, Priority2.MEDIUM);
        assertEquals(Priority2.MEDIUM, priority.get(store, 2));
    }

    @Test
    void multipleComponentTypesInOneStore() {
        DataStore store = DataStore.of(100, Terrain.class, Water.class);

        IntAccessor height  = Accessors.intFieldInStore(store, Terrain.class, "height");
        DoubleAccessor temp = Accessors.doubleFieldInStore(store, Terrain.class, "temperature");
        BoolAccessor active = Accessors.boolFieldInStore(store, Terrain.class, "active");

        DoubleAccessor salinity = Accessors.doubleFieldInStore(store, Water.class, "salinity");
        BoolAccessor frozen     = Accessors.boolFieldInStore(store, Water.class, "frozen");

        // Write Terrain fields to row 10
        height.set(store, 10, 100);
        temp.set(store, 10, 20.0);
        active.set(store, 10, true);

        // Write Water fields to row 10
        salinity.set(store, 10, 0.035);
        frozen.set(store, 10, false);

        // Verify Terrain
        assertEquals(100,   height.get(store, 10));
        assertEquals(20.0,  temp.get(store, 10), 0.01);
        assertTrue(active.get(store, 10));

        // Verify Water
        assertEquals(0.035, salinity.get(store, 10), 0.0001);
        assertFalse(frozen.get(store, 10));
    }

    @Test
    void rowIsolation() {
        DataStore store = DataStore.of(5, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");

        for (int i = 0; i < 5; i++) height.set(store, i, i * 50);
        for (int i = 0; i < 5; i++) assertEquals(i * 50, height.get(store, i));
    }

    @Test
    void bitsSpanWordBoundary() {
        // Force a field to straddle a 64-bit word boundary by creating a component
        // with a large first field and then checking the second field
        record BigFirst(
                @BitField(min = 0, max = (1 << 30) - 1) int big,
                @BitField(min = 0, max = 255) int small
        ) {}
        DataStore store = DataStore.of(5, BigFirst.class);
        IntAccessor big   = Accessors.intFieldInStore(store, BigFirst.class, "big");
        IntAccessor small = Accessors.intFieldInStore(store, BigFirst.class, "small");

        big.set(store, 0, (1 << 30) - 1);
        small.set(store, 0, 200);

        assertEquals((1 << 30) - 1, big.get(store, 0));
        assertEquals(200, small.get(store, 0));
    }

    @Test
    void layoutBuilderBitsRequired() {
        // 0..255 → 8 bits; 0..256 → 9 bits
        assertEquals(8, io.github.zzuegg.jbinary.schema.LayoutBuilder.bitsRequired(255));
        assertEquals(9, io.github.zzuegg.jbinary.schema.LayoutBuilder.bitsRequired(256));
        assertEquals(1, io.github.zzuegg.jbinary.schema.LayoutBuilder.bitsRequired(1));
        assertEquals(1, io.github.zzuegg.jbinary.schema.LayoutBuilder.bitsRequired(0));
    }
}
