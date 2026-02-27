package io.github.zzuegg.jbinary.schema;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Holds all field layouts for one component type and its total bit width.
 */
public final class ComponentLayout {
    private final Class<?> componentClass;
    private final List<FieldLayout> fields;
    private final Map<String, FieldLayout> byName;
    private final int totalBits;

    public ComponentLayout(Class<?> componentClass, List<FieldLayout> fields) {
        this.componentClass = componentClass;
        this.fields = List.copyOf(fields);
        this.byName = fields.stream()
                .collect(Collectors.toUnmodifiableMap(FieldLayout::name, f -> f));
        this.totalBits = fields.isEmpty() ? 0
                : fields.stream().mapToInt(f -> f.bitOffset() + f.bitWidth()).max().orElse(0);
    }

    public Class<?> componentClass() { return componentClass; }
    public List<FieldLayout> fields() { return fields; }
    public int totalBits() { return totalBits; }

    public FieldLayout field(String name) {
        FieldLayout f = byName.get(name);
        if (f == null) throw new IllegalArgumentException(
                "No field '" + name + "' in component " + componentClass.getSimpleName());
        return f;
    }
}
