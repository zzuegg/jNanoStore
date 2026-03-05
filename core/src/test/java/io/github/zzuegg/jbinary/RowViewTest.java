package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.annotation.*;
import io.github.zzuegg.jbinary.octree.CollapsingFunction;
import io.github.zzuegg.jbinary.octree.FastOctreeDataStore;
import io.github.zzuegg.jbinary.octree.OctreeDataStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RowView} and its factory methods.
 */
class RowViewTest {

    record Terrain(
            @BitField(min = 0, max = 255) int height,
            @DecimalField(min = -50.0, max = 50.0, precision = 2) double temperature,
            @BoolField boolean active
    ) {}

    record Water(
            @DecimalField(min = 0.0, max = 1.0, precision = 4) double salinity,
            @BoolField boolean frozen
    ) {}

    enum Biome { PLAINS, FOREST, DESERT, OCEAN }

    record BiomeData(
            @EnumField Biome biome,
            @BitField(min = 0, max = 100) int fertility
    ) {}

    // -----------------------------------------------------------------------
    // 1. Basic set/get round-trip on PackedDataStore

    @Test
    void setAndGetRoundTripPackedStore() {
        DataStore store = DataStore.of(100, Terrain.class);
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        view.set(store, 0, new Terrain(200, -12.5, true));
        Terrain result = view.get(store, 0);

        assertEquals(200,   result.height());
        assertEquals(-12.5, result.temperature(), 0.01);
        assertTrue(result.active());
    }

    // -----------------------------------------------------------------------
    // 2. Round-trip for Water

    @Test
    void waterRoundTrip() {
        DataStore store = DataStore.of(100, Water.class);
        RowView<Water> view = RowView.of(store, Water.class);

        view.set(store, 5, new Water(0.035, true));
        Water result = view.get(store, 5);

        assertEquals(0.035, result.salinity(), 0.0001);
        assertTrue(result.frozen());
    }

    // -----------------------------------------------------------------------
    // 3. Multi-component store: RowViews for Terrain and Water are independent

    @Test
    void multiComponentRowViewsAreIndependent() {
        DataStore store = DataStore.of(50, Terrain.class, Water.class);
        RowView<Terrain> terrainView = RowView.of(store, Terrain.class);
        RowView<Water>   waterView   = RowView.of(store, Water.class);

        terrainView.set(store, 10, new Terrain(100, 22.5, false));
        waterView.set(store, 10, new Water(0.9, false));

        Terrain t = terrainView.get(store, 10);
        Water   w = waterView.get(store, 10);

        assertEquals(100,  t.height());
        assertEquals(22.5, t.temperature(), 0.01);
        assertFalse(t.active());

        assertEquals(0.9,  w.salinity(), 0.0001);
        assertFalse(w.frozen());
    }

    // -----------------------------------------------------------------------
    // 4. Works with PackedDataStore

    @Test
    void worksWithPackedStore() {
        DataStore store = DataStore.of(10, Terrain.class);
        RowView<Terrain> view = RowView.of(store, Terrain.class);
        view.set(store, 3, new Terrain(50, 10.0, true));
        Terrain r = view.get(store, 3);
        assertEquals(50, r.height());
        assertEquals(10.0, r.temperature(), 0.01);
        assertTrue(r.active());
    }

    // -----------------------------------------------------------------------
    // 5. Works with SparseDataStore

    @Test
    void worksWithSparseStore() {
        DataStore store = DataStore.sparse(100, Terrain.class);
        RowView<Terrain> view = RowView.of(store, Terrain.class);
        view.set(store, 7, new Terrain(180, -5.0, false));
        Terrain r = view.get(store, 7);
        assertEquals(180, r.height());
        assertEquals(-5.0, r.temperature(), 0.01);
        assertFalse(r.active());
    }

    // -----------------------------------------------------------------------
    // 6. Works with OctreeDataStore

    @Test
    void worksWithOctreeStore() {
        OctreeDataStore store = OctreeDataStore.builder(3)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        RowView<Terrain> view = RowView.of(store, Terrain.class);
        int row = store.row(2, 3, 4);
        view.set(store, row, new Terrain(128, 0.0, true));
        Terrain r = view.get(store, row);
        assertEquals(128, r.height());
        assertEquals(0.0, r.temperature(), 0.01);
        assertTrue(r.active());
    }

    // -----------------------------------------------------------------------
    // 7. Works with FastOctreeDataStore

    @Test
    void worksWithFastOctreeStore() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(3)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        RowView<Terrain> view = RowView.of(store, Terrain.class);
        int row = store.row(1, 2, 3);
        view.set(store, row, new Terrain(64, 25.0, false));
        Terrain r = view.get(store, row);
        assertEquals(64,   r.height());
        assertEquals(25.0, r.temperature(), 0.01);
        assertFalse(r.active());
    }

    // -----------------------------------------------------------------------
    // 8. Accessors.rowViewInStore returns a functional RowView

    @Test
    void accessorsRowViewInStoreReturnsFunctionalView() {
        DataStore store = DataStore.of(20, Terrain.class);
        RowView<Terrain> view = Accessors.rowViewInStore(store, Terrain.class);
        view.set(store, 0, new Terrain(255, 50.0, true));
        Terrain r = view.get(store, 0);
        assertEquals(255,  r.height());
        assertEquals(50.0, r.temperature(), 0.01);
        assertTrue(r.active());
    }

    // -----------------------------------------------------------------------
    // 9. Setting row 5 does not affect row 10

    @Test
    void rowIsolation() {
        DataStore store = DataStore.of(20, Terrain.class);
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        view.set(store, 5,  new Terrain(100, 10.0, true));
        view.set(store, 10, new Terrain(200, 20.0, false));

        Terrain r5  = view.get(store, 5);
        Terrain r10 = view.get(store, 10);

        assertEquals(100,  r5.height());
        assertEquals(10.0, r5.temperature(), 0.01);
        assertTrue(r5.active());

        assertEquals(200,  r10.height());
        assertEquals(20.0, r10.temperature(), 0.01);
        assertFalse(r10.active());
    }

    // -----------------------------------------------------------------------
    // 10. Default values before write (get on unwritten row)

    @Test
    void defaultValuesBeforeWrite() {
        DataStore store = DataStore.of(10, Terrain.class);
        RowView<Terrain> view = RowView.of(store, Terrain.class);
        Terrain r = view.get(store, 0);
        // height min=0 → raw 0 → 0
        assertEquals(0, r.height());
        // temperature min=-50.0 → raw 0 → -50.0
        assertEquals(-50.0, r.temperature(), 0.01);
        // active default → false
        assertFalse(r.active());
    }

    // -----------------------------------------------------------------------
    // 11. OctreeDataStore: default values (unwritten voxel)

    @Test
    void octreeDefaultValuesBeforeWrite() {
        OctreeDataStore store = OctreeDataStore.builder(4)
                .component(Terrain.class)
                .build();
        RowView<Terrain> view = RowView.of(store, Terrain.class);
        Terrain r = view.get(store, store.row(5, 5, 5));
        assertEquals(0,     r.height());
        assertEquals(-50.0, r.temperature(), 0.01);
        assertFalse(r.active());
    }

    // -----------------------------------------------------------------------
    // 12. OctreeDataStore: row isolation

    @Test
    void octreeRowIsolation() {
        OctreeDataStore store = OctreeDataStore.builder(4)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        view.set(store, store.row(1, 2, 3), new Terrain(100, 10.0, true));
        view.set(store, store.row(4, 5, 6), new Terrain(200, 20.0, false));

        Terrain t1 = view.get(store, store.row(1, 2, 3));
        Terrain t2 = view.get(store, store.row(4, 5, 6));

        assertEquals(100,  t1.height());
        assertEquals(10.0, t1.temperature(), 0.01);
        assertTrue(t1.active());

        assertEquals(200,  t2.height());
        assertEquals(20.0, t2.temperature(), 0.01);
        assertFalse(t2.active());
    }

    // -----------------------------------------------------------------------
    // 13. OctreeDataStore: multi-component RowViews are independent

    @Test
    void octreeMultiComponentRowViewsAreIndependent() {
        OctreeDataStore store = OctreeDataStore.builder(4)
                .component(Terrain.class, CollapsingFunction.never())
                .component(Water.class, CollapsingFunction.never())
                .build();
        RowView<Terrain> terrainView = RowView.of(store, Terrain.class);
        RowView<Water>   waterView   = RowView.of(store, Water.class);

        int row = store.row(3, 3, 3);
        terrainView.set(store, row, new Terrain(150, -5.0, true));
        waterView.set(store, row, new Water(0.5, false));

        Terrain t = terrainView.get(store, row);
        Water   w = waterView.get(store, row);

        assertEquals(150,  t.height());
        assertEquals(-5.0, t.temperature(), 0.01);
        assertTrue(t.active());
        assertEquals(0.5,  w.salinity(), 0.0001);
        assertFalse(w.frozen());
    }

    // -----------------------------------------------------------------------
    // 14. OctreeDataStore: RowView set triggers collapse when all voxels identical

    @Test
    void octreeRowViewSetTriggersCollapse() {
        OctreeDataStore store = OctreeDataStore.builder(1)   // 2×2×2 space
                .component(Terrain.class)
                .build();
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    view.set(store, store.row(x, y, z), new Terrain(42, 5.0, true));

        assertEquals(1, store.nodeCount());

        // Values still readable via RowView after collapse
        Terrain r = view.get(store, store.row(0, 1, 0));
        assertEquals(42,  r.height());
        assertEquals(5.0, r.temperature(), 0.01);
        assertTrue(r.active());
    }

    // -----------------------------------------------------------------------
    // 15. OctreeDataStore: RowView get after expansion preserves neighbour data

    @Test
    void octreeRowViewGetAfterExpansionPreservesNeighbours() {
        OctreeDataStore store = OctreeDataStore.builder(2)   // 4×4×4 space
                .component(Terrain.class)
                .build();
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        // Collapse entire space
        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++)
                    view.set(store, store.row(x, y, z), new Terrain(10, 0.0, false));
        assertEquals(1, store.nodeCount());

        // Overwrite one voxel — forces expansion
        view.set(store, store.row(0, 0, 0), new Terrain(99, 0.0, false));

        assertEquals(99, view.get(store, store.row(0, 0, 0)).height());
        assertEquals(10, view.get(store, store.row(1, 0, 0)).height());
        assertEquals(10, view.get(store, store.row(3, 3, 3)).height());
    }

    // -----------------------------------------------------------------------
    // 16. OctreeDataStore: enum record RowView

    @Test
    void octreeEnumRecordRowView() {
        OctreeDataStore store = OctreeDataStore.builder(4)
                .component(BiomeData.class, CollapsingFunction.never())
                .build();
        RowView<BiomeData> view = RowView.of(store, BiomeData.class);

        int row = store.row(7, 3, 1);
        view.set(store, row, new BiomeData(Biome.FOREST, 75));
        BiomeData r = view.get(store, row);

        assertEquals(Biome.FOREST, r.biome());
        assertEquals(75, r.fertility());
    }

    // -----------------------------------------------------------------------
    // 17. OctreeDataStore: RowView with batch mode

    @Test
    void octreeRowViewWithBatchMode() {
        OctreeDataStore store = OctreeDataStore.builder(1)
                .component(Terrain.class)
                .build();
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        store.beginBatch();
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    view.set(store, store.row(x, y, z), new Terrain(20, 0.0, false));
        assertTrue(store.nodeCount() > 1);
        store.endBatch();
        assertEquals(1, store.nodeCount());

        assertEquals(20, view.get(store, store.row(1, 0, 1)).height());
    }

    // -----------------------------------------------------------------------
    // 18. OctreeDataStore: non-uniform builder with RowView

    @Test
    void octreeNonUniformBuilderRowView() {
        OctreeDataStore store = OctreeDataStore.builder(10, 10, 5)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        view.set(store, store.row(0, 0, 0), new Terrain(1, -50.0, false));
        view.set(store, store.row(9, 9, 4), new Terrain(255, 50.0, true));

        assertEquals(1,    view.get(store, store.row(0, 0, 0)).height());
        assertEquals(255,  view.get(store, store.row(9, 9, 4)).height());
    }

    // -----------------------------------------------------------------------
    // 19. FastOctreeDataStore: default values (unwritten voxel)

    @Test
    void fastOctreeDefaultValuesBeforeWrite() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4)
                .component(Terrain.class)
                .build();
        RowView<Terrain> view = RowView.of(store, Terrain.class);
        Terrain r = view.get(store, store.row(2, 7, 3));
        assertEquals(0,     r.height());
        assertEquals(-50.0, r.temperature(), 0.01);
        assertFalse(r.active());
    }

    // -----------------------------------------------------------------------
    // 20. FastOctreeDataStore: row isolation

    @Test
    void fastOctreeRowIsolation() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        view.set(store, store.row(0, 0, 0), new Terrain(10, -10.0, true));
        view.set(store, store.row(7, 7, 7), new Terrain(20, 20.0, false));

        Terrain t1 = view.get(store, store.row(0, 0, 0));
        Terrain t2 = view.get(store, store.row(7, 7, 7));

        assertEquals(10,   t1.height());
        assertEquals(-10.0, t1.temperature(), 0.01);
        assertTrue(t1.active());

        assertEquals(20,   t2.height());
        assertEquals(20.0, t2.temperature(), 0.01);
        assertFalse(t2.active());
    }

    // -----------------------------------------------------------------------
    // 21. FastOctreeDataStore: multi-component RowViews are independent

    @Test
    void fastOctreeMultiComponentRowViewsAreIndependent() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4)
                .component(Terrain.class, CollapsingFunction.never())
                .component(Water.class, CollapsingFunction.never())
                .build();
        RowView<Terrain> terrainView = RowView.of(store, Terrain.class);
        RowView<Water>   waterView   = RowView.of(store, Water.class);

        int row = store.row(5, 5, 5);
        terrainView.set(store, row, new Terrain(50, 15.0, false));
        waterView.set(store, row, new Water(0.01, true));

        assertEquals(50,   terrainView.get(store, row).height());
        assertEquals(15.0, terrainView.get(store, row).temperature(), 0.01);
        assertFalse(terrainView.get(store, row).active());

        assertEquals(0.01, waterView.get(store, row).salinity(), 0.0001);
        assertTrue(waterView.get(store, row).frozen());
    }

    // -----------------------------------------------------------------------
    // 22. FastOctreeDataStore: RowView set triggers collapse

    @Test
    void fastOctreeRowViewSetTriggersCollapse() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(1)
                .component(Terrain.class)
                .build();
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    view.set(store, store.row(x, y, z), new Terrain(77, 0.0, false));

        assertEquals(1, store.nodeCount());
        assertEquals(77, view.get(store, store.row(1, 0, 1)).height());
    }

    // -----------------------------------------------------------------------
    // 23. FastOctreeDataStore: RowView get after expansion

    @Test
    void fastOctreeRowViewGetAfterExpansionPreservesNeighbours() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(2)
                .component(Terrain.class)
                .build();
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++)
                    view.set(store, store.row(x, y, z), new Terrain(5, 0.0, false));
        assertEquals(1, store.nodeCount());

        view.set(store, store.row(3, 3, 3), new Terrain(50, 0.0, false));
        assertEquals(50, view.get(store, store.row(3, 3, 3)).height());
        assertEquals(5,  view.get(store, store.row(0, 0, 0)).height());
    }

    // -----------------------------------------------------------------------
    // 24. FastOctreeDataStore: enum record RowView

    @Test
    void fastOctreeEnumRecordRowView() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4)
                .component(BiomeData.class, CollapsingFunction.never())
                .build();
        RowView<BiomeData> view = RowView.of(store, BiomeData.class);

        int row = store.row(1, 2, 3);
        view.set(store, row, new BiomeData(Biome.OCEAN, 10));
        BiomeData r = view.get(store, row);

        assertEquals(Biome.OCEAN, r.biome());
        assertEquals(10, r.fertility());
    }

    // -----------------------------------------------------------------------
    // 25. FastOctreeDataStore: RowView with batch mode

    @Test
    void fastOctreeRowViewWithBatchMode() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(1)
                .component(Terrain.class)
                .build();
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        store.beginBatch();
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    view.set(store, store.row(x, y, z), new Terrain(33, 0.0, true));
        assertTrue(store.nodeCount() > 1);
        store.endBatch();
        assertEquals(1, store.nodeCount());

        assertEquals(33, view.get(store, store.row(0, 1, 0)).height());
        assertTrue(view.get(store, store.row(0, 1, 0)).active());
    }

    // -----------------------------------------------------------------------
    // 26. FastOctreeDataStore: non-uniform builder with RowView

    @Test
    void fastOctreeNonUniformBuilderRowView() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(10, 10, 5)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        view.set(store, store.row(0, 0, 0), new Terrain(1, -50.0, false));
        view.set(store, store.row(9, 9, 4), new Terrain(255, 50.0, true));

        assertEquals(1,   view.get(store, store.row(0, 0, 0)).height());
        assertEquals(255, view.get(store, store.row(9, 9, 4)).height());
    }

    // -----------------------------------------------------------------------
    // 27. Accessors.rowViewInStore with OctreeDataStore

    @Test
    void accessorsRowViewInStoreWorksWithOctree() {
        OctreeDataStore store = OctreeDataStore.builder(3)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        RowView<Terrain> view = Accessors.rowViewInStore(store, Terrain.class);

        int row = store.row(4, 3, 2);
        view.set(store, row, new Terrain(128, 25.0, true));
        Terrain r = view.get(store, row);
        assertEquals(128,  r.height());
        assertEquals(25.0, r.temperature(), 0.01);
        assertTrue(r.active());
    }

    // -----------------------------------------------------------------------
    // 28. Accessors.rowViewInStore with FastOctreeDataStore

    @Test
    void accessorsRowViewInStoreWorksWithFastOctree() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(3)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        RowView<Terrain> view = Accessors.rowViewInStore(store, Terrain.class);

        int row = store.row(2, 4, 1);
        view.set(store, row, new Terrain(64, -25.0, false));
        Terrain r = view.get(store, row);
        assertEquals(64,    r.height());
        assertEquals(-25.0, r.temperature(), 0.01);
        assertFalse(r.active());
    }
}
