package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.annotation.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that verify jBinary DataStore works correctly with JEP 401 {@code value record} types.
 *
 * <p>This test class requires the Project Valhalla early-access JDK, available from
 * <a href="https://jdk.java.net/valhalla/">https://jdk.java.net/valhalla/</a>.
 * Run with:
 * <pre>
 *   ./gradlew valhallaTest -PvalhallaJdkPath=/path/to/valhalla-jdk
 * </pre>
 *
 * <p>Value records (JEP 401) are identity-free: the JVM may flatten them into their container,
 * eliminating heap allocation entirely.  jBinary's bit-packed storage is a natural complement —
 * component records are small, immutable, and created/discarded frequently via
 * {@link RowView#get}/{@link RowView#set}.
 */
class ValhallaValueRecordTest {

    // ------------------------------------------------------------------ value record components

    value record Terrain(
            @BitField(min = 0, max = 255) int height,
            @DecimalField(min = -50.0, max = 50.0, precision = 2) double temperature,
            @BoolField boolean active
    ) {}

    value record Water(
            @DecimalField(min = 0.0, max = 1.0, precision = 4) double salinity,
            @BoolField boolean frozen
    ) {}

    enum Biome { PLAINS, FOREST, DESERT, OCEAN }

    value record BiomeData(
            @EnumField Biome biome,
            @BitField(min = 0, max = 100) int fertility
    ) {}

    // ------------------------------------------------------------------ helpers

    private static double expectedTemperature(int i) {
        return (i % 100) * 1.0 - 50.0;
    }

    // ------------------------------------------------------------------ correctness

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

    // ------------------------------------------------------------------ value-record-specific semantics

    /**
     * Value records are identity-free: two instances with identical field values satisfy {@code ==}.
     * This is the defining characteristic of JEP 401 value types.
     */
    @Test
    void valueRecordEqualityIsFieldBased() {
        Terrain a = new Terrain(100, 25.0, true);
        Terrain b = new Terrain(100, 25.0, true);

        assertTrue(a == b, "Value records with equal fields must satisfy == (identity-free)");
        assertEquals(a, b);
    }

    /**
     * After a round-trip through the DataStore, the reconstructed value record must equal
     * the original — both via {@code equals()} and via {@code ==}.
     */
    @Test
    void roundTripPreservesValueEquality() {
        DataStore<Terrain> store = DataStore.of(5, Terrain.class);
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        Terrain written = new Terrain(100, 25.0, true);
        view.set(store, 0, written);
        Terrain read = view.get(store, 0);

        assertEquals(written, read, "Round-trip must produce a field-equal instance");
        assertTrue(written == read,  "Value records: round-trip instance must satisfy ==");
    }

    /**
     * The typical hot-loop pattern: create → store → read → discard, N times.
     * With value records the JVM can stack-allocate / inline instances, avoiding heap pressure.
     */
    @Test
    void ephemeralCreateStoreReadPattern() {
        final int N = 1_000;
        DataStore<Terrain> store = DataStore.of(N, Terrain.class);
        RowView<Terrain> view = RowView.of(store, Terrain.class);

        for (int i = 0; i < N; i++) {
            view.set(store, i, new Terrain(i % 256, expectedTemperature(i), i % 2 == 0));
        }
        for (int i = 0; i < N; i++) {
            Terrain t = view.get(store, i);
            assertEquals(i % 256,              t.height());
            assertEquals(expectedTemperature(i), t.temperature(), 0.01);
            assertEquals(i % 2 == 0,            t.active());
        }
    }
}
