package io.github.zzuegg.jbinary.annotation;

import java.lang.annotation.*;

/**
 * Marks an int/long record component for bit-packed storage.
 * The number of bits required is computed from [min, max].
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface BitField {
    long min() default Long.MIN_VALUE;
    long max() default Long.MAX_VALUE;
}
