package io.github.zzuegg.jbinary.collections;

import io.github.zzuegg.jbinary.DataStore;
import io.github.zzuegg.jbinary.RowView;

import java.util.AbstractList;
import java.util.RandomAccess;

/**
 * A {@link java.util.List} implementation backed by a bit-packed {@link DataStore}.
 *
 * <p>All elements are stored in a dense, pre-allocated bit-packed store, so the memory
 * footprint is much smaller than a regular {@link java.util.ArrayList}.  Reads and writes
 * use the bit-packing logic from the underlying store.
 *
 * <p>The list has a fixed <em>capacity</em> (maximum elements) chosen at construction
 * time.  Attempting to add more than {@code capacity} elements throws
 * {@link IllegalStateException}.
 *
 * <pre>{@code
 * record Point(@BitField(min = 0, max = 1023) int x,
 *              @BitField(min = 0, max = 1023) int y) {}
 *
 * PackedList<Point> list = PackedList.create(100, Point.class);
 * list.add(new Point(10, 20));
 * list.add(new Point(30, 40));
 * Point p = list.get(0);  // → Point[x=10, y=20]
 * list.set(0, new Point(5, 6));
 * }</pre>
 *
 * <p><strong>Thread safety:</strong> Not thread-safe.
 *
 * @param <T> the record element type
 */
public final class PackedList<T extends Record> extends AbstractList<T> implements RandomAccess {

    private final DataStore<T> store;
    private final RowView<T>   view;
    private final int          capacity;
    private int                size;

    // -----------------------------------------------------------------------

    /**
     * Creates a new {@code PackedList} backed by a packed (bit-compressed) DataStore.
     *
     * @param capacity    maximum number of elements
     * @param recordClass the annotated record class for elements
     * @param <T>         the record type
     * @return a new empty {@code PackedList}
     */
    @SuppressWarnings("unchecked")
    public static <T extends Record> PackedList<T> create(int capacity, Class<T> recordClass) {
        DataStore<T> store = DataStore.of(capacity, recordClass);
        RowView<T>   view  = RowView.of(store, recordClass);
        return new PackedList<>(store, view, capacity);
    }

    private PackedList(DataStore<T> store, RowView<T> view, int capacity) {
        this.store    = store;
        this.view     = view;
        this.capacity = capacity;
        this.size     = 0;
    }

    // -----------------------------------------------------------------------
    // List contract

    @Override
    public T get(int index) {
        checkIndex(index);
        return view.get(store, index);
    }

    @Override
    public T set(int index, T element) {
        checkIndex(index);
        T old = view.get(store, index);
        view.set(store, index, element);
        return old;
    }

    @Override
    public void add(int index, T element) {
        if (size >= capacity) throw new IllegalStateException(
                "PackedList is full (capacity=" + capacity + ")");
        checkIndexForAdd(index);
        // Shift elements right from index to size-1
        for (int i = size; i > index; i--) {
            view.set(store, i, view.get(store, i - 1));
        }
        view.set(store, index, element);
        size++;
        modCount++;
    }

    @Override
    public T remove(int index) {
        checkIndex(index);
        T old = view.get(store, index);
        // Shift elements left from index+1 to size-1
        for (int i = index; i < size - 1; i++) {
            view.set(store, i, view.get(store, i + 1));
        }
        size--;
        modCount++;
        return old;
    }

    @Override
    public int size() { return size; }

    /** Returns the maximum number of elements this list can hold. */
    public int capacity() { return capacity; }

    /** Returns the underlying bit-packed {@link DataStore}. */
    public DataStore<T> store() { return store; }

    // -----------------------------------------------------------------------

    private void checkIndex(int index) {
        if (index < 0 || index >= size)
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
    }

    private void checkIndexForAdd(int index) {
        if (index < 0 || index > size)
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
    }
}
