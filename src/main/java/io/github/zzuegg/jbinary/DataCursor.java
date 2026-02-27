package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.*;
import io.github.zzuegg.jbinary.annotation.EnumField;
import io.github.zzuegg.jbinary.annotation.StoreField;
import io.github.zzuegg.jbinary.schema.FieldLayout;
import io.github.zzuegg.jbinary.schema.LayoutBuilder;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * A mutable, allocation-free cursor that holds primitive values from a subset of fields
 * across one or more DataStore component types.
 *
 * <p>Unlike {@link RowView} (which allocates a new record on every {@code get()}),
 * {@code DataCursor} keeps a single pre-allocated instance of {@code T} and updates its
 * fields in-place when {@link #load} is called.  All field reads/writes use {@link VarHandle}
 * so primitive values ({@code int}, {@code long}, {@code double}, {@code boolean}) are never
 * boxed.
 *
 * <p>Typical pattern:
 * <pre>{@code
 * // Define a "projection" class spanning two component types:
 * class NeededData {
 *     @StoreField(component = Terrain.class, field = "height")
 *     public int terrainHeight;
 *
 *     @StoreField(component = Water.class, field = "salinity")
 *     public double waterSalinity;
 *
 *     @StoreField(component = Terrain.class, field = "active")
 *     public boolean active;
 * }
 *
 * // Build the cursor once (pre-computes all accessors):
 * DataCursor<NeededData> cursor = DataCursor.of(store, NeededData.class);
 *
 * // Re-use the cursor in a hot loop:
 * for (int row = 0; row < N; row++) {
 *     cursor.load(store, row);          // populate in-place, no allocation
 *     NeededData data = cursor.get();   // always the same object
 *     if (data.active) {
 *         data.terrainHeight += 1;      // mutate in-place
 *         cursor.flush(store, row);     // write back
 *     }
 * }
 *
 * // Or use update() which combines load() and returns the instance:
 * NeededData d = cursor.update(store, 42);
 * }</pre>
 *
 * <p><strong>Thread safety:</strong> not thread-safe.  Use one cursor per thread.
 *
 * @param <T> the cursor class, annotated with {@link StoreField}
 */
public final class DataCursor<T> {

    /** Binds one cursor field to one DataStore bit-range via pre-computed accessors. */
    private interface Binding {
        /** Read from store → write into the cursor instance. */
        void load(Object instance, DataStore<?> store, int row);
        /** Read from cursor instance → write into store. */
        void flush(Object instance, DataStore<?> store, int row);
    }

    private final T        instance;
    private final Binding[] bindings;

    private DataCursor(T instance, Binding[] bindings) {
        this.instance = instance;
        this.bindings = bindings;
    }

    // -----------------------------------------------------------------------
    // Public API

    /**
     * Populates the cursor's held instance from the given row in the store.
     * The same object is reused on every call — no allocation occurs.
     *
     * @param store the DataStore to read from
     * @param row   row index
     */
    public void load(DataStore<?> store, int row) {
        for (Binding b : bindings) b.load(instance, store, row);
    }

    /**
     * Writes all cursor fields back into the given row in the store.
     *
     * @param store the DataStore to write to
     * @param row   row index
     */
    public void flush(DataStore<?> store, int row) {
        for (Binding b : bindings) b.flush(instance, store, row);
    }

    /**
     * Convenience: calls {@link #load(DataStore, int)} then returns {@link #get()}.
     *
     * @param store the DataStore to read from
     * @param row   row index
     * @return the cursor's held instance, populated with the row's values
     */
    public T update(DataStore<?> store, int row) {
        load(store, row);
        return instance;
    }

    /**
     * Returns the cursor's held instance.  This is the <em>same</em> object every call;
     * the caller must not retain it across {@link #load} calls in concurrent scenarios.
     *
     * @return the pre-allocated cursor instance
     */
    public T get() {
        return instance;
    }

    // -----------------------------------------------------------------------
    // Factory

    /**
     * Creates a {@code DataCursor} for class {@code T}, bound to the given store.
     *
     * <p>Every field in {@code T} annotated with {@link StoreField} must:
     * <ul>
     *   <li>be a public (or at least accessible) non-final, non-static primitive or enum field</li>
     *   <li>reference a component type registered in {@code store}</li>
     *   <li>name a field that exists in that component type</li>
     * </ul>
     *
     * @param store the DataStore the cursor will read from / write to
     * @param cls   the cursor class
     * @param <T>   cursor type
     * @return a fully pre-compiled {@code DataCursor}
     * @throws IllegalArgumentException if any {@link StoreField} mapping is invalid
     */
    public static <T> DataCursor<T> of(DataStore<?> store, Class<T> cls) {
        Field[] fields = cls.getDeclaredFields();

        // Count annotated fields
        int count = 0;
        for (Field f : fields) {
            if (f.getAnnotation(StoreField.class) != null) count++;
        }
        if (count == 0) {
            throw new IllegalArgumentException(
                    cls.getName() + " has no fields annotated with @StoreField");
        }

        T instance;
        try {
            instance = cls.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Cannot instantiate " + cls.getName() + " (needs a no-arg constructor)", e);
        }

        @SuppressWarnings("unchecked")
        Binding[] bindings = new Binding[count];
        int idx = 0;

        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(cls, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            lookup = MethodHandles.lookup();
        }

        for (Field f : fields) {
            StoreField ann = f.getAnnotation(StoreField.class);
            if (ann == null) continue;
            if (Modifier.isStatic(f.getModifiers())) {
                throw new IllegalArgumentException("@StoreField field must not be static: " + f.getName());
            }
            if (Modifier.isFinal(f.getModifiers())) {
                throw new IllegalArgumentException("@StoreField field must not be final: " + f.getName());
            }

            f.setAccessible(true);
            VarHandle vh;
            try {
                vh = lookup.unreflectVarHandle(f);
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException("Cannot access field " + f.getName(), e);
            }

            Class<?> compClass = ann.component();
            String fieldName   = ann.field();

            FieldLayout fl = LayoutBuilder.layout(compClass).field(fieldName);
            int absOffset  = store.componentBitOffset(compClass) + fl.bitOffset();
            Class<?> type  = f.getType();

            bindings[idx++] = buildBinding(type, vh, fl, absOffset, f, compClass);
        }

        return new DataCursor<>(instance, bindings);
    }

    // -----------------------------------------------------------------------
    // Internal

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Binding buildBinding(
            Class<?> type, VarHandle vh, FieldLayout fl, int absOffset,
            Field field, Class<?> compClass) {

        if (type == int.class) {
            IntAccessor acc = new IntAccessor(absOffset, fl.bitWidth(), fl.minRaw());
            return new Binding() {
                public void load(Object inst, DataStore<?> s, int r)  { vh.set(inst, acc.get(s, r)); }
                public void flush(Object inst, DataStore<?> s, int r) { acc.set(s, r, (int) vh.get(inst)); }
            };
        }
        if (type == long.class) {
            LongAccessor acc = new LongAccessor(absOffset, fl.bitWidth(), fl.minRaw());
            return new Binding() {
                public void load(Object inst, DataStore<?> s, int r)  { vh.set(inst, acc.get(s, r)); }
                public void flush(Object inst, DataStore<?> s, int r) { acc.set(s, r, (long) vh.get(inst)); }
            };
        }
        if (type == double.class) {
            DoubleAccessor acc = new DoubleAccessor(absOffset, fl.bitWidth(), fl.minRaw(), fl.scale());
            return new Binding() {
                public void load(Object inst, DataStore<?> s, int r)  { vh.set(inst, acc.get(s, r)); }
                public void flush(Object inst, DataStore<?> s, int r) { acc.set(s, r, (double) vh.get(inst)); }
            };
        }
        if (type == boolean.class) {
            BoolAccessor acc = new BoolAccessor(absOffset);
            return new Binding() {
                public void load(Object inst, DataStore<?> s, int r)  { vh.set(inst, acc.get(s, r)); }
                public void flush(Object inst, DataStore<?> s, int r) { acc.set(s, r, (boolean) vh.get(inst)); }
            };
        }
        if (type.isEnum()) {
            // Look up @EnumField on the component's field (not the cursor field)
            boolean explicit = false;
            try {
                // Try record component first
                for (java.lang.reflect.RecordComponent rc : compClass.getRecordComponents()) {
                    if (rc.getName().equals(fl.name())) {
                        EnumField enumAnn = rc.getAnnotation(EnumField.class);
                        if (enumAnn != null) explicit = enumAnn.useExplicitCodes();
                        break;
                    }
                }
            } catch (Exception ignored) {
                // Non-record component: try declared field
                try {
                    java.lang.reflect.Field compField = compClass.getDeclaredField(fl.name());
                    EnumField enumAnn = compField.getAnnotation(EnumField.class);
                    if (enumAnn != null) explicit = enumAnn.useExplicitCodes();
                } catch (NoSuchFieldException ignored2) {}
            }
            final boolean useExplicit = explicit;
            EnumAccessor acc = EnumAccessor.forField(absOffset, fl.bitWidth(),
                    (Class<? extends Enum>) type, useExplicit);
            return new Binding() {
                @SuppressWarnings("unchecked")
                public void load(Object inst, DataStore<?> s, int r)  { vh.set(inst, acc.get(s, r)); }
                @SuppressWarnings("unchecked")
                public void flush(Object inst, DataStore<?> s, int r) { acc.set(s, r, (Enum) vh.get(inst)); }
            };
        }
        throw new IllegalArgumentException(
                "Unsupported @StoreField type: " + type + " on field " + field.getName()
                + " (must be int, long, double, boolean, or an enum)");
    }
}
