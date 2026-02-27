package io.github.zzuegg.jbinary;

/**
 * Optional convenience marker interface for component types registered with a
 * {@link DataStore}.
 *
 * <p>{@link DataStore} is generic ({@code DataStore<T>}) with <em>no bound</em> on
 * {@code T}, so you are free to use any type — including your own marker interfaces — as
 * the type parameter.  {@code BinaryComponent} is provided only as a ready-made option
 * for projects that do not need a more specific shared base type.
 *
 * <pre>{@code
 * // Option A: use the built-in marker
 * public record Terrain(...) implements BinaryComponent {}
 * public record Water(...)   implements BinaryComponent {}
 * DataStore<BinaryComponent> store = DataStore.of(10_000, Terrain.class, Water.class);
 *
 * // Option B: define your own marker
 * public interface WorldData {}
 * public record Terrain(...) implements WorldData {}
 * public record Water(...)   implements WorldData {}
 * DataStore<WorldData> store = DataStore.of(10_000, Terrain.class, Water.class);
 *
 * // Option C: single-component, fully typed (no marker needed)
 * DataStore<Terrain> store = DataStore.of(10_000, Terrain.class);
 * }</pre>
 */
public interface BinaryComponent {}
