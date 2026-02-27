package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.BoolAccessor;
import io.github.zzuegg.jbinary.accessor.DoubleAccessor;
import io.github.zzuegg.jbinary.accessor.IntAccessor;
import io.github.zzuegg.jbinary.annotation.BitField;
import io.github.zzuegg.jbinary.annotation.BoolField;
import io.github.zzuegg.jbinary.annotation.DecimalField;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

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
    DataStore store;
    IntAccessor    heightAcc;
    DoubleAccessor tempAcc;
    BoolAccessor   activeAcc;

    // ------------------------------------------------------------------ baseline store (parallel arrays)
    int[]     baselineHeight;
    double[]  baselineTemp;
    boolean[] baselineActive;

    static final int N = 1024;

    @Setup
    public void setup() {
        store = DataStore.of(N, Terrain.class);
        heightAcc = Accessors.intFieldInStore(store, Terrain.class, "height");
        tempAcc   = Accessors.doubleFieldInStore(store, Terrain.class, "temperature");
        activeAcc = Accessors.boolFieldInStore(store, Terrain.class, "active");

        baselineHeight = new int[N];
        baselineTemp   = new double[N];
        baselineActive = new boolean[N];

        for (int i = 0; i < N; i++) {
            heightAcc.set(store, i, i % 256);
            tempAcc.set(store, i, (i % 100) - 50.0);
            activeAcc.set(store, i, (i & 1) == 0);

            baselineHeight[i] = i % 256;
            baselineTemp[i]   = (i % 100) - 50.0;
            baselineActive[i] = (i & 1) == 0;
        }
    }

    // ------------------------------------------------------------------ benchmarks

    @Benchmark
    public void packedReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            bh.consume(heightAcc.get(store, i));
            bh.consume(tempAcc.get(store, i));
            bh.consume(activeAcc.get(store, i));
        }
    }

    @Benchmark
    public void baselineReadAll(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            bh.consume(baselineHeight[i]);
            bh.consume(baselineTemp[i]);
            bh.consume(baselineActive[i]);
        }
    }

    @Benchmark
    public void packedWriteAll() {
        for (int i = 0; i < N; i++) {
            heightAcc.set(store, i, i % 256);
            tempAcc.set(store, i, (i % 100) - 50.0);
            activeAcc.set(store, i, (i & 1) == 0);
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
    public int packedReadSingle() {
        return heightAcc.get(store, N / 2);
    }

    @Benchmark
    public int baselineReadSingle() {
        return baselineHeight[N / 2];
    }
}
