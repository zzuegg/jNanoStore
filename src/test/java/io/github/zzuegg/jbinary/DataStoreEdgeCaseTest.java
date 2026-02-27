package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.*;
import io.github.zzuegg.jbinary.annotation.*;
import io.github.zzuegg.jbinary.schema.LayoutBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for wrong usage, validation errors, and edge cases across both
 * {@link DataStore#packed} and {@link DataStore#sparse} variants.
 */
class DataStoreEdgeCaseTest {

    // ------------------------------------------------------------------ shared component definitions

    record SimpleInt(
            @BitField(min = 0, max = 255) int value
    ) {}

    record SimpleDouble(
            @DecimalField(min = 0.0, max = 10.0, precision = 2) double value
    ) {}

    record SimpleBool(
            @BoolField boolean flag
    ) {}

    enum Color { RED, GREEN, BLUE }

    record SimpleEnum(
            @EnumField Color color
    ) {}

    record MultiField(
            @BitField(min = 0, max = 255) int a,
            @DecimalField(min = -100.0, max = 100.0, precision = 1) double b,
            @BoolField boolean c
    ) {}

    record Water(
            @DecimalField(min = 0.0, max = 1.0, precision = 4) double salinity,
            @BoolField boolean frozen
    ) {}

    // -----------------------------------------------------------------------
    // Factory helpers for parameterised tests (both store variants)

    static Stream<DataStore> simpleIntStores() {
        return Stream.of(
                DataStore.packed(100, SimpleInt.class),
                DataStore.sparse(100, SimpleInt.class)
        );
    }

    static Stream<DataStore> multiFieldStores() {
        return Stream.of(
                DataStore.packed(50, MultiField.class),
                DataStore.sparse(50, MultiField.class)
        );
    }

    static Stream<DataStore> twoComponentStores() {
        return Stream.of(
                DataStore.packed(50, SimpleInt.class, Water.class),
                DataStore.sparse(50, SimpleInt.class, Water.class)
        );
    }

    // -----------------------------------------------------------------------
    // Wrong usage: construction-time errors

    @Test
    void noComponentClassesThrows() {
        assertThrows(IllegalArgumentException.class, () -> DataStore.of(10));
        assertThrows(IllegalArgumentException.class, () -> DataStore.packed(10));
        assertThrows(IllegalArgumentException.class, () -> DataStore.sparse(10));
    }

    @Test
    void nullComponentClassesThrows() {
        assertThrows(Exception.class, () -> DataStore.of(10, (Class<?>[]) null));
        assertThrows(Exception.class, () -> DataStore.packed(10, (Class<?>[]) null));
        assertThrows(Exception.class, () -> DataStore.sparse(10, (Class<?>[]) null));
    }

    @Test
    void classWithNoAnnotatedFieldsThrows() {
        record NoAnnotations(int x, double y) {}
        assertThrows(IllegalArgumentException.class,
                () -> DataStore.of(10, NoAnnotations.class));
    }

    @Test
    void negativeSparseCapacityThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> DataStore.sparse(-1, SimpleInt.class));
    }

    @Test
    void bitFieldMinGreaterThanMaxThrows() {
        record Bad(@BitField(min = 100, max = 50) int v) {}
        assertThrows(IllegalArgumentException.class, () -> DataStore.of(10, Bad.class));
    }

    @Test
    void decimalFieldNegativePrecisionThrows() {
        record Bad(@DecimalField(min = 0.0, max = 1.0, precision = -1) double v) {}
        assertThrows(IllegalArgumentException.class, () -> DataStore.of(10, Bad.class));
    }

    @Test
    void enumFieldOnNonEnumTypeThrows() {
        record Bad(@EnumField int notAnEnum) {}
        assertThrows(IllegalArgumentException.class, () -> DataStore.of(10, Bad.class));
    }

    @Test
    void enumExplicitCodesMissingAnnotationThrows() {
        enum NoCode { A, B }
        record Bad(@EnumField(useExplicitCodes = true) NoCode v) {}
        assertThrows(IllegalArgumentException.class, () -> DataStore.of(10, Bad.class));
    }

    @Test
    void enumExplicitCodesDuplicateThrows() {
        enum DupCode {
            @EnumCode(1) A,
            @EnumCode(1) B   // duplicate
        }
        record Bad(@EnumField(useExplicitCodes = true) DupCode v) {}
        assertThrows(IllegalArgumentException.class, () -> DataStore.of(10, Bad.class));
    }

    @Test
    void enumExplicitCodesNegativeThrows() {
        enum NegCode { @EnumCode(-1) A }
        record Bad(@EnumField(useExplicitCodes = true) NegCode v) {}
        assertThrows(IllegalArgumentException.class, () -> DataStore.of(10, Bad.class));
    }

    @Test
    void packedCapacityOverflowThrows() {
        // A BitField spanning a huge range per row × huge capacity → overflow
        record HugeRow(
                @BitField(min = 0, max = Integer.MAX_VALUE) int a,
                @BitField(min = 0, max = Integer.MAX_VALUE) int b,
                @BitField(min = 0, max = Integer.MAX_VALUE) int c,
                @BitField(min = 0, max = Integer.MAX_VALUE) int d
        ) {}
        assertThrows(IllegalArgumentException.class,
                () -> DataStore.packed(Integer.MAX_VALUE, HugeRow.class));
    }

    // -----------------------------------------------------------------------
    // Wrong usage: accessor-time errors

    @Test
    void unregisteredComponentThrows() {
        DataStore store = DataStore.of(10, SimpleInt.class);
        assertThrows(IllegalArgumentException.class,
                () -> Accessors.intFieldInStore(store, Water.class, "salinity"));
    }

    @Test
    void nonexistentFieldNameThrows() {
        DataStore store = DataStore.of(10, SimpleInt.class);
        assertThrows(IllegalArgumentException.class,
                () -> Accessors.intFieldInStore(store, SimpleInt.class, "doesNotExist"));
    }

    // -----------------------------------------------------------------------
    // Edge cases: capacity boundary

    @ParameterizedTest
    @MethodSource("simpleIntStores")
    void capacityOfOneRowWorks(DataStore store) {
        DataStore s = DataStore.of(1, SimpleInt.class);
        IntAccessor v = Accessors.intFieldInStore(s, SimpleInt.class, "value");
        v.set(s, 0, 42);
        assertEquals(42, v.get(s, 0));
    }

    @Test
    void sparseWriteOutOfBoundsThrows() {
        DataStore store = DataStore.sparse(5, SimpleInt.class);
        IntAccessor v = Accessors.intFieldInStore(store, SimpleInt.class, "value");
        assertThrows(IndexOutOfBoundsException.class, () -> v.set(store, 5, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> v.set(store, -1, 0));
    }

    // -----------------------------------------------------------------------
    // Edge cases: field boundary values

    @ParameterizedTest
    @MethodSource("simpleIntStores")
    void bitFieldMinAndMaxValues(DataStore store) {
        IntAccessor v = Accessors.intFieldInStore(store, SimpleInt.class, "value");
        v.set(store, 0, 0);
        assertEquals(0, v.get(store, 0));
        v.set(store, 0, 255);
        assertEquals(255, v.get(store, 0));
    }

    @ParameterizedTest
    @MethodSource("simpleIntStores")
    void allRowsIndependent(DataStore store) {
        IntAccessor v = Accessors.intFieldInStore(store, SimpleInt.class, "value");
        for (int i = 0; i < 100; i++) v.set(store, i, i);
        for (int i = 0; i < 100; i++) assertEquals(i, v.get(store, i));
    }

    @ParameterizedTest
    @MethodSource("multiFieldStores")
    void multiFieldRoundTrip(DataStore store) {
        IntAccessor a    = Accessors.intFieldInStore(store, MultiField.class, "a");
        DoubleAccessor b = Accessors.doubleFieldInStore(store, MultiField.class, "b");
        BoolAccessor c   = Accessors.boolFieldInStore(store, MultiField.class, "c");

        a.set(store, 7, 200);
        b.set(store, 7, 99.5);
        c.set(store, 7, true);

        assertEquals(200,  a.get(store, 7));
        assertEquals(99.5, b.get(store, 7), 0.05);
        assertTrue(c.get(store, 7));

        // ensure adjacent rows are unaffected
        assertEquals(0,     a.get(store, 8));
        assertEquals(false, c.get(store, 8));
    }

    @ParameterizedTest
    @MethodSource("twoComponentStores")
    void twoComponentsCoexist(DataStore store) {
        IntAccessor value    = Accessors.intFieldInStore(store, SimpleInt.class, "value");
        DoubleAccessor salin = Accessors.doubleFieldInStore(store, Water.class, "salinity");
        BoolAccessor frozen  = Accessors.boolFieldInStore(store, Water.class, "frozen");

        value.set(store, 3, 77);
        salin.set(store, 3, 0.035);
        frozen.set(store, 3, true);

        assertEquals(77,    value.get(store, 3));
        assertEquals(0.035, salin.get(store, 3), 0.0001);
        assertTrue(frozen.get(store, 3));
    }

    // -----------------------------------------------------------------------
    // Edge cases: LongAccessor (previously untested)

    record LongRecord(
            @BitField(min = 0, max = (1 << 20) - 1) int largeInt
    ) {}

    @Test
    void longAccessorRoundTrip() {
        DataStore store = DataStore.of(5, LongRecord.class);
        LongAccessor acc = Accessors.longFieldInStore(store, LongRecord.class, "largeInt");
        acc.set(store, 0, 0L);
        assertEquals(0L, acc.get(store, 0));
        acc.set(store, 1, (1L << 20) - 1);
        assertEquals((1L << 20) - 1, acc.get(store, 1));
        acc.set(store, 2, 500_000L);
        assertEquals(500_000L, acc.get(store, 2));
    }

    @Test
    void sparseStoreRowNotAllocatedForLong() {
        DataStore store = DataStore.sparse(10, LongRecord.class);
        LongAccessor acc = Accessors.longFieldInStore(store, LongRecord.class, "largeInt");
        // Before any write, default is 0 (= min value)
        assertEquals(0L, acc.get(store, 5));
        assertEquals(0, ((SparseDataStore) store).allocatedRowCount());
    }

    // -----------------------------------------------------------------------
    // Edge cases: word-boundary spanning (packed and sparse)

    record BigFirst(
            @BitField(min = 0, max = (1 << 30) - 1) int big,
            @BitField(min = 0, max = 255) int small
    ) {}

    @Test
    void wordBoundarySpanPackedAndSparse() {
        for (DataStore store : new DataStore[]{
                DataStore.packed(5, BigFirst.class),
                DataStore.sparse(5, BigFirst.class)}) {
            IntAccessor big   = Accessors.intFieldInStore(store, BigFirst.class, "big");
            IntAccessor small = Accessors.intFieldInStore(store, BigFirst.class, "small");

            big.set(store, 0, (1 << 30) - 1);
            small.set(store, 0, 200);

            assertEquals((1 << 30) - 1, big.get(store, 0));
            assertEquals(200, small.get(store, 0));
        }
    }

    // -----------------------------------------------------------------------
    // Edge cases: all enum constants via both ordinal and explicit codes

    enum Status { PENDING, ACTIVE, CLOSED }

    enum Priority {
        @EnumCode(1) LOW,
        @EnumCode(5) MEDIUM,
        @EnumCode(9) HIGH
    }

    record StatusRecord(@EnumField Status status) {}

    record PriorityRecord(@EnumField(useExplicitCodes = true) Priority priority) {}

    @Test
    void enumAllOrdinalConstants() {
        DataStore store = DataStore.of(10, StatusRecord.class);
        EnumAccessor<Status> acc = Accessors.enumFieldInStore(store, StatusRecord.class, "status");
        for (Status s : Status.values()) {
            acc.set(store, s.ordinal(), s);
            assertEquals(s, acc.get(store, s.ordinal()));
        }
    }

    @Test
    void enumAllExplicitConstants() {
        DataStore store = DataStore.of(10, PriorityRecord.class);
        EnumAccessor<Priority> acc =
                Accessors.enumFieldInStore(store, PriorityRecord.class, "priority");
        for (Priority p : Priority.values()) {
            acc.set(store, p.ordinal(), p);
            assertEquals(p, acc.get(store, p.ordinal()));
        }
    }

    // -----------------------------------------------------------------------
    // DataStore interface factory methods produce correct types

    @Test
    void packedFactoryReturnsPacked() {
        DataStore store = DataStore.packed(10, SimpleInt.class);
        assertInstanceOf(PackedDataStore.class, store);
    }

    @Test
    void ofFactoryReturnsPacked() {
        DataStore store = DataStore.of(10, SimpleInt.class);
        assertInstanceOf(PackedDataStore.class, store);
    }

    @Test
    void sparseFactoryReturnsSparse() {
        DataStore store = DataStore.sparse(10, SimpleInt.class);
        assertInstanceOf(SparseDataStore.class, store);
    }

    // -----------------------------------------------------------------------
    // LayoutBuilder edge cases

    @Test
    void layoutBuilderCachesResult() {
        var l1 = LayoutBuilder.layout(SimpleInt.class);
        var l2 = LayoutBuilder.layout(SimpleInt.class);
        assertSame(l1, l2, "LayoutBuilder should return the same cached instance");
    }

    @Test
    void bitsRequiredEdgeCases() {
        assertEquals(1, LayoutBuilder.bitsRequired(0));
        assertEquals(1, LayoutBuilder.bitsRequired(1));
        assertEquals(8, LayoutBuilder.bitsRequired(255));
        assertEquals(9, LayoutBuilder.bitsRequired(256));
        assertEquals(63, LayoutBuilder.bitsRequired(Long.MAX_VALUE)); // 2^63-1 → 63 bits needed
    }

    @Test
    void componentBitOffsetMonotonicallyIncreases() {
        DataStore store = DataStore.of(1, SimpleInt.class, Water.class);
        int offInt  = store.componentBitOffset(SimpleInt.class);
        int offWater = store.componentBitOffset(Water.class);
        assertTrue(offWater > offInt,
                "Water component should start after SimpleInt component");
    }
}
