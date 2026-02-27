package io.github.zzuegg.jbinary.accessor;

import io.github.zzuegg.jbinary.DataStore;
import io.github.zzuegg.jbinary.annotation.EnumCode;
import io.github.zzuegg.jbinary.annotation.EnumField;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Allocation-free accessor for an enum field.
 * Supports both ordinal-based and explicit-code-based storage.
 */
public final class EnumAccessor<E extends Enum<E>> {
    private final int bitOffset;
    private final int bitWidth;
    private final E[] constants;         // enum constants in storage-code order
    private final int[] codeForOrdinal;  // codeForOrdinal[ordinal] → stored code

    @SuppressWarnings("unchecked")
    public static <E extends Enum<E>> EnumAccessor<E> forField(
            int bitOffset, int bitWidth,
            Class<E> enumType, boolean useExplicitCodes) {
        E[] vals = enumType.getEnumConstants();
        int maxCode;
        int[] codeForOrdinal = new int[vals.length];
        E[] constants;

        if (useExplicitCodes) {
            // Build a code→constant array
            int max = 0;
            int[] codes = new int[vals.length];
            for (int i = 0; i < vals.length; i++) {
                Field f;
                try { f = enumType.getField(vals[i].name()); }
                catch (NoSuchFieldException ex) { throw new IllegalStateException(ex); }
                codes[i] = f.getAnnotation(EnumCode.class).value();
                if (codes[i] > max) max = codes[i];
            }
            maxCode = max;
            constants = (E[]) new Enum<?>[max + 1];
            for (int i = 0; i < vals.length; i++) {
                constants[codes[i]] = vals[i];
                codeForOrdinal[vals[i].ordinal()] = codes[i];
            }
        } else {
            constants = vals;
            maxCode = vals.length - 1;
            for (int i = 0; i < vals.length; i++) codeForOrdinal[i] = i;
        }
        return new EnumAccessor<>(bitOffset, bitWidth, constants, codeForOrdinal);
    }

    private EnumAccessor(int bitOffset, int bitWidth, E[] constants, int[] codeForOrdinal) {
        this.bitOffset      = bitOffset;
        this.bitWidth       = bitWidth;
        this.constants      = constants;
        this.codeForOrdinal = codeForOrdinal;
    }

    public E get(DataStore store, int index) {
        int code = (int) store.readBits(index, bitOffset, bitWidth);
        return constants[code];
    }

    public void set(DataStore store, int index, E value) {
        store.writeBits(index, bitOffset, bitWidth, codeForOrdinal[value.ordinal()]);
    }
}
