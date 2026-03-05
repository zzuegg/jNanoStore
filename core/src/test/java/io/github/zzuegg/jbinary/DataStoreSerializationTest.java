package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.*;
import io.github.zzuegg.jbinary.annotation.*;
import io.github.zzuegg.jbinary.octree.CollapsingFunction;
import io.github.zzuegg.jbinary.octree.OctreeDataStore;
import org.junit.jupiter.api.Test;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip serialization tests for PackedDataStore, SparseDataStore, and OctreeDataStore.
 */
class DataStoreSerializationTest {

    // ------------------------------------------------------------------ shared records

    record Terrain(
            @BitField(min = 0, max = 255) int height,
            @DecimalField(min = -50.0, max = 50.0, precision = 2) double temperature,
            @BoolField boolean active
    ) {}

    record Water(
            @DecimalField(min = 0.0, max = 1.0, precision = 4) double salinity,
            @BoolField boolean frozen
    ) {}

    // ------------------------------------------------------------------ helpers

    private static byte[] serialize(DataStore store) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        store.write(baos);
        return baos.toByteArray();
    }

    private static void deserialize(DataStore store, byte[] bytes) throws IOException {
        store.read(new ByteArrayInputStream(bytes));
    }

    // ================================================================ PackedDataStore tests

    @Test
    void packed_roundTrip_singleComponent() throws IOException {
        DataStore original = DataStore.of(100, Terrain.class);
        IntAccessor    height = Accessors.intFieldInStore(original, Terrain.class, "height");
        DoubleAccessor temp   = Accessors.doubleFieldInStore(original, Terrain.class, "temperature");
        BoolAccessor   active = Accessors.boolFieldInStore(original, Terrain.class, "active");

        for (int i = 0; i < 100; i++) {
            height.set(original, i, i % 256);
            temp.set(original, i, (i % 100) - 50.0);
            active.set(original, i, (i & 1) == 0);
        }

        byte[] bytes = serialize(original);

        DataStore restored = DataStore.of(100, Terrain.class);
        deserialize(restored, bytes);

        IntAccessor    rh = Accessors.intFieldInStore(restored, Terrain.class, "height");
        DoubleAccessor rt = Accessors.doubleFieldInStore(restored, Terrain.class, "temperature");
        BoolAccessor   ra = Accessors.boolFieldInStore(restored, Terrain.class, "active");

        for (int i = 0; i < 100; i++) {
            assertEquals(i % 256,            rh.get(restored, i), "height at row " + i);
            assertEquals((i % 100) - 50.0,   rt.get(restored, i), 0.01, "temp at row " + i);
            assertEquals((i & 1) == 0,       ra.get(restored, i), "active at row " + i);
        }
    }

    @Test
    void packed_roundTrip_multiComponent() throws IOException {
        DataStore original = DataStore.of(50, Terrain.class, Water.class);
        IntAccessor    height   = Accessors.intFieldInStore(original, Terrain.class, "height");
        DoubleAccessor salinity = Accessors.doubleFieldInStore(original, Water.class, "salinity");

        height.set(original, 10, 200);
        salinity.set(original, 10, 0.5);

        byte[] bytes = serialize(original);
        DataStore restored = DataStore.of(50, Terrain.class, Water.class);
        deserialize(restored, bytes);

        assertEquals(200, Accessors.intFieldInStore(restored, Terrain.class, "height").get(restored, 10));
        assertEquals(0.5, Accessors.doubleFieldInStore(restored, Water.class, "salinity").get(restored, 10), 0.0001);
    }

    @Test
    void packed_write_doesNotCloseStream() throws IOException {
        DataStore store = DataStore.of(10, Terrain.class);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        store.write(baos);
        // stream still usable after write
        baos.write(42);
        assertTrue(baos.size() > 1);
    }

    @Test
    void packed_read_wrongMagicThrows() {
        DataStore store = DataStore.of(10, Terrain.class);
        byte[] garbage = new byte[]{0, 0, 0, 0, 0, 0, 0, 0};
        assertThrows(IOException.class, () -> deserialize(store, garbage));
    }

    @Test
    void packed_read_wrongTypeThrows() throws IOException {
        // Write as sparse, try to read into packed
        DataStore sparse  = DataStore.sparse(10, Terrain.class);
        byte[] sparseBytes = serialize(sparse);
        DataStore packed = DataStore.of(10, Terrain.class);
        assertThrows(IOException.class, () -> deserialize(packed, sparseBytes));
    }

    @Test
    void packed_read_capacityMismatchThrows() throws IOException {
        DataStore small = DataStore.of(10, Terrain.class);
        byte[] bytes    = serialize(small);
        DataStore large = DataStore.of(20, Terrain.class);
        assertThrows(IllegalArgumentException.class, () -> deserialize(large, bytes));
    }

    // ================================================================ SparseDataStore tests

    @Test
    void sparse_roundTrip_partiallyPopulated() throws IOException {
        DataStore original = DataStore.sparse(1000, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(original, Terrain.class, "height");
        BoolAccessor active = Accessors.boolFieldInStore(original, Terrain.class, "active");

        // Write only 10 rows (every 10th index up to 100)
        for (int i = 0; i < 100; i += 10) {
            height.set(original, i, i);
            active.set(original, i, true);
        }

        byte[] bytes = serialize(original);
        DataStore restored = DataStore.sparse(1000, Terrain.class);
        deserialize(restored, bytes);

        IntAccessor  rh = Accessors.intFieldInStore(restored, Terrain.class, "height");
        BoolAccessor ra = Accessors.boolFieldInStore(restored, Terrain.class, "active");

        for (int i = 0; i < 100; i += 10) {
            assertEquals(i,    rh.get(restored, i), "height at row " + i);
            assertTrue(ra.get(restored, i), "active at row " + i);
        }
        // Unwritten rows still zero
        assertEquals(0, rh.get(restored, 1));
        assertFalse(ra.get(restored, 1));
    }

    @Test
    void sparse_roundTrip_emptyStore() throws IOException {
        DataStore original = DataStore.sparse(100, Terrain.class);
        byte[] bytes = serialize(original);
        DataStore restored = DataStore.sparse(100, Terrain.class);
        deserialize(restored, bytes);
        assertEquals(0, ((SparseDataStore) restored).allocatedRowCount());
    }

    @Test
    void sparse_read_doesNotLeakPreviousContent() throws IOException {
        DataStore first = DataStore.sparse(100, Terrain.class);
        IntAccessor h = Accessors.intFieldInStore(first, Terrain.class, "height");
        h.set(first, 5, 200);

        // Serialize empty store and read into first
        DataStore empty = DataStore.sparse(100, Terrain.class);
        byte[] emptyBytes = serialize(empty);
        deserialize(first, emptyBytes);

        // Row 5 must now read back as 0 (cleared)
        assertEquals(0, h.get(first, 5));
    }

    @Test
    void sparse_read_wrongTypeThrows() throws IOException {
        DataStore packed = DataStore.of(10, Terrain.class);
        byte[] packedBytes = serialize(packed);
        DataStore sparse = DataStore.sparse(10, Terrain.class);
        assertThrows(IOException.class, () -> deserialize(sparse, packedBytes));
    }

    @Test
    void sparse_read_capacityMismatchThrows() throws IOException {
        DataStore small = DataStore.sparse(10, Terrain.class);
        byte[] bytes    = serialize(small);
        DataStore large = DataStore.sparse(20, Terrain.class);
        assertThrows(IllegalArgumentException.class, () -> deserialize(large, bytes));
    }

    // ================================================================ OctreeDataStore tests

    @Test
    void octree_roundTrip_singleComponent() throws IOException {
        OctreeDataStore original = OctreeDataStore.builder(3)   // 8×8×8
                .component(Terrain.class)
                .build();
        IntAccessor height = Accessors.intFieldInStore(original, Terrain.class, "height");
        BoolAccessor active = Accessors.boolFieldInStore(original, Terrain.class, "active");

        height.set(original, original.row(1, 2, 3), 100);
        active.set(original, original.row(5, 6, 7), true);

        byte[] bytes = serialize(original);

        OctreeDataStore restored = OctreeDataStore.builder(3)
                .component(Terrain.class)
                .build();
        deserialize(restored, bytes);

        IntAccessor  rh = Accessors.intFieldInStore(restored, Terrain.class, "height");
        BoolAccessor ra = Accessors.boolFieldInStore(restored, Terrain.class, "active");

        assertEquals(100,  rh.get(restored, restored.row(1, 2, 3)));
        assertTrue(ra.get(restored, restored.row(5, 6, 7)));
        assertEquals(0, rh.get(restored, restored.row(0, 0, 0)));
    }

    @Test
    void octree_roundTrip_preservesNodeCount() throws IOException {
        OctreeDataStore original = OctreeDataStore.builder(4)   // 16×16×16
                .component(Terrain.class)
                .build();
        IntAccessor height = Accessors.intFieldInStore(original, Terrain.class, "height");
        height.set(original, original.row(3, 3, 3), 77);
        height.set(original, original.row(7, 7, 7), 88);

        int nodesBefore = original.nodeCount();
        byte[] bytes = serialize(original);

        OctreeDataStore restored = OctreeDataStore.builder(4)
                .component(Terrain.class)
                .build();
        deserialize(restored, bytes);

        assertEquals(nodesBefore, restored.nodeCount());
    }

    @Test
    void octree_roundTrip_collapsedRegionSurvivesRoundTrip() throws IOException {
        OctreeDataStore original = OctreeDataStore.builder(2)   // 4×4×4
                .component(Terrain.class)
                .build();
        IntAccessor height = Accessors.intFieldInStore(original, Terrain.class, "height");
        // Fill entire space with value 5 — should collapse to 1 node
        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++)
                    height.set(original, original.row(x, y, z), 5);
        assertEquals(1, original.nodeCount());

        byte[] bytes = serialize(original);
        OctreeDataStore restored = OctreeDataStore.builder(2)
                .component(Terrain.class)
                .build();
        deserialize(restored, bytes);

        assertEquals(1, restored.nodeCount());
        IntAccessor rh = Accessors.intFieldInStore(restored, Terrain.class, "height");
        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++)
                    assertEquals(5, rh.get(restored, restored.row(x, y, z)),
                            "height at (" + x + "," + y + "," + z + ")");
    }

    @Test
    void octree_read_wrongTypeThrows() throws IOException {
        DataStore packed = DataStore.of(64, Terrain.class);
        byte[] packedBytes = serialize(packed);
        OctreeDataStore octree = OctreeDataStore.builder(2).component(Terrain.class).build();
        assertThrows(IOException.class, () -> deserialize(octree, packedBytes));
    }

    @Test
    void octree_read_maxDepthMismatchThrows() throws IOException {
        OctreeDataStore small = OctreeDataStore.builder(2).component(Terrain.class).build();
        byte[] bytes = serialize(small);
        OctreeDataStore large = OctreeDataStore.builder(3).component(Terrain.class).build();
        assertThrows(IllegalArgumentException.class, () -> deserialize(large, bytes));
    }

    @Test
    void octree_read_doesNotLeakPreviousContent() throws IOException {
        OctreeDataStore store = OctreeDataStore.builder(2).component(Terrain.class).build();
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");
        height.set(store, store.row(1, 1, 1), 99);
        assertTrue(store.nodeCount() > 0);

        // Read an empty octree into it
        OctreeDataStore empty = OctreeDataStore.builder(2).component(Terrain.class).build();
        byte[] emptyBytes = serialize(empty);
        deserialize(store, emptyBytes);

        assertEquals(0, store.nodeCount());
        assertEquals(0, height.get(store, store.row(1, 1, 1)));
    }
}
