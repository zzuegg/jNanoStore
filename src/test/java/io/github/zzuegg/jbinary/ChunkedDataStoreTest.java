package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.BoolAccessor;
import io.github.zzuegg.jbinary.accessor.DoubleAccessor;
import io.github.zzuegg.jbinary.accessor.IntAccessor;
import io.github.zzuegg.jbinary.annotation.BitField;
import io.github.zzuegg.jbinary.annotation.BoolField;
import io.github.zzuegg.jbinary.annotation.DecimalField;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ChunkedDataStoreTest {

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
    // Basic read/write

    @Test
    void singleVoxelRoundTrip() {
        ChunkedDataStore<Terrain> store = DataStore.chunked(32, 32, 32, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");

        int row = store.row(5, 3, 7);
        height.set(store, row, 200);
        assertEquals(200, height.get(store, row));
    }

    @Test
    void unwrittenVoxelReadsZero() {
        ChunkedDataStore<Terrain> store = DataStore.chunked(32, 32, 32, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");

        // row was never written — must read as 0 (field minimum)
        int row = store.row(10, 10, 10);
        assertEquals(0, height.get(store, row));
        assertEquals(0, store.allocatedChunkCount());
    }

    @Test
    void allFieldTypesRoundTrip() {
        ChunkedDataStore<Terrain> store = DataStore.chunked(16, 16, 16, Terrain.class);
        IntAccessor    height = Accessors.intFieldInStore(store, Terrain.class, "height");
        DoubleAccessor temp   = Accessors.doubleFieldInStore(store, Terrain.class, "temperature");
        BoolAccessor   active = Accessors.boolFieldInStore(store, Terrain.class, "active");

        int row = store.row(0, 0, 0);
        height.set(store, row, 123);
        temp.set(store, row, 37.5);
        active.set(store, row, true);

        assertEquals(123,  height.get(store, row));
        assertEquals(37.5, temp.get(store, row), 0.01);
        assertTrue(active.get(store, row));
    }

    @Test
    void multipleVoxelsAcrossMultipleChunks() {
        // World 64x64x64: chunks are 16x16x16 → 4x4x4 = 64 possible chunks
        ChunkedDataStore<Terrain> store = DataStore.chunked(64, 64, 64, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");

        // Write voxels in 4 different chunks
        int r0  = store.row(0,  0,  0);   // chunk (0,0,0)
        int r1  = store.row(16, 0,  0);   // chunk (1,0,0)
        int r2  = store.row(0,  16, 0);   // chunk (0,1,0)
        int r3  = store.row(0,  0,  16);  // chunk (0,0,1)

        height.set(store, r0, 10);
        height.set(store, r1, 20);
        height.set(store, r2, 30);
        height.set(store, r3, 40);

        assertEquals(10, height.get(store, r0));
        assertEquals(20, height.get(store, r1));
        assertEquals(30, height.get(store, r2));
        assertEquals(40, height.get(store, r3));
        assertEquals(4,  store.allocatedChunkCount());
    }

    @Test
    void allVoxelsInSingleChunk() {
        ChunkedDataStore<Terrain> store = DataStore.chunked(16, 16, 16, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");

        // Fill all 4096 voxels in the single chunk
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    height.set(store, store.row(x, y, z), (x + y + z) % 256);

        // Verify
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    assertEquals((x + y + z) % 256, height.get(store, store.row(x, y, z)));

        assertEquals(1, store.allocatedChunkCount());
    }

    // -----------------------------------------------------------------------
    // Multi-component

    @Test
    void multiComponentRoundTrip() {
        ChunkedDataStore<?> store = DataStore.chunked(32, 32, 32, Terrain.class, Water.class);
        IntAccessor    height   = Accessors.intFieldInStore(store, Terrain.class, "height");
        DoubleAccessor salinity = Accessors.doubleFieldInStore(store, Water.class, "salinity");

        int row = store.row(5, 5, 5);
        height.set(store, row, 100);
        salinity.set(store, row, 0.035);

        assertEquals(100,   height.get(store, row));
        assertEquals(0.035, salinity.get(store, row), 0.001);
    }

    // -----------------------------------------------------------------------
    // RowView

    @Test
    void rowViewRoundTrip() {
        ChunkedDataStore<Terrain> store = DataStore.chunked(16, 16, 16, Terrain.class);
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        int row = store.row(3, 3, 3);
        view.set(store, row, new Terrain(77, 25.0, true));

        Terrain t = view.get(store, row);
        assertEquals(77,   t.height());
        assertEquals(25.0, t.temperature(), 0.01);
        assertTrue(t.active());
    }

    // -----------------------------------------------------------------------
    // Bounds checking

    @Test
    void outOfBoundsThrows() {
        ChunkedDataStore<Terrain> store = DataStore.chunked(16, 16, 16, Terrain.class);
        assertThrows(IllegalArgumentException.class, () -> store.row(16, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> store.row(0, 16, 0));
        assertThrows(IllegalArgumentException.class, () -> store.row(0, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> store.row(-1, 0, 0));
    }

    // -----------------------------------------------------------------------
    // Capacity and chunk size

    @Test
    void capacityAndChunkSize() {
        ChunkedDataStore<Terrain> store = DataStore.chunked(32, 16, 8, Terrain.class);
        assertEquals(32 * 16 * 8, store.capacity());
        assertEquals(16, store.chunkSize());
        assertEquals(ChunkedDataStore.CHUNK_VOXELS, 4096);
    }

    // -----------------------------------------------------------------------
    // Serialisation

    @Test
    void serializationRoundTrip() throws IOException {
        ChunkedDataStore<Terrain> store = DataStore.chunked(32, 32, 32, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");

        height.set(store, store.row(0, 0, 0), 42);
        height.set(store, store.row(16, 0, 0), 99);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        store.write(baos);
        byte[] bytes = baos.toByteArray();

        ChunkedDataStore<Terrain> restored = DataStore.chunked(32, 32, 32, Terrain.class);
        restored.read(new ByteArrayInputStream(bytes));

        assertEquals(42, Accessors.intFieldInStore(restored, Terrain.class, "height")
                .get(restored, restored.row(0, 0, 0)));
        assertEquals(99, Accessors.intFieldInStore(restored, Terrain.class, "height")
                .get(restored, restored.row(16, 0, 0)));
    }

    // -----------------------------------------------------------------------
    // Factory validation

    @Test
    void invalidDimensionsThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> DataStore.chunked(0, 16, 16, Terrain.class));
        assertThrows(IllegalArgumentException.class,
                () -> DataStore.chunked(16, -1, 16, Terrain.class));
    }

    @Test
    void missingComponentThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> DataStore.chunked(16, 16, 16));
    }
}
