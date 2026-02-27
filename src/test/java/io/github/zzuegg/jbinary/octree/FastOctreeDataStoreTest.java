package io.github.zzuegg.jbinary.octree;

import io.github.zzuegg.jbinary.Accessors;
import io.github.zzuegg.jbinary.accessor.*;
import io.github.zzuegg.jbinary.annotation.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FastOctreeDataStore} mirroring {@link OctreeDataStoreTest}.
 */
class FastOctreeDataStoreTest {

    // ------------------------------------------------------------------ component definitions

    record Voxel(
            @BitField(min = 0, max = 15)    int material,
            @BitField(min = 0, max = 255)   int density
    ) {}

    record Terrain(
            @BitField(min = 0, max = 255)                          int height,
            @DecimalField(min = -50.0, max = 50.0, precision = 2)  double temperature,
            @BoolField                                              boolean active
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
    // Builder validation

    @Test
    void builderRejectsZeroMaxDepth() {
        assertThrows(IllegalArgumentException.class, () -> FastOctreeDataStore.builder(0));
    }

    @Test
    void builderRejectsMaxDepthAboveTen() {
        assertThrows(IllegalArgumentException.class, () -> FastOctreeDataStore.builder(11));
    }

    @Test
    void builderRejectsNoComponents() {
        assertThrows(IllegalArgumentException.class,
                () -> FastOctreeDataStore.builder(4).build());
    }

    @Test
    void builderRejectsDuplicateComponent() {
        var builder = FastOctreeDataStore.builder(4).component(Voxel.class);
        assertThrows(IllegalArgumentException.class, () -> builder.component(Voxel.class));
    }

    @Test
    void builderRejectsNullComponent() {
        assertThrows(NullPointerException.class,
                () -> FastOctreeDataStore.builder(4).component(null));
    }

    @Test
    void builderRejectsNullCollapsingFunction() {
        assertThrows(NullPointerException.class,
                () -> FastOctreeDataStore.builder(4).component(Voxel.class, null));
    }

    @Test
    void builderRejectsClassWithNoAnnotatedFields() {
        record NoAnno(int x) {}
        assertThrows(IllegalArgumentException.class,
                () -> FastOctreeDataStore.builder(4).component(NoAnno.class).build());
    }

    // -----------------------------------------------------------------------
    // Metadata

    @Test
    void maxDepthAndSideLengthReflectBuilder() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(6).component(Voxel.class).build();
        assertEquals(6, store.maxDepth());
        assertEquals(64, store.sideLength());
    }

    @Test
    void capacityEquals2To3TimeMaxDepth() {
        for (int d = 1; d <= 10; d++) {
            FastOctreeDataStore store = FastOctreeDataStore.builder(d).component(Voxel.class).build();
            assertEquals(1 << (3 * d), store.capacity());
        }
    }

    @Test
    void noNodesAllocatedBeforeAnyWrite() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(5).component(Voxel.class).build();
        assertEquals(0, store.nodeCount());
    }

    @Test
    void unregisteredComponentBitOffsetThrows() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4).component(Voxel.class).build();
        assertThrows(IllegalArgumentException.class,
                () -> store.componentBitOffset(Terrain.class));
    }

    // -----------------------------------------------------------------------
    // Coordinate bounds

    @Test
    void outOfBoundsWriteThrows() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(3).component(Voxel.class).build();
        assertThrows(IllegalArgumentException.class,
                () -> store.writeBitsAt(8, 0, 0, 0, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> store.writeBitsAt(-1, 0, 0, 0, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> store.writeBitsAt(0, 8, 0, 0, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> store.writeBitsAt(0, 0, -1, 0, 1, 0));
    }

    @Test
    void outOfBoundsRowCoordThrows() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(3).component(Voxel.class).build();
        assertThrows(IllegalArgumentException.class, () -> store.row(8, 0, 0));
    }

    // -----------------------------------------------------------------------
    // Default values

    @Test
    void unwrittenVoxelIntReturnsZero() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4).component(Voxel.class).build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");
        assertEquals(0, mat.get(store, store.row(5, 5, 5)));
    }

    @Test
    void unwrittenVoxelBoolReturnsFalse() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4).component(Terrain.class).build();
        BoolAccessor active = Accessors.boolFieldInStore(store, Terrain.class, "active");
        assertFalse(active.get(store, store.row(0, 0, 0)));
    }

    @Test
    void unwrittenVoxelDoubleReturnsMin() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4).component(Terrain.class).build();
        DoubleAccessor temp = Accessors.doubleFieldInStore(store, Terrain.class, "temperature");
        assertEquals(-50.0, temp.get(store, store.row(3, 3, 3)), 0.01);
    }

    @Test
    void unwrittenVoxelEnumReturnsFirstConstant() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4).component(BiomeData.class).build();
        EnumAccessor<Biome> biome = Accessors.enumFieldInStore(store, BiomeData.class, "biome");
        assertEquals(Biome.PLAINS, biome.get(store, store.row(1, 1, 1)));
    }

    // -----------------------------------------------------------------------
    // Round-trips

    @Test
    void intFieldRoundTrip() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4)
                .component(Voxel.class, CollapsingFunction.never())
                .build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");
        IntAccessor den = Accessors.intFieldInStore(store, Voxel.class, "density");

        mat.set(store, store.row(0, 0, 0), 7);
        den.set(store, store.row(0, 0, 0), 200);

        assertEquals(7,   mat.get(store, store.row(0, 0, 0)));
        assertEquals(200, den.get(store, store.row(0, 0, 0)));
    }

    @Test
    void decimalFieldRoundTrip() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        DoubleAccessor temp = Accessors.doubleFieldInStore(store, Terrain.class, "temperature");

        temp.set(store, store.row(1, 2, 3), 23.45);
        assertEquals(23.45, temp.get(store, store.row(1, 2, 3)), 0.01);

        temp.set(store, store.row(0, 0, 0), -50.0);
        assertEquals(-50.0, temp.get(store, store.row(0, 0, 0)), 0.01);
    }

    @Test
    void boolFieldRoundTrip() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4)
                .component(Terrain.class, CollapsingFunction.never())
                .build();
        BoolAccessor active = Accessors.boolFieldInStore(store, Terrain.class, "active");

        active.set(store, store.row(2, 3, 4), true);
        assertTrue(active.get(store, store.row(2, 3, 4)));

        active.set(store, store.row(2, 3, 4), false);
        assertFalse(active.get(store, store.row(2, 3, 4)));
    }

    @Test
    void enumFieldRoundTrip() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4)
                .component(BiomeData.class, CollapsingFunction.never())
                .build();
        EnumAccessor<Biome> biome = Accessors.enumFieldInStore(store, BiomeData.class, "biome");

        biome.set(store, store.row(3, 2, 1), Biome.OCEAN);
        assertEquals(Biome.OCEAN, biome.get(store, store.row(3, 2, 1)));
    }

    // -----------------------------------------------------------------------
    // Collapse

    @Test
    void eightIdenticalLeavesSiblingCollapseToParent() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(1)
                .component(Voxel.class)
                .build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    mat.set(store, store.row(x, y, z), 5);

        assertEquals(1, store.nodeCount());

        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    assertEquals(5, mat.get(store, store.row(x, y, z)));
    }

    @Test
    void differentValuesPreventCollapse() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(1).component(Voxel.class).build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    mat.set(store, store.row(x, y, z), x + y + z + 1); // all different

        assertEquals(8, store.nodeCount());
    }

    @Test
    void collapsePropagatesToRootWithDepth3() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(3)
                .component(Voxel.class)
                .build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        for (int x = 0; x < 8; x++)
            for (int y = 0; y < 8; y++)
                for (int z = 0; z < 8; z++)
                    mat.set(store, store.row(x, y, z), 9);

        assertEquals(1, store.nodeCount());
        assertEquals(9, mat.get(store, store.row(4, 4, 4)));
    }

    @Test
    void neverFunctionSuppressesAllCollapse() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(1)
                .component(Voxel.class, CollapsingFunction.never())
                .build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    mat.set(store, store.row(x, y, z), 5);

        assertEquals(8, store.nodeCount());
    }

    @Test
    void alwaysFunctionCollapsesDifferentValues() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(1)
                .component(Voxel.class, CollapsingFunction.always())
                .build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        int i = 0;
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    mat.set(store, store.row(x, y, z), i++);

        assertEquals(1, store.nodeCount());
    }

    // -----------------------------------------------------------------------
    // Ancestor expansion

    @Test
    void writingIntoCollapsedRegionExpandsAndPreservesNeighbours() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(2).component(Voxel.class).build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++)
                    mat.set(store, store.row(x, y, z), 3);
        assertEquals(1, store.nodeCount());

        mat.set(store, store.row(0, 0, 0), 7);

        assertEquals(7, mat.get(store, store.row(0, 0, 0)));
        assertEquals(3, mat.get(store, store.row(1, 0, 0)));
        assertEquals(3, mat.get(store, store.row(3, 3, 3)));
        assertTrue(store.nodeCount() > 1);
    }

    @Test
    void overwritingAllWithNewValueRecollapsesToOneNode() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(2).component(Voxel.class).build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++)
                    mat.set(store, store.row(x, y, z), 3);
        assertEquals(1, store.nodeCount());

        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++)
                    mat.set(store, store.row(x, y, z), 7);

        assertEquals(1, store.nodeCount());
        assertEquals(7, mat.get(store, store.row(2, 2, 2)));
    }

    // -----------------------------------------------------------------------
    // Row encoding

    @Test
    void rowMethodIsConsistentWithMortonDecode() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(6).component(Voxel.class).build();
        for (int x : new int[]{0, 1, 31, 63}) {
            for (int y : new int[]{0, 10, 63}) {
                for (int z : new int[]{0, 5, 63}) {
                    int row = store.row(x, y, z);
                    IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");
                    mat.set(store, row, (x + y + z) % 16);
                    assertEquals((x + y + z) % 16, mat.get(store, row));
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Serialization round-trip

    @Test
    void writeReadRoundTrip() throws Exception {
        FastOctreeDataStore store = FastOctreeDataStore.builder(3)
                .component(Voxel.class, CollapsingFunction.never())
                .build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        mat.set(store, store.row(0, 0, 0), 5);
        mat.set(store, store.row(7, 7, 7), 12);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        store.write(baos);

        FastOctreeDataStore store2 = FastOctreeDataStore.builder(3)
                .component(Voxel.class, CollapsingFunction.never())
                .build();
        store2.read(new ByteArrayInputStream(baos.toByteArray()));

        assertEquals(5,  mat.get(store2, store2.row(0, 0, 0)));
        assertEquals(12, mat.get(store2, store2.row(7, 7, 7)));
        assertEquals(0,  mat.get(store2, store2.row(3, 3, 3)));
    }

    // -----------------------------------------------------------------------
    // Batch mode

    @Test
    void batchWriteValuesReadBackCorrectly() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(2)
                .component(Voxel.class, CollapsingFunction.never())
                .build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        store.beginBatch();
        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++)
                    mat.set(store, store.row(x, y, z), (x + y + z) % 16);
        store.endBatch();

        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++)
                    assertEquals((x + y + z) % 16, mat.get(store, store.row(x, y, z)));
    }

    @Test
    void batchUniformFillCollapsesToOneNodeAfterEndBatch() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(1)
                .component(Voxel.class)
                .build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        store.beginBatch();
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    mat.set(store, store.row(x, y, z), 7);

        // Not yet collapsed during batch
        assertTrue(store.nodeCount() > 1);
        store.endBatch();
        // Collapsed after endBatch
        assertEquals(1, store.nodeCount());
        assertEquals(7, mat.get(store, store.row(0, 0, 0)));
    }

    @Test
    void endBatchWithoutBeginBatchIsNoOp() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(1)
                .component(Voxel.class)
                .build();
        assertDoesNotThrow(() -> store.endBatch());
    }

    // -----------------------------------------------------------------------
    // nodeCount correctness

    @Test
    void nodeCountIsZeroInitially() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(3).component(Voxel.class).build();
        assertEquals(0, store.nodeCount());
    }

    @Test
    void depth1OctreeHasCapacity8() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(1).component(Voxel.class).build();
        assertEquals(8, store.capacity());
        assertEquals(2, store.sideLength());
    }
}
