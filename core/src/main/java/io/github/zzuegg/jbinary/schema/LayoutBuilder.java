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
                collectEntryForField(list, rc.getName(), rc.getType(),
                        rc.getAnnotation(BitField.class),
                        rc.getAnnotation(DecimalField.class),
                        rc.getAnnotation(BoolField.class),
                        rc.getAnnotation(EnumField.class),
                        rc.getAnnotation(StringField.class));
            }
        } else {
            // Support plain classes / interfaces via declared fields
            for (Field f : cls.getDeclaredFields()) {
                if (f.isSynthetic()) continue;
                collectEntryForField(list, f.getName(), f.getType(),
                        f.getAnnotation(BitField.class),
                        f.getAnnotation(DecimalField.class),
                        f.getAnnotation(BoolField.class),
                        f.getAnnotation(EnumField.class),
                        f.getAnnotation(StringField.class));
            }
        }
        return list;
    }

    /**
     * Adds a field entry (or multiple flattened entries for composed objects) to {@code list}.
     *
     * <p>If {@code type} is non-primitive, non-enum and has none of the primitive bit-packing
     * annotations ({@link BitField}, {@link BoolField}, {@link EnumField}, {@link StringField}),
     * it is treated as a <em>composed object</em> (record or plain class) whose sub-fields are
     * expanded in-place with dotted names (e.g., {@code "position.x"}).  An optional
     * {@link DecimalField} on the composed field acts as a default for any sub-fields that do
     * not carry their own annotation; sub-field annotations always take priority.
     */
    private static void collectEntryForField(List<FieldEntry> list,
                                              String name, Class<?> type,
                                              BitField bitField, DecimalField decimalField,
                                              BoolField boolField, EnumField enumField,
                                              StringField stringField) {
        if (stringField != null) {
            // String field — store directly, do not expand
            list.add(new FieldEntry(name, type, bitField, decimalField, boolField, enumField, stringField));
        } else if (bitField == null && boolField == null && enumField == null
                && !type.isPrimitive() && !type.isEnum() && !type.isArray()
                && type != String.class) {
            // Composed type (record or plain class) — expand sub-fields recursively
            expandComposedType(list, name, type, decimalField);
        } else {
            list.add(new FieldEntry(name, type, bitField, decimalField, boolField, enumField, null));
        }
    }

    /**
     * Recursively expands all components/fields of a composed type (record or plain class),
     * prefixing each field name with {@code prefix + "."}.
     *
     * <p>For records, record components are iterated in declaration order.
     * For plain classes, non-static, non-synthetic declared fields are iterated.
     *
     * @param parentDecimalDefault  optional {@link DecimalField} from the parent field;
     *                              used as a default for sub-fields that carry no annotation
     *                              of their own
     */
    private static void expandComposedType(List<FieldEntry> list,
                                             String prefix, Class<?> subType,
                                             DecimalField parentDecimalDefault) {
        if (subType.isRecord()) {
            for (RecordComponent subRc : subType.getRecordComponents()) {
                String subName = prefix + "." + subRc.getName();
                Class<?> subFieldType = subRc.getType();

                BitField    bitField     = subRc.getAnnotation(BitField.class);
                DecimalField decimalField = subRc.getAnnotation(DecimalField.class);
                BoolField   boolField    = subRc.getAnnotation(BoolField.class);
                EnumField   enumField    = subRc.getAnnotation(EnumField.class);
                StringField stringField  = subRc.getAnnotation(StringField.class);

                // If the sub-field has no annotation of its own, inherit the parent default.
                if (decimalField == null && bitField == null && boolField == null
                        && enumField == null && stringField == null) {
                    decimalField = parentDecimalDefault;
                }

                collectEntryForField(list, subName, subFieldType, bitField, decimalField,
                        boolField, enumField, stringField);
            }
        } else {
            // Plain class — iterate declared fields (skip static and synthetic)
            for (Field subField : subType.getDeclaredFields()) {
                if (subField.isSynthetic() || java.lang.reflect.Modifier.isStatic(subField.getModifiers())) continue;
                String subName = prefix + "." + subField.getName();
                Class<?> subFieldType = subField.getType();

                BitField    bitField     = subField.getAnnotation(BitField.class);
                DecimalField decimalField = subField.getAnnotation(DecimalField.class);
                BoolField   boolField    = subField.getAnnotation(BoolField.class);
                EnumField   enumField    = subField.getAnnotation(EnumField.class);
                StringField stringField  = subField.getAnnotation(StringField.class);

                // If the sub-field has no annotation of its own, inherit the parent default.
                if (decimalField == null && bitField == null && boolField == null
                        && enumField == null && stringField == null) {
                    decimalField = parentDecimalDefault;
                }

                collectEntryForField(list, subName, subFieldType, bitField, decimalField,
                        boolField, enumField, stringField);
            }
        }
    }

    private record FieldEntry(String name, Class<?> type,
                               BitField bitField, DecimalField decimalField,
                               BoolField boolField, EnumField enumField,
                               StringField stringField) {}

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
        if (e.stringField() != null) {
            return buildStringFieldLayout(e, bitCursor);
        }
        // No annotation present: infer full-precision native layout from the field's Java type.
        return buildNativeFieldLayout(e, bitCursor);
    }

    /**
     * Infers a "full precision" {@link FieldLayout} from the raw Java type when a field
     * carries no bit-packing annotation.
     *
     * <ul>
     *   <li>{@code boolean} → 1 bit</li>
     *   <li>{@code byte}    → 8 bits, signed, min = {@link Byte#MIN_VALUE}</li>
     *   <li>{@code short}   → 16 bits, signed, min = {@link Short#MIN_VALUE}</li>
     *   <li>{@code char}    → 16 bits, unsigned, min = 0</li>
     *   <li>{@code int}     → 32 bits, signed, min = {@link Integer#MIN_VALUE}</li>
     *   <li>{@code long}    → 64 bits (full range, stored as raw bits, min = 0)</li>
     *   <li>{@code float}   → 32 bits, raw IEEE 754 bits (scale = 0 sentinel)</li>
     *   <li>{@code double}  → 64 bits, raw IEEE 754 bits (scale = 0 sentinel)</li>
     *   <li>enum (no annotation) → ordinal range</li>
     * </ul>
     *
     * <p>A {@code scale} of {@code 0} in the resulting {@link FieldLayout} serves as a
     * sentinel meaning "raw IEEE 754 bit pattern" for {@code float} and {@code double}
     * fields.  {@link io.github.zzuegg.jbinary.RowView} and
     * {@link io.github.zzuegg.jbinary.accessor.FloatBitsAccessor} /
     * {@link io.github.zzuegg.jbinary.accessor.DoubleBitsAccessor} honour this convention.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static FieldLayout buildNativeFieldLayout(FieldEntry e, int bitCursor) {
        Class<?> t = e.type();
        if (t == boolean.class)  return new FieldLayout(e.name(), bitCursor, 1,  0L, 1L);  // scale=1 matches @BoolField convention; BoolAccessor ignores scale
        if (t == byte.class)     return new FieldLayout(e.name(), bitCursor, 8,  Byte.MIN_VALUE,  1L);
        if (t == short.class)    return new FieldLayout(e.name(), bitCursor, 16, Short.MIN_VALUE, 1L);
        if (t == char.class)     return new FieldLayout(e.name(), bitCursor, 16, 0L, 1L);
        if (t == int.class)      return new FieldLayout(e.name(), bitCursor, 32, Integer.MIN_VALUE, 1L);
        if (t == long.class)     return new FieldLayout(e.name(), bitCursor, 64, 0L, 1L);
        // float / double: scale = 0 is the "raw IEEE 754 bits" sentinel
        if (t == float.class)    return new FieldLayout(e.name(), bitCursor, 32, 0L, 0L);
        if (t == double.class)   return new FieldLayout(e.name(), bitCursor, 64, 0L, 0L);
        if (t.isEnum()) {
            Object[] constants = t.getEnumConstants();
            int maxCode = constants.length - 1;
            int bits = maxCode <= 0 ? 1 : bitsRequired(maxCode);
            return new FieldLayout(e.name(), bitCursor, bits, 0L, 1L);
        }
        throw new IllegalArgumentException(
                "Field '" + e.name() + "' has no jBinary annotation and its type (" +
                t.getName() + ") cannot be inferred automatically. " +
                "Annotate the field or use a supported primitive type.");
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

    /**
     * Builds the layout for a {@link io.github.zzuegg.jbinary.annotation.StringField}-annotated
     * field.
     *
     * <p>Layout (contiguous bit region):
     * <ul>
     *   <li>{@code lengthBits} bits — actual string length (0..maxLength)</li>
     *   <li>{@code maxLength × 16} bits — UTF-16 character slots</li>
     * </ul>
     *
     * <p>The {@link FieldLayout} repurposes {@code minRaw} to hold {@code maxLength} and
     * {@code scale} to hold {@code lengthBits}; both values are needed by
     * {@link io.github.zzuegg.jbinary.accessor.StringAccessor}.
     */
    private static FieldLayout buildStringFieldLayout(FieldEntry e, int bitCursor) {
        StringField ann = e.stringField();
        int maxLength = ann.maxLength();
        if (maxLength < 1) throw new IllegalArgumentException(
                "StringField maxLength must be >= 1 for field '" + e.name() + "'");
        int lengthBits = bitsRequired(maxLength);
        int totalBits  = lengthBits + maxLength * 16;
        // Repurpose minRaw = maxLength, scale = lengthBits
        return new FieldLayout(e.name(), bitCursor, totalBits, maxLength, lengthBits);
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
