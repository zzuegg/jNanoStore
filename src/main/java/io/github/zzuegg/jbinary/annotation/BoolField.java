package io.github.zzuegg.jbinary.annotation;

import java.lang.annotation.*;

/**
 * Marks a boolean record component — stored as a single bit (0 = false, 1 = true).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface BoolField {
}
