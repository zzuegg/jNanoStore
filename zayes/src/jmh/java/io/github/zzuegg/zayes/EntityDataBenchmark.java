package io.github.zzuegg.zayes;

import com.simsilica.es.EntityComponent;
import com.simsilica.es.EntityId;
import com.simsilica.es.EntitySet;
import com.simsilica.es.base.DefaultEntityData;

import io.github.zzuegg.jbinary.DataCursor;
import io.github.zzuegg.jbinary.DataStore;
import io.github.zzuegg.jbinary.annotation.StoreField;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing {@link PackedEntityData} (BitKit-backed) against
 * {@link DefaultEntityData} (zay-es reference).
 *
 * <h3>Scenario — one full game-loop frame</h3>
 * Each benchmark invocation measures a complete "physics integration" step:
 * <ol>
 *   <li>{@code applyChanges()} — absorb the position writes from the previous frame.</li>
 *   <li>For every entity: read {@link Position}, {@link Orientation}, {@link Speed}.</li>
 *   <li>Compute {@code newPosition = position + orientation * speed}.</li>
 *   <li>Write {@code newPosition} back via {@code entity.set()} (which queues the change
 *       for the next frame).</li>
 * </ol>
 *
 * <p>Using <em>records</em> for all three component types lets
 * {@link PackedEntitySet} store their fields directly in a {@link io.github.zzuegg.jbinary.PackedDataStore}
 * (raw IEEE-754 bits, no heap allocation per component read) instead of
 * a plain {@code EntityComponent[]} array.
 *
 * <p>The two variants ({@code defaultEntityData} / {@code packedEntityData}) use
 * fully independent {@link DefaultEntityData} instances so writes in one benchmark
 * do not affect the other.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class EntityDataBenchmark {

    // -----------------------------------------------------------------------
    // Component types — records so PackedEntitySet can store them in the DataStore

    record Position(float x, float y, float z)       implements EntityComponent {}
    record Orientation(float yaw, float pitch, float roll) implements EntityComponent {}
    record Speed(float value)                         implements EntityComponent {}

    // -----------------------------------------------------------------------
    // Benchmark parameter

    @Param({"1000"})
    int entityCount;

    // -----------------------------------------------------------------------
    // Default (zay-es reference) state

    private DefaultEntityData defaultEd;
    private EntitySet          defaultSet;
    private EntityId[]         defaultIds;

    // -----------------------------------------------------------------------
    // Packed (BitKit-backed, wrapping DefaultEntityData) state

    private DefaultEntityData  packedUnderlyingEd;
    private PackedEntityData   packedEd;
    private EntitySet          packedSet;
    private EntityId[]         packedIds;

    // Pre-built direct-access cursors for the per-type cursor benchmark
    private PackedEntitySet.PackedCursor<Position>    posCursor;
    private PackedEntitySet.PackedCursor<Orientation> oriCursor;
    private PackedEntitySet.PackedCursor<Speed>       spdCursor;

    // Multi-component projection: all fields in one cursor, one load() per entity
    public static class PhysicsProjection {
        @StoreField(component = Position.class, field = "x")       public float posX;
        @StoreField(component = Position.class, field = "y")       public float posY;
        @StoreField(component = Position.class, field = "z")       public float posZ;
        @StoreField(component = Orientation.class, field = "yaw")  public float yaw;
        @StoreField(component = Orientation.class, field = "pitch") public float pitch;
        @StoreField(component = Orientation.class, field = "roll") public float roll;
        @StoreField(component = Speed.class, field = "value")      public float speed;
    }
    private DataCursor<PhysicsProjection> multiCursor;
    private DataStore<?> packedStore;

    // Auto-generated projection: all store-backed fields, one load() per entity
    private DataCursor<?> autoCursor;
    private Object autoCursorInstance;

    // -----------------------------------------------------------------------
    // BitKitEntityData (ground-up native implementation) state

    private BitKitEntityData   bitkitEd;
    private EntitySet          bitkitSet;
    private EntityId[]         bitkitIds;

    // Direct-access cursors for the BitKitEntityData cursor benchmark
    private PackedEntitySet.PackedCursor<Position>    bitkitPosCursor;
    private PackedEntitySet.PackedCursor<Orientation> bitkitOriCursor;
    private PackedEntitySet.PackedCursor<Speed>       bitkitSpdCursor;

    // Multi-component cursor for BitKitEntityData
    private DataCursor<PhysicsProjection> bitkitMultiCursor;
    private DataStore<?> bitkitStore;

    // -----------------------------------------------------------------------
    // Setup / teardown

    @Setup(Level.Trial)
    public void setupTrial() {
        // --- Default ---
        defaultEd  = new DefaultEntityData();
        defaultIds = new EntityId[entityCount];
        for (int i = 0; i < entityCount; i++) {
            defaultIds[i] = defaultEd.createEntity();
            defaultEd.setComponents(defaultIds[i],
                    new Position(i, i * 0.5f, i * 0.25f),
                    new Orientation(i * 0.1f, i * 0.2f, i * 0.3f),
                    new Speed(1.0f + i * 0.001f));
        }
        defaultSet = defaultEd.getEntities(Position.class, Orientation.class, Speed.class);
        defaultSet.applyChanges();

        // --- Packed (wrapping DefaultEntityData, independent data) ---
        packedUnderlyingEd = new DefaultEntityData();
        packedIds = new EntityId[entityCount];
        for (int i = 0; i < entityCount; i++) {
            packedIds[i] = packedUnderlyingEd.createEntity();
            packedUnderlyingEd.setComponents(packedIds[i],
                    new Position(i, i * 0.5f, i * 0.25f),
                    new Orientation(i * 0.1f, i * 0.2f, i * 0.3f),
                    new Speed(1.0f + i * 0.001f));
        }
        packedEd  = new PackedEntityData(packedUnderlyingEd);
        packedSet = packedEd.getEntities(Position.class, Orientation.class, Speed.class);
        packedSet.applyChanges();

        // Build direct-access cursors for the per-type cursor benchmark
        PackedEntitySet pes = (PackedEntitySet) packedSet;
        posCursor = pes.createCursor(Position.class);
        oriCursor = pes.createCursor(Orientation.class);
        spdCursor = pes.createCursor(Speed.class);

        // Build multi-component cursor (explicit projection class)
        multiCursor = pes.createDataCursor(PhysicsProjection.class);
        packedStore = pes.store();

        // Build auto-generated projection cursor
        autoCursor = pes.generateProjectionCursor();
        if (autoCursor != null) {
            autoCursorInstance = autoCursor.get();
        }

        // --- BitKitEntityData (ground-up native, independent data) ---
        bitkitEd  = new BitKitEntityData();
        bitkitIds = new EntityId[entityCount];
        for (int i = 0; i < entityCount; i++) {
            bitkitIds[i] = bitkitEd.createEntity();
            bitkitEd.setComponents(bitkitIds[i],
                    new Position(i, i * 0.5f, i * 0.25f),
                    new Orientation(i * 0.1f, i * 0.2f, i * 0.3f),
                    new Speed(1.0f + i * 0.001f));
        }
        bitkitSet = bitkitEd.getEntities(Position.class, Orientation.class, Speed.class);
        bitkitSet.applyChanges();

        PackedEntitySet bitkitPes = (PackedEntitySet) bitkitSet;
        bitkitPosCursor = bitkitPes.createCursor(Position.class);
        bitkitOriCursor = bitkitPes.createCursor(Orientation.class);
        bitkitSpdCursor = bitkitPes.createCursor(Speed.class);
        bitkitMultiCursor = bitkitPes.createDataCursor(PhysicsProjection.class);
        bitkitStore = bitkitPes.store();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        defaultSet.release();
        packedSet.release();
        bitkitSet.release();
        defaultEd.close();
        packedEd.close();
        bitkitEd.close();
    }

    // -----------------------------------------------------------------------
    // Benchmarks
    //
    // Each invocation is one complete game-loop frame:
    //   applyChanges()  →  for each entity: read pos/ori/spd, compute new pos, write back

    /**
     * Baseline: zay-es {@link DefaultEntityData}.
     */
    @Benchmark
    public void defaultEntityData_gameLoop(Blackhole bh) {
        defaultSet.applyChanges();
        for (var entity : defaultSet) {
            Position    pos = entity.get(Position.class);
            Orientation ori = entity.get(Orientation.class);
            Speed       spd = entity.get(Speed.class);
            Position newPos = new Position(
                    pos.x() + ori.yaw()   * spd.value(),
                    pos.y() + ori.pitch() * spd.value(),
                    pos.z() + ori.roll()  * spd.value());
            entity.set(newPos);
            bh.consume(newPos);
        }
    }

    /**
     * Target: {@link PackedEntityData} backed by BitKit {@link io.github.zzuegg.jbinary.PackedDataStore}.
     * Component fields are stored as raw IEEE-754 bits; reads reconstruct record instances
     * without touching the GC heap for the component data itself.
     */
    @Benchmark
    public void packedEntityData_gameLoop(Blackhole bh) {
        packedSet.applyChanges();
        for (var entity : packedSet) {
            Position    pos = entity.get(Position.class);
            Orientation ori = entity.get(Orientation.class);
            Speed       spd = entity.get(Speed.class);
            Position newPos = new Position(
                    pos.x() + ori.yaw()   * spd.value(),
                    pos.y() + ori.pitch() * spd.value(),
                    pos.z() + ori.roll()  * spd.value());
            entity.set(newPos);
            bh.consume(newPos);
        }
    }

    /**
     * Optimised variant: direct {@link PackedEntitySet.PackedCursor} reads bypass the
     * {@link IndexedEntity#get} abstraction (no type-index lookup, no indirection
     * through the entity wrapper).  Writes still use {@code entity.set()} to propagate
     * changes through the normal zay-es pipeline.
     *
     * <p>{@link PackedEntitySet} always returns {@link IndexedEntity} instances from its
     * iterator, so the cast below is safe by construction.
     */
    @Benchmark
    public void packedEntityData_gameLoop_cursor(Blackhole bh) {
        packedSet.applyChanges();
        for (var entity : packedSet) {
            // PackedEntitySet always returns IndexedEntity — cast is safe by construction.
            int idx = ((IndexedEntity) entity).getIndex();
            Position    pos = posCursor.read(idx);
            Orientation ori = oriCursor.read(idx);
            Speed       spd = spdCursor.read(idx);
            Position newPos = new Position(
                    pos.x() + ori.yaw()   * spd.value(),
                    pos.y() + ori.pitch() * spd.value(),
                    pos.z() + ori.roll()  * spd.value());
            entity.set(newPos);
            bh.consume(newPos);
        }
    }

    /**
     * Multi-component cursor: a single {@link DataCursor#load} populates all
     * Position, Orientation, and Speed fields in one call — no per-type cursor
     * overhead, no boxing, no allocation.
     *
     * <p>Uses an explicit user-defined {@link PhysicsProjection} class with
     * {@link StoreField} annotations spanning all three component types.
     */
    @Benchmark
    public void packedEntityData_gameLoop_multiCursor(Blackhole bh) {
        packedSet.applyChanges();
        for (var entity : packedSet) {
            int idx = ((IndexedEntity) entity).getIndex();
            PhysicsProjection p = multiCursor.update(packedStore, idx);
            Position newPos = new Position(
                    p.posX + p.yaw   * p.speed,
                    p.posY + p.pitch * p.speed,
                    p.posZ + p.roll  * p.speed);
            entity.set(newPos);
            bh.consume(newPos);
        }
    }

    // -----------------------------------------------------------------------
    // BitKitEntityData benchmarks

    /**
     * BitKitEntityData — standard entity.get() API (same pattern as defaultEntityData).
     * BitKitEntityData manages entity IDs and component storage natively without wrapping
     * DefaultEntityData.
     */
    @Benchmark
    public void bitkitEntityData_gameLoop(Blackhole bh) {
        bitkitSet.applyChanges();
        for (var entity : bitkitSet) {
            Position    pos = entity.get(Position.class);
            Orientation ori = entity.get(Orientation.class);
            Speed       spd = entity.get(Speed.class);
            Position newPos = new Position(
                    pos.x() + ori.yaw()   * spd.value(),
                    pos.y() + ori.pitch() * spd.value(),
                    pos.z() + ori.roll()  * spd.value());
            entity.set(newPos);
            bh.consume(newPos);
        }
    }

    /**
     * BitKitEntityData — allocation-free direct-cursor reads, bypassing the
     * {@link IndexedEntity#get} abstraction.
     */
    @Benchmark
    public void bitkitEntityData_gameLoop_cursor(Blackhole bh) {
        bitkitSet.applyChanges();
        for (var entity : bitkitSet) {
            int idx = ((IndexedEntity) entity).getIndex();
            Position    pos = bitkitPosCursor.read(idx);
            Orientation ori = bitkitOriCursor.read(idx);
            Speed       spd = bitkitSpdCursor.read(idx);
            Position newPos = new Position(
                    pos.x() + ori.yaw()   * spd.value(),
                    pos.y() + ori.pitch() * spd.value(),
                    pos.z() + ori.roll()  * spd.value());
            entity.set(newPos);
            bh.consume(newPos);
        }
    }

    /**
     * BitKitEntityData — single multi-component cursor load per entity (all three
     * component types in one call).
     */
    @Benchmark
    public void bitkitEntityData_gameLoop_multiCursor(Blackhole bh) {
        bitkitSet.applyChanges();
        for (var entity : bitkitSet) {
            int idx = ((IndexedEntity) entity).getIndex();
            PhysicsProjection p = bitkitMultiCursor.update(bitkitStore, idx);
            Position newPos = new Position(
                    p.posX + p.yaw   * p.speed,
                    p.posY + p.pitch * p.speed,
                    p.posZ + p.roll  * p.speed);
            entity.set(newPos);
            bh.consume(newPos);
        }
    }
}

