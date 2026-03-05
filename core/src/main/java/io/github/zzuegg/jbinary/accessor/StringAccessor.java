package io.github.zzuegg.jbinary.accessor;

import io.github.zzuegg.jbinary.DataStore;

/**
 * Allocation-free accessor for a {@link String} field stored as a bit-packed block.
 *
 * <p>On-disk format (contiguous bit region starting at {@code bitOffset}):
 * <ol>
 *   <li>{@code lengthBits} bits — the actual string length (0..maxLength).</li>
 *   <li>{@code maxLength} × 16 bits — UTF-16 character slots; unused slots are zero-filled.</li>
 * </ol>
 *
 * <p>Strings longer than {@code maxLength} <em>UTF-16 code units</em> are silently truncated
 * on {@link #set}.  {@code null} strings are treated as empty strings.  Note that supplementary
 * Unicode characters (code points above U+FFFF) require two UTF-16 code units (a surrogate pair)
 * and therefore consume two slots of the {@code maxLength} budget.
 *
 * <p>Use {@link io.github.zzuegg.jbinary.annotation.StringField} to annotate fields.
 */
public final class StringAccessor {
    private static final int CHAR_BITS = 16;

    private final int bitOffset;
    private final int maxLength;
    private final int lengthBits;

    public StringAccessor(int bitOffset, int maxLength, int lengthBits) {
        this.bitOffset  = bitOffset;
        this.maxLength  = maxLength;
        this.lengthBits = lengthBits;
    }

    public String get(DataStore<?> store, int index) {
        int len = (int) store.readBits(index, bitOffset, lengthBits);
        if (len == 0) return "";
        char[] chars = new char[len];
        int charBase = bitOffset + lengthBits;
        for (int i = 0; i < len; i++) {
            chars[i] = (char) store.readBits(index, charBase + i * CHAR_BITS, CHAR_BITS);
        }
        return new String(chars);
    }

    public void set(DataStore<?> store, int index, String value) {
        if (value == null) value = "";
        int len = Math.min(value.length(), maxLength);
        // Read old length to know exactly which char slots need clearing
        int oldLen = (int) store.readBits(index, bitOffset, lengthBits);
        store.writeBits(index, bitOffset, lengthBits, len);
        int charBase = bitOffset + lengthBits;
        for (int i = 0; i < len; i++) {
            store.writeBits(index, charBase + i * CHAR_BITS, CHAR_BITS, value.charAt(i));
        }
        // Zero-fill only slots that were previously occupied but are now beyond the new length
        for (int i = len; i < oldLen; i++) {
            store.writeBits(index, charBase + i * CHAR_BITS, CHAR_BITS, 0L);
        }
    }

    /** Returns the maximum number of characters this accessor can store. */
    public int maxLength() { return maxLength; }
}
