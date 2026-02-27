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
 * DataStore store = DataStore.of(1000, Terrain.class, Water.class);
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

    public static LongAccessor longField(Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        return new LongAccessor(fl.bitOffset(), fl.bitWidth(), fl.minRaw());
    }

    public static DoubleAccessor doubleField(Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        return new DoubleAccessor(fl.bitOffset(), fl.bitWidth(), fl.minRaw(), fl.scale());
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

    public static IntAccessor intFieldInStore(DataStore store, Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        int abs = store.componentBitOffset(component) + fl.bitOffset();
        return new IntAccessor(abs, fl.bitWidth(), fl.minRaw());
    }

    public static LongAccessor longFieldInStore(DataStore store, Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        int abs = store.componentBitOffset(component) + fl.bitOffset();
        return new LongAccessor(abs, fl.bitWidth(), fl.minRaw());
    }

    public static DoubleAccessor doubleFieldInStore(DataStore store, Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        int abs = store.componentBitOffset(component) + fl.bitOffset();
        return new DoubleAccessor(abs, fl.bitWidth(), fl.minRaw(), fl.scale());
    }

    public static BoolAccessor boolFieldInStore(DataStore store, Class<?> component, String fieldName) {
        FieldLayout fl = layout(component, fieldName);
        int abs = store.componentBitOffset(component) + fl.bitOffset();
        return new BoolAccessor(abs);
    }

    @SuppressWarnings("unchecked")
    public static <E extends Enum<E>> EnumAccessor<E> enumFieldInStore(
            DataStore store, Class<?> component, String fieldName) {
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
        for (RecordComponent rc : component.getRecordComponents()) {
            if (rc.getName().equals(fieldName)) return rc;
        }
        throw new IllegalArgumentException("Record component not found: " + fieldName);
    }

    private static EnumField enumFieldAnnotation(Class<?> component, String fieldName) {
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

    // ------------------------------------------------------------------
    // RowView factory

    /**
     * Creates a pre-compiled {@link RowView} for the given record component class,
     * bound to the given store for bit-offset resolution.
     *
     * @param store       the DataStore the view will be used with
     * @param recordClass the annotated record class
     * @param <T>         the record type
     * @return a {@link RowView} with all field accessors pre-computed
     */
    public static <T extends Record> RowView<T> rowViewInStore(DataStore store, Class<T> recordClass) {
        return RowView.of(store, recordClass);
    }
}
