package io.github.zzuegg.zayes;

import io.github.zzuegg.jbinary.DataCursor;
import io.github.zzuegg.jbinary.DataStore;
import io.github.zzuegg.jbinary.annotation.StoreField;
import io.github.zzuegg.jbinary.schema.ComponentLayout;
import io.github.zzuegg.jbinary.schema.FieldLayout;
import io.github.zzuegg.jbinary.schema.LayoutBuilder;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.jar.asm.Opcodes;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.RecordComponent;
import java.util.List;

/**
 * Bridges a zay-es {@link com.simsilica.es.EntityComponent} type {@code T} and a
 * {@link DataCursor} backed by a {@link DataStore}.
 *
 * <p>At construction time, ByteBuddy generates a mutable cursor class whose fields
 * mirror those of {@code T} and carry {@link StoreField} annotations pointing back to
 * {@code T}.  A {@link DataCursor} is then built over this generated class, so
 * {@link DataCursor#load} and {@link DataCursor#flush} use ByteBuddy-emitted
 * {@code PUTFIELD}/{@code GETFIELD} instructions instead of boxed
 * {@link java.lang.invoke.MethodHandle#invokeWithArguments} calls.
 *
 * <p><strong>Read path</strong> ({@link #read}):
 * {@code cursor.load(store, row)} populates the cursor's primitive fields via PUTFIELD.
 * A pre-built {@link MethodHandle} composed with {@link MethodHandles#filterArguments} and
 * {@link MethodHandles#permuteArguments} then reads each field as an unboxed primitive via
 * {@link VarHandle#toMethodHandle} GET and passes the values directly to the record
 * constructor — zero {@code Object[]} allocation, zero primitive boxing.
 *
 * <p><strong>Write path</strong> ({@link #write}):
 * typed {@link VarHandle} / record-accessor {@link MethodHandle#invokeExact} reads
 * field values from the incoming component → typed {@link VarHandle} writes to cursor
 * → {@code cursor.flush(store, row)}.
 *
 * @param <T> the component type
 */
final class ComponentCursorBridge<T> {

    private static final java.util.concurrent.atomic.AtomicInteger COUNTER =
            new java.util.concurrent.atomic.AtomicInteger();

    private final DataCursor<?>  cursor;
    private final Object         cursorInstance;
    private final FieldBridge[]  bridges;       // one per field, ordered by layout (write path)
    private final MethodHandle   typedReader;   // (Object) -> Object; reads all fields without boxing

    private ComponentCursorBridge(DataCursor<?> cursor,
                                   Object cursorInstance,
                                   FieldBridge[] bridges,
                                   MethodHandle typedReader) {
        this.cursor         = cursor;
        this.cursorInstance = cursorInstance;
        this.bridges        = bridges;
        this.typedReader    = typedReader;
    }

    // -----------------------------------------------------------------------
    // Hot-path operations

    /**
     * Reads all component fields from the store and returns a new {@code T} instance.
     *
     * <p>Uses a pre-built MethodHandle that reads each cursor field as an unboxed
     * primitive and passes the values directly to the record constructor.
     */
    @SuppressWarnings("unchecked")
    T read(DataStore<?> store, int row) {
        cursor.load(store, row);
        try {
            return (T) typedReader.invokeExact(cursorInstance);
        } catch (Throwable e) {
            throw new RuntimeException("ComponentCursorBridge.read failed", e);
        }
    }

    /** Copies all field values from {@code component} into the cursor, then flushes to the store. */
    void write(DataStore<?> store, int row, T component) {
        for (FieldBridge bridge : bridges) {
            bridge.copyFromComponent(component, cursorInstance);
        }
        cursor.flush(store, row);
    }

    // -----------------------------------------------------------------------
    // Per-field bridge (writes one field from a component to the cursor)

    private interface FieldBridge {
        /** Copies the field value from the source component to the cursor instance. */
        void copyFromComponent(Object component, Object cursorInst);
    }

    // -----------------------------------------------------------------------
    // Factory

    /**
     * Generates a cursor class for {@code componentClass} at runtime using ByteBuddy,
     * then builds a fully pre-compiled {@link ComponentCursorBridge}.
     *
     * @return the bridge, or {@code null} if the component type cannot be supported
     *         (e.g. LayoutBuilder fails, no suitable constructor exists)
     */
    @SuppressWarnings("unchecked")
    static <T> ComponentCursorBridge<T> tryCreate(DataStore<?> store, Class<T> componentClass) {
        ComponentLayout layout;
        try {
            layout = LayoutBuilder.layout(componentClass);
        } catch (Exception e) {
            return null;   // unsupported type — caller falls back to heap array
        }

        List<FieldLayout> fields = layout.fields();
        if (fields.isEmpty()) return null;

        // Only flat field names (no dots) are supported; dotted names arise from composed
        // sub-records and require a more complex bridge.
        for (FieldLayout fl : fields) {
            if (fl.name().contains(".")) return null;
        }

        try {
            // ── Step 1: generate the cursor class with ByteBuddy ──────────────────────
            Class<?> cursorClass = generateCursorClass(componentClass, fields);

            // ── Step 2: build DataCursor over the generated class ─────────────────────
            DataCursor<?> cursor = DataCursor.of(store, cursorClass);
            Object cursorInst = cursor.get();

            // ── Step 3: pre-compute VarHandles for cursor fields ──────────────────────
            MethodHandles.Lookup cursorLookup =
                    MethodHandles.privateLookupIn(cursorClass, MethodHandles.lookup());

            // ── Step 4: pre-compute component constructor and field accessors ─────────
            MethodHandles.Lookup compLookup =
                    MethodHandles.privateLookupIn(componentClass, MethodHandles.lookup());

            FieldBridge[] bridges = buildBridges(componentClass, cursorClass,
                    fields, cursorLookup, compLookup);
            if (bridges == null) return null;

            MethodHandle ctor = findConstructor(componentClass, fields, compLookup);
            if (ctor == null) return null;

            // ── Step 5: build unboxed typed reader via MethodHandles composition ──────
            MethodHandle typedReader = buildTypedReader(cursorClass, componentClass,
                    fields, cursorLookup, ctor);

            return new ComponentCursorBridge<>((DataCursor<T>) cursor, cursorInst, bridges, typedReader);

        } catch (Exception e) {
            return null;   // fall back to heap
        }
    }

    // -----------------------------------------------------------------------
    // Helpers

    private static Class<?> generateCursorClass(Class<?> componentClass,
                                                  List<FieldLayout> fields) throws Exception {
        String name = componentClass.getName() + "$$Cursor$$" + COUNTER.incrementAndGet();

        var builder = new ByteBuddy().subclass(Object.class).name(name);

        for (FieldLayout fl : fields) {
            Class<?> javaType = layoutFieldType(componentClass, fl.name());
            builder = builder
                    .defineField(fl.name(), javaType, Opcodes.ACC_PUBLIC)
                    .annotateField(AnnotationDescription.Builder.ofType(StoreField.class)
                            .define("component", componentClass)
                            .define("field", fl.name())
                            .build());
        }

        try {
            MethodHandles.Lookup lookup =
                    MethodHandles.privateLookupIn(componentClass, MethodHandles.lookup());
            return builder.make()
                    .load(componentClass.getClassLoader(),
                            ClassLoadingStrategy.UsingLookup.of(lookup))
                    .getLoaded();
        } catch (Exception e) {
            // Fall back to injection when the lookup-based strategy is unavailable
            return builder.make()
                    .load(componentClass.getClassLoader(),
                            ClassLoadingStrategy.Default.INJECTION)
                    .getLoaded();
        }
    }

    /** Returns the Java primitive/reference type for a named field in the component class. */
    private static Class<?> layoutFieldType(Class<?> cls, String name) {
        if (cls.isRecord()) {
            for (RecordComponent rc : cls.getRecordComponents()) {
                if (rc.getName().equals(name)) return rc.getType();
            }
        }
        try {
            return cls.getDeclaredField(name).getType();
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException("Field '" + name + "' not found in " + cls, e);
        }
    }

    /** Builds per-field bridges using pre-computed VarHandles and MethodHandles. */
    private static FieldBridge[] buildBridges(Class<?> componentClass, Class<?> cursorClass,
                                               List<FieldLayout> fields,
                                               MethodHandles.Lookup cursorLookup,
                                               MethodHandles.Lookup compLookup) throws Exception {
        FieldBridge[] bridges = new FieldBridge[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            String fieldName  = fields.get(i).name();
            Class<?> javaType = layoutFieldType(componentClass, fieldName);

            VarHandle  cursorVh   = cursorLookup.findVarHandle(cursorClass, fieldName, javaType);
            MethodHandle compReader = findComponentReader(componentClass, fieldName, javaType, compLookup);
            if (compReader == null) return null;

            bridges[i] = new VarHandleFieldBridge(cursorVh, compReader, javaType);
        }
        return bridges;
    }

    /**
     * Builds a MethodHandle with erased type {@code (Object) -> Object} that, when called
     * with the cursor instance, reads every field from the cursor as an <em>unboxed</em>
     * primitive and invokes the component constructor directly — no {@code Object[]}
     * allocation, no boxing of primitive values.
     *
     * <p>Composition:
     * <ol>
     *   <li>{@code getter[i]} — VarHandle GET accessor: {@code (CursorClass) -> Fi}</li>
     *   <li>{@code filterArguments(ctor, 0, getters)} — chains getters as pre-filters for the
     *       constructor: {@code (CursorClass × N) -> T}</li>
     *   <li>{@code permuteArguments(…, {0,0,…})} — collapses N copies of the cursor argument
     *       down to one: {@code (CursorClass) -> T}</li>
     *   <li>{@code asType((Object) -> Object)} — erases types for uniform storage;
     *       internally emits a {@code CHECKCAST}, no boxing.</li>
     * </ol>
     */
    private static MethodHandle buildTypedReader(Class<?> cursorClass, Class<?> componentClass,
                                                  List<FieldLayout> fields,
                                                  MethodHandles.Lookup cursorLookup,
                                                  MethodHandle ctor) throws Exception {
        int n = fields.size();
        MethodHandle[] getters = new MethodHandle[n];
        for (int i = 0; i < n; i++) {
            String name = fields.get(i).name();
            Class<?> type = layoutFieldType(componentClass, name);
            VarHandle vh = cursorLookup.findVarHandle(cursorClass, name, type);
            // GET accessor: (CursorClass) -> primitiveType  — no boxing
            getters[i] = vh.toMethodHandle(VarHandle.AccessMode.GET);
        }

        // filterArguments: ctor(getter0(cursor), getter1(cursor), …)
        // result type: (CursorClass × n) -> T
        MethodHandle filtered = MethodHandles.filterArguments(ctor, 0, getters);

        // permuteArguments: collapse n CursorClass args into the single cursor arg
        int[] reorder = new int[n];   // all zeros — every source slot maps to arg 0
        MethodHandle typedReader = MethodHandles.permuteArguments(
                filtered,
                MethodType.methodType(componentClass, cursorClass),
                reorder);

        // Erase to (Object) -> Object for uniform storage and invokeExact call site
        return typedReader.asType(MethodType.methodType(Object.class, Object.class));
    }

    /** Finds either a record accessor or a plain field getter for the component. */
    private static MethodHandle findComponentReader(Class<?> cls, String name,
                                                     Class<?> type,
                                                     MethodHandles.Lookup lookup) {
        // Record accessor method: T fieldName()
        if (cls.isRecord()) {
            try {
                return lookup.findVirtual(cls, name, MethodType.methodType(type));
            } catch (Exception ignored) {}
        }
        // Plain field (may be private — setAccessible)
        try {
            var f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return lookup.unreflectGetter(f);
        } catch (Exception ignored) {}
        return null;
    }

    /** Finds the right constructor for the component type. */
    private static MethodHandle findConstructor(Class<?> cls, List<FieldLayout> fields,
                                                 MethodHandles.Lookup lookup) {
        Class<?>[] paramTypes = fields.stream()
                .map(fl -> layoutFieldType(cls, fl.name()))
                .toArray(Class<?>[]::new);
        try {
            return lookup.findConstructor(cls, MethodType.methodType(void.class, paramTypes));
        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // VarHandle-based FieldBridge (write path only)

    private static final class VarHandleFieldBridge implements FieldBridge {
        private final VarHandle    cursorVh;
        private final MethodHandle compReader;
        private final Class<?>     type;

        VarHandleFieldBridge(VarHandle cursorVh, MethodHandle compReader, Class<?> type) {
            this.cursorVh   = cursorVh;
            this.compReader = compReader;
            this.type       = type;
        }

        @Override
        public void copyFromComponent(Object component, Object cursorInst) {
            try {
                Object val = compReader.invoke(component);
                cursorVh.set(cursorInst, val);
            } catch (Throwable e) {
                throw new RuntimeException("Field copy failed", e);
            }
        }
    }
}
