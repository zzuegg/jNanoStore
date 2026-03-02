package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.IntAccessor;
import io.github.zzuegg.jbinary.annotation.*;
import io.github.zzuegg.jbinary.octree.OctreeDataStore;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link DataStore#write(ByteBuffer)} and {@link DataStore#read(ByteBuffer)}
 * default methods.
 */
class ByteBufferSerializationTest {

    record Terrain(
            @BitField(min = 0, max = 255) int height,
            @DecimalField(min = -50.0, max = 50.0, precision = 2) double temperature,
            @BoolField boolean active
    ) {}

    // ------------------------------------------------------------------ helpers

    private static ByteBuffer serialize(DataStore<?> store) throws IOException {
        // Allocate a generous buffer
        ByteBuffer buf = ByteBuffer.allocate(4096);
        store.write(buf);
        buf.flip();
        return buf;
    }

    // ------------------------------------------------------------------ PackedDataStore

    @Test
    void packed_byteBuffer_roundTrip() throws IOException {
        DataStore<Terrain> original = DataStore.of(50, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(original, Terrain.class, "height");
        for (int i = 0; i < 50; i++) height.set(original, i, i % 256);

        ByteBuffer buf = serialize(original);

        DataStore<Terrain> restored = DataStore.of(50, Terrain.class);
        restored.read(buf);

        IntAccessor rh = Accessors.intFieldInStore(restored, Terrain.class, "height");
        for (int i = 0; i < 50; i++) assertEquals(i % 256, rh.get(restored, i));
    }

    @Test
    void packed_byteBuffer_bufferPositionAdvances() throws IOException {
        DataStore<Terrain> store = DataStore.of(10, Terrain.class);
        ByteBuffer buf = ByteBuffer.allocate(4096);
        int before = buf.position();
        store.write(buf);
        assertTrue(buf.position() > before, "position should advance after write");
    }

    @Test
    void packed_byteBuffer_wrongMagicThrows() {
        DataStore<Terrain> store = DataStore.of(10, Terrain.class);
        ByteBuffer bad = ByteBuffer.wrap(new byte[16]);
        assertThrows(IOException.class, () -> store.read(bad));
    }

    // ------------------------------------------------------------------ SparseDataStore

    @Test
    void sparse_byteBuffer_roundTrip() throws IOException {
        DataStore<Terrain> original = DataStore.sparse(200, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(original, Terrain.class, "height");
        height.set(original, 50, 123);
        height.set(original, 150, 200);

        ByteBuffer buf = serialize(original);

        DataStore<Terrain> restored = DataStore.sparse(200, Terrain.class);
        restored.read(buf);

        IntAccessor rh = Accessors.intFieldInStore(restored, Terrain.class, "height");
        assertEquals(123, rh.get(restored, 50));
        assertEquals(200, rh.get(restored, 150));
        assertEquals(0,   rh.get(restored, 0));
    }

    // ------------------------------------------------------------------ RawDataStore

    @Test
    void raw_byteBuffer_roundTrip() throws IOException {
        RawDataStore<Terrain> original = DataStore.raw(20, Terrain.class);
        original.intAccessor(Terrain.class, "height").set(original, 5, 77);

        ByteBuffer buf = serialize(original);

        RawDataStore<Terrain> restored = DataStore.raw(20, Terrain.class);
        restored.read(buf);

        assertEquals(77, restored.intAccessor(Terrain.class, "height").get(restored, 5));
    }

    // ------------------------------------------------------------------ OctreeDataStore

    @Test
    void octree_byteBuffer_roundTrip() throws IOException {
        OctreeDataStore original = OctreeDataStore.builder(2)
                .component(Terrain.class)
                .build();
        Accessors.intFieldInStore(original, Terrain.class, "height")
                .set(original, original.row(1, 2, 3), 55);

        ByteBuffer buf = serialize(original);

        OctreeDataStore restored = OctreeDataStore.builder(2)
                .component(Terrain.class)
                .build();
        restored.read(buf);

        assertEquals(55,
                Accessors.intFieldInStore(restored, Terrain.class, "height")
                        .get(restored, restored.row(1, 2, 3)));
    }

    // ------------------------------------------------------------------ direct buffer

    @Test
    void packed_directByteBuffer_roundTrip() throws IOException {
        DataStore<Terrain> original = DataStore.of(10, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(original, Terrain.class, "height");
        height.set(original, 0, 42);

        ByteBuffer direct = ByteBuffer.allocateDirect(4096);
        original.write(direct);
        direct.flip();

        DataStore<Terrain> restored = DataStore.of(10, Terrain.class);
        restored.read(direct);

        assertEquals(42, Accessors.intFieldInStore(restored, Terrain.class, "height").get(restored, 0));
    }
}
