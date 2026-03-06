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
    // Packed (BitKit-backed) state — separate underlying EntityData instance

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
    // Bit (standalone BitKit-native) state — no wrapping, no parent EntityData

    private BitEntityData bitEd;
    private EntitySet      bitSet;
    private EntityId[]     bitIds;

    // Direct-access cursors for BitEntityData benchmarks
    private PackedEntitySet.PackedCursor<Position>    bitPosCursor;
    private PackedEntitySet.PackedCursor<Orientation> bitOriCursor;
    private PackedEntitySet.PackedCursor<Speed>       bitSpdCursor;

    // Multi-component projection for BitEntityData
    private DataCursor<PhysicsProjection> bitMultiCursor;
    private DataStore<?> bitStore;

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

        // --- Packed (independent data, same initial values) ---
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

        // --- Bit (standalone, no wrapping) ---
        bitEd  = new BitEntityData();
        bitIds = new EntityId[entityCount];
        for (int i = 0; i < entityCount; i++) {
            bitIds[i] = bitEd.createEntity();
            bitEd.setComponents(bitIds[i],
                    new Position(i, i * 0.5f, i * 0.25f),
                    new Orientation(i * 0.1f, i * 0.2f, i * 0.3f),
                    new Speed(1.0f + i * 0.001f));
        }
        bitSet = bitEd.getEntities(Position.class, Orientation.class, Speed.class);
        bitSet.applyChanges();

        // Build direct-access cursors for the BitEntityData cursor benchmark
        BitEntitySet bes = (BitEntitySet) bitSet;
        bitPosCursor = bes.createCursor(Position.class);
        bitOriCursor = bes.createCursor(Orientation.class);
        bitSpdCursor = bes.createCursor(Speed.class);

        // Build multi-component cursor (explicit projection class)
        bitMultiCursor = bes.createDataCursor(PhysicsProjection.class);
        bitStore = bes.store();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        defaultSet.release();
        packedSet.release();
        bitSet.release();
        defaultEd.close();
        packedEd.close();
        bitEd.close();
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
    // BitEntityData benchmarks (standalone, no wrapping)

    /**
     * Standalone {@link BitEntityData} — entity.get() path.
     * Same game-loop pattern as the other benchmarks but using the standalone
     * BitKit-native EntityData that does not wrap DefaultEntityData.
     */
    @Benchmark
    public void bitEntityData_gameLoop(Blackhole bh) {
        bitSet.applyChanges();
        for (var entity : bitSet) {
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
     * Standalone {@link BitEntityData} with direct cursor access.
     * Uses {@link BitEntity#getIndex()} for dense row access.
     */
    @Benchmark
    public void bitEntityData_gameLoop_cursor(Blackhole bh) {
        bitSet.applyChanges();
        for (var entity : bitSet) {
            int idx = ((BitEntity) entity).getIndex();
            Position    pos = bitPosCursor.read(idx);
            Orientation ori = bitOriCursor.read(idx);
            Speed       spd = bitSpdCursor.read(idx);
            Position newPos = new Position(
                    pos.x() + ori.yaw()   * spd.value(),
                    pos.y() + ori.pitch() * spd.value(),
                    pos.z() + ori.roll()  * spd.value());
            entity.set(newPos);
            bh.consume(newPos);
        }
    }

    /**
     * Standalone {@link BitEntityData} with multi-component projection cursor.
     * A single {@link DataCursor#load} populates all fields in one call.
     */
    @Benchmark
    public void bitEntityData_gameLoop_multiCursor(Blackhole bh) {
        bitSet.applyChanges();
        for (var entity : bitSet) {
            int idx = ((BitEntity) entity).getIndex();
            PhysicsProjection p = bitMultiCursor.update(bitStore, idx);
            Position newPos = new Position(
                    p.posX + p.yaw   * p.speed,
                    p.posY + p.pitch * p.speed,
                    p.posZ + p.roll  * p.speed);
            entity.set(newPos);
            bh.consume(newPos);
        }
    }
}

