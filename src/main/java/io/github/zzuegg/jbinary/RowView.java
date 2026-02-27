package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.*;
import io.github.zzuegg.jbinary.annotation.EnumField;
import io.github.zzuegg.jbinary.schema.ComponentLayout;
import io.github.zzuegg.jbinary.schema.FieldLayout;
import io.github.zzuegg.jbinary.schema.LayoutBuilder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;

/**
 * Pre-compiled bundled accessor for all fields of a record component type.
 *
 * <p>A {@code RowView} is constructed once (with {@link #of}) and reused.
 * All bit offsets, widths, and field decoders are pre-computed at construction time.
 * At call time, {@link #get} reads all fields from the store in a single loop and
 * constructs a new record instance; {@link #set} writes all fields from a record instance.
 *
 * <p>This is the "compiled POJO" approach: instead of managing individual
 * {@link io.github.zzuegg.jbinary.accessor.IntAccessor} /
 * {@link io.github.zzuegg.jbinary.accessor.DoubleAccessor}
 * etc. per field, a single {@code RowView} handles the whole component type ergonomically.
 *
 * <pre>{@code
 * RowView<Terrain> view = RowView.of(store, Terrain.class);
 *
 * // Write a full component row
 * view.set(store, 42, new Terrain(200, -12.5, true));
 *
 * // Read a full component row
 * Terrain t = view.get(store, 42);   // → Terrain[height=200, temperature=-12.5, active=true]
 * }</pre>
 *
 * @param <T> the record type
 */
public interface RowView<T extends Record> {

    /**
     * Reads all fields of the component type from the given row in the store
     * and returns a new record instance.
     */
    T get(DataStore store, int row);

    /**
     * Writes all fields of the given record instance to the given row in the store.
     */
    void set(DataStore store, int row, T value);

    /**
     * Creates a {@code RowView} for record type {@code T} using the given store
     * to resolve the per-component bit offset.
     *
     * @param store       the DataStore the view will be used with
     * @param recordClass the annotated record class
     * @param <T>         the record type
     * @return a pre-compiled RowView
     */
    static <T extends Record> RowView<T> of(DataStore store, Class<T> recordClass) {
        return new RecordRowView<>(store, recordClass);
    }
}

/** Package-private implementation of {@link RowView}. */
final class RecordRowView<T extends Record> implements RowView<T> {

    /** Per-field read/write abstraction (boxed). */
    private interface FieldAccessor {
        Object get(DataStore store, int row);
        void set(DataStore store, int row, Object value);
    }

    private final FieldAccessor[] accessors;
    private final MethodHandle[]  getters;         // record component accessor handles
    private final MethodHandle    constructorHandle;

    @SuppressWarnings({"unchecked", "rawtypes"})
    RecordRowView(DataStore store, Class<T> recordClass) {
        ComponentLayout compLayout = LayoutBuilder.layout(recordClass);
        RecordComponent[] rcs = recordClass.getRecordComponents();
        int n = rcs.length;
        accessors = new FieldAccessor[n];
        getters   = new MethodHandle[n];

        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(recordClass, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            lookup = MethodHandles.lookup();
        }

        int compOffset = store.componentBitOffset(recordClass);

        for (int i = 0; i < n; i++) {
            RecordComponent rc = rcs[i];
            FieldLayout fl = compLayout.field(rc.getName());
            int absOffset = compOffset + fl.bitOffset();
            Class<?> type = rc.getType();

            if (type == int.class) {
                IntAccessor acc = new IntAccessor(absOffset, fl.bitWidth(), fl.minRaw());
                accessors[i] = new FieldAccessor() {
                    public Object get(DataStore s, int r) { return acc.get(s, r); }
                    public void set(DataStore s, int r, Object v) { acc.set(s, r, (Integer) v); }
                };
            } else if (type == long.class) {
                LongAccessor acc = new LongAccessor(absOffset, fl.bitWidth(), fl.minRaw());
                accessors[i] = new FieldAccessor() {
                    public Object get(DataStore s, int r) { return acc.get(s, r); }
                    public void set(DataStore s, int r, Object v) { acc.set(s, r, (Long) v); }
                };
            } else if (type == double.class) {
                DoubleAccessor acc = new DoubleAccessor(absOffset, fl.bitWidth(), fl.minRaw(), fl.scale());
                accessors[i] = new FieldAccessor() {
                    public Object get(DataStore s, int r) { return acc.get(s, r); }
                    public void set(DataStore s, int r, Object v) { acc.set(s, r, (Double) v); }
                };
            } else if (type == boolean.class) {
                BoolAccessor acc = new BoolAccessor(absOffset);
                accessors[i] = new FieldAccessor() {
                    public Object get(DataStore s, int r) { return acc.get(s, r); }
                    public void set(DataStore s, int r, Object v) { acc.set(s, r, (Boolean) v); }
                };
            } else if (type.isEnum()) {
                EnumField ann = rc.getAnnotation(EnumField.class);
                boolean explicit = ann != null && ann.useExplicitCodes();
                EnumAccessor acc = EnumAccessor.forField(absOffset, fl.bitWidth(),
                        (Class<? extends Enum>) type, explicit);
                accessors[i] = new FieldAccessor() {
                    public Object get(DataStore s, int r) { return acc.get(s, r); }
                    @SuppressWarnings("unchecked")
                    public void set(DataStore s, int r, Object v) { acc.set(s, r, (Enum) v); }
                };
            } else {
                throw new IllegalArgumentException(
                        "Unsupported field type for RowView: " + type + " in " + rc.getName());
            }

            // Getter method handle for the record component accessor method.
            // Record accessor methods are public so the standard lookup succeeds.
            // The fallback with setAccessible handles package-private nested records.
            try {
                getters[i] = lookup.unreflect(rc.getAccessor());
            } catch (IllegalAccessException e) {
                try {
                    rc.getAccessor().setAccessible(true);
                    getters[i] = MethodHandles.lookup().unreflect(rc.getAccessor());
                } catch (IllegalAccessException ex) {
                    throw new RuntimeException("Cannot access accessor for " + rc.getName(), ex);
                }
            }
        }

        // Constructor method handle
        Class<?>[] paramTypes = Arrays.stream(rcs)
                .map(RecordComponent::getType)
                .toArray(Class[]::new);
        try {
            Constructor<T> ctor = recordClass.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            constructorHandle = lookup.unreflectConstructor(ctor);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Cannot access canonical constructor of " + recordClass, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get(DataStore store, int row) {
        Object[] args = new Object[accessors.length];
        for (int i = 0; i < accessors.length; i++) {
            args[i] = accessors[i].get(store, row);
        }
        try {
            return (T) constructorHandle.invokeWithArguments(args);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to construct record instance", e);
        }
    }

    @Override
    public void set(DataStore store, int row, T value) {
        for (int i = 0; i < accessors.length; i++) {
            Object fieldVal;
            try {
                fieldVal = getters[i].invoke(value);
            } catch (Throwable e) {
                throw new RuntimeException("Failed to read record field", e);
            }
            accessors[i].set(store, row, fieldVal);
        }
    }
}
