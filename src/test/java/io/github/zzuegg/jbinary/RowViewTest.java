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
}
