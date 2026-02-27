package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.annotation.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying that jBinary DataStore works correctly with Project Valhalla
 * value-type usage patterns.
 *
 * <p>Project Valhalla introduces <em>value classes</em> (JEP 401) — identity-free,
 * immutable types whose instances can be flattened/inlined by the JVM. The DataStore
 * is a natural fit: component records are small, immutable, and created/discarded
 * frequently via {@link RowView#get}/{@link RowView#set}.
 *
 * <p>When {@code value record} becomes available as a GA language feature, the record
 * declarations in this class can be prefixed with {@code value}:
 * <pre>{@code
 *   value record Terrain(
 *       @BitField(min = 0, max = 255) int height,
 *       @DecimalField(min = -50.0, max = 50.0, precision = 2) double temperature,
 *       @BoolField boolean active
 *   ) {}
 * }</pre>
 * All tests are expected to pass without modification.
 *
 * <p>The build enables {@code --enable-preview} in preparation for when JEP 401 lands in a
 * future Java release.  {@code --enable-preview} does <em>not</em> currently unlock
 * {@code value record} syntax in Java 25 LTS because that feature is not yet part of any
 * preview release; it is enabled so that the build infrastructure is already in place.
 */
class ValhallaValueTypeTest {

    // ------------------------------------------------------------------ helpers

    /** Maps a loop index to a temperature value within the {@code @DecimalField} range [-50, 50]. */
    private static double expectedTemperature(int i) {
        return (i % 100) * 1.0 - 50.0;
    }

    // ------------------------------------------------------------------ component records
    // Ready to become "value record" once JEP 401 is GA.

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

    // ------------------------------------------------------------------ correctness tests

    @Test
    void roundTripPackedStore() {
        DataStore<Terrain> store = DataStore.of(10, Terrain.class);
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        view.set(store, 0, new Terrain(200, -12.5, true));
        Terrain result = view.get(store, 0);

        assertEquals(200,   result.height());
        assertEquals(-12.5, result.temperature(), 0.01);
        assertTrue(result.active());
    }

    @Test
    void roundTripSparseStore() {
        DataStore<Terrain> store = DataStore.sparse(10, Terrain.class);
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        view.set(store, 3, new Terrain(128, 22.5, false));
        Terrain result = view.get(store, 3);

        assertEquals(128,  result.height());
        assertEquals(22.5, result.temperature(), 0.01);
        assertFalse(result.active());
    }

    @Test
    void multiComponentStore() {
        @SuppressWarnings("unchecked")
        DataStore store = DataStore.of(50, Terrain.class, Water.class);
        @SuppressWarnings("unchecked")
        RowView<Terrain> terrainView = RowView.of(store, Terrain.class);
        @SuppressWarnings("unchecked")
        RowView<Water> waterView = RowView.of(store, Water.class);

        terrainView.set(store, 10, new Terrain(100, 20.0, true));
        waterView.set(store, 10, new Water(0.035, false));

        Terrain t = terrainView.get(store, 10);
        Water   w = waterView.get(store, 10);

        assertEquals(100,   t.height());
        assertEquals(20.0,  t.temperature(), 0.01);
        assertTrue(t.active());
        assertEquals(0.035, w.salinity(), 0.0001);
        assertFalse(w.frozen());
    }

    @Test
    void enumField() {
        DataStore<BiomeData> store = DataStore.of(10, BiomeData.class);
        RowView<BiomeData> view = RowView.of(store, BiomeData.class);

        view.set(store, 0, new BiomeData(Biome.FOREST, 75));
        BiomeData result = view.get(store, 0);

        assertEquals(Biome.FOREST, result.biome());
        assertEquals(75, result.fertility());
    }

    @Test
    void defaultValues() {
        DataStore<Terrain> store = DataStore.of(10, Terrain.class);
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        Terrain result = view.get(store, 0);

        // height min=0 → raw 0 → 0; temperature min=-50.0 → raw 0 → -50.0; active=false
        assertEquals(0,     result.height());
        assertEquals(-50.0, result.temperature(), 0.01);
        assertFalse(result.active());
    }

    @Test
    void rowIsolation() {
        DataStore<Terrain> store = DataStore.of(5, Terrain.class);
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        for (int i = 0; i < 5; i++) {
            view.set(store, i, new Terrain(i * 50, i * 5.0 - 20.0, i % 2 == 0));
        }
        for (int i = 0; i < 5; i++) {
            Terrain r = view.get(store, i);
            assertEquals(i * 50,         r.height());
            assertEquals(i * 5.0 - 20.0, r.temperature(), 0.01);
            assertEquals(i % 2 == 0,     r.active());
        }
    }

    // ------------------------------------------------------------------ value-type semantics
    // The following tests validate behaviours that value types require:
    //   • equality is by value, not by identity
    //   • instances are immutable (no mutation after construction)
    //   • no synchronisation / identity-based operations needed

    /**
     * Value types require equality by field content.  Records already satisfy this;
     * value records make it the only kind of equality available.
     */
    @Test
    void equalityByValue() {
        DataStore<Terrain> store = DataStore.of(5, Terrain.class);
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        Terrain written = new Terrain(100, 25.0, true);
        view.set(store, 0, written);
        Terrain read = view.get(store, 0);

        // The read-back instance must be field-equal to the written one.
        assertEquals(written, read,
                "RowView.get must return a field-equal instance (value equality)");
    }

    /**
     * Demonstrates the typical value-type usage pattern: create, store, read, discard —
     * repeated many times without retaining references (allocation-free hot loop).
     * With value records the JVM can stack-allocate or inline these instances.
     */
    @Test
    void ephemeralCreateStoreReadPattern() {
        final int N = 1_000;
        DataStore<Terrain> store = DataStore.of(N, Terrain.class);
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        // Write phase: create and immediately discard each instance.
        for (int i = 0; i < N; i++) {
            view.set(store, i, new Terrain(i % 256, expectedTemperature(i), i % 2 == 0));
        }

        // Read phase: retrieve and verify each value then discard it.
        for (int i = 0; i < N; i++) {
            Terrain t = view.get(store, i);
            assertEquals(i % 256,              t.height());
            assertEquals(expectedTemperature(i), t.temperature(), 0.01);
            assertEquals(i % 2 == 0,            t.active());
        }
    }

    /**
     * Two records constructed with identical field values must compare equal.
     * For value records (JEP 401) {@code ==} will also be {@code true}; for regular
     * records only {@code equals()} is guaranteed to be {@code true}.
     */
    @Test
    void sameFieldValuesMeansEquals() {
        Terrain a = new Terrain(100, 25.0, true);
        Terrain b = new Terrain(100, 25.0, true);

        assertEquals(a, b, "Instances with identical field values must be equal");
        assertNotSame(a, b, "Regular records are identity-based for ==; value records will not be");
    }

    /**
     * After a round-trip through the DataStore the reconstructed instance must be
     * equal to the original, confirming the store preserves value semantics.
     */
    @Test
    void roundTripPreservesValueEquality() {
        DataStore<Water> store = DataStore.of(3, Water.class);
        RowView<Water> view = RowView.of(store, Water.class);

        Water original = new Water(0.035, false);
        view.set(store, 1, original);
        Water roundTripped = view.get(store, 1);

        assertEquals(original, roundTripped,
                "Round-trip through DataStore must produce a field-equal instance");
    }
}

