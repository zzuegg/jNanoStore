package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.DoubleAccessor;
import io.github.zzuegg.jbinary.accessor.FloatAccessor;
import io.github.zzuegg.jbinary.accessor.IntAccessor;
import io.github.zzuegg.jbinary.annotation.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for composed record fields — records that contain other records as components.
 *
 * <p>Composed fields are flattened into the parent component's bit layout using dotted names
 * (e.g. {@code "position.x"}).  An optional {@link DecimalField} on the composed field acts
 * as a default for sub-fields that carry no annotation of their own; a sub-field's own
 * annotation always takes priority.
 */
class ComposedFieldTest {

    // ------------------------------------------------------------------ composed types

    /** A simple 3-component vector using its own decimal annotations. */
    record Vec3(
            @DecimalField(min = -1000.0, max = 1000.0, precision = 2) double x,
            @DecimalField(min = -1000.0, max = 1000.0, precision = 2) double y,
            @DecimalField(min = -1000.0, max = 1000.0, precision = 2) double z
    ) {}

    /** A 3-component float vector (float sub-fields). */
    record Vec3f(
            @DecimalField(min = -100.0, max = 100.0, precision = 2) float x,
            @DecimalField(min = -100.0, max = 100.0, precision = 2) float y,
            @DecimalField(min = -100.0, max = 100.0, precision = 2) float z
    ) {}

    /** Record using Vec3 sub-field annotations unchanged. */
    record Entity(
            @BitField(min = 0, max = 255) int id,
            Vec3 position            // sub-fields use Vec3's own annotations
    ) {}

    /** Record overriding Vec3 precision/range with a parent @DecimalField. */
    record EntityLowPrecision(
            @BitField(min = 0, max = 255) int id,
            @DecimalField(min = -100.0, max = 100.0, precision = 1) Vec3 position
    ) {}

    /**
     * Vec3 variant where x has its own annotation and y/z do not;
     * y/z will inherit the parent @DecimalField when used in EntityMixed.
     */
    record Vec3Mixed(
            @DecimalField(min = -500.0, max = 500.0, precision = 3) double x,  // own annotation
            double y,   // no annotation — inherits parent default
            double z    // no annotation — inherits parent default
    ) {}

    /** Record where sub-field's own annotation overrides parent's default. */
    record EntityMixed(
            @DecimalField(min = -100.0, max = 100.0, precision = 1) Vec3Mixed position
    ) {}

    /** Record using float-typed composed field. */
    record EntityFloat(
            @BitField(min = 0, max = 255) int id,
            Vec3f position
    ) {}

    // ------------------------------------------------------------------ cursor classes

    static class EntityCursor {
        @StoreField(component = Entity.class, field = "id")
        public int id;

        @StoreField(component = Entity.class, field = "position.x")
        public double posX;

        @StoreField(component = Entity.class, field = "position.y")
        public double posY;

        @StoreField(component = Entity.class, field = "position.z")
        public double posZ;
    }

    // ------------------------------------------------------------------ schema / accessor tests

    @Test
    void subFieldAnnotationsLayout() {
        // Entity.position uses Vec3's own annotations (precision=2, range -1000..1000)
        DataStore<?> store = DataStore.packed(10, Entity.class);

        DoubleAccessor posX = Accessors.doubleFieldInStore(store, Entity.class, "position.x");
        DoubleAccessor posY = Accessors.doubleFieldInStore(store, Entity.class, "position.y");
        DoubleAccessor posZ = Accessors.doubleFieldInStore(store, Entity.class, "position.z");

        posX.set(store, 0, 123.45);
        posY.set(store, 0, -678.90);
        posZ.set(store, 0, 0.01);

        assertEquals(123.45, posX.get(store, 0), 0.01);
        assertEquals(-678.90, posY.get(store, 0), 0.01);
        assertEquals(0.01, posZ.get(store, 0), 0.01);
    }

    @Test
    void parentAnnotationOverridesSubFieldDefaults() {
        // EntityLowPrecision.position overrides Vec3's own annotations (precision=1, range -100..100)
        DataStore<?> store = DataStore.packed(10, EntityLowPrecision.class);

        DoubleAccessor posX = Accessors.doubleFieldInStore(store, EntityLowPrecision.class, "position.x");
        DoubleAccessor posY = Accessors.doubleFieldInStore(store, EntityLowPrecision.class, "position.y");

        posX.set(store, 0, 50.5);
        posY.set(store, 0, -33.3);

        assertEquals(50.5, posX.get(store, 0), 0.1);
        assertEquals(-33.3, posY.get(store, 0), 0.1);
    }

    @Test
    void subFieldAnnotationTakesPriorityOverParent() {
        // EntityMixed: position.x has precision=3 (own), position.y/z inherit precision=1 (parent)
        DataStore<?> store = DataStore.packed(10, EntityMixed.class);

        DoubleAccessor posX = Accessors.doubleFieldInStore(store, EntityMixed.class, "position.x");
        DoubleAccessor posY = Accessors.doubleFieldInStore(store, EntityMixed.class, "position.y");

        // x uses precision=3 → can store 3 decimal places accurately
        posX.set(store, 0, 12.345);
        assertEquals(12.345, posX.get(store, 0), 0.001);

        // y uses parent precision=1 → only 1 decimal place
        posY.set(store, 0, 7.8);
        assertEquals(7.8, posY.get(store, 0), 0.1);
    }

    @Test
    void composedFieldIntAccessorStillWorks() {
        DataStore<?> store = DataStore.packed(10, Entity.class);
        IntAccessor id = Accessors.intFieldInStore(store, Entity.class, "id");
        id.set(store, 3, 42);
        assertEquals(42, id.get(store, 3));
    }

    @Test
    void floatSubFieldRoundTrip() {
        // EntityFloat uses Vec3f with float sub-fields — use FloatAccessor
        DataStore<?> store = DataStore.packed(10, EntityFloat.class);

        FloatAccessor posX = Accessors.floatFieldInStore(store, EntityFloat.class, "position.x");
        posX.set(store, 0, 12.34f);
        assertEquals(12.34f, posX.get(store, 0), 0.01f);
    }

    // ------------------------------------------------------------------ RowView tests

    @Test
    void rowViewGetWithComposedField() {
        DataStore<?> store = DataStore.packed(10, Entity.class);

        DoubleAccessor posX = Accessors.doubleFieldInStore(store, Entity.class, "position.x");
        DoubleAccessor posY = Accessors.doubleFieldInStore(store, Entity.class, "position.y");
        DoubleAccessor posZ = Accessors.doubleFieldInStore(store, Entity.class, "position.z");
        IntAccessor    id   = Accessors.intFieldInStore(store, Entity.class, "id");

        id.set(store, 0, 7);
        posX.set(store, 0, 1.0);
        posY.set(store, 0, 2.0);
        posZ.set(store, 0, 3.0);

        RowView<Entity> view = RowView.of(store, Entity.class);
        Entity e = view.get(store, 0);

        assertEquals(7, e.id());
        assertEquals(1.0, e.position().x(), 0.01);
        assertEquals(2.0, e.position().y(), 0.01);
        assertEquals(3.0, e.position().z(), 0.01);
    }

    @Test
    void rowViewSetWithComposedField() {
        DataStore<?> store = DataStore.packed(10, Entity.class);
        RowView<Entity> view = RowView.of(store, Entity.class);

        Entity entity = new Entity(99, new Vec3(10.5, -20.25, 30.0));
        view.set(store, 0, entity);

        Entity got = view.get(store, 0);
        assertEquals(99, got.id());
        assertEquals(10.5,   got.position().x(), 0.01);
        assertEquals(-20.25, got.position().y(), 0.01);
        assertEquals(30.0,   got.position().z(), 0.01);
    }

    @Test
    void rowViewRoundTripFloat() {
        DataStore<?> store = DataStore.packed(10, EntityFloat.class);
        RowView<EntityFloat> view = RowView.of(store, EntityFloat.class);

        EntityFloat entity = new EntityFloat(5, new Vec3f(1.5f, -2.5f, 3.0f));
        view.set(store, 0, entity);

        EntityFloat got = view.get(store, 0);
        assertEquals(5, got.id());
        assertEquals(1.5f,  got.position().x(), 0.01f);
        assertEquals(-2.5f, got.position().y(), 0.01f);
        assertEquals(3.0f,  got.position().z(), 0.01f);
    }

    // ------------------------------------------------------------------ cursor tests

    @Test
    void cursorLoadWithComposedField() {
        DataStore<?> store = DataStore.packed(10, Entity.class);

        Accessors.intFieldInStore(store, Entity.class, "id").set(store, 0, 42);
        Accessors.doubleFieldInStore(store, Entity.class, "position.x").set(store, 0, 1.5);
        Accessors.doubleFieldInStore(store, Entity.class, "position.y").set(store, 0, 2.5);
        Accessors.doubleFieldInStore(store, Entity.class, "position.z").set(store, 0, 3.5);

        DataCursor<EntityCursor> cursor = DataCursor.of(store, EntityCursor.class);
        cursor.load(store, 0);
        EntityCursor data = cursor.get();

        assertEquals(42,  data.id);
        assertEquals(1.5, data.posX, 0.01);
        assertEquals(2.5, data.posY, 0.01);
        assertEquals(3.5, data.posZ, 0.01);
    }

    @Test
    void cursorFlushWithComposedField() {
        DataStore<?> store = DataStore.packed(10, Entity.class);
        DataCursor<EntityCursor> cursor = DataCursor.of(store, EntityCursor.class);

        // Populate via cursor flush
        cursor.load(store, 0);
        EntityCursor data = cursor.get();
        data.id   = 77;
        data.posX = 10.0;
        data.posY = 20.0;
        data.posZ = 30.0;
        cursor.flush(store, 0);

        // Read back via accessors
        assertEquals(77,   Accessors.intFieldInStore(store, Entity.class, "id").get(store, 0));
        assertEquals(10.0, Accessors.doubleFieldInStore(store, Entity.class, "position.x").get(store, 0), 0.01);
        assertEquals(20.0, Accessors.doubleFieldInStore(store, Entity.class, "position.y").get(store, 0), 0.01);
        assertEquals(30.0, Accessors.doubleFieldInStore(store, Entity.class, "position.z").get(store, 0), 0.01);
    }

    @Test
    void cursorUpdateConvenienceMethod() {
        DataStore<?> store = DataStore.packed(5, Entity.class);
        DataCursor<EntityCursor> cursor = DataCursor.of(store, EntityCursor.class);

        Accessors.intFieldInStore(store, Entity.class, "id").set(store, 2, 13);
        Accessors.doubleFieldInStore(store, Entity.class, "position.x").set(store, 2, -7.77);

        EntityCursor data = cursor.update(store, 2);
        assertEquals(13,    data.id);
        assertEquals(-7.77, data.posX, 0.01);
    }

    // ------------------------------------------------------------------ multi-row isolation

    @Test
    void multipleRowsDoNotInterfere() {
        DataStore<?> store = DataStore.packed(5, Entity.class);
        RowView<Entity> view = RowView.of(store, Entity.class);

        view.set(store, 0, new Entity(1, new Vec3(1.0, 2.0, 3.0)));
        view.set(store, 1, new Entity(2, new Vec3(4.0, 5.0, 6.0)));
        view.set(store, 2, new Entity(3, new Vec3(7.0, 8.0, 9.0)));

        Entity e0 = view.get(store, 0);
        Entity e1 = view.get(store, 1);
        Entity e2 = view.get(store, 2);

        assertEquals(1, e0.id()); assertEquals(1.0, e0.position().x(), 0.01);
        assertEquals(2, e1.id()); assertEquals(4.0, e1.position().x(), 0.01);
        assertEquals(3, e2.id()); assertEquals(7.0, e2.position().x(), 0.01);
    }

    // ------------------------------------------------------------------ plain class composed types

    /** A plain Java class (not a record) used as a composed position type. */
    static class PlainVec3 {
        @DecimalField(min = -1000.0, max = 1000.0, precision = 2) public double x;
        @DecimalField(min = -1000.0, max = 1000.0, precision = 2) public double y;
        @DecimalField(min = -1000.0, max = 1000.0, precision = 2) public double z;
        public PlainVec3() {}
        public PlainVec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    }

    /** A record whose composed field is a plain class (not a record). */
    record EntityWithPlain(
            @BitField(min = 0, max = 255) int id,
            PlainVec3 position
    ) {}

    /** A plain class at the top-level component (not a record). */
    static class PlainTerrain {
        @BitField(min = 0, max = 255) public int height;
        @DecimalField(min = -50.0, max = 50.0, precision = 2) public double temperature;
        @BoolField public boolean active;
        public PlainTerrain() {}
        public PlainTerrain(int h, double t, boolean a) { height = h; temperature = t; active = a; }
    }

    /** A plain class with a plain class composed sub-field. */
    static class PlainEntity {
        @BitField(min = 0, max = 255) public int id;
        public PlainVec3 position;
        public PlainEntity() {}
        public PlainEntity(int id, PlainVec3 pos) { this.id = id; this.position = pos; }
    }

    @Test
    void plainClassComposedInRecordAccessors() {
        // EntityWithPlain.position is PlainVec3 (plain class, not record)
        DataStore<?> store = DataStore.packed(10, EntityWithPlain.class);

        DoubleAccessor posX = Accessors.doubleFieldInStore(store, EntityWithPlain.class, "position.x");
        DoubleAccessor posY = Accessors.doubleFieldInStore(store, EntityWithPlain.class, "position.y");
        IntAccessor    id   = Accessors.intFieldInStore(store, EntityWithPlain.class, "id");

        id.set(store, 0, 7);
        posX.set(store, 0, 5.5);
        posY.set(store, 0, -3.3);

        assertEquals(7,   id.get(store, 0));
        assertEquals(5.5,  posX.get(store, 0), 0.01);
        assertEquals(-3.3, posY.get(store, 0), 0.01);
    }

    @Test
    void plainClassComposedInRecordRowView() {
        DataStore<?> store = DataStore.packed(10, EntityWithPlain.class);
        RowView<EntityWithPlain> view = RowView.of(store, EntityWithPlain.class);

        PlainVec3 pos = new PlainVec3(1.1, 2.2, 3.3);
        view.set(store, 0, new EntityWithPlain(42, pos));

        EntityWithPlain got = view.get(store, 0);
        assertEquals(42,  got.id());
        assertEquals(1.1, got.position().x, 0.01);
        assertEquals(2.2, got.position().y, 0.01);
        assertEquals(3.3, got.position().z, 0.01);
    }

    @Test
    void plainTopLevelComponentRowViewRoundTrip() {
        // PlainTerrain is not a record — use RowView.of(store, PlainTerrain.class)
        DataStore<?> store = DataStore.packed(10, PlainTerrain.class);
        RowView<PlainTerrain> view = RowView.of(store, PlainTerrain.class);

        view.set(store, 3, new PlainTerrain(200, -12.5, true));

        PlainTerrain got = view.get(store, 3);
        assertEquals(200,   got.height);
        assertEquals(-12.5, got.temperature, 0.01);
        assertTrue(got.active);
    }

    @Test
    void plainTopLevelComponentAccessors() {
        DataStore<?> store = DataStore.packed(5, PlainTerrain.class);

        Accessors.intFieldInStore(store, PlainTerrain.class, "height").set(store, 0, 128);
        Accessors.doubleFieldInStore(store, PlainTerrain.class, "temperature").set(store, 0, 22.5);
        Accessors.boolFieldInStore(store, PlainTerrain.class, "active").set(store, 0, true);

        assertEquals(128,  Accessors.intFieldInStore(store, PlainTerrain.class, "height").get(store, 0));
        assertEquals(22.5, Accessors.doubleFieldInStore(store, PlainTerrain.class, "temperature").get(store, 0), 0.01);
        assertTrue(Accessors.boolFieldInStore(store, PlainTerrain.class, "active").get(store, 0));
    }

    @Test
    void plainTopLevelWithPlainComposedSubField() {
        // PlainEntity has a PlainVec3 composed sub-field — both are plain classes
        DataStore<?> store = DataStore.packed(10, PlainEntity.class);
        RowView<PlainEntity> view = RowView.of(store, PlainEntity.class);

        view.set(store, 0, new PlainEntity(55, new PlainVec3(10.0, 20.0, 30.0)));

        PlainEntity got = view.get(store, 0);
        assertEquals(55,   got.id);
        assertEquals(10.0, got.position.x, 0.01);
        assertEquals(20.0, got.position.y, 0.01);
        assertEquals(30.0, got.position.z, 0.01);
    }

    @Test
    void plainTopLevelMultipleRows() {
        DataStore<?> store = DataStore.packed(5, PlainTerrain.class);
        RowView<PlainTerrain> view = RowView.of(store, PlainTerrain.class);

        view.set(store, 0, new PlainTerrain(10, 0.0,  false));
        view.set(store, 1, new PlainTerrain(20, 10.5, true));
        view.set(store, 2, new PlainTerrain(30, -5.5, false));

        assertEquals(10, view.get(store, 0).height);
        assertEquals(20, view.get(store, 1).height);
        assertEquals(30, view.get(store, 2).height);
        assertFalse(view.get(store, 0).active);
        assertTrue(view.get(store, 1).active);
    }
}
