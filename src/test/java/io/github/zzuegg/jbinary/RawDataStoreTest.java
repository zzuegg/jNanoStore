package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.*;
import io.github.zzuegg.jbinary.annotation.*;
import org.junit.jupiter.api.Test;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RawDataStore}.
 */
class RawDataStoreTest {

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

    // ------------------------------------------------------------------ factory

    @Test
    void factory_createsRawDataStore() {
        RawDataStore<Terrain> raw = DataStore.raw(100, Terrain.class);
        assertNotNull(raw);
        assertEquals(100, raw.capacity());
    }

    @Test
    void factory_rowStrideEqualsFieldCount() {
        // Terrain has 3 fields → stride = 3
        RawDataStore<Terrain> raw = DataStore.raw(10, Terrain.class);
        assertEquals(3, raw.rowStrideLongs());
    }

    @Test
    void factory_multiComponentStrideIsSum() {
        // Terrain(3) + Water(2) = 5 fields
        RawDataStore<?> raw = DataStore.raw(10, Terrain.class, Water.class);
        assertEquals(5, raw.rowStrideLongs());
    }

    // ------------------------------------------------------------------ int field

    @Test
    void intField_roundTrip() {
        RawDataStore<Terrain> raw = DataStore.raw(10, Terrain.class);
        IntAccessor height = raw.intAccessor(Terrain.class, "height");

        height.set(raw, 0, 0);
        assertEquals(0, height.get(raw, 0));

        height.set(raw, 0, 255);
        assertEquals(255, height.get(raw, 0));

        height.set(raw, 5, 128);
        assertEquals(128, height.get(raw, 5));
        assertEquals(255, height.get(raw, 0));
    }

    @Test
    void intField_rowIsolation() {
        RawDataStore<Terrain> raw = DataStore.raw(5, Terrain.class);
        IntAccessor height = raw.intAccessor(Terrain.class, "height");
        for (int i = 0; i < 5; i++) height.set(raw, i, i * 50);
        for (int i = 0; i < 5; i++) assertEquals(i * 50, height.get(raw, i));
    }

    // ------------------------------------------------------------------ double field

    @Test
    void doubleField_roundTrip() {
        RawDataStore<Terrain> raw = DataStore.raw(10, Terrain.class);
        DoubleAccessor temp = raw.doubleAccessor(Terrain.class, "temperature");

        temp.set(raw, 0, 0.0);
        assertEquals(0.0, temp.get(raw, 0), 0.01);

        temp.set(raw, 1, -50.0);
        assertEquals(-50.0, temp.get(raw, 1), 0.01);

        temp.set(raw, 2, 50.0);
        assertEquals(50.0, temp.get(raw, 2), 0.01);

        temp.set(raw, 3, 23.45);
        assertEquals(23.45, temp.get(raw, 3), 0.01);
    }

    // ------------------------------------------------------------------ bool field

    @Test
    void boolField_roundTrip() {
        RawDataStore<Terrain> raw = DataStore.raw(10, Terrain.class);
        BoolAccessor active = raw.boolAccessor(Terrain.class, "active");

        active.set(raw, 0, true);
        assertTrue(active.get(raw, 0));

        active.set(raw, 1, false);
        assertFalse(active.get(raw, 1));
    }

    // ------------------------------------------------------------------ enum field

    @Test
    void enumField_roundTrip() {
        RawDataStore<BiomeData> raw = DataStore.raw(10, BiomeData.class);
        EnumAccessor<BiomeType> biome = raw.enumAccessor(BiomeData.class, "biome");

        biome.set(raw, 0, BiomeType.PLAINS);
        assertEquals(BiomeType.PLAINS, biome.get(raw, 0));

        biome.set(raw, 1, BiomeType.OCEAN);
        assertEquals(BiomeType.OCEAN, biome.get(raw, 1));
    }

    // ------------------------------------------------------------------ multi-field independence

    @Test
    void multipleFields_doNotInterfere() {
        RawDataStore<Terrain> raw = DataStore.raw(5, Terrain.class);
        IntAccessor    height = raw.intAccessor(Terrain.class, "height");
        DoubleAccessor temp   = raw.doubleAccessor(Terrain.class, "temperature");
        BoolAccessor   active = raw.boolAccessor(Terrain.class, "active");

        height.set(raw, 0, 200);
        temp.set(raw, 0, 37.5);
        active.set(raw, 0, true);

        assertEquals(200,  height.get(raw, 0));
        assertEquals(37.5, temp.get(raw, 0), 0.01);
        assertTrue(active.get(raw, 0));
    }

    @Test
    void multiComponent_fieldsDoNotInterfere() {
        RawDataStore<?> raw = DataStore.raw(10, Terrain.class, Water.class);
        IntAccessor    height   = raw.intAccessor(Terrain.class, "height");
        DoubleAccessor salinity = raw.doubleAccessor(Water.class, "salinity");
        BoolAccessor   frozen   = raw.boolAccessor(Water.class, "frozen");

        height.set(raw, 3, 100);
        salinity.set(raw, 3, 0.035);
        frozen.set(raw, 3, true);

        assertEquals(100,   height.get(raw, 3));
        assertEquals(0.035, salinity.get(raw, 3), 0.0001);
        assertTrue(frozen.get(raw, 3));
    }

    // ------------------------------------------------------------------ RowView

    @Test
    void rowView_getRoundTrip() {
        RawDataStore<Terrain> raw = DataStore.raw(10, Terrain.class);
        RowView<Terrain> view = raw.rowView(Terrain.class);

        view.set(raw, 2, new Terrain(150, -12.5, true));
        Terrain t = view.get(raw, 2);

        assertEquals(150,   t.height());
        assertEquals(-12.5, t.temperature(), 0.01);
        assertTrue(t.active());
    }

    @Test
    void rowView_multipleRows() {
        RawDataStore<Terrain> raw = DataStore.raw(5, Terrain.class);
        RowView<Terrain> view = raw.rowView(Terrain.class);

        for (int i = 0; i < 5; i++) {
            view.set(raw, i, new Terrain(i * 10, i - 2.5, i % 2 == 0));
        }
        for (int i = 0; i < 5; i++) {
            Terrain t = view.get(raw, i);
            assertEquals(i * 10, t.height());
            assertEquals(i - 2.5, t.temperature(), 0.01);
            assertEquals(i % 2 == 0, t.active());
        }
    }

    // ------------------------------------------------------------------ serialization

    @Test
    void serialization_roundTrip() throws IOException {
        RawDataStore<Terrain> original = DataStore.raw(10, Terrain.class);
        RowView<Terrain> view = original.rowView(Terrain.class);
        view.set(original, 3, new Terrain(99, 12.3, true));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        original.write(baos);

        RawDataStore<Terrain> restored = DataStore.raw(10, Terrain.class);
        restored.read(new ByteArrayInputStream(baos.toByteArray()));

        RowView<Terrain> rv = restored.rowView(Terrain.class);
        Terrain t = rv.get(restored, 3);
        assertEquals(99,   t.height());
        assertEquals(12.3, t.temperature(), 0.01);
        assertTrue(t.active());
    }

    @Test
    void serialization_wrongMagicThrows() {
        RawDataStore<Terrain> raw = DataStore.raw(10, Terrain.class);
        assertThrows(IOException.class,
                () -> raw.read(new ByteArrayInputStream(new byte[16])));
    }

    @Test
    void serialization_wrongTypeThrows() throws IOException {
        // Write packed, try to read into raw
        DataStore<Terrain> packed = DataStore.of(10, Terrain.class);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        packed.write(baos);
        RawDataStore<Terrain> raw = DataStore.raw(10, Terrain.class);
        assertThrows(IOException.class,
                () -> raw.read(new ByteArrayInputStream(baos.toByteArray())));
    }

    @Test
    void serialization_capacityMismatchThrows() throws IOException {
        RawDataStore<Terrain> small = DataStore.raw(10, Terrain.class);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        small.write(baos);
        RawDataStore<Terrain> large = DataStore.raw(20, Terrain.class);
        assertThrows(IllegalArgumentException.class,
                () -> large.read(new ByteArrayInputStream(baos.toByteArray())));
    }
}
