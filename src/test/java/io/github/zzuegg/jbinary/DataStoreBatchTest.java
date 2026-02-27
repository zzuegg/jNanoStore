package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.IntAccessor;
import io.github.zzuegg.jbinary.annotation.*;
import io.github.zzuegg.jbinary.octree.CollapsingFunction;
import io.github.zzuegg.jbinary.octree.FastOctreeDataStore;
import io.github.zzuegg.jbinary.octree.OctreeDataStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link DataStore#beginBatch()} / {@link DataStore#endBatch()} API
 * across all store implementations.
 */
class DataStoreBatchTest {

    record Terrain(
            @BitField(min = 0, max = 255) int height,
            @DecimalField(min = -50.0, max = 50.0, precision = 2) double temperature,
            @BoolField boolean active
    ) {}

    // -----------------------------------------------------------------------
    // 1. PackedDataStore: beginBatch/endBatch are no-ops; writes are immediately visible

    @Test
    void packedBatchWritesImmediatelyVisible() {
        DataStore store = DataStore.of(100, Terrain.class);
        IntAccessor heightAcc = Accessors.intFieldInStore(store, Terrain.class, "height");
        store.beginBatch();
        heightAcc.set(store, 5, 100);
        assertEquals(100, heightAcc.get(store, 5));   // visible before endBatch
        store.endBatch();
        assertEquals(100, heightAcc.get(store, 5));   // still visible after endBatch
    }

    // -----------------------------------------------------------------------
    // 2. SparseDataStore: same

    @Test
    void sparseBatchWritesImmediatelyVisible() {
        DataStore store = DataStore.sparse(100, Terrain.class);
        IntAccessor heightAcc = Accessors.intFieldInStore(store, Terrain.class, "height");
        store.beginBatch();
        heightAcc.set(store, 5, 100);
        assertEquals(100, heightAcc.get(store, 5));
        store.endBatch();
        assertEquals(100, heightAcc.get(store, 5));
    }

    // -----------------------------------------------------------------------
    // 3. OctreeDataStore: batch writes are visible after endBatch

    @Test
    void octreeBatchWritesVisibleAfterEndBatch() {
        OctreeDataStore store = OctreeDataStore.builder(2)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        IntAccessor heightAcc = Accessors.intFieldInStore(store, Terrain.class, "height");
        store.beginBatch();
        heightAcc.set(store, store.row(0, 0, 0), 42);
        assertEquals(42, heightAcc.get(store, store.row(0, 0, 0)));
        store.endBatch();
        assertEquals(42, heightAcc.get(store, store.row(0, 0, 0)));
    }

    // -----------------------------------------------------------------------
    // 4. OctreeDataStore: batch uniform fill collapses to 1 node after endBatch, not before

    @Test
    void octreeBatchUniformFillCollapsesAfterEndBatch() {
        OctreeDataStore store = OctreeDataStore.builder(1)
                .component(Terrain.class)
                .build();
        IntAccessor heightAcc = Accessors.intFieldInStore(store, Terrain.class, "height");
        store.beginBatch();
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    heightAcc.set(store, store.row(x, y, z), 99);
        // No collapse yet during batch
        assertTrue(store.nodeCount() > 1);
        store.endBatch();
        // Collapsed after endBatch
        assertEquals(1, store.nodeCount());
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    assertEquals(99, heightAcc.get(store, store.row(x, y, z)));
    }

    // -----------------------------------------------------------------------
    // 5. OctreeDataStore: nested beginBatch calls are idempotent

    @Test
    void octreeNestedBeginBatchIsIdempotent() {
        OctreeDataStore store = OctreeDataStore.builder(1)
                .component(Terrain.class)
                .build();
        IntAccessor heightAcc = Accessors.intFieldInStore(store, Terrain.class, "height");
        store.beginBatch();
        store.beginBatch();  // second call is a no-op
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    heightAcc.set(store, store.row(x, y, z), 77);
        store.endBatch();  // single endBatch works
        assertEquals(1, store.nodeCount());
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    assertEquals(77, heightAcc.get(store, store.row(x, y, z)));
    }

    // -----------------------------------------------------------------------
    // 6. FastOctreeDataStore: batch uniform fill collapses after endBatch

    @Test
    void fastOctreeBatchUniformFillCollapsesAfterEndBatch() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(1)
                .component(Terrain.class)
                .build();
        IntAccessor heightAcc = Accessors.intFieldInStore(store, Terrain.class, "height");
        store.beginBatch();
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    heightAcc.set(store, store.row(x, y, z), 55);
        assertTrue(store.nodeCount() > 1);
        store.endBatch();
        assertEquals(1, store.nodeCount());
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    assertEquals(55, heightAcc.get(store, store.row(x, y, z)));
    }

    // -----------------------------------------------------------------------
    // 7. FastOctreeDataStore: partial fill in batch mode collapses only qualifying groups

    @Test
    void fastOctreeBatchPartialFillCollapsesQualifyingGroups() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(2)  // 4×4×4
                .component(Terrain.class)
                .build();
        IntAccessor heightAcc = Accessors.intFieldInStore(store, Terrain.class, "height");
        store.beginBatch();
        // Fill only one octant (0-1, 0-1, 0-1) uniformly
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    heightAcc.set(store, store.row(x, y, z), 33);
        store.endBatch();
        // Values still correct
        assertEquals(33, heightAcc.get(store, store.row(0, 0, 0)));
        assertEquals(33, heightAcc.get(store, store.row(1, 1, 1)));
        // Unwritten area still reads default
        assertEquals(0, heightAcc.get(store, store.row(3, 3, 3)));
    }

    // -----------------------------------------------------------------------
    // 8. FastOctreeDataStore: calling endBatch without beginBatch is a no-op

    @Test
    void fastOctreeEndBatchWithoutBeginBatchIsNoOp() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(1)
                .component(Terrain.class)
                .build();
        assertDoesNotThrow(() -> store.endBatch());
    }
}
