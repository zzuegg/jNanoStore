package io.github.zzuegg.jbinary.schema;

/**
 * Immutable description of one packed field's position within a row.
 *
 * @param name        field/component name
 * @param bitOffset   bit offset from the start of the component's block in a row
 * @param bitWidth    number of bits used
 * @param minRaw      minimum raw (stored) value — subtracted on read, added on write
 * @param scale       scale factor for decimal fields (1 for integer/bool/enum fields)
 */
public record FieldLayout(
    String name,
    int bitOffset,
    int bitWidth,
    long minRaw,
    long scale
) {
    /** Maximum raw value that fits in {@code bitWidth} bits. */
    public long maxStoredValue() {
        return bitWidth == 64 ? Long.MAX_VALUE : (1L << bitWidth) - 1L;
    }
}
