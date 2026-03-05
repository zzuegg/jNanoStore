package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.annotation.*;
import io.github.zzuegg.jbinary.octree.CollapsingFunction;
import io.github.zzuegg.jbinary.octree.FastOctreeDataStore;
import io.github.zzuegg.jbinary.octree.OctreeDataStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DataCursor} — the mutable, allocation-free multi-component cursor.
 */
class DataCursorTest {

    // ------------------------------------------------------------------ component records

    record Terrain(
            @BitField(min = 0, max = 255)                          int height,
            @DecimalField(min = -50.0, max = 50.0, precision = 2)  double temperature,
            @BoolField                                              boolean active
    ) {}

    record Water(
            @DecimalField(min = 0.0, max = 1.0, precision = 4)  double salinity,
            @BoolField                                           boolean frozen
    ) {}

    enum Biome { PLAINS, FOREST, DESERT, OCEAN }

    record BiomeData(
            @EnumField Biome biome,
            @BitField(min = 0, max = 100) int fertility
    ) {}

    // ------------------------------------------------------------------ cursor classes

    static class TerrainCursor {
        @StoreField(component = Terrain.class, field = "height")
        public int height;

        @StoreField(component = Terrain.class, field = "temperature")
        public double temperature;

        @StoreField(component = Terrain.class, field = "active")
        public boolean active;
    }

    /** Cursor spanning Terrain + Water — each field from a different component. */
    static class MultiCursor {
        @StoreField(component = Terrain.class, field = "height")
        public int terrainHeight;

        @StoreField(component = Water.class, field = "salinity")
        public double waterSalinity;

        @StoreField(component = Water.class, field = "frozen")
        public boolean waterFrozen;
    }

    /** Partial cursor: reads only a subset of Terrain fields. */
    static class PartialCursor {
        @StoreField(component = Terrain.class, field = "height")
        public int height;
        // temperature is intentionally omitted
        @StoreField(component = Terrain.class, field = "active")
        public boolean active;
    }

    static class EnumCursor {
        @StoreField(component = BiomeData.class, field = "biome")
        public Biome biome;

        @StoreField(component = BiomeData.class, field = "fertility")
        public int fertility;
    }

    // ------------------------------------------------------------------ tests: single component

    @Test
    void singleComponentRoundTrip() {
        DataStore store = DataStore.packed(100, Terrain.class);
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        // Write via accessors
        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, 5, 200);
        Accessors.doubleFieldInStore(store, Terrain.class, "temperature").set(store, 5, -12.5);
        Accessors.boolFieldInStore(store, Terrain.class, "active").set(store, 5, true);

        cursor.load(store, 5);
        TerrainCursor data = cursor.get();

        assertEquals(200, data.height);
        assertEquals(-12.5, data.temperature, 0.01);
        assertTrue(data.active);
    }

    @Test
    void singleComponentFlush() {
        DataStore store = DataStore.packed(100, Terrain.class);
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        cursor.get().height      = 199;
        cursor.get().temperature = 37.0;
        cursor.get().active      = false;
        cursor.flush(store, 7);

        assertEquals(199, Accessors.intFieldInStore(store, Terrain.class, "height").get(store, 7));
        assertEquals(37.0, Accessors.doubleFieldInStore(store, Terrain.class, "temperature").get(store, 7), 0.01);
        assertFalse(Accessors.boolFieldInStore(store, Terrain.class, "active").get(store, 7));
    }

    @Test
    void updateReturnsSameInstance() {
        DataStore store = DataStore.packed(100, Terrain.class);
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, 0, 42);

        TerrainCursor a = cursor.update(store, 0);
        TerrainCursor b = cursor.get();
        assertSame(a, b, "update() and get() must return the same object");
        assertEquals(42, a.height);
    }

    @Test
    void cursorInstanceIsReusedAcrossLoads() {
        DataStore store = DataStore.packed(100, Terrain.class);
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, 0, 10);
        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, 1, 20);

        cursor.load(store, 0);
        TerrainCursor ref = cursor.get();
        assertEquals(10, ref.height);

        cursor.load(store, 1);
        assertSame(ref, cursor.get(), "same object must be returned");
        assertEquals(20, ref.height);
    }

    @Test
    void partialCursorReadWriteOtherFieldUntouched() {
        DataStore store = DataStore.packed(100, Terrain.class);
        DataCursor<PartialCursor> cursor = DataCursor.of(store, PartialCursor.class);

        // Pre-set temperature (not in cursor)
        Accessors.doubleFieldInStore(store, Terrain.class, "temperature").set(store, 3, 25.0);
        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, 3, 128);
        Accessors.boolFieldInStore(store, Terrain.class, "active").set(store, 3, true);

        cursor.load(store, 3);
        assertEquals(128, cursor.get().height);
        assertTrue(cursor.get().active);

        // Modify height, flush — temperature must be unchanged
        cursor.get().height = 77;
        cursor.flush(store, 3);

        assertEquals(77,   Accessors.intFieldInStore(store, Terrain.class, "height").get(store, 3));
        assertEquals(25.0, Accessors.doubleFieldInStore(store, Terrain.class, "temperature").get(store, 3), 0.01);
    }

    // ------------------------------------------------------------------ tests: multi-component

    @Test
    void multiComponentLoad() {
        DataStore store = DataStore.packed(100, Terrain.class, Water.class);
        DataCursor<MultiCursor> cursor = DataCursor.of(store, MultiCursor.class);

        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, 10, 180);
        Accessors.doubleFieldInStore(store, Water.class, "salinity").set(store, 10, 0.035);
        Accessors.boolFieldInStore(store, Water.class, "frozen").set(store, 10, true);

        cursor.load(store, 10);
        MultiCursor d = cursor.get();

        assertEquals(180, d.terrainHeight);
        assertEquals(0.035, d.waterSalinity, 0.0001);
        assertTrue(d.waterFrozen);
    }

    @Test
    void multiComponentFlush() {
        DataStore store = DataStore.packed(100, Terrain.class, Water.class);
        DataCursor<MultiCursor> cursor = DataCursor.of(store, MultiCursor.class);

        cursor.get().terrainHeight = 99;
        cursor.get().waterSalinity = 0.5;
        cursor.get().waterFrozen   = false;
        cursor.flush(store, 20);

        assertEquals(99,  Accessors.intFieldInStore(store, Terrain.class, "height").get(store, 20));
        assertEquals(0.5, Accessors.doubleFieldInStore(store, Water.class, "salinity").get(store, 20), 0.0001);
        assertFalse(Accessors.boolFieldInStore(store, Water.class, "frozen").get(store, 20));
    }

    // ------------------------------------------------------------------ tests: enum

    @Test
    void enumFieldRoundTrip() {
        DataStore store = DataStore.packed(100, BiomeData.class);
        DataCursor<EnumCursor> cursor = DataCursor.of(store, EnumCursor.class);

        Accessors.<Biome>enumFieldInStore(store, BiomeData.class, "biome").set(store, 0, Biome.DESERT);
        Accessors.intFieldInStore(store, BiomeData.class, "fertility").set(store, 0, 75);

        cursor.load(store, 0);
        assertEquals(Biome.DESERT, cursor.get().biome);
        assertEquals(75, cursor.get().fertility);
    }

    @Test
    void enumFieldFlush() {
        DataStore store = DataStore.packed(100, BiomeData.class);
        DataCursor<EnumCursor> cursor = DataCursor.of(store, EnumCursor.class);

        cursor.get().biome     = Biome.OCEAN;
        cursor.get().fertility = 30;
        cursor.flush(store, 1);

        assertEquals(Biome.OCEAN, Accessors.<Biome>enumFieldInStore(store, BiomeData.class, "biome").get(store, 1));
        assertEquals(30,          Accessors.intFieldInStore(store, BiomeData.class, "fertility").get(store, 1));
    }

    // ------------------------------------------------------------------ tests: different store types

    @Test
    void worksWithSparseDataStore() {
        DataStore store = DataStore.sparse(1000, Terrain.class);
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        cursor.get().height = 55;
        cursor.get().active = true;
        cursor.flush(store, 500);

        cursor.load(store, 500);
        assertEquals(55, cursor.get().height);
        assertTrue(cursor.get().active);
    }

    @Test
    void worksWithOctreeDataStore() {
        OctreeDataStore store = OctreeDataStore.builder(4)
                .component(Terrain.class)
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        int row = store.row(3, 2, 1);
        cursor.get().height = 100;
        cursor.get().temperature = 20.0;
        cursor.get().active = false;
        cursor.flush(store, row);

        cursor.load(store, row);
        assertEquals(100,  cursor.get().height);
        assertEquals(20.0, cursor.get().temperature, 0.01);
        assertFalse(cursor.get().active);
    }

    @Test
    void worksWithFastOctreeDataStore() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4)
                .component(Terrain.class)
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        int row = store.row(1, 2, 3);
        cursor.get().height = 77;
        cursor.flush(store, row);

        cursor.load(store, row);
        assertEquals(77, cursor.get().height);
    }

    @Test
    void accessorsFactoryMethod() {
        DataStore store = DataStore.packed(10, Terrain.class);
        DataCursor<TerrainCursor> cursor = Accessors.dataCursorOf(store, TerrainCursor.class);

        cursor.get().height = 33;
        cursor.flush(store, 0);
        cursor.load(store, 0);
        assertEquals(33, cursor.get().height);
    }

    // ------------------------------------------------------------------ tests: default values

    @Test
    void unwrittenRowDefaultsToZero() {
        DataStore store = DataStore.packed(100, Terrain.class);
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        cursor.load(store, 99);
        // Field minimum for height(min=0) encodes as 0
        assertEquals(0, cursor.get().height);
        assertFalse(cursor.get().active);
    }

    // ------------------------------------------------------------------ tests: error cases

    @Test
    void noAnnotationsThrows() {
        class NoAnnotations { public int x; }
        DataStore store = DataStore.packed(10, Terrain.class);
        assertThrows(IllegalArgumentException.class, () -> DataCursor.of(store, NoAnnotations.class));
    }

    @Test
    void noNoArgConstructorThrows() {
        DataStore store = DataStore.packed(10, Terrain.class);
        // Anonymous class has no accessible no-arg ctor
        class NoDefaultCtor {
            @StoreField(component = Terrain.class, field = "height")
            public int height;
            NoDefaultCtor(int ignored) {}
        }
        assertThrows(IllegalArgumentException.class, () -> DataCursor.of(store, NoDefaultCtor.class));
    }

    @Test
    void unknownComponentThrows() {
        DataStore store = DataStore.packed(10, Terrain.class); // Water NOT registered
        class BadCursor {
            @StoreField(component = Water.class, field = "salinity")
            public double salinity;
        }
        assertThrows(IllegalArgumentException.class, () -> DataCursor.of(store, BadCursor.class));
    }

    @Test
    void unknownFieldNameThrows() {
        DataStore store = DataStore.packed(10, Terrain.class);
        class BadCursor {
            @StoreField(component = Terrain.class, field = "nonexistent")
            public int x;
        }
        assertThrows(IllegalArgumentException.class, () -> DataCursor.of(store, BadCursor.class));
    }

    // ------------------------------------------------------------------ tests: OctreeDataStore

    @Test
    void octreeMultiComponentCursorLoadFlush() {
        OctreeDataStore store = OctreeDataStore.builder(4)
                .component(Terrain.class, CollapsingFunction.never())
                .component(Water.class, CollapsingFunction.never())
                .build();
        DataCursor<MultiCursor> cursor = DataCursor.of(store, MultiCursor.class);

        int row = store.row(5, 3, 7);
        cursor.get().terrainHeight = 180;
        cursor.get().waterSalinity = 0.035;
        cursor.get().waterFrozen   = true;
        cursor.flush(store, row);

        cursor.load(store, row);
        assertEquals(180,   cursor.get().terrainHeight);
        assertEquals(0.035, cursor.get().waterSalinity, 0.0001);
        assertTrue(cursor.get().waterFrozen);
    }

    @Test
    void octreePartialCursorDoesNotOverwriteOmittedField() {
        OctreeDataStore store = OctreeDataStore.builder(4)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        DataCursor<PartialCursor> cursor = DataCursor.of(store, PartialCursor.class);

        // Pre-set temperature via a separate accessor
        Accessors.doubleFieldInStore(store, Terrain.class, "temperature").set(store, store.row(1, 1, 1), 30.0);

        cursor.get().height = 99;
        cursor.get().active = true;
        cursor.flush(store, store.row(1, 1, 1));

        // temperature must still be 30.0 (not touched by partial cursor)
        assertEquals(30.0, Accessors.doubleFieldInStore(store, Terrain.class, "temperature")
                .get(store, store.row(1, 1, 1)), 0.01);
        assertEquals(99, Accessors.intFieldInStore(store, Terrain.class, "height")
                .get(store, store.row(1, 1, 1)));
    }

    @Test
    void octreeEnumCursorRoundTrip() {
        OctreeDataStore store = OctreeDataStore.builder(4)
                .component(BiomeData.class, CollapsingFunction.never())
                .build();
        DataCursor<EnumCursor> cursor = DataCursor.of(store, EnumCursor.class);

        int row = store.row(2, 3, 4);
        cursor.get().biome     = Biome.DESERT;
        cursor.get().fertility = 60;
        cursor.flush(store, row);

        cursor.load(store, row);
        assertEquals(Biome.DESERT, cursor.get().biome);
        assertEquals(60,           cursor.get().fertility);
    }

    @Test
    void octreeUpdateReturnsPopulatedInstance() {
        OctreeDataStore store = OctreeDataStore.builder(4)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        int row = store.row(0, 0, 1);
        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, row, 77);

        TerrainCursor result = cursor.update(store, row);
        assertSame(result, cursor.get());
        assertEquals(77, result.height);
    }

    @Test
    void octreeCursorInstanceReusedAcrossDifferentRows() {
        OctreeDataStore store = OctreeDataStore.builder(4)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, store.row(0, 0, 0), 10);
        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, store.row(1, 0, 0), 20);

        cursor.load(store, store.row(0, 0, 0));
        TerrainCursor ref = cursor.get();
        assertEquals(10, ref.height);

        cursor.load(store, store.row(1, 0, 0));
        assertSame(ref, cursor.get());
        assertEquals(20, ref.height);
    }

    @Test
    void octreeCursorRowIsolation() {
        OctreeDataStore store = OctreeDataStore.builder(4)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        cursor.get().height = 100;
        cursor.flush(store, store.row(3, 3, 3));

        cursor.load(store, store.row(4, 4, 4));  // different voxel, never written
        assertEquals(0, cursor.get().height);    // should be 0 (unwritten)
    }

    @Test
    void octreeCursorFlushAllSameValueTriggerCollapse() {
        OctreeDataStore store = OctreeDataStore.builder(1)  // 2×2×2 space
                .component(Terrain.class)
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        // Write same height to all 8 voxels via cursor → should collapse
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++) {
                    cursor.get().height      = 42;
                    cursor.get().temperature = 10.0;
                    cursor.get().active      = false;
                    cursor.flush(store, store.row(x, y, z));
                }

        assertEquals(1, store.nodeCount());

        // Values still readable via cursor
        cursor.load(store, store.row(1, 1, 1));
        assertEquals(42, cursor.get().height);
    }

    @Test
    void octreeCursorLoadFromCollapsedRegion() {
        OctreeDataStore store = OctreeDataStore.builder(2)  // 4×4×4 space
                .component(Terrain.class)
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        // Collapse entire space to a single node with height=99
        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++) {
                    cursor.get().height      = 99;
                    cursor.get().temperature = 0.0;
                    cursor.get().active      = false;
                    cursor.flush(store, store.row(x, y, z));
                }
        assertEquals(1, store.nodeCount());

        // Cursor load from any voxel should still return 99
        cursor.load(store, store.row(2, 2, 2));
        assertEquals(99, cursor.get().height);
        cursor.load(store, store.row(0, 3, 1));
        assertEquals(99, cursor.get().height);
    }

    @Test
    void octreeCursorWithBatchMode() {
        OctreeDataStore store = OctreeDataStore.builder(1)
                .component(Terrain.class)
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        store.beginBatch();
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++) {
                    cursor.get().height = 55;
                    cursor.flush(store, store.row(x, y, z));
                }
        // Not yet collapsed during batch
        assertTrue(store.nodeCount() > 1);
        store.endBatch();
        // Collapsed after endBatch
        assertEquals(1, store.nodeCount());

        cursor.load(store, store.row(0, 0, 0));
        assertEquals(55, cursor.get().height);
    }

    @Test
    void octreeDefaultValuesReadByUnwrittenCursorFields() {
        OctreeDataStore store = OctreeDataStore.builder(4)
                .component(Terrain.class)
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        cursor.load(store, store.row(5, 5, 5));
        assertEquals(0,     cursor.get().height);
        assertEquals(-50.0, cursor.get().temperature, 0.01);
        assertFalse(cursor.get().active);
    }

    // ------------------------------------------------------------------ tests: FastOctreeDataStore

    @Test
    void fastOctreeMultiComponentCursorLoadFlush() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4)
                .component(Terrain.class, CollapsingFunction.never())
                .component(Water.class, CollapsingFunction.never())
                .build();
        DataCursor<MultiCursor> cursor = DataCursor.of(store, MultiCursor.class);

        int row = store.row(7, 2, 5);
        cursor.get().terrainHeight = 200;
        cursor.get().waterSalinity = 0.9;
        cursor.get().waterFrozen   = false;
        cursor.flush(store, row);

        cursor.load(store, row);
        assertEquals(200, cursor.get().terrainHeight);
        assertEquals(0.9, cursor.get().waterSalinity, 0.0001);
        assertFalse(cursor.get().waterFrozen);
    }

    @Test
    void fastOctreePartialCursorDoesNotOverwriteOmittedField() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        DataCursor<PartialCursor> cursor = DataCursor.of(store, PartialCursor.class);

        Accessors.doubleFieldInStore(store, Terrain.class, "temperature").set(store, store.row(2, 2, 2), -10.0);

        cursor.get().height = 50;
        cursor.get().active = true;
        cursor.flush(store, store.row(2, 2, 2));

        assertEquals(-10.0, Accessors.doubleFieldInStore(store, Terrain.class, "temperature")
                .get(store, store.row(2, 2, 2)), 0.01);
        assertEquals(50, Accessors.intFieldInStore(store, Terrain.class, "height")
                .get(store, store.row(2, 2, 2)));
    }

    @Test
    void fastOctreeEnumCursorRoundTrip() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4)
                .component(BiomeData.class, CollapsingFunction.never())
                .build();
        DataCursor<EnumCursor> cursor = DataCursor.of(store, EnumCursor.class);

        int row = store.row(0, 1, 2);
        cursor.get().biome     = Biome.OCEAN;
        cursor.get().fertility = 80;
        cursor.flush(store, row);

        cursor.load(store, row);
        assertEquals(Biome.OCEAN, cursor.get().biome);
        assertEquals(80,          cursor.get().fertility);
    }

    @Test
    void fastOctreeUpdateReturnsPopulatedInstance() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        int row = store.row(3, 0, 2);
        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, row, 33);

        TerrainCursor result = cursor.update(store, row);
        assertSame(result, cursor.get());
        assertEquals(33, result.height);
    }

    @Test
    void fastOctreeCursorRowIsolation() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        cursor.get().height = 111;
        cursor.flush(store, store.row(1, 1, 1));

        cursor.load(store, store.row(2, 2, 2));
        assertEquals(0, cursor.get().height);
    }

    @Test
    void fastOctreeCursorFlushAllSameValueTriggerCollapse() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(1)
                .component(Terrain.class)
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++) {
                    cursor.get().height      = 7;
                    cursor.get().temperature = 0.0;
                    cursor.get().active      = false;
                    cursor.flush(store, store.row(x, y, z));
                }

        assertEquals(1, store.nodeCount());
        cursor.load(store, store.row(0, 0, 0));
        assertEquals(7, cursor.get().height);
    }

    @Test
    void fastOctreeCursorLoadFromCollapsedRegion() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(2)
                .component(Terrain.class)
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++) {
                    cursor.get().height      = 15;
                    cursor.get().temperature = 0.0;
                    cursor.get().active      = false;
                    cursor.flush(store, store.row(x, y, z));
                }
        assertEquals(1, store.nodeCount());

        cursor.load(store, store.row(3, 2, 1));
        assertEquals(15, cursor.get().height);
    }

    @Test
    void fastOctreeCursorWithBatchMode() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(1)
                .component(Terrain.class)
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        store.beginBatch();
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++) {
                    cursor.get().height = 88;
                    cursor.flush(store, store.row(x, y, z));
                }
        assertTrue(store.nodeCount() > 1);
        store.endBatch();
        assertEquals(1, store.nodeCount());

        cursor.load(store, store.row(1, 0, 1));
        assertEquals(88, cursor.get().height);
    }

    @Test
    void fastOctreeDefaultValuesReadByUnwrittenCursorFields() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4)
                .component(Terrain.class)
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        cursor.load(store, store.row(3, 2, 1));
        assertEquals(0,     cursor.get().height);
        assertEquals(-50.0, cursor.get().temperature, 0.01);
        assertFalse(cursor.get().active);
    }

    @Test
    void fastOctreeCursorInstanceReusedAcrossRows() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, store.row(0, 0, 0), 5);
        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, store.row(1, 1, 1), 10);

        cursor.load(store, store.row(0, 0, 0));
        TerrainCursor ref = cursor.get();
        assertEquals(5, ref.height);

        cursor.load(store, store.row(1, 1, 1));
        assertSame(ref, cursor.get());
        assertEquals(10, ref.height);
    }

    // ------------------------------------------------------------------ tests: new primitive types

    record AllPrimitivesComponent(
            @BitField(min = 0, max = 255)                          int    intVal,
            @BitField(min = 0, max = 127)                          byte   byteVal,
            @BitField(min = 0, max = 32767)                        short  shortVal,
            @BitField(min = 0, max = 65535)                        char   charVal,
            @BitField(min = 0, max = 1_000_000)                    long   longVal,
            @DecimalField(min = 0.0, max = 1.0, precision = 3)    float  floatVal,
            @DecimalField(min = -1.0, max = 1.0, precision = 4)   double doubleVal,
            @BoolField                                              boolean boolVal,
            @StringField(maxLength = 8)                            String  strVal
    ) {}

    static class AllPrimitivesCursor {
        @StoreField(component = AllPrimitivesComponent.class, field = "intVal")
        public int intVal;

        @StoreField(component = AllPrimitivesComponent.class, field = "byteVal")
        public byte byteVal;

        @StoreField(component = AllPrimitivesComponent.class, field = "shortVal")
        public short shortVal;

        @StoreField(component = AllPrimitivesComponent.class, field = "charVal")
        public char charVal;

        @StoreField(component = AllPrimitivesComponent.class, field = "longVal")
        public long longVal;

        @StoreField(component = AllPrimitivesComponent.class, field = "floatVal")
        public float floatVal;

        @StoreField(component = AllPrimitivesComponent.class, field = "doubleVal")
        public double doubleVal;

        @StoreField(component = AllPrimitivesComponent.class, field = "boolVal")
        public boolean boolVal;

        @StoreField(component = AllPrimitivesComponent.class, field = "strVal")
        public String strVal;
    }

    @Test
    void cursorAllPrimitivesRoundTripByteBuddy() {
        DataStore<?> store = DataStore.packed(10, AllPrimitivesComponent.class);
        DataCursor<AllPrimitivesCursor> cursor = DataCursor.of(store, AllPrimitivesCursor.class);

        cursor.get().intVal    = 200;
        cursor.get().byteVal   = 42;
        cursor.get().shortVal  = 1000;
        cursor.get().charVal   = 'Z';
        cursor.get().longVal   = 500_000L;
        cursor.get().floatVal  = 0.5f;
        cursor.get().doubleVal = -0.75;
        cursor.get().boolVal   = true;
        cursor.get().strVal    = "hello";
        cursor.flush(store, 3);

        // Reset cursor state
        cursor.get().intVal    = 0;
        cursor.get().byteVal   = 0;
        cursor.get().shortVal  = 0;
        cursor.get().charVal   = '\0';
        cursor.get().longVal   = 0L;
        cursor.get().floatVal  = 0f;
        cursor.get().doubleVal = 0.0;
        cursor.get().boolVal   = false;
        cursor.get().strVal    = null;

        cursor.load(store, 3);
        assertEquals(200,     cursor.get().intVal);
        assertEquals(42,      cursor.get().byteVal);
        assertEquals(1000,    cursor.get().shortVal);
        assertEquals('Z',     cursor.get().charVal);
        assertEquals(500_000L, cursor.get().longVal);
        assertEquals(0.5f,    cursor.get().floatVal,  0.001f);
        assertEquals(-0.75,   cursor.get().doubleVal, 0.0001);
        assertTrue(cursor.get().boolVal);
        assertEquals("hello", cursor.get().strVal);
    }

    @Test
    void cursorAllPrimitivesRoundTripVarHandle() {
        DataStore<?> store = DataStore.packed(10, AllPrimitivesComponent.class);
        DataCursor<AllPrimitivesCursor> cursor = DataCursor.ofVarHandle(store, AllPrimitivesCursor.class);

        cursor.get().intVal    = 100;
        cursor.get().byteVal   = 10;
        cursor.get().shortVal  = 500;
        cursor.get().charVal   = 'A';
        cursor.get().longVal   = 999_999L;
        cursor.get().floatVal  = 0.25f;
        cursor.get().doubleVal = 0.5;
        cursor.get().boolVal   = false;
        cursor.get().strVal    = "world";
        cursor.flush(store, 7);

        cursor.get().strVal = null;
        cursor.load(store, 7);
        assertEquals(100,     cursor.get().intVal);
        assertEquals(10,      cursor.get().byteVal);
        assertEquals(500,     cursor.get().shortVal);
        assertEquals('A',     cursor.get().charVal);
        assertEquals(999_999L, cursor.get().longVal);
        assertEquals(0.25f,   cursor.get().floatVal,  0.001f);
        assertEquals(0.5,     cursor.get().doubleVal, 0.0001);
        assertFalse(cursor.get().boolVal);
        assertEquals("world", cursor.get().strVal);
    }
}
