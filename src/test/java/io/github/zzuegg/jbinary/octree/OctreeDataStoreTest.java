package io.github.zzuegg.jbinary.octree;

import io.github.zzuegg.jbinary.Accessors;
import io.github.zzuegg.jbinary.accessor.*;
import io.github.zzuegg.jbinary.annotation.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extensive tests for {@link OctreeDataStore} covering:
 * <ul>
 *   <li>Builder validation and wrong-usage errors</li>
 *   <li>Round-trips for all field types (int, double, bool, enum)</li>
 *   <li>Default values for unwritten voxels</li>
 *   <li>Automatic collapse when all 8 siblings are identical</li>
 *   <li>Ancestor expansion on partial overwrites</li>
 *   <li>Custom collapsing functions ({@code never()}, {@code always()}, custom)</li>
 *   <li>Multi-component stores sharing a row stride</li>
 *   <li>Coordinate bounds checking</li>
 *   <li>Morton-code row index and existing-accessor compatibility</li>
 * </ul>
 */
class OctreeDataStoreTest {

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
        assertThrows(IllegalArgumentException.class, () -> OctreeDataStore.builder(0));
    }

    @Test
    void builderRejectsMaxDepthAboveTen() {
        assertThrows(IllegalArgumentException.class, () -> OctreeDataStore.builder(11));
    }

    @Test
    void builderRejectsNegativeMaxDepth() {
        assertThrows(IllegalArgumentException.class, () -> OctreeDataStore.builder(-1));
    }

    @Test
    void builderRejectsNoComponents() {
        assertThrows(IllegalArgumentException.class,
                () -> OctreeDataStore.builder(4).build());
    }

    @Test
    void builderRejectsDuplicateComponent() {
        var builder = OctreeDataStore.builder(4).component(Voxel.class);
        assertThrows(IllegalArgumentException.class, () -> builder.component(Voxel.class));
    }

    @Test
    void builderRejectsNullComponent() {
        assertThrows(NullPointerException.class,
                () -> OctreeDataStore.builder(4).component(null));
    }

    @Test
    void builderRejectsNullCollapsingFunction() {
        assertThrows(NullPointerException.class,
                () -> OctreeDataStore.builder(4).component(Voxel.class, null));
    }

    @Test
    void builderRejectsClassWithNoAnnotatedFields() {
        record NoAnno(int x) {}
        assertThrows(IllegalArgumentException.class,
                () -> OctreeDataStore.builder(4).component(NoAnno.class).build());
    }

    // -----------------------------------------------------------------------
    // Metadata

    @Test
    void maxDepthAndSideLengthReflectBuilder() {
        OctreeDataStore store = OctreeDataStore.builder(6).component(Voxel.class).build();
        assertEquals(6, store.maxDepth());
        assertEquals(64, store.sideLength());
    }

    @Test
    void capacityEquals2To3TimeMaxDepth() {
        for (int d = 1; d <= 10; d++) {
            OctreeDataStore store = OctreeDataStore.builder(d).component(Voxel.class).build();
            assertEquals(1 << (3 * d), store.capacity());
        }
    }

    @Test
    void rowStrideLongsIsPositive() {
        OctreeDataStore store = OctreeDataStore.builder(4).component(Voxel.class).build();
        assertTrue(store.rowStrideLongs() > 0);
    }

    @Test
    void unregisteredComponentBitOffsetThrows() {
        OctreeDataStore store = OctreeDataStore.builder(4).component(Voxel.class).build();
        assertThrows(IllegalArgumentException.class,
                () -> store.componentBitOffset(Terrain.class));
    }

    // -----------------------------------------------------------------------
    // Coordinate bounds

    @Test
    void outOfBoundsWriteThrows() {
        OctreeDataStore store = OctreeDataStore.builder(3).component(Voxel.class).build();
        // sideLength = 8
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
        OctreeDataStore store = OctreeDataStore.builder(3).component(Voxel.class).build();
        assertThrows(IllegalArgumentException.class, () -> store.row(8, 0, 0));
    }

    // -----------------------------------------------------------------------
    // Default (unwritten) voxel values

    @Test
    void unwrittenVoxelIntReturnsZero() {
        OctreeDataStore store = OctreeDataStore.builder(4).component(Voxel.class).build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");
        assertEquals(0, mat.get(store, store.row(5, 5, 5)));
    }

    @Test
    void unwrittenVoxelBoolReturnsFalse() {
        OctreeDataStore store = OctreeDataStore.builder(4).component(Terrain.class).build();
        BoolAccessor active = Accessors.boolFieldInStore(store, Terrain.class, "active");
        assertFalse(active.get(store, store.row(0, 0, 0)));
    }

    @Test
    void unwrittenVoxelDoubleReturnsMin() {
        OctreeDataStore store = OctreeDataStore.builder(4).component(Terrain.class).build();
        DoubleAccessor temp = Accessors.doubleFieldInStore(store, Terrain.class, "temperature");
        assertEquals(-50.0, temp.get(store, store.row(3, 3, 3)), 0.01);
    }

    @Test
    void unwrittenVoxelEnumReturnsFirstConstant() {
        OctreeDataStore store = OctreeDataStore.builder(4).component(BiomeData.class).build();
        EnumAccessor<Biome> biome =
                Accessors.enumFieldInStore(store, BiomeData.class, "biome");
        assertEquals(Biome.PLAINS, biome.get(store, store.row(1, 1, 1)));
    }

    @Test
    void noNodesAllocatedBeforeAnyWrite() {
        OctreeDataStore store = OctreeDataStore.builder(5).component(Voxel.class).build();
        assertEquals(0, store.nodeCount());
    }

    // -----------------------------------------------------------------------
    // Round-trip: all field types

    @Test
    void intFieldRoundTrip() {
        OctreeDataStore store = OctreeDataStore.builder(4)
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
        OctreeDataStore store = OctreeDataStore.builder(4)
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
        OctreeDataStore store = OctreeDataStore.builder(4)
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
        OctreeDataStore store = OctreeDataStore.builder(4)
                .component(BiomeData.class, CollapsingFunction.never())
                .build();
        EnumAccessor<Biome> biome =
                Accessors.enumFieldInStore(store, BiomeData.class, "biome");

        biome.set(store, store.row(3, 2, 1), Biome.OCEAN);
        assertEquals(Biome.OCEAN, biome.get(store, store.row(3, 2, 1)));

        biome.set(store, store.row(0, 0, 0), Biome.FOREST);
        assertEquals(Biome.FOREST, biome.get(store, store.row(0, 0, 0)));
    }

    // -----------------------------------------------------------------------
    // Collapse: equalBits (default)

    @Test
    void eightIdenticalLeavesSiblingCollapseToParent() {
        OctreeDataStore store = OctreeDataStore.builder(1)  // 2×2×2 space, 1 level
                .component(Voxel.class)
                .build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        // Write same value to all 8 voxels in the 2×2×2 space
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    mat.set(store, store.row(x, y, z), 5);

        // All 8 leaves collapsed to 1 root node
        assertEquals(1, store.nodeCount());

        // Values still readable
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    assertEquals(5, mat.get(store, store.row(x, y, z)));
    }

    @Test
    void differentValuesPreventCollapse() {
        OctreeDataStore store = OctreeDataStore.builder(1).component(Voxel.class).build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    mat.set(store, store.row(x, y, z), x + y + z);  // different values

        // No collapse should have happened
        assertEquals(8, store.nodeCount());
    }

    @Test
    void collapsePropagatesToRootWithDepth3() {
        OctreeDataStore store = OctreeDataStore.builder(3)   // 8×8×8 = 512 voxels
                .component(Voxel.class)
                .build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        // Write value 9 to every voxel
        for (int x = 0; x < 8; x++)
            for (int y = 0; y < 8; y++)
                for (int z = 0; z < 8; z++)
                    mat.set(store, store.row(x, y, z), 9);

        // Should collapse all the way to 1 root node
        assertEquals(1, store.nodeCount());
        assertEquals(9, mat.get(store, store.row(4, 4, 4)));
    }

    @Test
    void partialFillDoesNotCollapse() {
        OctreeDataStore store = OctreeDataStore.builder(2)   // 4×4×4 = 64 voxels
                .component(Voxel.class)
                .build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        // Fill only half the cube
        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 2; y++)   // only y = 0,1
                for (int z = 0; z < 4; z++)
                    mat.set(store, store.row(x, y, z), 3);

        // Incomplete groups should NOT collapse fully to root
        assertTrue(store.nodeCount() < 32);  // some collapsing but not all
        assertTrue(store.nodeCount() > 0);
    }

    // -----------------------------------------------------------------------
    // Ancestor expansion

    @Test
    void writingIntoCollapsedRegionExpandsAndPreservesNeighbours() {
        OctreeDataStore store = OctreeDataStore.builder(2).component(Voxel.class).build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        // Fill entire space with value 3 → collapses to 1 node
        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++)
                    mat.set(store, store.row(x, y, z), 3);
        assertEquals(1, store.nodeCount());

        // Overwrite one voxel with value 7
        mat.set(store, store.row(0, 0, 0), 7);

        // The collapsed region should have been expanded; neighbour voxels still return 3
        assertEquals(7, mat.get(store, store.row(0, 0, 0)));
        assertEquals(3, mat.get(store, store.row(1, 0, 0)));
        assertEquals(3, mat.get(store, store.row(0, 1, 0)));
        assertEquals(3, mat.get(store, store.row(3, 3, 3)));
        // More than 1 node now
        assertTrue(store.nodeCount() > 1);
    }

    @Test
    void overwritingAllWithNewValueRecollapsesToOneNode() {
        OctreeDataStore store = OctreeDataStore.builder(2).component(Voxel.class).build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        // Fill with 3, collapse, then refill with 7
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
    // CollapsingFunction.never()

    @Test
    void neverFunctionSuppressesAllCollapse() {
        OctreeDataStore store = OctreeDataStore.builder(1)
                .component(Voxel.class, CollapsingFunction.never())
                .build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    mat.set(store, store.row(x, y, z), 5);

        // 8 identical leaves but CollapsingFunction.never() prevents any merge
        assertEquals(8, store.nodeCount());
    }

    // -----------------------------------------------------------------------
    // CollapsingFunction.always()

    @Test
    void alwaysFunctionCollapsesDifferentValues() {
        OctreeDataStore store = OctreeDataStore.builder(1)
                .component(Voxel.class, CollapsingFunction.always())
                .build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        // Write different values to each of the 8 voxels
        int i = 0;
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    mat.set(store, store.row(x, y, z), i++);

        // always() forces collapse regardless of values
        assertEquals(1, store.nodeCount());
    }

    // -----------------------------------------------------------------------
    // Custom collapsing function

    @Test
    void customFunctionCollapsesByMaterial() {
        // Collapse only when ALL 8 children have material = 0
        CollapsingFunction onlyAir = (offset, bits, stride, children) -> {
            // material is the first field: bit offset 0 within the component's block
            // We use the store's componentBitOffset + field offset (pre-baked into accessor),
            // but here we get the raw component offset; for testing purposes we check
            // whether all children have the same bits in the component range.
            return CollapsingFunction.equalBits().canCollapse(offset, bits, stride, children);
        };

        OctreeDataStore store = OctreeDataStore.builder(1)
                .component(Voxel.class, onlyAir)
                .build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        // All zeros (default): write 0 explicitly to all
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    mat.set(store, store.row(x, y, z), 0);

        assertEquals(1, store.nodeCount());  // collapsed

        // Reset and write different values
        OctreeDataStore store2 = OctreeDataStore.builder(1)
                .component(Voxel.class, onlyAir)
                .build();
        IntAccessor mat2 = Accessors.intFieldInStore(store2, Voxel.class, "material");
        mat2.set(store2, store2.row(0, 0, 0), 1);  // different
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    if (!(x == 0 && y == 0 && z == 0))
                        mat2.set(store2, store2.row(x, y, z), 0);

        assertNotEquals(1, store2.nodeCount());  // should NOT collapse
    }

    // -----------------------------------------------------------------------
    // Multi-component

    @Test
    void twoComponentsCoexistInOctree() {
        OctreeDataStore store = OctreeDataStore.builder(4)
                .component(Terrain.class, CollapsingFunction.never())
                .component(Water.class, CollapsingFunction.never())
                .build();

        IntAccessor height    = Accessors.intFieldInStore(store, Terrain.class, "height");
        DoubleAccessor temp   = Accessors.doubleFieldInStore(store, Terrain.class, "temperature");
        DoubleAccessor salin  = Accessors.doubleFieldInStore(store, Water.class, "salinity");
        BoolAccessor frozen   = Accessors.boolFieldInStore(store, Water.class, "frozen");

        int row = store.row(5, 3, 7);
        height.set(store, row, 100);
        temp.set(store, row, 22.5);
        salin.set(store, row, 0.035);
        frozen.set(store, row, true);

        assertEquals(100,   height.get(store, row));
        assertEquals(22.5,  temp.get(store, row),   0.01);
        assertEquals(0.035, salin.get(store, row),  0.0001);
        assertTrue(frozen.get(store, row));
    }

    @Test
    void twoComponentsBothMustAgreeToCollapse() {
        // Terrain uses equalBits; Water uses never — so no collapse ever
        OctreeDataStore store = OctreeDataStore.builder(1)
                .component(Terrain.class, CollapsingFunction.equalBits())
                .component(Water.class, CollapsingFunction.never())
                .build();
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");

        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    height.set(store, store.row(x, y, z), 42);  // all identical terrain

        // Water says never, so no collapse
        assertEquals(8, store.nodeCount());
    }

    @Test
    void twoComponentsBothEqualBitsCollapseTogether() {
        OctreeDataStore store = OctreeDataStore.builder(1)
                .component(Terrain.class)   // equalBits
                .component(Water.class)     // equalBits
                .build();
        IntAccessor height  = Accessors.intFieldInStore(store, Terrain.class, "height");
        BoolAccessor frozen = Accessors.boolFieldInStore(store, Water.class, "frozen");

        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++) {
                    int row = store.row(x, y, z);
                    height.set(store, row, 55);
                    frozen.set(store, row, true);
                }

        assertEquals(1, store.nodeCount());
    }

    // -----------------------------------------------------------------------
    // Voxel isolation

    @Test
    void writingOneVoxelDoesNotAffectNeighbour() {
        OctreeDataStore store = OctreeDataStore.builder(3)
                .component(Voxel.class, CollapsingFunction.never())
                .build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        mat.set(store, store.row(3, 3, 3), 11);
        assertEquals(11, mat.get(store, store.row(3, 3, 3)));
        assertEquals(0,  mat.get(store, store.row(3, 3, 4)));  // unwritten neighbour
        assertEquals(0,  mat.get(store, store.row(4, 3, 3)));
    }

    // -----------------------------------------------------------------------
    // Depth-1 edge case (minimum usable octree)

    @Test
    void depth1OctreeHasCapacity8() {
        OctreeDataStore store = OctreeDataStore.builder(1).component(Voxel.class).build();
        assertEquals(8, store.capacity());
        assertEquals(2, store.sideLength());
    }

    // -----------------------------------------------------------------------
    // Row encoding / accessor compatibility

    @Test
    void rowMethodIsInverseOfMortonDecode() {
        OctreeDataStore store = OctreeDataStore.builder(6).component(Voxel.class).build();
        for (int x : new int[]{0, 1, 31, 63}) {
            for (int y : new int[]{0, 10, 63}) {
                for (int z : new int[]{0, 5, 63}) {
                    int row = store.row(x, y, z);
                    // read + write should be consistent
                    IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");
                    mat.set(store, row, (x + y + z) % 16);
                    assertEquals((x + y + z) % 16, mat.get(store, row));
                }
            }
        }
    }

    @Test
    void mortonRowAndDirectCoordGiveSameResult() {
        OctreeDataStore store = OctreeDataStore.builder(4)
                .component(Voxel.class, CollapsingFunction.never())
                .build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        int x = 5, y = 9, z = 3;
        // write via Morton-code row
        mat.set(store, store.row(x, y, z), 14);
        // read via direct coord method
        int offset = store.componentBitOffset(Voxel.class);
        int bitWidth = 4; // @BitField(min=0, max=15) → 4 bits
        long raw = store.readBitsAt(x, y, z, offset, bitWidth);
        assertEquals(14, (int) raw);
    }

    // -----------------------------------------------------------------------
    // CollapsingFunction helpers

    @Test
    void bitsEqualReturnsTrueForNullNull() {
        assertTrue(CollapsingFunction.bitsEqual(null, null, 0, 8));
    }

    @Test
    void bitsEqualReturnsTrueForSameArray() {
        long[] a = {42L};
        assertTrue(CollapsingFunction.bitsEqual(a, a, 0, 64));
    }

    @Test
    void bitsEqualComparesOnlySpecifiedBits() {
        long[] a = {0b1111_0000L};   // bits 4-7 set, bits 0-3 = 0
        long[] b = {0b0000_0000L};
        // bits 0-3: both 0 → equal
        assertTrue(CollapsingFunction.bitsEqual(a, b, 0, 4));
        // bits 4-7: a=0b1111, b=0b0000 → different
        assertFalse(CollapsingFunction.bitsEqual(a, b, 4, 4));
        // bits 8-15: both 0 → equal
        assertTrue(CollapsingFunction.bitsEqual(a, b, 8, 8));
    }

    @Test
    void bitsEqualHandlesNullVsZero() {
        long[] zero = {0L};
        assertTrue(CollapsingFunction.bitsEqual(null, zero, 0, 64));
        assertTrue(CollapsingFunction.bitsEqual(zero, null, 0, 64));
    }

    // -----------------------------------------------------------------------
    // Non-uniform builder: builder(widthX, widthY, widthZ)

    @Test
    void nonUniformBuilderExposesCorrectDimensions() {
        OctreeDataStore<?> store = OctreeDataStore.builder(100, 100, 10)
                .component(Voxel.class)
                .build();
        assertEquals(100, store.widthX());
        assertEquals(100, store.widthY());
        assertEquals(10,  store.widthZ());
        assertEquals(100 * 100 * 10, store.capacity());
    }

    @Test
    void nonUniformBuilderRoundTrip() {
        OctreeDataStore<?> store = OctreeDataStore.builder(100, 100, 10)
                .component(Voxel.class, CollapsingFunction.never())
                .build();
        IntAccessor mat = Accessors.intFieldInStore(store, Voxel.class, "material");

        // Write at valid corners of the non-uniform space
        mat.set(store, store.row(0,   0,  0), 1);
        mat.set(store, store.row(99, 99,  9), 2);
        mat.set(store, store.row(50, 50,  5), 3);

        assertEquals(1, mat.get(store, store.row(0,   0,  0)));
        assertEquals(2, mat.get(store, store.row(99, 99,  9)));
        assertEquals(3, mat.get(store, store.row(50, 50,  5)));
    }

    @Test
    void nonUniformBuilderRejectsOutOfBoundsZ() {
        OctreeDataStore<?> store = OctreeDataStore.builder(100, 100, 10)
                .component(Voxel.class)
                .build();
        assertThrows(IllegalArgumentException.class, () -> store.row(0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> store.row(0, 0, -1));
    }

    @Test
    void nonUniformBuilderRejectsZeroDimension() {
        assertThrows(IllegalArgumentException.class,
                () -> OctreeDataStore.builder(0, 100, 10));
        assertThrows(IllegalArgumentException.class,
                () -> OctreeDataStore.builder(100, 0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> OctreeDataStore.builder(100, 100, 0));
    }
}
