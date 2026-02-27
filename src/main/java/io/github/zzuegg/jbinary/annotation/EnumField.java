package io.github.zzuegg.jbinary.annotation;

import java.lang.annotation.*;

/**
 * Marks an enum record component for bit-packed storage.
 * By default ordinals are used; set {@code useExplicitCodes=true} to read
 * {@link EnumCode} annotations on enum constants instead (codes must be unique and ≥ 0).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface EnumField {
    boolean useExplicitCodes() default false;
}
