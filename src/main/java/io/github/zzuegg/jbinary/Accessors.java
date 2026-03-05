package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.*;
import io.github.zzuegg.jbinary.annotation.EnumField;
import io.github.zzuegg.jbinary.schema.ComponentLayout;
import io.github.zzuegg.jbinary.schema.FieldLayout;
import io.github.zzuegg.jbinary.schema.LayoutBuilder;

import java.lang.reflect.RecordComponent;

/**
 * Static factory for creating allocation-free field accessors.
 *
 * <p>Usage pattern (store accessors as static interface fields):
 * <pre>{@code
 * public interface Terrain {
 *     IntAccessor    HEIGHT      = Accessors.intField(Terrain.class,      "height");
 *     DoubleAccessor TEMPERATURE = Accessors.doubleField(Terrain.class,   "temperature");
 *     BoolAccessor   ACTIVE      = Accessors.boolField(Terrain.class,     "active");
 * }
 *
 * DataStore<?> store = DataStore.of(1000, Terrain.class, Water.class);
 * int h = Terrain.HEIGHT.get(store, i);
 * Terrain.HEIGHT.set(store, i, 200);
 * }</pre>
 *
 * <p>Accessor objects are <em>not</em> bound to a specific {@link DataStore}; the store
 * is passed at each access call, keeping the pattern allocation-free and allowing one
 * accessor to be used with multiple stores of compatible schema.
 *
 * <p><strong>Note:</strong> The bit offset used by the accessor is the field's offset
 * <em>within the component's block</em>.  When the component is registered in a
 * {@link DataStore} (via {@link DataStore#of}), the store adds its own per-component
 * offset at construction time.  To obtain accessors that include the full row offset
 * for a given store, use {@link #intFieldInStore}/{@link #doubleFieldInStore} etc.
 * For the common case where each interface is used with exactly one DataStore,
 * store-relative accessors ({@link #intFieldInStore} etc.) should be preferred.
 * </p>
 */
public final class Accessors {

    private Accessors() {}

    // ------------------------------------------------------------------
    // Store-unaware accessors (offset = field offset within component block)
    // These work correctly ONLY when the component occupies the full row
    // (i.e., the DataStore was created with just that one component class).

    public static IntAccessor intField(Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        return new IntAccessor(fl.bitOffset(), fl.bitWidth(), fl.minRaw());
    }

    public static ByteAccessor byteField(Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        return new ByteAccessor(fl.bitOffset(), fl.bitWidth(), fl.minRaw());
    }

    public static ShortAccessor shortField(Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        return new ShortAccessor(fl.bitOffset(), fl.bitWidth(), fl.minRaw());
    }

    public static CharAccessor charField(Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        return new CharAccessor(fl.bitOffset(), fl.bitWidth(), fl.minRaw());
    }

    public static LongAccessor longField(Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        return new LongAccessor(fl.bitOffset(), fl.bitWidth(), fl.minRaw());
    }

    public static DoubleAccessor doubleField(Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        return new DoubleAccessor(fl.bitOffset(), fl.bitWidth(), fl.minRaw(), fl.scale());
    }

    public static FloatAccessor floatField(Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        return new FloatAccessor(fl.bitOffset(), fl.bitWidth(), fl.minRaw(), fl.scale());
    }

    public static BoolAccessor boolField(Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        return new BoolAccessor(fl.bitOffset());
    }

    @SuppressWarnings("unchecked")
    public static <E extends Enum<E>> EnumAccessor<E> enumField(
            Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        Class<E> enumType = enumType(component, fieldName);
        boolean explicit = component.isRecord()
                ? recordComponent(component, fieldName)
                      .getAnnotation(io.github.zzuegg.jbinary.annotation.EnumField.class)
                      .useExplicitCodes()
                : enumFieldAnnotation(component, fieldName).useExplicitCodes();
        return EnumAccessor.forField(fl.bitOffset(), fl.bitWidth(), enumType, explicit);
    }

    // ------------------------------------------------------------------
    // Store-aware accessors (absolute bit offset = component offset in store + field offset)

    public static IntAccessor intFieldInStore(DataStore<?> store, Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        int abs = store.componentBitOffset(component) + fl.bitOffset();
        return new IntAccessor(abs, fl.bitWidth(), fl.minRaw());
    }

    public static ByteAccessor byteFieldInStore(DataStore<?> store, Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        int abs = store.componentBitOffset(component) + fl.bitOffset();
        return new ByteAccessor(abs, fl.bitWidth(), fl.minRaw());
    }

    public static ShortAccessor shortFieldInStore(DataStore<?> store, Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        int abs = store.componentBitOffset(component) + fl.bitOffset();
        return new ShortAccessor(abs, fl.bitWidth(), fl.minRaw());
    }

    public static CharAccessor charFieldInStore(DataStore<?> store, Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        int abs = store.componentBitOffset(component) + fl.bitOffset();
        return new CharAccessor(abs, fl.bitWidth(), fl.minRaw());
    }

    public static LongAccessor longFieldInStore(DataStore<?> store, Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        int abs = store.componentBitOffset(component) + fl.bitOffset();
        return new LongAccessor(abs, fl.bitWidth(), fl.minRaw());
    }

    public static DoubleAccessor doubleFieldInStore(DataStore<?> store, Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        int abs = store.componentBitOffset(component) + fl.bitOffset();
        return new DoubleAccessor(abs, fl.bitWidth(), fl.minRaw(), fl.scale());
    }

    public static FloatAccessor floatFieldInStore(DataStore<?> store, Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        int abs = store.componentBitOffset(component) + fl.bitOffset();
        return new FloatAccessor(abs, fl.bitWidth(), fl.minRaw(), fl.scale());
    }

    public static BoolAccessor boolFieldInStore(DataStore<?> store, Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        int abs = store.componentBitOffset(component) + fl.bitOffset();
        return new BoolAccessor(abs);
    }

    @SuppressWarnings("unchecked")
    public static <E extends Enum<E>> EnumAccessor<E> enumFieldInStore(
            DataStore<?> store, Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        int abs = store.componentBitOffset(component) + fl.bitOffset();
        Class<E> enumType = enumType(component, fieldName);
        boolean explicit = enumFieldAnnotation(component, fieldName).useExplicitCodes();
        return EnumAccessor.forField(abs, fl.bitWidth(), enumType, explicit);
    }

    // ------------------------------------------------------------------
    private static FieldLayout layout(Class<?> component, String fieldName) {
        return LayoutBuilder.layout(component).field(fieldName);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Enum<E>> Class<E> enumType(Class<?> component, String fieldName) {
        int dot = fieldName.indexOf('.');
        if (dot >= 0) {
            Class<?> subType = resolveFieldType(component, fieldName.substring(0, dot));
            return enumType(subType, fieldName.substring(dot + 1));
        }
        if (component.isRecord()) {
            for (RecordComponent rc : component.getRecordComponents()) {
                if (rc.getName().equals(fieldName)) return (Class<E>) rc.getType();
            }
        } else {
            try {
                return (Class<E>) component.getDeclaredField(fieldName).getType();
            } catch (NoSuchFieldException e) {
                throw new IllegalArgumentException(e);
            }
        }
        throw new IllegalArgumentException("Field not found: " + fieldName);
    }

    private static RecordComponent recordComponent(Class<?> component, String fieldName) {
        int dot = fieldName.indexOf('.');
        if (dot >= 0) {
            Class<?> subType = resolveFieldType(component, fieldName.substring(0, dot));
            return recordComponent(subType, fieldName.substring(dot + 1));
        }
        for (RecordComponent rc : component.getRecordComponents()) {
            if (rc.getName().equals(fieldName)) return rc;
        }
        throw new IllegalArgumentException("Record component not found: " + fieldName);
    }

    private static EnumField enumFieldAnnotation(Class<?> component, String fieldName) {
        int dot = fieldName.indexOf('.');
        if (dot >= 0) {
            Class<?> subType = resolveFieldType(component, fieldName.substring(0, dot));
            return enumFieldAnnotation(subType, fieldName.substring(dot + 1));
        }
        if (component.isRecord()) {
            return recordComponent(component, fieldName)
                    .getAnnotation(EnumField.class);
        }
        try {
            return component.getDeclaredField(fieldName).getAnnotation(EnumField.class);
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Returns the declared type of the field/record-component named {@code name} in {@code cls}.
     */
    private static Class<?> resolveFieldType(Class<?> cls, String name) {
        if (cls.isRecord()) {
            for (RecordComponent rc : cls.getRecordComponents()) {
                if (rc.getName().equals(name)) return rc.getType();
            }
            throw new IllegalArgumentException(
                    "Field '" + name + "' not found in record " + cls.getSimpleName());
        } else {
            try {
                return cls.getDeclaredField(name).getType();
            } catch (NoSuchFieldException e) {
                throw new IllegalArgumentException(
                        "Field '" + name + "' not found in " + cls.getSimpleName(), e);
            }
        }
    }

    // ------------------------------------------------------------------
    // RowView factory

    /**
     * Creates a pre-compiled {@link RowView} for the given component class,
     * bound to the given store for bit-offset resolution.
     *
     * <p>The class may be a record or a plain class with a no-arg constructor.
     *
     * @param store the DataStore the view will be used with
     * @param cls   the annotated component class (record or plain class)
     * @param <T>   the component type
     * @return a {@link RowView} with all field accessors pre-computed
     */
    public static <T> RowView<T> rowViewInStore(DataStore<?> store, Class<T> cls) {
        return RowView.of(store, cls);
    }

    // ------------------------------------------------------------------
    // DataCursor factory

    /**
     * Creates a pre-compiled {@link DataCursor} for the given class, bound to the given store.
     *
     * <p>The class must have a public no-arg constructor and at least one field annotated with
     * {@link io.github.zzuegg.jbinary.annotation.StoreField}.  The cursor holds a single
     * pre-allocated instance and updates it in-place on every {@link DataCursor#load} call,
     * avoiding all per-row allocation.
     *
     * @param store the DataStore the cursor will be used with
     * @param cls   the cursor class
     * @param <T>   cursor type
     * @return a pre-compiled {@link DataCursor}
     */
    public static <T> DataCursor<T> dataCursorOf(DataStore<?> store, Class<T> cls) {
        return DataCursor.of(store, cls);
    }
}
