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
import java.util.ArrayList;
import java.util.List;

/**
 * A mutable, allocation-free cursor that holds primitive values from a subset of fields
 * across one or more DataStore component types.
 *
 * <p>Unlike {@link RowView} (which allocates a new record on every {@code get()}),
 * {@code DataCursor} keeps a single pre-allocated instance of {@code T} and updates its
 * fields in-place when {@link #load} is called.  Field reads/writes are performed by a
 * {@link CursorCodec}: when the cursor class is accessible, ByteBuddy generates a class
 * that uses direct {@code PUTFIELD}/{@code GETFIELD} JVM instructions, eliminating
 * {@link VarHandle} dispatch overhead.  If bytecode generation is unavailable, the
 * implementation falls back to VarHandle-based access.
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

    private final T          instance;
    private final CursorCodec codec;

    private DataCursor(T instance, CursorCodec codec) {
        this.instance = instance;
        this.codec    = codec;
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
        codec.load(instance, store, row);
    }

    /**
     * Writes all cursor fields back into the given row in the store.
     *
     * @param store the DataStore to write to
     * @param row   row index
     */
    public void flush(DataStore<?> store, int row) {
        codec.flush(instance, store, row);
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
     * @return a fully pre-compiled {@code DataCursor} (uses ByteBuddy direct-field codec when available)
     * @throws IllegalArgumentException if any {@link StoreField} mapping is invalid
     */
    public static <T> DataCursor<T> of(DataStore<?> store, Class<T> cls) {
        BuildResult<T> r = prepareSpecs(store, cls);
        CursorCodec codec = CursorCodecGenerator.build(cls, r.specs, r.lookup);
        return new DataCursor<>(r.instance, codec);
    }

    /**
     * Creates a {@code DataCursor} for class {@code T} that uses {@link VarHandle}-based field
     * access only, bypassing ByteBuddy code generation.
     *
     * <p>Useful for benchmarking to compare the overhead of the VarHandle fallback path against
     * the ByteBuddy-generated codec produced by {@link #of(DataStore, Class)}.
     *
     * @param store the DataStore the cursor will read from / write to
     * @param cls   the cursor class
     * @param <T>   cursor type
     * @return a VarHandle-based {@code DataCursor}
     * @throws IllegalArgumentException if any {@link StoreField} mapping is invalid
     */
    public static <T> DataCursor<T> ofVarHandle(DataStore<?> store, Class<T> cls) {
        BuildResult<T> r = prepareSpecs(store, cls);
        CursorCodec codec = CursorCodecGenerator.buildVarHandleFallback(r.specs);
        return new DataCursor<>(r.instance, codec);
    }

    // -----------------------------------------------------------------------
    // Internal helpers

    /** Transient holder returned by {@link #prepareSpecs}. */
    private record BuildResult<T>(T instance,
                                   List<CursorCodecGenerator.FieldSpec> specs,
                                   MethodHandles.Lookup lookup) {}

    private static <T> BuildResult<T> prepareSpecs(DataStore<?> store, Class<T> cls) {
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

        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(cls, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            lookup = MethodHandles.lookup();
        }

        List<CursorCodecGenerator.FieldSpec> specs = new ArrayList<>(count);

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

            FieldLayout fl    = LayoutBuilder.layout(compClass).field(fieldName);
            int absOffset     = store.componentBitOffset(compClass) + fl.bitOffset();
            Class<?> type     = f.getType();

            Object accessor = buildAccessor(type, fl, absOffset, f, compClass);
            specs.add(new CursorCodecGenerator.FieldSpec(f, vh, accessor));
        }

        return new BuildResult<>(instance, specs, lookup);
    }

    // -----------------------------------------------------------------------
    // Internal

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object buildAccessor(
            Class<?> type, FieldLayout fl, int absOffset,
            Field field, Class<?> compClass) {

        if (type == int.class) {
            return new IntAccessor(absOffset, fl.bitWidth(), fl.minRaw());
        }
        if (type == long.class) {
            return new LongAccessor(absOffset, fl.bitWidth(), fl.minRaw());
        }
        if (type == double.class) {
            return new DoubleAccessor(absOffset, fl.bitWidth(), fl.minRaw(), fl.scale());
        }
        if (type == boolean.class) {
            return new BoolAccessor(absOffset);
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
            return EnumAccessor.forField(absOffset, fl.bitWidth(),
                    (Class<? extends Enum>) type, explicit);
        }
        throw new IllegalArgumentException(
                "Unsupported @StoreField type: " + type + " on field " + field.getName()
                + " (must be int, long, double, boolean, or an enum)");
    }
}
