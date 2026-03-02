package io.github.zzuegg.jbinary.collections;

import io.github.zzuegg.jbinary.DataStore;
import io.github.zzuegg.jbinary.RowView;

import java.util.*;

/**
 * A {@link java.util.Map Map&lt;Integer,&nbsp;T&gt;} implementation backed by a sparse
 * bit-packed {@link DataStore}.
 *
 * <p>Keys are arbitrary non-negative {@code int} values in {@code [0, capacity)}.
 * Values are records of type {@code T}, stored using the bit-packing logic of the
 * underlying sparse store — unwritten entries use no memory.
 *
 * <p>Primitive-key convenience methods {@link #get(int)}, {@link #put(int, T)},
 * {@link #remove(int)}, and {@link #containsKey(int)} are provided to avoid boxing in
 * performance-sensitive code.
 *
 * <pre>{@code
 * record Vertex(@BitField(min = 0, max = 1023) int x,
 *               @BitField(min = 0, max = 1023) int y) {}
 *
 * PackedIntMap<Vertex> map = PackedIntMap.create(10_000, Vertex.class);
 * map.put(42, new Vertex(100, 200));
 * Vertex v = map.get(42);  // → Vertex[x=100, y=200]
 * map.remove(42);
 * }</pre>
 *
 * <p><strong>Thread safety:</strong> Not thread-safe.
 *
 * @param <T> the record value type
 */
public final class PackedIntMap<T extends Record> extends AbstractMap<Integer, T> {

    private final DataStore<T> store;
    private final RowView<T>   view;
    private final int          capacity;
    /** Tracks which integer keys are currently present. */
    private final Set<Integer> presentKeys = new HashSet<>();

    // -----------------------------------------------------------------------

    /**
     * Creates a new {@code PackedIntMap} backed by a sparse bit-packed DataStore.
     *
     * @param capacity    maximum key value + 1 (keys must be in {@code [0, capacity)})
     * @param recordClass the annotated record class for values
     * @param <T>         the record type
     * @return a new empty {@code PackedIntMap}
     */
    @SuppressWarnings("unchecked")
    public static <T extends Record> PackedIntMap<T> create(int capacity, Class<T> recordClass) {
        DataStore<T> store = DataStore.sparse(capacity, recordClass);
        RowView<T>   view  = RowView.of(store, recordClass);
        return new PackedIntMap<>(store, view, capacity);
    }

    private PackedIntMap(DataStore<T> store, RowView<T> view, int capacity) {
        this.store    = store;
        this.view     = view;
        this.capacity = capacity;
    }

    // -----------------------------------------------------------------------
    // Primitive-key API (no boxing)

    /**
     * Returns the value associated with the given integer key, or {@code null} if
     * no mapping exists.
     */
    public T get(int key) {
        return presentKeys.contains(key) ? view.get(store, key) : null;
    }

    /**
     * Associates the given value with the given integer key, returning the
     * previously associated value or {@code null}.
     */
    public T put(int key, T value) {
        checkKey(key);
        T old = presentKeys.contains(key) ? view.get(store, key) : null;
        view.set(store, key, value);
        presentKeys.add(key);
        return old;
    }

    /**
     * Removes the mapping for the given integer key, returning the previously
     * associated value or {@code null}.
     */
    public T remove(int key) {
        if (!presentKeys.contains(key)) return null;
        T old = view.get(store, key);
        presentKeys.remove(key);
        return old;
    }

    /** Returns {@code true} if this map contains a mapping for the given integer key. */
    public boolean containsKey(int key) {
        return presentKeys.contains(key);
    }

    // -----------------------------------------------------------------------
    // Map contract (boxed)

    @Override
    public T get(Object key) {
        if (!(key instanceof Integer k)) return null;
        return get((int) k);
    }

    @Override
    public T put(Integer key, T value) {
        return put((int) key, value);
    }

    @Override
    public T remove(Object key) {
        if (!(key instanceof Integer k)) return null;
        return remove((int) k);
    }

    @Override
    public boolean containsKey(Object key) {
        if (!(key instanceof Integer k)) return false;
        return containsKey((int) k);
    }

    @Override
    public int size() { return presentKeys.size(); }

    /** Returns the maximum key (exclusive) this map can accept. */
    public int capacity() { return capacity; }

    /** Returns the underlying bit-packed {@link DataStore}. */
    public DataStore<T> store() { return store; }

    @Override
    public Set<Entry<Integer, T>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Entry<Integer, T>> iterator() {
                Iterator<Integer> keyIter = presentKeys.iterator();
                return new Iterator<>() {
                    public boolean hasNext() { return keyIter.hasNext(); }
                    public Entry<Integer, T> next() {
                        int k = keyIter.next();
                        return Map.entry(k, view.get(store, k));
                    }
                };
            }
            @Override public int size() { return presentKeys.size(); }
        };
    }

    // -----------------------------------------------------------------------

    private void checkKey(int key) {
        if (key < 0 || key >= capacity)
            throw new IndexOutOfBoundsException(
                    "Key " + key + " out of range [0, " + capacity + ")");
    }
}
