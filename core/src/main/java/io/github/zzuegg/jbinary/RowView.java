package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.*;
import io.github.zzuegg.jbinary.annotation.EnumField;
import io.github.zzuegg.jbinary.schema.ComponentLayout;
import io.github.zzuegg.jbinary.schema.FieldLayout;
import io.github.zzuegg.jbinary.schema.LayoutBuilder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;

/**
 * Pre-compiled bundled accessor for all fields of a component type (record or plain class).
 *
 * <p>A {@code RowView} is constructed once (with {@link #of}) and reused.
 * All bit offsets, widths, and field decoders are pre-computed at construction time.
 * At call time, {@link #get} reads all fields from the store and returns an instance;
 * {@link #set} writes all fields from an instance back to the store.
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
 * @param <T> the component type (record or plain class with a no-arg constructor)
 */
public interface RowView<T> {

    /**
     * Reads all fields of the component type from the given row in the store
     * and returns a new record instance.
     */
    T get(DataStore<?> store, int row);

    /**
     * Writes all fields of the given record instance to the given row in the store.
     */
    void set(DataStore<?> store, int row, T value);

    /**
     * Creates a {@code RowView} for component type {@code T} using the given store
     * to resolve the per-component bit offset.
     *
     * <p>If {@code cls} is a record, fields are read/written via record accessor methods
     * and the canonical constructor.  If {@code cls} is a plain class, it must have a
     * public or accessible no-arg constructor; fields are read/written via reflection.
     *
     * @param store the DataStore the view will be used with
     * @param cls   the annotated component class (record or plain class)
     * @param <T>   the component type
     * @return a pre-compiled RowView
     */
    @SuppressWarnings("unchecked")
    static <T> RowView<T> of(DataStore<?> store, Class<T> cls) {
        if (cls.isRecord()) {
            return (RowView<T>) new RecordRowView<>(store, (Class<? extends Record>) cls);
        }
        return new PlainClassRowView<>(store, cls);
    }
}

/** Package-private implementation of {@link RowView} for record types. */
final class RecordRowView<T extends Record> implements RowView<T> {

    /** Per-field read/write abstraction (boxed). */
    interface FieldAccessor {
        Object get(DataStore<?> store, int row);
        void set(DataStore<?> store, int row, Object value);
    }

    private final FieldAccessor[] accessors;
    private final MethodHandle[]  getters;         // record component accessor handles
    private final MethodHandle    constructorHandle;

    @SuppressWarnings({"unchecked", "rawtypes"})
    RecordRowView(DataStore<?> store, Class<T> recordClass) {
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
            Class<?> type = rc.getType();

            if (hasComposedFields(compLayout, rc.getName())) {
                // Composed component — build a recursive accessor
                accessors[i] = buildComposedAccessor(rc.getName(), type,
                        compLayout, compOffset, lookup);
            } else {
                FieldLayout fl = compLayout.field(rc.getName());
                int absOffset = compOffset + fl.bitOffset();
                accessors[i] = buildPrimitiveAccessor(type, fl, absOffset,
                        rc.getAnnotation(EnumField.class));
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

    /**
     * Returns {@code true} if {@code layout} contains any field whose name starts with
     * {@code fieldName + "."}, indicating that {@code fieldName} is a composed type.
     */
    static boolean hasComposedFields(ComponentLayout layout, String fieldName) {
        String prefix = fieldName + ".";
        return layout.fields().stream().anyMatch(f -> f.name().startsWith(prefix));
    }

    /**
     * Builds a {@link FieldAccessor} for a primitive (non-composed) field.
     * Supports {@code int}, {@code long}, {@code double}, {@code float}, {@code boolean},
     * and enum types.
     *
     * @param enumAnn  the {@link EnumField} annotation on the field (may be {@code null})
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static FieldAccessor buildPrimitiveAccessor(
            Class<?> type, FieldLayout fl, int absOffset, EnumField enumAnn) {
        if (type == int.class) {
            IntAccessor acc = new IntAccessor(absOffset, fl.bitWidth(), fl.minRaw());
            return new FieldAccessor() {
                public Object get(DataStore s, int r) { return acc.get(s, r); }
                public void set(DataStore s, int r, Object v) { acc.set(s, r, (Integer) v); }
            };
        } else if (type == byte.class) {
            ByteAccessor acc = new ByteAccessor(absOffset, fl.bitWidth(), fl.minRaw());
            return new FieldAccessor() {
                public Object get(DataStore s, int r) { return acc.get(s, r); }
                public void set(DataStore s, int r, Object v) { acc.set(s, r, (Byte) v); }
            };
        } else if (type == short.class) {
            ShortAccessor acc = new ShortAccessor(absOffset, fl.bitWidth(), fl.minRaw());
            return new FieldAccessor() {
                public Object get(DataStore s, int r) { return acc.get(s, r); }
                public void set(DataStore s, int r, Object v) { acc.set(s, r, (Short) v); }
            };
        } else if (type == char.class) {
            CharAccessor acc = new CharAccessor(absOffset, fl.bitWidth(), fl.minRaw());
            return new FieldAccessor() {
                public Object get(DataStore s, int r) { return acc.get(s, r); }
                public void set(DataStore s, int r, Object v) { acc.set(s, r, (Character) v); }
            };
        } else if (type == long.class) {
            LongAccessor acc = new LongAccessor(absOffset, fl.bitWidth(), fl.minRaw());
            return new FieldAccessor() {
                public Object get(DataStore s, int r) { return acc.get(s, r); }
                public void set(DataStore s, int r, Object v) { acc.set(s, r, (Long) v); }
            };
        } else if (type == double.class) {
            if (fl.scale() == 0) {
                // scale=0 means raw IEEE 754 bits (unannotated field or explicit raw storage)
                DoubleBitsAccessor acc = new DoubleBitsAccessor(absOffset);
                return new FieldAccessor() {
                    public Object get(DataStore s, int r) { return acc.get(s, r); }
                    public void set(DataStore s, int r, Object v) { acc.set(s, r, (Double) v); }
                };
            }
            DoubleAccessor acc = new DoubleAccessor(absOffset, fl.bitWidth(), fl.minRaw(), fl.scale());
            return new FieldAccessor() {
                public Object get(DataStore s, int r) { return acc.get(s, r); }
                public void set(DataStore s, int r, Object v) { acc.set(s, r, (Double) v); }
            };
        } else if (type == float.class) {
            if (fl.scale() == 0) {
                // scale=0 means raw IEEE 754 bits (unannotated field or explicit raw storage)
                FloatBitsAccessor acc = new FloatBitsAccessor(absOffset);
                return new FieldAccessor() {
                    public Object get(DataStore s, int r) { return acc.get(s, r); }
                    public void set(DataStore s, int r, Object v) { acc.set(s, r, (Float) v); }
                };
            }
            FloatAccessor acc = new FloatAccessor(absOffset, fl.bitWidth(), fl.minRaw(), fl.scale());
            return new FieldAccessor() {
                public Object get(DataStore s, int r) { return acc.get(s, r); }
                public void set(DataStore s, int r, Object v) { acc.set(s, r, (Float) v); }
            };
        } else if (type == boolean.class) {
            BoolAccessor acc = new BoolAccessor(absOffset);
            return new FieldAccessor() {
                public Object get(DataStore s, int r) { return acc.get(s, r); }
                public void set(DataStore s, int r, Object v) { acc.set(s, r, (Boolean) v); }
            };
        } else if (type.isEnum()) {
            boolean explicit = enumAnn != null && enumAnn.useExplicitCodes();
            EnumAccessor acc = EnumAccessor.forField(absOffset, fl.bitWidth(),
                    (Class<? extends Enum>) type, explicit);
            return new FieldAccessor() {
                public Object get(DataStore s, int r) { return acc.get(s, r); }
                @SuppressWarnings("unchecked")
                public void set(DataStore s, int r, Object v) { acc.set(s, r, (Enum) v); }
            };
        } else if (type == String.class) {
            StringAccessor acc = new StringAccessor(absOffset, (int) fl.minRaw(), (int) fl.scale());
            return new FieldAccessor() {
                public Object get(DataStore s, int r) { return acc.get(s, r); }
                public void set(DataStore s, int r, Object v) { acc.set(s, r, (String) v); }
            };
        }
        throw new IllegalArgumentException("Unsupported field type for RowView: " + type);
    }

    /**
     * Builds a {@link FieldAccessor} for a composed component (record or plain class).
     * Dispatches to a record-specific or plain-class-specific implementation.
     */
    static FieldAccessor buildComposedAccessor(
            String prefix, Class<?> subType,
            ComponentLayout compLayout, int compOffset,
            MethodHandles.Lookup lookup) {
        if (subType.isRecord()) {
            return buildComposedRecordAccessor(prefix, subType, compLayout, compOffset, lookup);
        } else {
            return buildComposedClassAccessor(prefix, subType, compLayout, compOffset, lookup);
        }
    }

    /**
     * Builds a {@link FieldAccessor} for a composed <em>record</em> sub-type.
     * Reconstructs the sub-record on reads (via its canonical constructor)
     * and decomposes it on writes (via record accessor methods).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static FieldAccessor buildComposedRecordAccessor(
            String prefix, Class<?> subType,
            ComponentLayout compLayout, int compOffset,
            MethodHandles.Lookup lookup) {

        RecordComponent[] subRcs = subType.getRecordComponents();
        FieldAccessor[]  subAccessors = new FieldAccessor[subRcs.length];
        MethodHandle[]   subGetters   = new MethodHandle[subRcs.length];

        for (int j = 0; j < subRcs.length; j++) {
            RecordComponent subRc = subRcs[j];
            String subFieldName = prefix + "." + subRc.getName();
            Class<?> subFieldType = subRc.getType();

            if (hasComposedFields(compLayout, subFieldName)) {
                subAccessors[j] = buildComposedAccessor(subFieldName, subFieldType,
                        compLayout, compOffset, lookup);
            } else {
                FieldLayout fl = compLayout.field(subFieldName);
                int absOffset = compOffset + fl.bitOffset();
                subAccessors[j] = buildPrimitiveAccessor(subFieldType, fl, absOffset,
                        subRc.getAnnotation(EnumField.class));
            }

            try {
                subGetters[j] = lookup.unreflect(subRc.getAccessor());
            } catch (IllegalAccessException e) {
                try {
                    subRc.getAccessor().setAccessible(true);
                    subGetters[j] = MethodHandles.lookup().unreflect(subRc.getAccessor());
                } catch (IllegalAccessException ex) {
                    throw new RuntimeException("Cannot access accessor for " + subRc.getName(), ex);
                }
            }
        }

        // Constructor handle for the sub-record
        Class<?>[] paramTypes = Arrays.stream(subRcs)
                .map(RecordComponent::getType).toArray(Class[]::new);
        MethodHandle ctorHandle;
        try {
            Constructor<?> ctor = subType.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            ctorHandle = lookup.unreflectConstructor(ctor);
        } catch (Exception e) {
            throw new RuntimeException("Cannot access canonical constructor of " + subType, e);
        }

        final FieldAccessor[] finalSubAccessors = subAccessors;
        final MethodHandle[]  finalSubGetters   = subGetters;
        final MethodHandle    finalCtorHandle   = ctorHandle;

        return new FieldAccessor() {
            public Object get(DataStore<?> s, int r) {
                Object[] args = new Object[finalSubAccessors.length];
                for (int j = 0; j < finalSubAccessors.length; j++) {
                    args[j] = finalSubAccessors[j].get(s, r);
                }
                try {
                    return finalCtorHandle.invokeWithArguments(args);
                } catch (Throwable e) {
                    throw new RuntimeException("Failed to construct " + subType.getSimpleName(), e);
                }
            }

            public void set(DataStore<?> s, int r, Object v) {
                for (int j = 0; j < finalSubAccessors.length; j++) {
                    Object fieldVal;
                    try {
                        fieldVal = finalSubGetters[j].invoke(v);
                    } catch (Throwable e) {
                        throw new RuntimeException(
                                "Failed to read field from " + subType.getSimpleName(), e);
                    }
                    finalSubAccessors[j].set(s, r, fieldVal);
                }
            }
        };
    }

    /**
     * Builds a {@link FieldAccessor} for a composed <em>plain class</em> sub-type.
     * Reconstructs an instance on reads (via no-arg constructor + field writes)
     * and decomposes it on writes (via VarHandle field reads).
     * The plain class must have an accessible no-arg constructor.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static FieldAccessor buildComposedClassAccessor(
            String prefix, Class<?> subType,
            ComponentLayout compLayout, int compOffset,
            MethodHandles.Lookup lookup) {

        MethodHandles.Lookup classLookup;
        try {
            classLookup = MethodHandles.privateLookupIn(subType, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            classLookup = lookup;
        }

        Field[] subFields = Arrays.stream(subType.getDeclaredFields())
                .filter(f -> !f.isSynthetic() && !Modifier.isStatic(f.getModifiers()))
                .toArray(Field[]::new);

        FieldAccessor[] subAccessors = new FieldAccessor[subFields.length];
        VarHandle[]     subVarHandles = new VarHandle[subFields.length];

        for (int j = 0; j < subFields.length; j++) {
            Field subField = subFields[j];
            String subFieldName = prefix + "." + subField.getName();
            Class<?> subFieldType = subField.getType();

            if (hasComposedFields(compLayout, subFieldName)) {
                subAccessors[j] = buildComposedAccessor(subFieldName, subFieldType,
                        compLayout, compOffset, classLookup);
            } else {
                FieldLayout fl = compLayout.field(subFieldName);
                int absOffset = compOffset + fl.bitOffset();
                subAccessors[j] = buildPrimitiveAccessor(subFieldType, fl, absOffset,
                        subField.getAnnotation(EnumField.class));
            }

            subField.setAccessible(true);
            try {
                subVarHandles[j] = classLookup.unreflectVarHandle(subField);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(
                        "Cannot access field " + subField.getName()
                        + " in " + subType.getSimpleName(), e);
            }
        }

        Constructor<?> noArgCtor;
        try {
            noArgCtor = subType.getDeclaredConstructor();
            noArgCtor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "Composed plain-class type " + subType.getSimpleName()
                    + " must have a no-arg constructor", e);
        }

        final FieldAccessor[] finalSubAccessors = subAccessors;
        final VarHandle[]     finalVarHandles   = subVarHandles;
        final Constructor<?>  finalCtor         = noArgCtor;

        return new FieldAccessor() {
            public Object get(DataStore<?> s, int r) {
                Object instance;
                try {
                    instance = finalCtor.newInstance();
                } catch (Exception e) {
                    throw new RuntimeException(
                            "Failed to create instance of " + subType.getSimpleName(), e);
                }
                for (int j = 0; j < finalSubAccessors.length; j++) {
                    finalVarHandles[j].set(instance, finalSubAccessors[j].get(s, r));
                }
                return instance;
            }

            public void set(DataStore<?> s, int r, Object v) {
                for (int j = 0; j < finalSubAccessors.length; j++) {
                    finalSubAccessors[j].set(s, r, finalVarHandles[j].get(v));
                }
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get(DataStore<?> store, int row) {
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
    public void set(DataStore<?> store, int row, T value) {
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

/**
 * Package-private implementation of {@link RowView} for plain (non-record) classes.
 *
 * <p>The class must have an accessible no-arg constructor.  All non-static, non-synthetic
 * declared fields that carry bit-packing annotations (or belong to a composed sub-type)
 * are read/written via {@link VarHandle}.
 *
 * <p>On {@link #get}: a new instance is created via the no-arg constructor and each field
 * is populated from the store.
 * On {@link #set}: each field is read from the instance via VarHandle and written to the store.
 */
final class PlainClassRowView<T> implements RowView<T> {

    private final RecordRowView.FieldAccessor[] accessors;
    private final VarHandle[]   varHandles;    // one per top-level field
    private final Constructor<T> constructor;

    @SuppressWarnings({"unchecked", "rawtypes"})
    PlainClassRowView(DataStore<?> store, Class<T> cls) {
        ComponentLayout compLayout = LayoutBuilder.layout(cls);

        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(cls, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            lookup = MethodHandles.lookup();
        }

        int compOffset = store.componentBitOffset(cls);

        Field[] fields = Arrays.stream(cls.getDeclaredFields())
                .filter(f -> !f.isSynthetic() && !Modifier.isStatic(f.getModifiers()))
                .toArray(Field[]::new);

        int n = fields.length;
        accessors   = new RecordRowView.FieldAccessor[n];
        varHandles  = new VarHandle[n];

        for (int i = 0; i < n; i++) {
            Field f = fields[i];
            Class<?> type = f.getType();

            if (RecordRowView.hasComposedFields(compLayout, f.getName())) {
                accessors[i] = RecordRowView.buildComposedAccessor(
                        f.getName(), type, compLayout, compOffset, lookup);
            } else {
                FieldLayout fl = compLayout.field(f.getName());
                int absOffset = compOffset + fl.bitOffset();
                accessors[i] = RecordRowView.buildPrimitiveAccessor(type, fl, absOffset,
                        f.getAnnotation(EnumField.class));
            }

            f.setAccessible(true);
            try {
                varHandles[i] = lookup.unreflectVarHandle(f);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot access field " + f.getName(), e);
            }
        }

        try {
            constructor = cls.getDeclaredConstructor();
            constructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    cls.getSimpleName() + " must have a no-arg constructor for use with RowView", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get(DataStore<?> store, int row) {
        T instance;
        try {
            instance = constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create instance", e);
        }
        for (int i = 0; i < accessors.length; i++) {
            varHandles[i].set(instance, accessors[i].get(store, row));
        }
        return instance;
    }

    @Override
    public void set(DataStore<?> store, int row, T value) {
        for (int i = 0; i < accessors.length; i++) {
            accessors[i].set(store, row, varHandles[i].get(value));
        }
    }
}
