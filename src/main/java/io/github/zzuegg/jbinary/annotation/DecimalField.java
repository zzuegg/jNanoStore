package io.github.zzuegg.jbinary.annotation;

import java.lang.annotation.*;

/**
 * Marks a double/float record component for fixed-point packed storage.
 * The value is stored as {@code round((value - min) * 10^precision)} in the minimum
 * number of bits required for the range.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface DecimalField {
    double min();
    double max();
    int precision() default 2;
}
