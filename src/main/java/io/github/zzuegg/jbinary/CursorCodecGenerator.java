package io.github.zzuegg.jbinary;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.InstrumentedType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.assign.TypeCasting;
import net.bytebuddy.implementation.bytecode.member.FieldAccess;
import net.bytebuddy.implementation.bytecode.member.MethodInvocation;
import net.bytebuddy.implementation.bytecode.member.MethodReturn;
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates optimized {@link CursorCodec} implementations using ByteBuddy bytecode generation.
 *
 * <p>Instead of using {@link java.lang.invoke.VarHandle} to access cursor fields, the generated
 * class uses direct {@code PUTFIELD}/{@code GETFIELD} JVM instructions. All load/flush logic for
 * a given cursor type is compiled into a single class, giving the JIT a better optimization
 * opportunity compared to an array of heterogeneous call sites.
 *
 * <p>Falls back to a VarHandle-based implementation if bytecode generation fails (e.g., in
 * environments with restricted class loading).
 */
final class CursorCodecGenerator {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private CursorCodecGenerator() {}

    /**
     * Per-field spec: cursor class field, VarHandle (for VarHandle fallback), and the
     * pre-built data-store accessor (IntAccessor, DoubleAccessor, …).
     */
    record FieldSpec(java.lang.reflect.Field cursorField,
                     java.lang.invoke.VarHandle vh,
                     Object accessor) {}

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    /**
     * Builds a {@link CursorCodec} for the given cursor class.
     *
     * <p>Attempts to generate a ByteBuddy class with direct field access.
     * Falls back to the VarHandle-based implementation if generation fails.
     */
    static <T> CursorCodec build(Class<T> cursorClass,
                                  List<FieldSpec> specs,
                                  MethodHandles.Lookup lookup) {
        try {
            return generateWithByteBuddy(cursorClass, specs, lookup);
        } catch (Throwable t) {
            return buildVarHandleFallback(specs);
        }
    }

    // -----------------------------------------------------------------------
    // ByteBuddy fast path
    // -----------------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> CursorCodec generateWithByteBuddy(Class<T> cursorClass,
                                                          List<FieldSpec> specs,
                                                          MethodHandles.Lookup lookup)
            throws Exception {

        int n = specs.size();
        Class<?>[] accTypes = specs.stream()
                .map(s -> s.accessor().getClass())
                .toArray(Class<?>[]::new);

        // The generated class must be in the same package as the cursor class so that
        // MethodHandles.Lookup.defineClass() accepts it.
        String pkg = cursorClass.getPackageName();
        String base = cursorClass.getSimpleName().replace('$', '_');
        String generatedName = (pkg.isEmpty() ? "" : pkg + ".")
                + "CursorCodec$$" + base + "$$" + COUNTER.incrementAndGet();

        String[] accFieldNames = new String[n];
        for (int i = 0; i < n; i++) accFieldNames[i] = "acc" + i;

        // ByteBuddy's fluent API changes return types at each step; use raw DynamicType.Builder
        // to avoid complex generic type-inference issues.
        DynamicType.Builder builder = new ByteBuddy()
                .subclass(Object.class)
                .name(generatedName)
                .implement(CursorCodec.class);

        // Declare a public field per accessor so we can inject instances after construction.
        for (int i = 0; i < n; i++) {
            builder = builder.defineField(accFieldNames[i], accTypes[i], Opcodes.ACC_PUBLIC);
        }

        builder = builder
                .method(ElementMatchers.named("load"))
                .intercept(new DirectFieldAccess(cursorClass, specs, accFieldNames, accTypes, true))
                .method(ElementMatchers.named("flush"))
                .intercept(new DirectFieldAccess(cursorClass, specs, accFieldNames, accTypes, false));

        Class<?> generated = builder.make()
                .load(cursorClass.getClassLoader(), ClassLoadingStrategy.UsingLookup.of(lookup))
                .getLoaded();

        // Instantiate and inject the accessor objects.
        Object instance = generated.getDeclaredConstructor().newInstance();
        for (int i = 0; i < n; i++) {
            generated.getField(accFieldNames[i]).set(instance, specs.get(i).accessor());
        }
        return (CursorCodec) instance;
    }

    // -----------------------------------------------------------------------
    // VarHandle fallback
    // -----------------------------------------------------------------------

    static CursorCodec buildVarHandleFallback(List<FieldSpec> specs) {
        List<FieldSpec> copy = List.copyOf(specs);
        return new CursorCodec() {
            @Override
            public void load(Object inst, DataStore<?> store, int row) {
                for (FieldSpec s : copy) applyLoad(s, inst, store, row);
            }

            @Override
            public void flush(Object inst, DataStore<?> store, int row) {
                for (FieldSpec s : copy) applyFlush(s, inst, store, row);
            }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applyLoad(FieldSpec spec, Object inst, DataStore<?> store, int row) {
        Object acc = spec.accessor();
        if (acc instanceof io.github.zzuegg.jbinary.accessor.IntAccessor a) {
            spec.vh().set(inst, a.get(store, row));
        } else if (acc instanceof io.github.zzuegg.jbinary.accessor.LongAccessor a) {
            spec.vh().set(inst, a.get(store, row));
        } else if (acc instanceof io.github.zzuegg.jbinary.accessor.DoubleAccessor a) {
            spec.vh().set(inst, a.get(store, row));
        } else if (acc instanceof io.github.zzuegg.jbinary.accessor.BoolAccessor a) {
            spec.vh().set(inst, a.get(store, row));
        } else if (acc instanceof io.github.zzuegg.jbinary.accessor.EnumAccessor<?> a) {
            spec.vh().set(inst, a.get(store, row));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applyFlush(FieldSpec spec, Object inst, DataStore<?> store, int row) {
        Object acc = spec.accessor();
        if (acc instanceof io.github.zzuegg.jbinary.accessor.IntAccessor a) {
            a.set(store, row, (int) spec.vh().get(inst));
        } else if (acc instanceof io.github.zzuegg.jbinary.accessor.LongAccessor a) {
            a.set(store, row, (long) spec.vh().get(inst));
        } else if (acc instanceof io.github.zzuegg.jbinary.accessor.DoubleAccessor a) {
            a.set(store, row, (double) spec.vh().get(inst));
        } else if (acc instanceof io.github.zzuegg.jbinary.accessor.BoolAccessor a) {
            a.set(store, row, (boolean) spec.vh().get(inst));
        } else if (acc instanceof io.github.zzuegg.jbinary.accessor.EnumAccessor a) {
            a.set(store, row, (Enum) spec.vh().get(inst));
        }
    }

    // -----------------------------------------------------------------------
    // ByteBuddy Implementation: generates load() / flush() method bodies
    // -----------------------------------------------------------------------

    /**
     * ByteBuddy {@link Implementation} that emits direct {@code PUTFIELD}/{@code GETFIELD}
     * instructions for every cursor field.
     *
     * <p>For load():
     * <pre>
     *   aload_1                 // inst
     *   checkcast CursorClass
     *   aload_0                 // this
     *   getfield acc_i          // accessor field
     *   aload_2                 // store
     *   iload_3                 // row
     *   invokevirtual get(…)    // call accessor.get(store, row)
     *   [checkcast EnumType]    // only for enum fields
     *   putfield cursor.field   // store result directly into cursor field
     * </pre>
     *
     * <p>For flush():
     * <pre>
     *   aload_0                 // this
     *   getfield acc_i          // accessor field
     *   aload_2                 // store
     *   iload_3                 // row
     *   aload_1                 // inst
     *   checkcast CursorClass
     *   getfield cursor.field   // read cursor field directly
     *   invokevirtual set(…)    // call accessor.set(store, row, value)
     * </pre>
     */
    private static final class DirectFieldAccess implements Implementation {

        private final Class<?> cursorClass;
        private final List<FieldSpec> specs;
        private final String[] accFieldNames;
        private final Class<?>[] accTypes;
        private final boolean isLoad;

        DirectFieldAccess(Class<?> cursorClass, List<FieldSpec> specs,
                          String[] accFieldNames, Class<?>[] accTypes, boolean isLoad) {
            this.cursorClass   = cursorClass;
            this.specs         = specs;
            this.accFieldNames = accFieldNames;
            this.accTypes      = accTypes;
            this.isLoad        = isLoad;
        }

        @Override
        public InstrumentedType prepare(InstrumentedType instrumentedType) {
            return instrumentedType;
        }

        @Override
        public ByteCodeAppender appender(Target target) {
            TypeDescription instrumentedType = target.getInstrumentedType();
            TypeDescription cursorTypeDesc   = TypeDescription.ForLoadedType.of(cursorClass);

            return (methodVisitor, ctx, method) -> {
                List<StackManipulation> ops = new ArrayList<>();

                for (int i = 0; i < specs.size(); i++) {
                    FieldSpec spec     = specs.get(i);
                    Class<?> accType   = accTypes[i];
                    Class<?> fieldType = spec.cursorField().getType();

                    // Latent descriptor for the accessor field in the generated class.
                    FieldDescription.Latent accFieldDesc = new FieldDescription.Latent(
                            instrumentedType,
                            accFieldNames[i],
                            Opcodes.ACC_PUBLIC,
                            TypeDescription.Generic.OfNonGenericType.ForLoadedType.of(accType),
                            new ArrayList<>());

                    // Descriptor for the cursor's field.
                    FieldDescription cursorFieldDesc = cursorTypeDesc
                            .getDeclaredFields()
                            .filter(ElementMatchers.named(spec.cursorField().getName()))
                            .getOnly();

                    // MethodDescription for accessor.get(DataStore, int)
                    MethodDescription accGetDesc = TypeDescription.ForLoadedType.of(accType)
                            .getDeclaredMethods()
                            .filter(ElementMatchers.named("get"))
                            .getOnly();

                    // MethodDescription for accessor.set(DataStore, int, T)
                    MethodDescription accSetDesc = TypeDescription.ForLoadedType.of(accType)
                            .getDeclaredMethods()
                            .filter(ElementMatchers.named("set"))
                            .getOnly();

                    if (isLoad) {
                        // ((CursorClass) inst).field = acc.get(store, row);
                        ops.add(MethodVariableAccess.REFERENCE.loadFrom(1));       // inst
                        ops.add(TypeCasting.to(cursorTypeDesc));                   // checkcast
                        ops.add(MethodVariableAccess.REFERENCE.loadFrom(0));       // this
                        ops.add(FieldAccess.forField(accFieldDesc).read());        // getfield acc
                        ops.add(MethodVariableAccess.REFERENCE.loadFrom(2));       // store
                        ops.add(MethodVariableAccess.INTEGER.loadFrom(3));         // row
                        ops.add(MethodInvocation.invoke(accGetDesc));              // get(store,row)
                        // EnumAccessor.get() return type is erased to Enum; cast to concrete type.
                        if (fieldType.isEnum()) {
                            ops.add(TypeCasting.to(TypeDescription.ForLoadedType.of(fieldType)));
                        }
                        ops.add(FieldAccess.forField(cursorFieldDesc).write());    // putfield
                    } else {
                        // acc.set(store, row, ((CursorClass) inst).field);
                        ops.add(MethodVariableAccess.REFERENCE.loadFrom(0));       // this
                        ops.add(FieldAccess.forField(accFieldDesc).read());        // getfield acc
                        ops.add(MethodVariableAccess.REFERENCE.loadFrom(2));       // store
                        ops.add(MethodVariableAccess.INTEGER.loadFrom(3));         // row
                        ops.add(MethodVariableAccess.REFERENCE.loadFrom(1));       // inst
                        ops.add(TypeCasting.to(cursorTypeDesc));                   // checkcast
                        ops.add(FieldAccess.forField(cursorFieldDesc).read());     // getfield
                        ops.add(MethodInvocation.invoke(accSetDesc));              // set(…)
                    }
                }

                ops.add(MethodReturn.VOID);
                StackManipulation.Size size =
                        new StackManipulation.Compound(ops).apply(methodVisitor, ctx);
                return new ByteCodeAppender.Size(size.getMaximalSize(), method.getStackSize());
            };
        }
    }
}
