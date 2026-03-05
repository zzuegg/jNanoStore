package io.github.zzuegg.jbinary.annotation;

import java.lang.annotation.*;

/**
 * Marks a {@link String} record component or field for bit-packed storage.
 *
 * <p>Strings are stored as a fixed-width bit block:
 * <ul>
 *   <li>A length prefix using {@code ceil(log2(maxLength+1))} bits (stores 0..maxLength).</li>
 *   <li>{@code maxLength} character slots of 16 bits each (UTF-16 code units), padded
 *       with zeros for unused slots.</li>
 * </ul>
 *
 * <p>Total bits per field = {@code lengthBits + maxLength * 16}, where
 * {@code lengthBits = bitsRequired(maxLength)}.
 *
 * <p>Example:
 * <pre>{@code
 * record Player(
 *     @BitField(min = 0, max = 255) int id,
 *     @StringField(maxLength = 16) String name
 * ) {}
 * }</pre>
 *
 * <p>Strings longer than {@code maxLength} characters will be silently truncated on write.
 * Null strings are treated as empty strings.  Note that supplementary Unicode characters
 * (code points above U+FFFF) require two UTF-16 code units (a surrogate pair) and therefore
 * consume two positions of the {@code maxLength} budget.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface StringField {
    /** Maximum number of characters this field can store. Must be >= 1. */
    int maxLength();
}
