package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.*;
import io.github.zzuegg.jbinary.annotation.EnumField;
import io.github.zzuegg.jbinary.schema.ComponentLayout;
import io.github.zzuegg.jbinary.schema.FieldLayout;
import io.github.zzuegg.jbinary.schema.LayoutBuilder;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.*;

/**
 * An array-backed {@link DataStore} that stores each field in its own {@code long} slot
 * <strong>without</strong> bit-level compression.
 *
 * <p>Every field — regardless of its declared range — occupies exactly one {@code long}
 * in the backing array.  Reads and writes are simple array accesses with no bit-shift or
 * mask arithmetic, making this the fastest possible DataStore for throughput-critical
 * code paths that do not need memory compactness.
 *
 * <p><strong>Accessor creation:</strong> Because the field layout is different from the
 * bit-packed layout used by {@link PackedDataStore}, standard
 * {@link Accessors#intFieldInStore Accessors.intFieldInStore} will <em>not</em> work with
 * this store.  Use the dedicated factory methods on this class instead:
 * <pre>{@code
 * RawDataStore<Terrain> raw = DataStore.raw(10_000, Terrain.class);
 * IntAccessor    height = raw.intAccessor(Terrain.class, "height");
 * DoubleAccessor temp   = raw.doubleAccessor(Terrain.class, "temperature");
 * BoolAccessor   active = raw.boolAccessor(Terrain.class, "active");
 *
 * height.set(raw, 0, 200);
 * int h = height.get(raw, 0);   // → single array read, no bit-shift
 *
 * RowView<Terrain> view = raw.rowView(Terrain.class);
 * view.set(raw, 0, new Terrain(100, 22.5, true));
 * }</pre>
 *
 * <p>Use {@link DataStore#raw} to create instances.
 *
 * @param <T> marker type for the stored component(s)
 */
public final class RawDataStore<T> implements DataStore<T> {

    static final int  MAGIC    = 0x4A42494E; // "JBIN"
    static final byte TYPE_RAW = 3;

    private final long[] data;
    private final int capacity;
    private final int rowStrideLongs; // = total number of fields across all components

    /** Map from component class to the global slot index of its first field. */
    private final Map<Class<?>, Integer> componentSlotOffsets;

    /** Map from (component, fieldName) to global slot index. */
    private final Map<Class<?>, int[]> fieldSlotsByComponent; // fieldIndex → slotIndex

    /** Ordered list of component classes (for serialisation). */
    private final List<Class<?>> componentOrder;

    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static <T> RawDataStore<T> create(int capacity, Class<?>... componentClasses) {
        if (componentClasses == null || componentClasses.length == 0) {
            throw new IllegalArgumentException("At least one component class is required");
        }

        Map<Class<?>, ComponentLayout> layouts = new LinkedHashMap<>();
        for (Class<?> cls : componentClasses) {
            layouts.put(cls, LayoutBuilder.layout(cls));
        }

        // Assign one slot per field across all components
        int slotCursor = 0;
        Map<Class<?>, Integer> slotOffsets = new LinkedHashMap<>();
        Map<Class<?>, int[]> fieldSlots = new LinkedHashMap<>();
        for (Map.Entry<Class<?>, ComponentLayout> e : layouts.entrySet()) {
            ComponentLayout layout = e.getValue();
            slotOffsets.put(e.getKey(), slotCursor);
            int[] slots = new int[layout.fields().size()];
            for (int i = 0; i < slots.length; i++) {
                slots[i] = slotCursor + i;
            }
            fieldSlots.put(e.getKey(), slots);
            slotCursor += layout.fields().size();
        }
        int totalSlots = slotCursor;

        return new RawDataStore<>(capacity, totalSlots,
                Collections.unmodifiableMap(slotOffsets),
                Collections.unmodifiableMap(fieldSlots),
                List.copyOf(layouts.keySet()));
    }

    private RawDataStore(int capacity, int rowStrideLongs,
                         Map<Class<?>, Integer> componentSlotOffsets,
                         Map<Class<?>, int[]> fieldSlotsByComponent,
                         List<Class<?>> componentOrder) {
        this.capacity = capacity;
        this.rowStrideLongs = rowStrideLongs;
        this.componentSlotOffsets = componentSlotOffsets;
        this.fieldSlotsByComponent = fieldSlotsByComponent;
        this.componentOrder = componentOrder;
        long totalLongs = (long) capacity * rowStrideLongs;
        if (totalLongs > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "RawDataStore too large: capacity=" + capacity + " rowStride=" + rowStrideLongs);
        }
        this.data = new long[(int) totalLongs];
    }

    // -----------------------------------------------------------------------
    // DataStore implementation

    /**
     * Reads the raw value stored at the given slot.
     *
     * <p>The {@code bitOffset} parameter is interpreted as {@code slotIndex * 64};
     * the {@code bitWidth} parameter is ignored.  Use the accessor factory methods
     * on this class to obtain correctly configured accessors.
     */
    @Override
    public long readBits(int row, int bitOffset, int bitWidth) {
        return data[row * rowStrideLongs + (bitOffset >>> 6)];
    }

    /**
     * Writes a raw value to the slot at {@code slotIndex = bitOffset / 64}.
     *
     * <p>The {@code bitWidth} parameter is ignored.
     */
    @Override
    public void writeBits(int row, int bitOffset, int bitWidth, long value) {
        data[row * rowStrideLongs + (bitOffset >>> 6)] = value;
    }

    @Override
    public int capacity() { return capacity; }

    @Override
    public int rowStrideLongs() { return rowStrideLongs; }

    /**
     * Returns the bit offset of the first slot of the given component.
     *
     * <p>This equals {@code firstFieldSlotIndex * 64}.  It is exposed for
     * interface completeness but direct use via {@link #intAccessor} etc. is preferred.
     */
    @Override
    public int componentBitOffset(Class<?> cls) {
        Integer off = componentSlotOffsets.get(cls);
        if (off == null) throw new IllegalArgumentException(
                "Component " + cls.getSimpleName() + " not registered in this RawDataStore");
        return off * 64;
    }

    // -----------------------------------------------------------------------
    // Accessor factory methods

    /** Returns an allocation-free {@code int} accessor for the named field. */
    public IntAccessor intAccessor(Class<?> component, String fieldName) {
        int slot = slotFor(component, fieldName);
        FieldLayout fl = LayoutBuilder.layout(component).field(fieldName);
        return new IntAccessor(slot * 64, 64, fl.minRaw());
    }

    /** Returns an allocation-free {@code long} accessor for the named field. */
    public LongAccessor longAccessor(Class<?> component, String fieldName) {
        int slot = slotFor(component, fieldName);
        FieldLayout fl = LayoutBuilder.layout(component).field(fieldName);
        return new LongAccessor(slot * 64, 64, fl.minRaw());
    }

    /** Returns an allocation-free {@code double} accessor for the named field. */
    public DoubleAccessor doubleAccessor(Class<?> component, String fieldName) {
        int slot = slotFor(component, fieldName);
        FieldLayout fl = LayoutBuilder.layout(component).field(fieldName);
        return new DoubleAccessor(slot * 64, 64, fl.minRaw(), fl.scale());
    }

    /** Returns an allocation-free {@code boolean} accessor for the named field. */
    public BoolAccessor boolAccessor(Class<?> component, String fieldName) {
        int slot = slotFor(component, fieldName);
        return new BoolAccessor(slot * 64);
    }

    /** Returns an allocation-free enum accessor for the named field. */
    @SuppressWarnings("unchecked")
    public <E extends Enum<E>> EnumAccessor<E> enumAccessor(Class<?> component, String fieldName) {
        int slot = slotFor(component, fieldName);
        FieldLayout fl = LayoutBuilder.layout(component).field(fieldName);
        Class<E> enumType = enumType(component, fieldName);
        boolean explicit = enumFieldAnnotation(component, fieldName).useExplicitCodes();
        return EnumAccessor.forField(slot * 64, fl.bitWidth(), enumType, explicit);
    }

    /**
     * Creates a {@link RowView} for the given record class that is correctly configured
     * for this raw store's slot-based layout.
     *
     * <p>Do <em>not</em> use {@link RowView#of} with a {@code RawDataStore}; use this
     * method instead.
     */
    public <R extends Record> RowView<R> rowView(Class<R> recordClass) {
        return new RawRecordRowView<>(this, recordClass);
    }

    // -----------------------------------------------------------------------
    // Serialization (type tag = 3)

    @Override
    public void write(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(MAGIC);
        dos.writeByte(TYPE_RAW);
        dos.writeInt(capacity);
        dos.writeInt(rowStrideLongs);
        for (long word : data) {
            dos.writeLong(word);
        }
        dos.flush();
    }

    @Override
    public void read(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int magic = dis.readInt();
        if (magic != MAGIC) throw new IOException(
                "Invalid magic bytes: expected 0x" + Integer.toHexString(MAGIC));
        int type = dis.readByte();
        if (type != TYPE_RAW) throw new IOException(
                "Expected raw store (type 3), got type " + type);
        int cap    = dis.readInt();
        int stride = dis.readInt();
        if (cap != capacity || stride != rowStrideLongs) throw new IllegalArgumentException(
                "Store metadata mismatch: stream has capacity=" + cap +
                " rowStride=" + stride + " but store has capacity=" + capacity +
                " rowStride=" + rowStrideLongs);
        for (int i = 0; i < data.length; i++) {
            data[i] = dis.readLong();
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers

    private int slotFor(Class<?> component, String fieldName) {
        int[] slots = fieldSlotsByComponent.get(component);
        if (slots == null) throw new IllegalArgumentException(
                "Component " + component.getSimpleName() + " not registered");
        ComponentLayout layout = LayoutBuilder.layout(component);
        List<FieldLayout> fields = layout.fields();
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equals(fieldName)) return slots[i];
        }
        throw new IllegalArgumentException(
                "Field '" + fieldName + "' not found in " + component.getSimpleName());
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

    private static io.github.zzuegg.jbinary.annotation.EnumField enumFieldAnnotation(
            Class<?> component, String fieldName) {
        if (component.isRecord()) {
            for (RecordComponent rc : component.getRecordComponents()) {
                if (rc.getName().equals(fieldName))
                    return rc.getAnnotation(io.github.zzuegg.jbinary.annotation.EnumField.class);
            }
        } else {
            try {
                return component.getDeclaredField(fieldName)
                        .getAnnotation(io.github.zzuegg.jbinary.annotation.EnumField.class);
            } catch (NoSuchFieldException e) {
                throw new IllegalArgumentException(e);
            }
        }
        throw new IllegalArgumentException("Field not found: " + fieldName);
    }
}

// -----------------------------------------------------------------------
// RowView implementation for RawDataStore

/** Package-private RowView implementation for {@link RawDataStore}. */
final class RawRecordRowView<T extends Record> implements RowView<T> {

    private interface FieldAccessor {
        Object get(DataStore<?> store, int row);
        void   set(DataStore<?> store, int row, Object value);
    }

    private final FieldAccessor[] accessors;
    private final MethodHandle[]  getters;
    private final MethodHandle    constructorHandle;

    @SuppressWarnings({"unchecked", "rawtypes"})
    RawRecordRowView(RawDataStore<?> rawStore, Class<T> recordClass) {
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

        for (int i = 0; i < n; i++) {
            RecordComponent rc = rcs[i];
            Class<?> type = rc.getType();

            if (type == int.class) {
                IntAccessor acc = rawStore.intAccessor(recordClass, rc.getName());
                accessors[i] = new FieldAccessor() {
                    public Object get(DataStore s, int r) { return acc.get(s, r); }
                    public void set(DataStore s, int r, Object v) { acc.set(s, r, (Integer) v); }
                };
            } else if (type == long.class) {
                LongAccessor acc = rawStore.longAccessor(recordClass, rc.getName());
                accessors[i] = new FieldAccessor() {
                    public Object get(DataStore s, int r) { return acc.get(s, r); }
                    public void set(DataStore s, int r, Object v) { acc.set(s, r, (Long) v); }
                };
            } else if (type == double.class) {
                DoubleAccessor acc = rawStore.doubleAccessor(recordClass, rc.getName());
                accessors[i] = new FieldAccessor() {
                    public Object get(DataStore s, int r) { return acc.get(s, r); }
                    public void set(DataStore s, int r, Object v) { acc.set(s, r, (Double) v); }
                };
            } else if (type == boolean.class) {
                BoolAccessor acc = rawStore.boolAccessor(recordClass, rc.getName());
                accessors[i] = new FieldAccessor() {
                    public Object get(DataStore s, int r) { return acc.get(s, r); }
                    public void set(DataStore s, int r, Object v) { acc.set(s, r, (Boolean) v); }
                };
            } else if (type.isEnum()) {
                EnumField ann = rc.getAnnotation(EnumField.class);
                boolean explicit = ann != null && ann.useExplicitCodes();
                EnumAccessor acc = rawStore.enumAccessor(recordClass, rc.getName());
                accessors[i] = new FieldAccessor() {
                    public Object get(DataStore s, int r) { return acc.get(s, r); }
                    @SuppressWarnings("unchecked")
                    public void set(DataStore s, int r, Object v) { acc.set(s, r, (Enum) v); }
                };
            } else {
                throw new IllegalArgumentException(
                        "Unsupported field type for RawRowView: " + type + " in " + rc.getName());
            }

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

        Class<?>[] paramTypes = java.util.Arrays.stream(rcs)
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
    public T get(DataStore<?> store, int row) {
        Object[] args = new Object[accessors.length];
        for (int i = 0; i < accessors.length; i++) args[i] = accessors[i].get(store, row);
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
            try { fieldVal = getters[i].invoke(value); }
            catch (Throwable e) { throw new RuntimeException("Failed to read record field", e); }
            accessors[i].set(store, row, fieldVal);
        }
    }
}
