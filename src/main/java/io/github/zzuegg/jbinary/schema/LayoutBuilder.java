package io.github.zzuegg.jbinary.schema;

import io.github.zzuegg.jbinary.annotation.*;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds {@link ComponentLayout} instances from annotated record components / fields
 * using reflection. Results are cached.
 */
public final class LayoutBuilder {

    private static final ConcurrentHashMap<Class<?>, ComponentLayout> CACHE =
            new ConcurrentHashMap<>();

    private LayoutBuilder() {}

    /** Returns (possibly cached) {@link ComponentLayout} for {@code cls}. */
    public static ComponentLayout layout(Class<?> cls) {
        return CACHE.computeIfAbsent(cls, LayoutBuilder::build);
    }

    // -----------------------------------------------------------------------
    private static ComponentLayout build(Class<?> cls) {
        List<FieldEntry> entries = collectEntries(cls);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "Class " + cls.getName() + " has no annotated fields/record-components");
        }

        List<FieldLayout> layouts = new ArrayList<>();
        int bitCursor = 0;
        for (FieldEntry e : entries) {
            FieldLayout fl = buildFieldLayout(e, bitCursor);
            layouts.add(fl);
            bitCursor += fl.bitWidth();
        }
        return new ComponentLayout(cls, layouts);
    }

    // -----------------------------------------------------------------------
    private static List<FieldEntry> collectEntries(Class<?> cls) {
        List<FieldEntry> list = new ArrayList<>();
        if (cls.isRecord()) {
            for (RecordComponent rc : cls.getRecordComponents()) {
                list.add(new FieldEntry(rc.getName(), rc.getType(),
                        rc.getAnnotation(BitField.class),
                        rc.getAnnotation(DecimalField.class),
                        rc.getAnnotation(BoolField.class),
                        rc.getAnnotation(EnumField.class)));
            }
        } else {
            // Support plain classes / interfaces via declared fields
            for (Field f : cls.getDeclaredFields()) {
                if (f.isSynthetic()) continue;
                list.add(new FieldEntry(f.getName(), f.getType(),
                        f.getAnnotation(BitField.class),
                        f.getAnnotation(DecimalField.class),
                        f.getAnnotation(BoolField.class),
                        f.getAnnotation(EnumField.class)));
            }
        }
        return list;
    }

    private record FieldEntry(String name, Class<?> type,
                               BitField bitField, DecimalField decimalField,
                               BoolField boolField, EnumField enumField) {}

    // -----------------------------------------------------------------------
    private static FieldLayout buildFieldLayout(FieldEntry e, int bitCursor) {
        if (e.boolField() != null) {
            return new FieldLayout(e.name(), bitCursor, 1, 0L, 1L);
        }
        if (e.bitField() != null) {
            return buildBitFieldLayout(e, bitCursor);
        }
        if (e.decimalField() != null) {
            return buildDecimalFieldLayout(e, bitCursor);
        }
        if (e.enumField() != null) {
            return buildEnumFieldLayout(e, bitCursor);
        }
        throw new IllegalArgumentException(
                "Field '" + e.name() + "' has no jBinary annotation");
    }

    private static FieldLayout buildBitFieldLayout(FieldEntry e, int bitCursor) {
        BitField ann = e.bitField();
        long min = ann.min();
        long max = ann.max();
        validateRange(e.name(), min, max);
        long range = max - min;
        int bits = bitsRequired(range);
        return new FieldLayout(e.name(), bitCursor, bits, min, 1L);
    }

    private static FieldLayout buildDecimalFieldLayout(FieldEntry e, int bitCursor) {
        DecimalField ann = e.decimalField();
        int precision = ann.precision();
        if (precision < 0) throw new IllegalArgumentException(
                "DecimalField precision must be >= 0 for field '" + e.name() + "'");
        long scale = pow10(precision);
        long minRaw = Math.round(ann.min() * scale);
        long maxRaw = Math.round(ann.max() * scale);
        validateRange(e.name(), minRaw, maxRaw);
        long range = maxRaw - minRaw;
        int bits = bitsRequired(range);
        return new FieldLayout(e.name(), bitCursor, bits, minRaw, scale);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static FieldLayout buildEnumFieldLayout(FieldEntry e, int bitCursor) {
        if (!e.type().isEnum()) throw new IllegalArgumentException(
                "Field '" + e.name() + "' is @EnumField but type is not an enum");
        Class<? extends Enum<?>> enumType = (Class<? extends Enum<?>>) e.type();
        EnumField ann = e.enumField();
        Object[] constants = enumType.getEnumConstants();
        int maxCode;
        if (ann.useExplicitCodes()) {
            maxCode = validateExplicitCodes(enumType, constants, e.name());
        } else {
            maxCode = constants.length - 1;
        }
        int bits = bitsRequired(maxCode);
        return new FieldLayout(e.name(), bitCursor, bits, 0L, 1L);
    }

    @SuppressWarnings("rawtypes")
    private static int validateExplicitCodes(
            Class<? extends Enum<?>> enumType, Object[] constants, String fieldName) {
        Set<Integer> seen = new HashSet<>();
        int max = 0;
        for (Object c : constants) {
            Field f;
            try {
                f = enumType.getField(((Enum) c).name());
            } catch (NoSuchFieldException ex) {
                throw new IllegalStateException(ex);
            }
            EnumCode ec = f.getAnnotation(EnumCode.class);
            if (ec == null) throw new IllegalArgumentException(
                    "Enum constant " + ((Enum) c).name() + " in " + enumType.getSimpleName()
                    + " lacks @EnumCode (required because useExplicitCodes=true)");
            int code = ec.value();
            if (code < 0) throw new IllegalArgumentException(
                    "@EnumCode value must be >= 0 for " + ((Enum) c).name());
            if (!seen.add(code)) throw new IllegalArgumentException(
                    "Duplicate @EnumCode " + code + " in " + enumType.getSimpleName());
            if (code > max) max = code;
        }
        return max;
    }

    // -----------------------------------------------------------------------
    public static int bitsRequired(long range) {
        if (range <= 0) return 1;
        return 64 - Long.numberOfLeadingZeros(range);
    }

    private static long pow10(int n) {
        long result = 1;
        for (int i = 0; i < n; i++) result *= 10;
        return result;
    }

    private static void validateRange(String name, long min, long max) {
        if (min > max) throw new IllegalArgumentException(
                "Field '" + name + "': min (" + min + ") > max (" + max + ")");
    }
}
