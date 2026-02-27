package io.github.zzuegg.jbinary.annotation;

import java.lang.annotation.*;

/**
 * Assigns an explicit non-negative storage code to an enum constant when
 * {@link EnumField#useExplicitCodes()} is {@code true}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface EnumCode {
    int value();
}
