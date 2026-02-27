package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.BoolAccessor;
import io.github.zzuegg.jbinary.accessor.DoubleAccessor;
import io.github.zzuegg.jbinary.accessor.IntAccessor;
import io.github.zzuegg.jbinary.annotation.BitField;
import io.github.zzuegg.jbinary.annotation.BoolField;
import io.github.zzuegg.jbinary.annotation.DecimalField;
import io.github.zzuegg.jbinary.octree.FastOctreeDataStore;
import io.github.zzuegg.jbinary.octree.OctreeDataStore;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.HashMap;
import java.util.Map;
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

    // ------------------------------------------------------------------ packed store
    DataStore packedStore;
    IntAccessor    packedHeightAcc;
    DoubleAccessor packedTempAcc;
    BoolAccessor   packedActiveAcc;
    RowView<Terrain> packedRowView;

    // ------------------------------------------------------------------ sparse store
    DataStore sparseStore;
    IntAccessor    sparseHeightAcc;
    DoubleAccessor sparseTempAcc;
    BoolAccessor   sparseActiveAcc;

    // ------------------------------------------------------------------ octree store
    // maxDepth=4 → 16×16×16 = 4 096 voxels; we use N=1024 of them
    OctreeDataStore octreeStore;
    IntAccessor    octreeHeightAcc;
    DoubleAccessor octreeTempAcc;
    BoolAccessor   octreeActiveAcc;
    int[]          octreeRows;   // pre-computed Morton-code row indices

    // ------------------------------------------------------------------ fast octree store
    FastOctreeDataStore fastOctreeStore;
    IntAccessor    fastOctreeHeightAcc;
    DoubleAccessor fastOctreeTempAcc;
    BoolAccessor   fastOctreeActiveAcc;
    int[]          fastOctreeRows;

    // ------------------------------------------------------------------ baseline store (parallel arrays)
    int[]     baselineHeight;
    double[]  baselineTemp;
    boolean[] baselineActive;

    // ------------------------------------------------------------------ HashMap store (row index → Object[]{height, temp, active}, boxed values)
    Map<Integer, Object[]> hashmapStore;

    static final int N = 1024;

    @Setup
    public void setup() {
        // packed store
        packedStore = DataStore.of(N, Terrain.class);
        packedHeightAcc = Accessors.intFieldInStore(packedStore, Terrain.class, "height");
        packedTempAcc   = Accessors.doubleFieldInStore(packedStore, Terrain.class, "temperature");
        packedActiveAcc = Accessors.boolFieldInStore(packedStore, Terrain.class, "active");
        packedRowView   = RowView.of(packedStore, Terrain.class);

        // sparse store
        sparseStore = DataStore.sparse(N, Terrain.class);
        sparseHeightAcc = Accessors.intFieldInStore(sparseStore, Terrain.class, "height");
        sparseTempAcc   = Accessors.doubleFieldInStore(sparseStore, Terrain.class, "temperature");
        sparseActiveAcc = Accessors.boolFieldInStore(sparseStore, Terrain.class, "active");

        // octree store (maxDepth=4 → 16×16×16 space; first N Morton-coded rows)
        octreeStore = OctreeDataStore.builder(4)
                .component(Terrain.class)
                .build();
        octreeHeightAcc = Accessors.intFieldInStore(octreeStore, Terrain.class, "height");
        octreeTempAcc   = Accessors.doubleFieldInStore(octreeStore, Terrain.class, "temperature");
        octreeActiveAcc = Accessors.boolFieldInStore(octreeStore, Terrain.class, "active");

        // pre-compute N octree row indices using x,y,z in the 16×16×16 space
        octreeRows = new int[N];
        for (int i = 0; i < N; i++) {
            int x = i & 0xF;        // 0..15
            int y = (i >> 4) & 0xF; // 0..15
            int z = (i >> 8) & 0x3; // 0..3 (N=1024 → z in 0..3)
            octreeRows[i] = octreeStore.row(x, y, z);
        }

        // fast octree store (same layout as octree)
        fastOctreeStore = FastOctreeDataStore.builder(4)
                .component(Terrain.class)
                .build();
        fastOctreeHeightAcc = Accessors.intFieldInStore(fastOctreeStore, Terrain.class, "height");
        fastOctreeTempAcc   = Accessors.doubleFieldInStore(fastOctreeStore, Terrain.class, "temperature");
        fastOctreeActiveAcc = Accessors.boolFieldInStore(fastOctreeStore, Terrain.class, "active");

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

        for (int i = 0; i < N; i++) {
            int h    = i % 256;
            double t = (i % 100) - 50.0;
            boolean a = (i & 1) == 0;

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
    }

    // ------------------------------------------------------------------ packed benchmarks

    @Benchmark
    public void packedReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            bh.consume(packedHeightAcc.get(packedStore, i));
            bh.consume(packedTempAcc.get(packedStore, i));
            bh.consume(packedActiveAcc.get(packedStore, i));
        }
    }

    @Benchmark
    public void packedWriteAll() {
        for (int i = 0; i < N; i++) {
            packedHeightAcc.set(packedStore, i, i % 256);
            packedTempAcc.set(packedStore, i, (i % 100) - 50.0);
            packedActiveAcc.set(packedStore, i, (i & 1) == 0);
        }
    }

    @Benchmark
    public int packedReadSingle() {
        return packedHeightAcc.get(packedStore, N / 2);
    }

    // ------------------------------------------------------------------ packed RowView benchmarks

    @Benchmark
    public void packedRowViewReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            bh.consume(packedRowView.get(packedStore, i));
        }
    }

    @Benchmark
    public void packedRowViewWriteAll() {
        for (int i = 0; i < N; i++) {
            packedRowView.set(packedStore, i,
                    new Terrain(i % 256, (i % 100) - 50.0, (i & 1) == 0));
        }
    }

    // ------------------------------------------------------------------ sparse benchmarks

    @Benchmark
    public void sparseReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            bh.consume(sparseHeightAcc.get(sparseStore, i));
            bh.consume(sparseTempAcc.get(sparseStore, i));
            bh.consume(sparseActiveAcc.get(sparseStore, i));
        }
    }

    @Benchmark
    public void sparseWriteAll() {
        for (int i = 0; i < N; i++) {
            sparseHeightAcc.set(sparseStore, i, i % 256);
            sparseTempAcc.set(sparseStore, i, (i % 100) - 50.0);
            sparseActiveAcc.set(sparseStore, i, (i & 1) == 0);
        }
    }

    @Benchmark
    public int sparseReadSingle() {
        return sparseHeightAcc.get(sparseStore, N / 2);
    }

    // ------------------------------------------------------------------ octree benchmarks

    @Benchmark
    public void octreeReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            bh.consume(octreeHeightAcc.get(octreeStore, octreeRows[i]));
            bh.consume(octreeTempAcc.get(octreeStore, octreeRows[i]));
            bh.consume(octreeActiveAcc.get(octreeStore, octreeRows[i]));
        }
    }

    @Benchmark
    public void octreeWriteAll() {
        for (int i = 0; i < N; i++) {
            octreeHeightAcc.set(octreeStore, octreeRows[i], i % 256);
            octreeTempAcc.set(octreeStore, octreeRows[i], (i % 100) - 50.0);
            octreeActiveAcc.set(octreeStore, octreeRows[i], (i & 1) == 0);
        }
    }

    @Benchmark
    public int octreeReadSingle() {
        return octreeHeightAcc.get(octreeStore, octreeRows[N / 2]);
    }

    @Benchmark
    public void octreeBatchWriteAll() {
        octreeStore.beginBatch();
        for (int i = 0; i < N; i++) {
            octreeHeightAcc.set(octreeStore, octreeRows[i], i % 256);
            octreeTempAcc.set(octreeStore, octreeRows[i], (i % 100) - 50.0);
            octreeActiveAcc.set(octreeStore, octreeRows[i], (i & 1) == 0);
        }
        octreeStore.endBatch();
    }

    // ------------------------------------------------------------------ fast octree benchmarks

    @Benchmark
    public void fastOctreeReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            bh.consume(fastOctreeHeightAcc.get(fastOctreeStore, fastOctreeRows[i]));
            bh.consume(fastOctreeTempAcc.get(fastOctreeStore, fastOctreeRows[i]));
            bh.consume(fastOctreeActiveAcc.get(fastOctreeStore, fastOctreeRows[i]));
        }
    }

    @Benchmark
    public void fastOctreeWriteAll() {
        for (int i = 0; i < N; i++) {
            fastOctreeHeightAcc.set(fastOctreeStore, fastOctreeRows[i], i % 256);
            fastOctreeTempAcc.set(fastOctreeStore, fastOctreeRows[i], (i % 100) - 50.0);
            fastOctreeActiveAcc.set(fastOctreeStore, fastOctreeRows[i], (i & 1) == 0);
        }
    }

    @Benchmark
    public int fastOctreeReadSingle() {
        return fastOctreeHeightAcc.get(fastOctreeStore, fastOctreeRows[N / 2]);
    }

    @Benchmark
    public void fastOctreeBatchWriteAll() {
        fastOctreeStore.beginBatch();
        for (int i = 0; i < N; i++) {
            fastOctreeHeightAcc.set(fastOctreeStore, fastOctreeRows[i], i % 256);
            fastOctreeTempAcc.set(fastOctreeStore, fastOctreeRows[i], (i % 100) - 50.0);
            fastOctreeActiveAcc.set(fastOctreeStore, fastOctreeRows[i], (i & 1) == 0);
        }
        fastOctreeStore.endBatch();
    }

    // ------------------------------------------------------------------ baseline benchmarks

    @Benchmark
    public void baselineReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            bh.consume(baselineHeight[i]);
            bh.consume(baselineTemp[i]);
            bh.consume(baselineActive[i]);
        }
    }

    @Benchmark
    public void baselineWriteAll() {
        for (int i = 0; i < N; i++) {
            baselineHeight[i] = i % 256;
            baselineTemp[i]   = (i % 100) - 50.0;
            baselineActive[i] = (i & 1) == 0;
        }
    }

    @Benchmark
    public int baselineReadSingle() {
        return baselineHeight[N / 2];
    }

    // ------------------------------------------------------------------ hashmap benchmarks

    @Benchmark
    public void hashmapReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            Object[] row = hashmapStore.get(i);
            bh.consume((int) row[0]);
            bh.consume((double) row[1]);
            bh.consume((boolean) row[2]);
        }
    }

    @Benchmark
    public void hashmapWriteAll() {
        for (int i = 0; i < N; i++) {
            hashmapStore.put(i, new Object[]{i % 256, (i % 100) - 50.0, (i & 1) == 0});
        }
    }

    @Benchmark
    public int hashmapReadSingle() {
        return (int) hashmapStore.get(N / 2)[0];
    }
}
