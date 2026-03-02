package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.BoolAccessor;
import io.github.zzuegg.jbinary.accessor.DoubleAccessor;
import io.github.zzuegg.jbinary.accessor.IntAccessor;
import io.github.zzuegg.jbinary.annotation.BitField;
import io.github.zzuegg.jbinary.annotation.BoolField;
import io.github.zzuegg.jbinary.annotation.DecimalField;
import io.github.zzuegg.jbinary.annotation.StoreField;
import io.github.zzuegg.jbinary.octree.FastOctreeDataStore;
import io.github.zzuegg.jbinary.octree.OctreeDataStore;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class DataStoreBenchmark {

    // ------------------------------------------------------------------ records
    record Terrain(
            @BitField(min = 0, max = 255) int height,
            @DecimalField(min = -50.0, max = 50.0, precision = 2) double temperature,
            @BoolField boolean active
    ) {}

    record Water(
            @DecimalField(min = 0.0, max = 1.0, precision = 4) double salinity,
            @BoolField boolean frozen
    ) {}

    // ------------------------------------------------------------------ DataCursor projection classes
    public static class TerrainCursor {
        @StoreField(component = Terrain.class, field = "height")      public int     height;
        @StoreField(component = Terrain.class, field = "temperature") public double  temperature;
        @StoreField(component = Terrain.class, field = "active")      public boolean active;
    }

    /** Multi-component cursor spanning both Terrain and Water fields. */
    public static class WorldCursor {
        @StoreField(component = Terrain.class, field = "height")      public int     height;
        @StoreField(component = Terrain.class, field = "temperature") public double  temperature;
        @StoreField(component = Terrain.class, field = "active")      public boolean active;
        @StoreField(component = Water.class,   field = "salinity")    public double  salinity;
        @StoreField(component = Water.class,   field = "frozen")      public boolean frozen;
    }

    // ------------------------------------------------------------------ packed store
    DataStore packedStore;
    IntAccessor    packedHeightAcc;
    DoubleAccessor packedTempAcc;
    BoolAccessor   packedActiveAcc;
    RowView<Terrain> packedRowView;
    DataCursor<TerrainCursor> packedCursor;
    DataCursor<TerrainCursor> packedCursorVH;   // VarHandle fallback (no ByteBuddy)

    // ------------------------------------------------------------------ sparse store
    DataStore sparseStore;
    IntAccessor    sparseHeightAcc;
    DoubleAccessor sparseTempAcc;
    BoolAccessor   sparseActiveAcc;
    RowView<Terrain> sparseRowView;
    DataCursor<TerrainCursor> sparseCursor;
    DataCursor<TerrainCursor> sparseCursorVH;   // VarHandle fallback (no ByteBuddy)

    // ------------------------------------------------------------------ octree store (maxDepth=4 → 16×16×16; first N voxels)
    OctreeDataStore octreeStore;
    IntAccessor    octreeHeightAcc;
    DoubleAccessor octreeTempAcc;
    BoolAccessor   octreeActiveAcc;
    int[]          octreeRows;
    RowView<Terrain> octreeRowView;
    DataCursor<TerrainCursor> octreeCursor;

    // ------------------------------------------------------------------ fast octree store
    FastOctreeDataStore fastOctreeStore;
    IntAccessor    fastOctreeHeightAcc;
    DoubleAccessor fastOctreeTempAcc;
    BoolAccessor   fastOctreeActiveAcc;
    int[]          fastOctreeRows;
    RowView<Terrain> fastOctreeRowView;
    DataCursor<TerrainCursor> fastOctreeCursor;

    // ------------------------------------------------------------------ baseline store (parallel arrays)
    int[]     baselineHeight;
    double[]  baselineTemp;
    boolean[] baselineActive;

    // ------------------------------------------------------------------ HashMap store (boxed Object[] per row)
    Map<Integer, Object[]> hashmapStore;

    // ------------------------------------------------------------------ multi-component packed store (Terrain + Water)
    DataStore multiPackedStore;
    DataCursor<WorldCursor> multiPackedCursor;

    // ------------------------------------------------------------------ multi-component sparse store
    DataStore multiSparseStore;
    DataCursor<WorldCursor> multiSparseCursor;

    // ------------------------------------------------------------------ multi-component octree store
    OctreeDataStore     multiOctreeStore;
    int[]               multiOctreeRows;
    int[]               randMultiOctreeRows;
    DataCursor<WorldCursor> multiOctreeCursor;

    // ------------------------------------------------------------------ multi-component fast octree store
    FastOctreeDataStore multiFastOctreeStore;
    int[]               multiFastOctreeRows;
    int[]               randMultiFastOctreeRows;
    DataCursor<WorldCursor> multiFastOctreeCursor;

    // ------------------------------------------------------------------ multi-component baseline (5 parallel arrays)
    int[]     multiBaselineHeight;
    double[]  multiBaselineTemp;
    boolean[] multiBaselineActive;
    double[]  multiBaselineSalinity;
    boolean[] multiBaselineFrozen;

    // ------------------------------------------------------------------ pre-computed random test data (fixed seed)
    int[]     testHeight;
    double[]  testTemp;
    boolean[] testActive;
    double[]  testSalinity;
    boolean[] testFrozen;

    static final int N = 1000;

    // ------------------------------------------------------------------ random access index arrays (fixed-seed Fisher-Yates)
    int[] randIdx;              // shuffled [0..N-1] for packed / sparse / baseline / hashmap
    int[] randOctreeRows;       // shuffled octreeRows
    int[] randFastOctreeRows;   // shuffled fastOctreeRows

    // ------------------------------------------------------------------ setup

    @Setup
    public void setup() {
        // packed store
        packedStore     = DataStore.of(N, Terrain.class);
        packedHeightAcc = Accessors.intFieldInStore(packedStore, Terrain.class, "height");
        packedTempAcc   = Accessors.doubleFieldInStore(packedStore, Terrain.class, "temperature");
        packedActiveAcc = Accessors.boolFieldInStore(packedStore, Terrain.class, "active");
        packedRowView   = RowView.of(packedStore, Terrain.class);
        packedCursor    = DataCursor.of(packedStore, TerrainCursor.class);
        packedCursorVH  = DataCursor.ofVarHandle(packedStore, TerrainCursor.class);

        // sparse store
        sparseStore     = DataStore.sparse(N, Terrain.class);
        sparseHeightAcc = Accessors.intFieldInStore(sparseStore, Terrain.class, "height");
        sparseTempAcc   = Accessors.doubleFieldInStore(sparseStore, Terrain.class, "temperature");
        sparseActiveAcc = Accessors.boolFieldInStore(sparseStore, Terrain.class, "active");
        sparseRowView   = RowView.of(sparseStore, Terrain.class);
        sparseCursor    = DataCursor.of(sparseStore, TerrainCursor.class);
        sparseCursorVH  = DataCursor.ofVarHandle(sparseStore, TerrainCursor.class);

        // octree store (maxDepth=4 → 16×16×16 space; first N Morton-coded rows)
        octreeStore = OctreeDataStore.builder(4)
                .component(Terrain.class)
                .build();
        octreeHeightAcc = Accessors.intFieldInStore(octreeStore, Terrain.class, "height");
        octreeTempAcc   = Accessors.doubleFieldInStore(octreeStore, Terrain.class, "temperature");
        octreeActiveAcc = Accessors.boolFieldInStore(octreeStore, Terrain.class, "active");
        octreeRowView   = RowView.of(octreeStore, Terrain.class);
        octreeCursor    = DataCursor.of(octreeStore, TerrainCursor.class);

        octreeRows = new int[N];
        for (int i = 0; i < N; i++) {
            int x = i & 0xF;
            int y = (i >> 4) & 0xF;
            int z = (i >> 8) & 0x3;
            octreeRows[i] = octreeStore.row(x, y, z);
        }

        // fast octree store (same voxel layout)
        fastOctreeStore = FastOctreeDataStore.builder(4)
                .component(Terrain.class)
                .build();
        fastOctreeHeightAcc = Accessors.intFieldInStore(fastOctreeStore, Terrain.class, "height");
        fastOctreeTempAcc   = Accessors.doubleFieldInStore(fastOctreeStore, Terrain.class, "temperature");
        fastOctreeActiveAcc = Accessors.boolFieldInStore(fastOctreeStore, Terrain.class, "active");
        fastOctreeRowView   = RowView.of(fastOctreeStore, Terrain.class);
        fastOctreeCursor    = DataCursor.of(fastOctreeStore, TerrainCursor.class);

        fastOctreeRows = new int[N];
        for (int i = 0; i < N; i++) {
            int x = i & 0xF;
            int y = (i >> 4) & 0xF;
            int z = (i >> 8) & 0x3;
            fastOctreeRows[i] = fastOctreeStore.row(x, y, z);
        }

        // baseline (parallel primitive arrays)
        baselineHeight = new int[N];
        baselineTemp   = new double[N];
        baselineActive = new boolean[N];

        // HashMap store (boxed Object[] per row, no packing)
        hashmapStore = new HashMap<>((int)(N / 0.75) + 1);

        // pre-computed realistic random test data (fixed seed for reproducibility)
        testHeight   = new int[N];
        testTemp     = new double[N];
        testActive   = new boolean[N];
        testSalinity = new double[N];
        testFrozen   = new boolean[N];
        Random dataRng = new Random(123L);
        for (int i = 0; i < N; i++) {
            testHeight[i]   = dataRng.nextInt(256);
            testTemp[i]     = dataRng.nextDouble() * 100.0 - 50.0;
            testActive[i]   = dataRng.nextBoolean();
            testSalinity[i] = dataRng.nextDouble();
            testFrozen[i]   = dataRng.nextBoolean();
        }

        for (int i = 0; i < N; i++) {
            int h     = testHeight[i];
            double t  = testTemp[i];
            boolean a = testActive[i];

            packedHeightAcc.set(packedStore, i, h);
            packedTempAcc.set(packedStore, i, t);
            packedActiveAcc.set(packedStore, i, a);

            sparseHeightAcc.set(sparseStore, i, h);
            sparseTempAcc.set(sparseStore, i, t);
            sparseActiveAcc.set(sparseStore, i, a);

            octreeHeightAcc.set(octreeStore, octreeRows[i], h);
            octreeTempAcc.set(octreeStore, octreeRows[i], t);
            octreeActiveAcc.set(octreeStore, octreeRows[i], a);

            fastOctreeHeightAcc.set(fastOctreeStore, fastOctreeRows[i], h);
            fastOctreeTempAcc.set(fastOctreeStore, fastOctreeRows[i], t);
            fastOctreeActiveAcc.set(fastOctreeStore, fastOctreeRows[i], a);

            baselineHeight[i] = h;
            baselineTemp[i]   = t;
            baselineActive[i] = a;

            hashmapStore.put(i, new Object[]{h, t, a});
        }

        // multi-component packed store (Terrain + Water)
        multiPackedStore  = DataStore.of(N, Terrain.class, Water.class);
        multiPackedCursor = DataCursor.of(multiPackedStore, WorldCursor.class);

        // multi-component sparse store
        multiSparseStore  = DataStore.sparse(N, Terrain.class, Water.class);
        multiSparseCursor = DataCursor.of(multiSparseStore, WorldCursor.class);

        // multi-component octree store (maxDepth=4 → 16×16×16)
        multiOctreeStore = OctreeDataStore.builder(4)
                .component(Terrain.class)
                .component(Water.class)
                .build();
        multiOctreeRows = new int[N];
        for (int i = 0; i < N; i++) {
            int x = i & 0xF; int y = (i >> 4) & 0xF; int z = (i >> 8) & 0x3;
            multiOctreeRows[i] = multiOctreeStore.row(x, y, z);
        }
        multiOctreeCursor = DataCursor.of(multiOctreeStore, WorldCursor.class);

        // multi-component fast octree store
        multiFastOctreeStore = FastOctreeDataStore.builder(4)
                .component(Terrain.class)
                .component(Water.class)
                .build();
        multiFastOctreeRows = new int[N];
        for (int i = 0; i < N; i++) {
            int x = i & 0xF; int y = (i >> 4) & 0xF; int z = (i >> 8) & 0x3;
            multiFastOctreeRows[i] = multiFastOctreeStore.row(x, y, z);
        }
        multiFastOctreeCursor = DataCursor.of(multiFastOctreeStore, WorldCursor.class);

        // multi-component baseline (5 parallel primitive arrays)
        multiBaselineHeight   = new int[N];
        multiBaselineTemp     = new double[N];
        multiBaselineActive   = new boolean[N];
        multiBaselineSalinity = new double[N];
        multiBaselineFrozen   = new boolean[N];

        // populate all multi-component stores with the same random test data
        WorldCursor wd = multiPackedCursor.get();
        for (int i = 0; i < N; i++) {
            wd.height = testHeight[i]; wd.temperature = testTemp[i]; wd.active = testActive[i];
            wd.salinity = testSalinity[i]; wd.frozen = testFrozen[i];
            multiPackedCursor.flush(multiPackedStore, i);
            multiSparseCursor.flush(multiSparseStore, i);
            multiOctreeCursor.flush(multiOctreeStore, multiOctreeRows[i]);
            multiFastOctreeCursor.flush(multiFastOctreeStore, multiFastOctreeRows[i]);
            multiBaselineHeight[i]   = testHeight[i];
            multiBaselineTemp[i]     = testTemp[i];
            multiBaselineActive[i]   = testActive[i];
            multiBaselineSalinity[i] = testSalinity[i];
            multiBaselineFrozen[i]   = testFrozen[i];
        }

        // random-access index arrays (fixed seed for reproducibility)
        randIdx                 = shuffled(N, 42L);
        randOctreeRows          = shuffledArray(octreeRows, 42L);
        randFastOctreeRows      = shuffledArray(fastOctreeRows, 42L);
        randMultiOctreeRows     = shuffledArray(multiOctreeRows, 42L);
        randMultiFastOctreeRows = shuffledArray(multiFastOctreeRows, 42L);

        // raw store (no bit compression)
        rawStore     = DataStore.raw(N, Terrain.class);
        rawHeightAcc = rawStore.intAccessor(Terrain.class, "height");
        rawTempAcc   = rawStore.doubleAccessor(Terrain.class, "temperature");
        rawActiveAcc = rawStore.boolAccessor(Terrain.class, "active");
        rawRowView   = rawStore.rowView(Terrain.class);
        for (int i = 0; i < N; i++) {
            rawHeightAcc.set(rawStore, i, testHeight[i]);
            rawTempAcc.set(rawStore, i, testTemp[i]);
            rawActiveAcc.set(rawStore, i, testActive[i]);
        }

        // collections
        packedList   = io.github.zzuegg.jbinary.collections.PackedList.create(N, Terrain.class);
        packedIntMap = io.github.zzuegg.jbinary.collections.PackedIntMap.create(N, Terrain.class);
        for (int i = 0; i < N; i++) {
            Terrain t = new Terrain(testHeight[i], testTemp[i], testActive[i]);
            packedList.add(t);
            packedIntMap.put(i, t);
        }
    }

    // ------------------------------------------------------------------ shuffle helpers

    private static int[] shuffled(int n, long seed) {
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = i;
        Random rng = new Random(seed);
        for (int i = n - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int t = arr[i]; arr[i] = arr[j]; arr[j] = t;
        }
        return arr;
    }

    private static int[] shuffledArray(int[] src, long seed) {
        int[] arr = src.clone();
        Random rng = new Random(seed);
        for (int i = arr.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int t = arr[i]; arr[i] = arr[j]; arr[j] = t;
        }
        return arr;
    }

    // ====================================================================
    // PACKED — direct IntAccessor
    // ====================================================================

    @Benchmark public void packedReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            bh.consume(packedHeightAcc.get(packedStore, i));
            bh.consume(packedTempAcc.get(packedStore, i));
            bh.consume(packedActiveAcc.get(packedStore, i));
        }
    }

    @Benchmark public void packedWriteAll() {
        for (int i = 0; i < N; i++) {
            packedHeightAcc.set(packedStore, i, testHeight[i]);
            packedTempAcc.set(packedStore, i, testTemp[i]);
            packedActiveAcc.set(packedStore, i, testActive[i]);
        }
    }

    @Benchmark public void packedRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            bh.consume(packedHeightAcc.get(packedStore, idx));
            bh.consume(packedTempAcc.get(packedStore, idx));
            bh.consume(packedActiveAcc.get(packedStore, idx));
        }
    }

    @Benchmark public void packedRandomWrite() {
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            packedHeightAcc.set(packedStore, idx, testHeight[i]);
            packedTempAcc.set(packedStore, idx, testTemp[i]);
            packedActiveAcc.set(packedStore, idx, testActive[i]);
        }
    }

    @Benchmark public void packedRandomReadWrite(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            if ((i & 1) == 0) {
                bh.consume(packedHeightAcc.get(packedStore, idx));
                bh.consume(packedTempAcc.get(packedStore, idx));
                bh.consume(packedActiveAcc.get(packedStore, idx));
            } else {
                packedHeightAcc.set(packedStore, idx, testHeight[i]);
                packedTempAcc.set(packedStore, idx, testTemp[i]);
                packedActiveAcc.set(packedStore, idx, testActive[i]);
            }
        }
    }

    @Benchmark public int packedReadSingle() {
        return packedHeightAcc.get(packedStore, N / 2);
    }

    // ------------------------------------------------------------------ packed RowView

    @Benchmark public void packedRowViewReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) bh.consume(packedRowView.get(packedStore, i));
    }

    @Benchmark public void packedRowViewWriteAll() {
        for (int i = 0; i < N; i++)
            packedRowView.set(packedStore, i, new Terrain(testHeight[i], testTemp[i], testActive[i]));
    }

    @Benchmark public void packedRowViewRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) bh.consume(packedRowView.get(packedStore, randIdx[i]));
    }

    @Benchmark public void packedRowViewRandomWrite() {
        for (int i = 0; i < N; i++)
            packedRowView.set(packedStore, randIdx[i], new Terrain(testHeight[i], testTemp[i], testActive[i]));
    }

    @Benchmark public void packedRowViewRandomReadWrite(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            if ((i & 1) == 0) bh.consume(packedRowView.get(packedStore, idx));
            else               packedRowView.set(packedStore, idx, new Terrain(testHeight[i], testTemp[i], testActive[i]));
        }
    }

    // ------------------------------------------------------------------ packed DataCursor

    @Benchmark public void packedCursorReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            TerrainCursor d = packedCursor.update(packedStore, i);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
        }
    }

    @Benchmark public void packedCursorWriteAll() {
        TerrainCursor d = packedCursor.get();
        for (int i = 0; i < N; i++) {
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            packedCursor.flush(packedStore, i);
        }
    }

    @Benchmark public void packedCursorRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            TerrainCursor d = packedCursor.update(packedStore, randIdx[i]);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
        }
    }

    @Benchmark public void packedCursorRandomWrite() {
        TerrainCursor d = packedCursor.get();
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            packedCursor.flush(packedStore, idx);
        }
    }

    @Benchmark public void packedCursorRandomReadWrite(Blackhole bh) {
        TerrainCursor d = packedCursor.get();
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            if ((i & 1) == 0) {
                packedCursor.load(packedStore, idx);
                bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
            } else {
                d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
                packedCursor.flush(packedStore, idx);
            }
        }
    }

    @Benchmark public int packedCursorReadSingle() {
        return packedCursor.update(packedStore, N / 2).height;
    }

    // ====================================================================
    // SPARSE — direct IntAccessor
    // ====================================================================

    @Benchmark public void sparseReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            bh.consume(sparseHeightAcc.get(sparseStore, i));
            bh.consume(sparseTempAcc.get(sparseStore, i));
            bh.consume(sparseActiveAcc.get(sparseStore, i));
        }
    }

    @Benchmark public void sparseWriteAll() {
        for (int i = 0; i < N; i++) {
            sparseHeightAcc.set(sparseStore, i, testHeight[i]);
            sparseTempAcc.set(sparseStore, i, testTemp[i]);
            sparseActiveAcc.set(sparseStore, i, testActive[i]);
        }
    }

    @Benchmark public void sparseRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            bh.consume(sparseHeightAcc.get(sparseStore, idx));
            bh.consume(sparseTempAcc.get(sparseStore, idx));
            bh.consume(sparseActiveAcc.get(sparseStore, idx));
        }
    }

    @Benchmark public void sparseRandomWrite() {
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            sparseHeightAcc.set(sparseStore, idx, testHeight[i]);
            sparseTempAcc.set(sparseStore, idx, testTemp[i]);
            sparseActiveAcc.set(sparseStore, idx, testActive[i]);
        }
    }

    @Benchmark public void sparseRandomReadWrite(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            if ((i & 1) == 0) {
                bh.consume(sparseHeightAcc.get(sparseStore, idx));
                bh.consume(sparseTempAcc.get(sparseStore, idx));
                bh.consume(sparseActiveAcc.get(sparseStore, idx));
            } else {
                sparseHeightAcc.set(sparseStore, idx, testHeight[i]);
                sparseTempAcc.set(sparseStore, idx, testTemp[i]);
                sparseActiveAcc.set(sparseStore, idx, testActive[i]);
            }
        }
    }

    @Benchmark public int sparseReadSingle() {
        return sparseHeightAcc.get(sparseStore, N / 2);
    }

    // ------------------------------------------------------------------ sparse RowView

    @Benchmark public void sparseRowViewReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) bh.consume(sparseRowView.get(sparseStore, i));
    }

    @Benchmark public void sparseRowViewWriteAll() {
        for (int i = 0; i < N; i++)
            sparseRowView.set(sparseStore, i, new Terrain(testHeight[i], testTemp[i], testActive[i]));
    }

    @Benchmark public void sparseRowViewRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) bh.consume(sparseRowView.get(sparseStore, randIdx[i]));
    }

    @Benchmark public void sparseRowViewRandomWrite() {
        for (int i = 0; i < N; i++)
            sparseRowView.set(sparseStore, randIdx[i], new Terrain(testHeight[i], testTemp[i], testActive[i]));
    }

    @Benchmark public void sparseRowViewRandomReadWrite(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            if ((i & 1) == 0) bh.consume(sparseRowView.get(sparseStore, idx));
            else               sparseRowView.set(sparseStore, idx, new Terrain(testHeight[i], testTemp[i], testActive[i]));
        }
    }

    // ------------------------------------------------------------------ sparse DataCursor

    @Benchmark public void sparseCursorReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            TerrainCursor d = sparseCursor.update(sparseStore, i);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
        }
    }

    @Benchmark public void sparseCursorWriteAll() {
        TerrainCursor d = sparseCursor.get();
        for (int i = 0; i < N; i++) {
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            sparseCursor.flush(sparseStore, i);
        }
    }

    @Benchmark public void sparseCursorRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            TerrainCursor d = sparseCursor.update(sparseStore, randIdx[i]);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
        }
    }

    @Benchmark public void sparseCursorRandomWrite() {
        TerrainCursor d = sparseCursor.get();
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            sparseCursor.flush(sparseStore, idx);
        }
    }

    @Benchmark public void sparseCursorRandomReadWrite(Blackhole bh) {
        TerrainCursor d = sparseCursor.get();
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            if ((i & 1) == 0) {
                sparseCursor.load(sparseStore, idx);
                bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
            } else {
                d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
                sparseCursor.flush(sparseStore, idx);
            }
        }
    }

    // ====================================================================
    // PACKED — DataCursor VarHandle (no ByteBuddy; baseline comparison)
    // ====================================================================

    @Benchmark public void packedCursorVhReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            TerrainCursor d = packedCursorVH.update(packedStore, i);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
        }
    }

    @Benchmark public void packedCursorVhWriteAll() {
        TerrainCursor d = packedCursorVH.get();
        for (int i = 0; i < N; i++) {
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            packedCursorVH.flush(packedStore, i);
        }
    }

    @Benchmark public void packedCursorVhRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            TerrainCursor d = packedCursorVH.update(packedStore, randIdx[i]);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
        }
    }

    @Benchmark public void packedCursorVhRandomWrite() {
        TerrainCursor d = packedCursorVH.get();
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            packedCursorVH.flush(packedStore, idx);
        }
    }

    @Benchmark public int packedCursorVhReadSingle() {
        return packedCursorVH.update(packedStore, N / 2).height;
    }

    // ====================================================================
    // SPARSE — DataCursor VarHandle (no ByteBuddy; baseline comparison)
    // ====================================================================

    @Benchmark public void sparseCursorVhReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            TerrainCursor d = sparseCursorVH.update(sparseStore, i);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
        }
    }

    @Benchmark public void sparseCursorVhWriteAll() {
        TerrainCursor d = sparseCursorVH.get();
        for (int i = 0; i < N; i++) {
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            sparseCursorVH.flush(sparseStore, i);
        }
    }

    @Benchmark public void sparseCursorVhRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            TerrainCursor d = sparseCursorVH.update(sparseStore, randIdx[i]);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
        }
    }

    @Benchmark public void sparseCursorVhRandomWrite() {
        TerrainCursor d = sparseCursorVH.get();
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            sparseCursorVH.flush(sparseStore, idx);
        }
    }

    // ====================================================================
    // OCTREE — direct IntAccessor
    // ====================================================================

    @Benchmark public void octreeReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            bh.consume(octreeHeightAcc.get(octreeStore, octreeRows[i]));
            bh.consume(octreeTempAcc.get(octreeStore, octreeRows[i]));
            bh.consume(octreeActiveAcc.get(octreeStore, octreeRows[i]));
        }
    }

    @Benchmark public void octreeWriteAll() {
        for (int i = 0; i < N; i++) {
            octreeHeightAcc.set(octreeStore, octreeRows[i], testHeight[i]);
            octreeTempAcc.set(octreeStore, octreeRows[i], testTemp[i]);
            octreeActiveAcc.set(octreeStore, octreeRows[i], testActive[i]);
        }
    }

    @Benchmark public void octreeBatchWriteAll() {
        octreeStore.beginBatch();
        for (int i = 0; i < N; i++) {
            octreeHeightAcc.set(octreeStore, octreeRows[i], testHeight[i]);
            octreeTempAcc.set(octreeStore, octreeRows[i], testTemp[i]);
            octreeActiveAcc.set(octreeStore, octreeRows[i], testActive[i]);
        }
        octreeStore.endBatch();
    }

    @Benchmark public void octreeRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int row = randOctreeRows[i];
            bh.consume(octreeHeightAcc.get(octreeStore, row));
            bh.consume(octreeTempAcc.get(octreeStore, row));
            bh.consume(octreeActiveAcc.get(octreeStore, row));
        }
    }

    @Benchmark public void octreeRandomWrite() {
        for (int i = 0; i < N; i++) {
            int row = randOctreeRows[i];
            octreeHeightAcc.set(octreeStore, row, testHeight[i]);
            octreeTempAcc.set(octreeStore, row, testTemp[i]);
            octreeActiveAcc.set(octreeStore, row, testActive[i]);
        }
    }

    @Benchmark public void octreeRandomReadWrite(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int row = randOctreeRows[i];
            if ((i & 1) == 0) {
                bh.consume(octreeHeightAcc.get(octreeStore, row));
                bh.consume(octreeTempAcc.get(octreeStore, row));
                bh.consume(octreeActiveAcc.get(octreeStore, row));
            } else {
                octreeHeightAcc.set(octreeStore, row, testHeight[i]);
                octreeTempAcc.set(octreeStore, row, testTemp[i]);
                octreeActiveAcc.set(octreeStore, row, testActive[i]);
            }
        }
    }

    @Benchmark public int octreeReadSingle() {
        return octreeHeightAcc.get(octreeStore, octreeRows[N / 2]);
    }

    // ------------------------------------------------------------------ octree RowView

    @Benchmark public void octreeRowViewReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) bh.consume(octreeRowView.get(octreeStore, octreeRows[i]));
    }

    @Benchmark public void octreeRowViewWriteAll() {
        for (int i = 0; i < N; i++)
            octreeRowView.set(octreeStore, octreeRows[i], new Terrain(testHeight[i], testTemp[i], testActive[i]));
    }

    @Benchmark public void octreeRowViewRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) bh.consume(octreeRowView.get(octreeStore, randOctreeRows[i]));
    }

    @Benchmark public void octreeRowViewRandomWrite() {
        for (int i = 0; i < N; i++)
            octreeRowView.set(octreeStore, randOctreeRows[i], new Terrain(testHeight[i], testTemp[i], testActive[i]));
    }

    @Benchmark public void octreeRowViewRandomReadWrite(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int row = randOctreeRows[i];
            if ((i & 1) == 0) bh.consume(octreeRowView.get(octreeStore, row));
            else               octreeRowView.set(octreeStore, row, new Terrain(testHeight[i], testTemp[i], testActive[i]));
        }
    }

    // ------------------------------------------------------------------ octree DataCursor

    @Benchmark public void octreeCursorReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            TerrainCursor d = octreeCursor.update(octreeStore, octreeRows[i]);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
        }
    }

    @Benchmark public void octreeCursorWriteAll() {
        TerrainCursor d = octreeCursor.get();
        for (int i = 0; i < N; i++) {
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            octreeCursor.flush(octreeStore, octreeRows[i]);
        }
    }

    @Benchmark public void octreeCursorRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            TerrainCursor d = octreeCursor.update(octreeStore, randOctreeRows[i]);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
        }
    }

    @Benchmark public void octreeCursorRandomWrite() {
        TerrainCursor d = octreeCursor.get();
        for (int i = 0; i < N; i++) {
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            octreeCursor.flush(octreeStore, randOctreeRows[i]);
        }
    }

    @Benchmark public void octreeCursorRandomReadWrite(Blackhole bh) {
        TerrainCursor d = octreeCursor.get();
        for (int i = 0; i < N; i++) {
            int row = randOctreeRows[i];
            if ((i & 1) == 0) {
                octreeCursor.load(octreeStore, row);
                bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
            } else {
                d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
                octreeCursor.flush(octreeStore, row);
            }
        }
    }

    // ====================================================================
    // FAST OCTREE — direct IntAccessor
    // ====================================================================

    @Benchmark public void fastOctreeReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            bh.consume(fastOctreeHeightAcc.get(fastOctreeStore, fastOctreeRows[i]));
            bh.consume(fastOctreeTempAcc.get(fastOctreeStore, fastOctreeRows[i]));
            bh.consume(fastOctreeActiveAcc.get(fastOctreeStore, fastOctreeRows[i]));
        }
    }

    @Benchmark public void fastOctreeWriteAll() {
        for (int i = 0; i < N; i++) {
            fastOctreeHeightAcc.set(fastOctreeStore, fastOctreeRows[i], testHeight[i]);
            fastOctreeTempAcc.set(fastOctreeStore, fastOctreeRows[i], testTemp[i]);
            fastOctreeActiveAcc.set(fastOctreeStore, fastOctreeRows[i], testActive[i]);
        }
    }

    @Benchmark public void fastOctreeBatchWriteAll() {
        fastOctreeStore.beginBatch();
        for (int i = 0; i < N; i++) {
            fastOctreeHeightAcc.set(fastOctreeStore, fastOctreeRows[i], testHeight[i]);
            fastOctreeTempAcc.set(fastOctreeStore, fastOctreeRows[i], testTemp[i]);
            fastOctreeActiveAcc.set(fastOctreeStore, fastOctreeRows[i], testActive[i]);
        }
        fastOctreeStore.endBatch();
    }

    @Benchmark public void fastOctreeRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int row = randFastOctreeRows[i];
            bh.consume(fastOctreeHeightAcc.get(fastOctreeStore, row));
            bh.consume(fastOctreeTempAcc.get(fastOctreeStore, row));
            bh.consume(fastOctreeActiveAcc.get(fastOctreeStore, row));
        }
    }

    @Benchmark public void fastOctreeRandomWrite() {
        for (int i = 0; i < N; i++) {
            int row = randFastOctreeRows[i];
            fastOctreeHeightAcc.set(fastOctreeStore, row, testHeight[i]);
            fastOctreeTempAcc.set(fastOctreeStore, row, testTemp[i]);
            fastOctreeActiveAcc.set(fastOctreeStore, row, testActive[i]);
        }
    }

    @Benchmark public void fastOctreeRandomReadWrite(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int row = randFastOctreeRows[i];
            if ((i & 1) == 0) {
                bh.consume(fastOctreeHeightAcc.get(fastOctreeStore, row));
                bh.consume(fastOctreeTempAcc.get(fastOctreeStore, row));
                bh.consume(fastOctreeActiveAcc.get(fastOctreeStore, row));
            } else {
                fastOctreeHeightAcc.set(fastOctreeStore, row, testHeight[i]);
                fastOctreeTempAcc.set(fastOctreeStore, row, testTemp[i]);
                fastOctreeActiveAcc.set(fastOctreeStore, row, testActive[i]);
            }
        }
    }

    @Benchmark public int fastOctreeReadSingle() {
        return fastOctreeHeightAcc.get(fastOctreeStore, fastOctreeRows[N / 2]);
    }

    // ------------------------------------------------------------------ fast octree RowView

    @Benchmark public void fastOctreeRowViewReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) bh.consume(fastOctreeRowView.get(fastOctreeStore, fastOctreeRows[i]));
    }

    @Benchmark public void fastOctreeRowViewWriteAll() {
        for (int i = 0; i < N; i++)
            fastOctreeRowView.set(fastOctreeStore, fastOctreeRows[i], new Terrain(testHeight[i], testTemp[i], testActive[i]));
    }

    @Benchmark public void fastOctreeRowViewRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) bh.consume(fastOctreeRowView.get(fastOctreeStore, randFastOctreeRows[i]));
    }

    @Benchmark public void fastOctreeRowViewRandomWrite() {
        for (int i = 0; i < N; i++)
            fastOctreeRowView.set(fastOctreeStore, randFastOctreeRows[i], new Terrain(testHeight[i], testTemp[i], testActive[i]));
    }

    @Benchmark public void fastOctreeRowViewRandomReadWrite(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int row = randFastOctreeRows[i];
            if ((i & 1) == 0) bh.consume(fastOctreeRowView.get(fastOctreeStore, row));
            else               fastOctreeRowView.set(fastOctreeStore, row, new Terrain(testHeight[i], testTemp[i], testActive[i]));
        }
    }

    // ------------------------------------------------------------------ fast octree DataCursor

    @Benchmark public void fastOctreeCursorReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            TerrainCursor d = fastOctreeCursor.update(fastOctreeStore, fastOctreeRows[i]);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
        }
    }

    @Benchmark public void fastOctreeCursorWriteAll() {
        TerrainCursor d = fastOctreeCursor.get();
        for (int i = 0; i < N; i++) {
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            fastOctreeCursor.flush(fastOctreeStore, fastOctreeRows[i]);
        }
    }

    @Benchmark public void fastOctreeCursorRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            TerrainCursor d = fastOctreeCursor.update(fastOctreeStore, randFastOctreeRows[i]);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
        }
    }

    @Benchmark public void fastOctreeCursorRandomWrite() {
        TerrainCursor d = fastOctreeCursor.get();
        for (int i = 0; i < N; i++) {
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            fastOctreeCursor.flush(fastOctreeStore, randFastOctreeRows[i]);
        }
    }

    @Benchmark public void fastOctreeCursorRandomReadWrite(Blackhole bh) {
        TerrainCursor d = fastOctreeCursor.get();
        for (int i = 0; i < N; i++) {
            int row = randFastOctreeRows[i];
            if ((i & 1) == 0) {
                fastOctreeCursor.load(fastOctreeStore, row);
                bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
            } else {
                d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
                fastOctreeCursor.flush(fastOctreeStore, row);
            }
        }
    }

    // ====================================================================
    // BASELINE — parallel primitive arrays (reference)
    // ====================================================================

    @Benchmark public void baselineReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            bh.consume(baselineHeight[i]);
            bh.consume(baselineTemp[i]);
            bh.consume(baselineActive[i]);
        }
    }

    @Benchmark public void baselineWriteAll() {
        for (int i = 0; i < N; i++) {
            baselineHeight[i] = testHeight[i];
            baselineTemp[i]   = testTemp[i];
            baselineActive[i] = testActive[i];
        }
    }

    @Benchmark public void baselineRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            bh.consume(baselineHeight[idx]);
            bh.consume(baselineTemp[idx]);
            bh.consume(baselineActive[idx]);
        }
    }

    @Benchmark public void baselineRandomWrite() {
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            baselineHeight[idx] = testHeight[i];
            baselineTemp[idx]   = testTemp[i];
            baselineActive[idx] = testActive[i];
        }
    }

    @Benchmark public void baselineRandomReadWrite(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            if ((i & 1) == 0) {
                bh.consume(baselineHeight[idx]);
                bh.consume(baselineTemp[idx]);
                bh.consume(baselineActive[idx]);
            } else {
                baselineHeight[idx] = testHeight[i];
                baselineTemp[idx]   = testTemp[i];
                baselineActive[idx] = testActive[i];
            }
        }
    }

    @Benchmark public int baselineReadSingle() {
        return baselineHeight[N / 2];
    }

    // ====================================================================
    // HASHMAP — boxed Object[] reference
    // ====================================================================

    @Benchmark public void hashmapReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            Object[] row = hashmapStore.get(i);
            bh.consume((int) row[0]);
            bh.consume((double) row[1]);
            bh.consume((boolean) row[2]);
        }
    }

    @Benchmark public void hashmapWriteAll() {
        for (int i = 0; i < N; i++)
            hashmapStore.put(i, new Object[]{testHeight[i], testTemp[i], testActive[i]});
    }

    @Benchmark public void hashmapRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            Object[] row = hashmapStore.get(randIdx[i]);
            bh.consume((int) row[0]);
            bh.consume((double) row[1]);
            bh.consume((boolean) row[2]);
        }
    }

    @Benchmark public void hashmapRandomWrite() {
        for (int i = 0; i < N; i++)
            hashmapStore.put(randIdx[i], new Object[]{testHeight[i], testTemp[i], testActive[i]});
    }

    @Benchmark public void hashmapRandomReadWrite(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            if ((i & 1) == 0) {
                Object[] row = hashmapStore.get(idx);
                bh.consume((int) row[0]);
                bh.consume((double) row[1]);
                bh.consume((boolean) row[2]);
            } else {
                hashmapStore.put(idx, new Object[]{testHeight[i], testTemp[i], testActive[i]});
            }
        }
    }

    @Benchmark public int hashmapReadSingle() {
        return (int) hashmapStore.get(N / 2)[0];
    }

    // ====================================================================
    // MULTI-COMPONENT — baseline: 5 parallel primitive arrays (Terrain + Water)
    // ====================================================================

    @Benchmark public void multiBaselineReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            bh.consume(multiBaselineHeight[i]);
            bh.consume(multiBaselineTemp[i]);
            bh.consume(multiBaselineActive[i]);
            bh.consume(multiBaselineSalinity[i]);
            bh.consume(multiBaselineFrozen[i]);
        }
    }

    @Benchmark public void multiBaselineWriteAll() {
        for (int i = 0; i < N; i++) {
            multiBaselineHeight[i]   = testHeight[i];
            multiBaselineTemp[i]     = testTemp[i];
            multiBaselineActive[i]   = testActive[i];
            multiBaselineSalinity[i] = testSalinity[i];
            multiBaselineFrozen[i]   = testFrozen[i];
        }
    }

    @Benchmark public void multiBaselineRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            bh.consume(multiBaselineHeight[idx]);
            bh.consume(multiBaselineTemp[idx]);
            bh.consume(multiBaselineActive[idx]);
            bh.consume(multiBaselineSalinity[idx]);
            bh.consume(multiBaselineFrozen[idx]);
        }
    }

    @Benchmark public void multiBaselineRandomWrite() {
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            multiBaselineHeight[idx]   = testHeight[i];
            multiBaselineTemp[idx]     = testTemp[i];
            multiBaselineActive[idx]   = testActive[i];
            multiBaselineSalinity[idx] = testSalinity[i];
            multiBaselineFrozen[idx]   = testFrozen[i];
        }
    }

    @Benchmark public void multiBaselineRandomReadWrite(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            if ((i & 1) == 0) {
                bh.consume(multiBaselineHeight[idx]);
                bh.consume(multiBaselineTemp[idx]);
                bh.consume(multiBaselineActive[idx]);
                bh.consume(multiBaselineSalinity[idx]);
                bh.consume(multiBaselineFrozen[idx]);
            } else {
                multiBaselineHeight[idx]   = testHeight[i];
                multiBaselineTemp[idx]     = testTemp[i];
                multiBaselineActive[idx]   = testActive[i];
                multiBaselineSalinity[idx] = testSalinity[i];
                multiBaselineFrozen[idx]   = testFrozen[i];
            }
        }
    }

    // ====================================================================
    // MULTI-COMPONENT — packed DataCursor (Terrain + Water via WorldCursor)
    // ====================================================================

    @Benchmark public void multiPackedCursorReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            WorldCursor d = multiPackedCursor.update(multiPackedStore, i);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
            bh.consume(d.salinity); bh.consume(d.frozen);
        }
    }

    @Benchmark public void multiPackedCursorWriteAll() {
        WorldCursor d = multiPackedCursor.get();
        for (int i = 0; i < N; i++) {
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            d.salinity = testSalinity[i]; d.frozen = testFrozen[i];
            multiPackedCursor.flush(multiPackedStore, i);
        }
    }

    @Benchmark public void multiPackedCursorRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            WorldCursor d = multiPackedCursor.update(multiPackedStore, randIdx[i]);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
            bh.consume(d.salinity); bh.consume(d.frozen);
        }
    }

    @Benchmark public void multiPackedCursorRandomWrite() {
        WorldCursor d = multiPackedCursor.get();
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            d.salinity = testSalinity[i]; d.frozen = testFrozen[i];
            multiPackedCursor.flush(multiPackedStore, idx);
        }
    }

    @Benchmark public void multiPackedCursorRandomReadWrite(Blackhole bh) {
        WorldCursor d = multiPackedCursor.get();
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            if ((i & 1) == 0) {
                multiPackedCursor.load(multiPackedStore, idx);
                bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
                bh.consume(d.salinity); bh.consume(d.frozen);
            } else {
                d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
                d.salinity = testSalinity[i]; d.frozen = testFrozen[i];
                multiPackedCursor.flush(multiPackedStore, idx);
            }
        }
    }

    // ====================================================================
    // MULTI-COMPONENT — sparse DataCursor (Terrain + Water via WorldCursor)
    // ====================================================================

    @Benchmark public void multiSparseCursorReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            WorldCursor d = multiSparseCursor.update(multiSparseStore, i);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
            bh.consume(d.salinity); bh.consume(d.frozen);
        }
    }

    @Benchmark public void multiSparseCursorWriteAll() {
        WorldCursor d = multiSparseCursor.get();
        for (int i = 0; i < N; i++) {
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            d.salinity = testSalinity[i]; d.frozen = testFrozen[i];
            multiSparseCursor.flush(multiSparseStore, i);
        }
    }

    @Benchmark public void multiSparseCursorRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            WorldCursor d = multiSparseCursor.update(multiSparseStore, randIdx[i]);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
            bh.consume(d.salinity); bh.consume(d.frozen);
        }
    }

    @Benchmark public void multiSparseCursorRandomWrite() {
        WorldCursor d = multiSparseCursor.get();
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            d.salinity = testSalinity[i]; d.frozen = testFrozen[i];
            multiSparseCursor.flush(multiSparseStore, idx);
        }
    }

    @Benchmark public void multiSparseCursorRandomReadWrite(Blackhole bh) {
        WorldCursor d = multiSparseCursor.get();
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            if ((i & 1) == 0) {
                multiSparseCursor.load(multiSparseStore, idx);
                bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
                bh.consume(d.salinity); bh.consume(d.frozen);
            } else {
                d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
                d.salinity = testSalinity[i]; d.frozen = testFrozen[i];
                multiSparseCursor.flush(multiSparseStore, idx);
            }
        }
    }

    // ====================================================================
    // MULTI-COMPONENT — octree DataCursor (Terrain + Water via WorldCursor)
    // ====================================================================

    @Benchmark public void multiOctreeCursorReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            WorldCursor d = multiOctreeCursor.update(multiOctreeStore, multiOctreeRows[i]);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
            bh.consume(d.salinity); bh.consume(d.frozen);
        }
    }

    @Benchmark public void multiOctreeCursorWriteAll() {
        WorldCursor d = multiOctreeCursor.get();
        for (int i = 0; i < N; i++) {
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            d.salinity = testSalinity[i]; d.frozen = testFrozen[i];
            multiOctreeCursor.flush(multiOctreeStore, multiOctreeRows[i]);
        }
    }

    @Benchmark public void multiOctreeCursorRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            WorldCursor d = multiOctreeCursor.update(multiOctreeStore, randMultiOctreeRows[i]);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
            bh.consume(d.salinity); bh.consume(d.frozen);
        }
    }

    @Benchmark public void multiOctreeCursorRandomWrite() {
        WorldCursor d = multiOctreeCursor.get();
        for (int i = 0; i < N; i++) {
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            d.salinity = testSalinity[i]; d.frozen = testFrozen[i];
            multiOctreeCursor.flush(multiOctreeStore, randMultiOctreeRows[i]);
        }
    }

    @Benchmark public void multiOctreeCursorRandomReadWrite(Blackhole bh) {
        WorldCursor d = multiOctreeCursor.get();
        for (int i = 0; i < N; i++) {
            int row = randMultiOctreeRows[i];
            if ((i & 1) == 0) {
                multiOctreeCursor.load(multiOctreeStore, row);
                bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
                bh.consume(d.salinity); bh.consume(d.frozen);
            } else {
                d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
                d.salinity = testSalinity[i]; d.frozen = testFrozen[i];
                multiOctreeCursor.flush(multiOctreeStore, row);
            }
        }
    }

    // ====================================================================
    // MULTI-COMPONENT — fast octree DataCursor (Terrain + Water via WorldCursor)
    // ====================================================================

    @Benchmark public void multiFastOctreeCursorReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            WorldCursor d = multiFastOctreeCursor.update(multiFastOctreeStore, multiFastOctreeRows[i]);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
            bh.consume(d.salinity); bh.consume(d.frozen);
        }
    }

    @Benchmark public void multiFastOctreeCursorWriteAll() {
        WorldCursor d = multiFastOctreeCursor.get();
        for (int i = 0; i < N; i++) {
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            d.salinity = testSalinity[i]; d.frozen = testFrozen[i];
            multiFastOctreeCursor.flush(multiFastOctreeStore, multiFastOctreeRows[i]);
        }
    }

    @Benchmark public void multiFastOctreeCursorRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            WorldCursor d = multiFastOctreeCursor.update(multiFastOctreeStore, randMultiFastOctreeRows[i]);
            bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
            bh.consume(d.salinity); bh.consume(d.frozen);
        }
    }

    @Benchmark public void multiFastOctreeCursorRandomWrite() {
        WorldCursor d = multiFastOctreeCursor.get();
        for (int i = 0; i < N; i++) {
            d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
            d.salinity = testSalinity[i]; d.frozen = testFrozen[i];
            multiFastOctreeCursor.flush(multiFastOctreeStore, randMultiFastOctreeRows[i]);
        }
    }

    @Benchmark public void multiFastOctreeCursorRandomReadWrite(Blackhole bh) {
        WorldCursor d = multiFastOctreeCursor.get();
        for (int i = 0; i < N; i++) {
            int row = randMultiFastOctreeRows[i];
            if ((i & 1) == 0) {
                multiFastOctreeCursor.load(multiFastOctreeStore, row);
                bh.consume(d.height); bh.consume(d.temperature); bh.consume(d.active);
                bh.consume(d.salinity); bh.consume(d.frozen);
            } else {
                d.height = testHeight[i]; d.temperature = testTemp[i]; d.active = testActive[i];
                d.salinity = testSalinity[i]; d.frozen = testFrozen[i];
                multiFastOctreeCursor.flush(multiFastOctreeStore, row);
            }
        }
    }

    // ====================================================================
    // RAW STORE — array-backed, no bit compression
    // ====================================================================

    RawDataStore<Terrain> rawStore;
    IntAccessor    rawHeightAcc;
    DoubleAccessor rawTempAcc;
    BoolAccessor   rawActiveAcc;
    RowView<Terrain> rawRowView;

    @Benchmark public void rawReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            bh.consume(rawHeightAcc.get(rawStore, i));
            bh.consume(rawTempAcc.get(rawStore, i));
            bh.consume(rawActiveAcc.get(rawStore, i));
        }
    }

    @Benchmark public void rawWriteAll() {
        for (int i = 0; i < N; i++) {
            rawHeightAcc.set(rawStore, i, testHeight[i]);
            rawTempAcc.set(rawStore, i, testTemp[i]);
            rawActiveAcc.set(rawStore, i, testActive[i]);
        }
    }

    @Benchmark public void rawRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            bh.consume(rawHeightAcc.get(rawStore, idx));
            bh.consume(rawTempAcc.get(rawStore, idx));
            bh.consume(rawActiveAcc.get(rawStore, idx));
        }
    }

    @Benchmark public void rawRandomWrite() {
        for (int i = 0; i < N; i++) {
            int idx = randIdx[i];
            rawHeightAcc.set(rawStore, idx, testHeight[i]);
            rawTempAcc.set(rawStore, idx, testTemp[i]);
            rawActiveAcc.set(rawStore, idx, testActive[i]);
        }
    }

    @Benchmark public int rawReadSingle() {
        return rawHeightAcc.get(rawStore, N / 2);
    }

    @Benchmark public void rawRowViewReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) bh.consume(rawRowView.get(rawStore, i));
    }

    @Benchmark public void rawRowViewWriteAll() {
        for (int i = 0; i < N; i++)
            rawRowView.set(rawStore, i, new Terrain(testHeight[i], testTemp[i], testActive[i]));
    }

    @Benchmark public void rawRowViewRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) bh.consume(rawRowView.get(rawStore, randIdx[i]));
    }

    @Benchmark public void rawRowViewRandomWrite() {
        for (int i = 0; i < N; i++)
            rawRowView.set(rawStore, randIdx[i], new Terrain(testHeight[i], testTemp[i], testActive[i]));
    }

    // ====================================================================
    // COLLECTIONS — PackedList and PackedIntMap
    // ====================================================================

    io.github.zzuegg.jbinary.collections.PackedList<Terrain>    packedList;
    io.github.zzuegg.jbinary.collections.PackedIntMap<Terrain>  packedIntMap;

    @Benchmark public void packedListReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) bh.consume(packedList.get(i));
    }

    @Benchmark public void packedListWriteAll() {
        for (int i = 0; i < N; i++)
            packedList.set(i, new Terrain(testHeight[i], testTemp[i], testActive[i]));
    }

    @Benchmark public void packedListRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) bh.consume(packedList.get(randIdx[i]));
    }

    @Benchmark public void packedListRandomWrite() {
        for (int i = 0; i < N; i++)
            packedList.set(randIdx[i], new Terrain(testHeight[i], testTemp[i], testActive[i]));
    }

    @Benchmark public void packedIntMapReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) bh.consume(packedIntMap.get(i));
    }

    @Benchmark public void packedIntMapWriteAll() {
        for (int i = 0; i < N; i++)
            packedIntMap.put(i, new Terrain(testHeight[i], testTemp[i], testActive[i]));
    }

    @Benchmark public void packedIntMapRandomRead(Blackhole bh) {
        for (int i = 0; i < N; i++) bh.consume(packedIntMap.get(randIdx[i]));
    }

    @Benchmark public void packedIntMapRandomWrite() {
        for (int i = 0; i < N; i++)
            packedIntMap.put(randIdx[i], new Terrain(testHeight[i], testTemp[i], testActive[i]));
    }
}
