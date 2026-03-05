package io.github.zzuegg.jbinary.annotation;

import java.lang.annotation.*;

/**
 * Maps a field in a plain Java class to a field in a jBinary {@link io.github.zzuegg.jbinary.DataStore}
 * component, enabling the {@link io.github.zzuegg.jbinary.DataCursor} pattern.
 *
 * <p>Example — a "cursor" class that spans two component types:
 * <pre>{@code
 * class NeededData {
 *     @StoreField(component = Terrain.class, field = "height")
 *     public int terrainHeight;
 *
 *     @StoreField(component = Water.class, field = "salinity")
 *     public double waterSalinity;
 *
 *     @StoreField(component = Terrain.class, field = "active")
 *     public boolean active;
 * }
 * }</pre>
 *
 * <p>The annotated field must be a primitive type ({@code int}, {@code long},
 * {@code double}, {@code boolean}) or an {@code Enum} type matching the component field.
 * It must not be {@code final} or {@code static}.
 *
 * @see io.github.zzuegg.jbinary.DataCursor
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface StoreField {

    /**
     * The annotated record/component class that holds the target field.
     * The component class must be registered in the {@link io.github.zzuegg.jbinary.DataStore}.
     */
    Class<?> component();

    /**
     * The name of the field within the component class to map to.
     */
    String field();
}
