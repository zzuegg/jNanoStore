package io.github.zzuegg.zayes;

import com.simsilica.es.ComponentFilter;
import com.simsilica.es.Entity;
import com.simsilica.es.EntityChange;
import com.simsilica.es.EntityComponent;
import com.simsilica.es.EntityId;
import com.simsilica.es.EntitySet;

import io.github.zzuegg.jbinary.Accessors;
import io.github.zzuegg.jbinary.DataCursor;
import io.github.zzuegg.jbinary.DataStore;
import io.github.zzuegg.jbinary.accessor.BoolAccessor;
import io.github.zzuegg.jbinary.annotation.BoolField;
import io.github.zzuegg.jbinary.schema.LayoutBuilder;

import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A self-managing {@link EntitySet} backed by BitKit {@link DataStore}, designed
 * for the standalone {@link BitEntityData}.
 *
 * <p>Unlike {@link PackedEntitySet} which wraps an existing parent EntityData,
 * this class receives change notifications directly from {@link BitEntityData}
 * and queries the central component store through it — no wrapping overhead.</p>
 *
 * <p>For each tracked component type, the constructor attempts to register it
 * in a shared {@link DataStore} using {@link ComponentCursorBridge}. Types that
 * cannot be bit-packed fall back to plain {@code EntityComponent[]} heap arrays.</p>
 */
public final class BitEntitySet extends AbstractSet<Entity> implements EntitySet {

    // -----------------------------------------------------------------------
    // Membership marker (1 bit per entity slot)
    record MembershipRecord(@BoolField boolean present) {}

    // -----------------------------------------------------------------------
    // Fields

    private final BitEntityData ed;
    @SuppressWarnings("unchecked")
    private final Class<EntityComponent>[] types;
    private ComponentFilter filter;

    private final int capacity;
    private int nextIndex = 0;
    private final ArrayDeque<Integer> freeIndices = new ArrayDeque<>();

    /** Packed store holding MembershipRecord + all store-backed component types. */
    private final DataStore<?> store;
    private final BoolAccessor presentAccessor;

    /**
     * Per-type cursor bridge. {@code bridges[ti] != null} iff component type {@code ti} is
     * stored in the packed store; {@code null} means heap fallback.
     */
    @SuppressWarnings("rawtypes")
    private final ComponentCursorBridge[] bridges;

    /**
     * Heap-fallback arrays. {@code heapComponents[ti] != null} iff component type
     * {@code ti} could <em>not</em> be stored in the packed store.
     */
    private final EntityComponent[][] heapComponents;

    /** Dense index → EntityId (null if slot is free). */
    private final EntityId[] slotIds;

    /** Dense index → BitEntity (null if slot is free). */
    private final BitEntity[] entities;

    /** EntityId (raw long) → dense index. */
    private final HashMap<Long, Integer> idToIndex = new HashMap<>();

    private int size = 0;

    private Set<Entity> addedEntities   = new HashSet<>();
    private Set<Entity> changedEntities = new HashSet<>();
    private Set<Entity> removedEntities = new HashSet<>();

    private final ConcurrentLinkedQueue<EntityChange> pendingChanges = new ConcurrentLinkedQueue<>();

    private boolean filtersChanged = false;
    private boolean released = false;

    // -----------------------------------------------------------------------
    // Constructor

    @SuppressWarnings({"unchecked", "rawtypes"})
    BitEntitySet(BitEntityData ed, ComponentFilter filter,
                 Class<?>[] rawTypes, int capacity) {
        this.ed       = ed;
        this.filter   = filter;
        this.capacity = capacity;

        this.types = new Class[rawTypes.length];
        for (int i = 0; i < rawTypes.length; i++) {
            this.types[i] = (Class<EntityComponent>) rawTypes[i];
        }

        // ── Phase 1: determine which component types can be stored in the DataStore ──
        boolean[] canUseStore = new boolean[rawTypes.length];
        List<Class<?>> storeClasses = new ArrayList<>();
        storeClasses.add(MembershipRecord.class);
        for (int i = 0; i < rawTypes.length; i++) {
            try {
                LayoutBuilder.layout(rawTypes[i]);
                canUseStore[i] = true;
                storeClasses.add(rawTypes[i]);
            } catch (Exception ignored) {
                canUseStore[i] = false;
            }
        }

        // ── Phase 2: build the single shared DataStore ──
        this.store = DataStore.packed(capacity, storeClasses.toArray(new Class<?>[0]));
        this.presentAccessor = Accessors.boolFieldInStore(store, MembershipRecord.class, "present");

        // ── Phase 3: build ComponentCursorBridges for storable types; allocate heap arrays for others ──
        this.bridges        = new ComponentCursorBridge[rawTypes.length];
        this.heapComponents = new EntityComponent[rawTypes.length][];
        for (int i = 0; i < rawTypes.length; i++) {
            if (canUseStore[i]) {
                ComponentCursorBridge<?> bridge = ComponentCursorBridge.tryCreate(store, rawTypes[i]);
                if (bridge != null) {
                    bridges[i] = bridge;
                } else {
                    heapComponents[i] = new EntityComponent[capacity];
                }
            } else {
                heapComponents[i] = new EntityComponent[capacity];
            }
        }

        this.slotIds  = new EntityId[capacity];
        this.entities = new BitEntity[capacity];

        loadInitialEntities();
    }

    // -----------------------------------------------------------------------
    // Bootstrap

    private void loadInitialEntities() {
        Set<EntityId> matching = ed.findMatchingEntities(types, filter);
        for (EntityId eid : matching) {
            EntityComponent[] comps = ed.loadComponents(eid, types, filter);
            if (comps != null) {
                int index = allocateIndex();
                putEntity(index, eid, comps);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Internal entity management

    private int allocateIndex() {
        if (!freeIndices.isEmpty()) {
            return freeIndices.poll();
        }
        if (nextIndex >= capacity) {
            throw new IllegalStateException(
                    "BitEntitySet capacity exceeded: " + capacity);
        }
        return nextIndex++;
    }

    private void putEntity(int index, EntityId eid, EntityComponent[] comps) {
        BitEntity ie = new BitEntity(eid, ed, this, index);

        presentAccessor.set(store, index, true);
        for (int ti = 0; ti < types.length; ti++) {
            if (bridges[ti] != null) {
                @SuppressWarnings("unchecked")
                ComponentCursorBridge<EntityComponent> bridge = bridges[ti];
                bridge.write(store, index, comps[ti]);
            } else {
                heapComponents[ti][index] = comps[ti];
            }
        }
        slotIds[index]  = eid;
        entities[index] = ie;
        idToIndex.put(eid.getId(), index);
        size++;
    }

    private void removeEntity(int index) {
        presentAccessor.set(store, index, false);
        for (int ti = 0; ti < types.length; ti++) {
            if (heapComponents[ti] != null) {
                heapComponents[ti][index] = null;
            }
        }
        slotIds[index]  = null;
        entities[index] = null;
        freeIndices.add(index);
        size--;
    }

    // -----------------------------------------------------------------------
    // Package-private accessors for BitEntity

    @SuppressWarnings("unchecked")
    void writeComponentDirect(int index, EntityComponent c) {
        int ti = typeIndex(c.getClass());
        if (ti < 0) return;
        if (bridges[ti] != null) {
            ((ComponentCursorBridge<EntityComponent>) bridges[ti]).write(store, index, c);
        } else {
            heapComponents[ti][index] = c;
        }
    }

    @SuppressWarnings("unchecked")
    EntityComponent getComponentForIndex(int index, Class<?> type) {
        int ti = typeIndex((Class<EntityComponent>) type);
        if (ti < 0) return null;
        if (bridges[ti] != null) {
            return ((ComponentCursorBridge<EntityComponent>) bridges[ti]).read(store, index);
        }
        return heapComponents[ti][index];
    }

    boolean isEntityComplete(int index) {
        return presentAccessor.get(store, index);
    }

    EntityComponent[] getComponentsForIndex(int index) {
        EntityComponent[] result = new EntityComponent[types.length];
        for (int ti = 0; ti < types.length; ti++) {
            if (bridges[ti] != null) {
                result[ti] = ((ComponentCursorBridge<EntityComponent>) bridges[ti]).read(store, index);
            } else {
                result[ti] = heapComponents[ti][index];
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Change notification (called by BitEntityData)

    void entityChange(EntityChange change) {
        if (!released) {
            pendingChanges.add(change);
        }
    }

    // -----------------------------------------------------------------------
    // EntitySet interface

    @Override
    public boolean applyChanges() {
        addedEntities.clear();
        changedEntities.clear();
        removedEntities.clear();

        if (filtersChanged) {
            filtersChanged = false;
            applyFilterChange();
        }

        EntityChange change;
        while ((change = pendingChanges.poll()) != null) {
            processChange(change);
        }

        return !addedEntities.isEmpty() || !changedEntities.isEmpty() || !removedEntities.isEmpty();
    }

    @Override
    public boolean applyChanges(Set<EntityChange> changes) {
        addedEntities.clear();
        changedEntities.clear();
        removedEntities.clear();

        if (filtersChanged) {
            filtersChanged = false;
            applyFilterChange();
        }

        EntityChange change;
        while ((change = pendingChanges.poll()) != null) {
            processChange(change);
        }
        if (changes != null) {
            for (EntityChange c : changes) {
                processChange(c);
            }
        }

        return !addedEntities.isEmpty() || !changedEntities.isEmpty() || !removedEntities.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private void processChange(EntityChange change) {
        EntityId eid = change.getEntityId();
        Class<EntityComponent> changedType = (Class<EntityComponent>) change.getComponentType();
        int changedTi = typeIndex(changedType);

        boolean relevant = changedTi >= 0
                || (filter != null && filter.getComponentType() == changedType);
        if (!relevant) return;

        Integer idx = idToIndex.get(eid.getId());

        if (idx != null) {
            // Entity is already in the set
            EntityComponent newComp = ed.getComponent(eid, changedType);

            if (newComp == null) {
                removedEntities.add(entities[idx]);
                idToIndex.remove(eid.getId());
                removeEntity(idx);
                return;
            }

            boolean needsFilterCheck = filter != null
                    && filter.getComponentType() == changedType;
            if (needsFilterCheck && !filter.evaluate(newComp)) {
                removedEntities.add(entities[idx]);
                idToIndex.remove(eid.getId());
                removeEntity(idx);
                return;
            }

            if (changedTi >= 0) {
                if (bridges[changedTi] != null) {
                    ((ComponentCursorBridge<EntityComponent>) bridges[changedTi])
                            .write(store, idx, newComp);
                } else if (heapComponents[changedTi] != null) {
                    heapComponents[changedTi][idx] = newComp;
                }
            }
            changedEntities.add(entities[idx]);
        } else {
            // Entity is not in the set — check if it now qualifies
            EntityComponent[] comps = ed.loadComponents(eid, types, filter);
            if (comps != null) {
                int index = allocateIndex();
                putEntity(index, eid, comps);
                addedEntities.add(entities[index]);
            }
        }
    }

    private void applyFilterChange() {
        // Remove entities that no longer match the new filter
        for (int i = 0; i < nextIndex; i++) {
            if (entities[i] == null) continue;
            EntityId eid = slotIds[i];
            EntityComponent[] comps = ed.loadComponents(eid, types, filter);
            if (comps == null) {
                removedEntities.add(entities[i]);
                idToIndex.remove(eid.getId());
                removeEntity(i);
            }
        }

        // Find entities that now match but are not yet in the set
        Set<EntityId> matching = ed.findMatchingEntities(types, filter);
        for (EntityId eid : matching) {
            if (idToIndex.containsKey(eid.getId())) continue;
            EntityComponent[] comps = ed.loadComponents(eid, types, filter);
            if (comps != null) {
                int index = allocateIndex();
                putEntity(index, eid, comps);
                addedEntities.add(entities[index]);
            }
        }
    }

    @Override
    public void resetFilter(ComponentFilter newFilter) {
        this.filter = newFilter;
        this.filtersChanged = true;
    }

    @Override
    public boolean containsId(EntityId id) {
        return idToIndex.containsKey(id.getId());
    }

    @Override
    public Set<EntityId> getEntityIds() {
        Set<EntityId> result = new HashSet<>(size);
        for (int i = 0; i < nextIndex; i++) {
            if (slotIds[i] != null) {
                result.add(slotIds[i]);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public Entity getEntity(EntityId id) {
        Integer idx = idToIndex.get(id.getId());
        return (idx != null) ? entities[idx] : null;
    }

    @Override
    public Set<Entity> getAddedEntities()   { return Collections.unmodifiableSet(addedEntities); }

    @Override
    public Set<Entity> getChangedEntities() { return Collections.unmodifiableSet(changedEntities); }

    @Override
    public Set<Entity> getRemovedEntities() { return Collections.unmodifiableSet(removedEntities); }

    @Override
    public void clearChangeSets() {
        addedEntities.clear();
        changedEntities.clear();
        removedEntities.clear();
    }

    @Override
    public boolean hasChanges() {
        return !addedEntities.isEmpty() || !changedEntities.isEmpty() || !removedEntities.isEmpty()
                || !pendingChanges.isEmpty() || filtersChanged;
    }

    @Override
    public void release() {
        released = true;
        pendingChanges.clear();
    }

    boolean isReleased() { return released; }

    @Override
    @SuppressWarnings("rawtypes")
    public boolean hasType(Class type) {
        return typeIndex(type) >= 0;
    }

    // -----------------------------------------------------------------------
    // Set<Entity> interface

    @Override
    public int size() { return size; }

    @Override
    public boolean contains(Object o) {
        if (o instanceof Entity e) {
            return containsId(e.getId());
        }
        return false;
    }

    @Override
    public Iterator<Entity> iterator() {
        return new EntityIterator();
    }

    private final class EntityIterator implements Iterator<Entity> {
        private int cursor = 0;
        private BitEntity next;

        EntityIterator() { advance(); }

        private void advance() {
            next = null;
            while (cursor < nextIndex) {
                BitEntity ie = entities[cursor++];
                if (ie != null) { next = ie; break; }
            }
        }

        @Override public boolean hasNext() { return next != null; }

        @Override
        public Entity next() {
            if (next == null) throw new NoSuchElementException();
            Entity current = next;
            advance();
            return current;
        }
    }

    // -----------------------------------------------------------------------
    // Cursor factory (allocation-free access)

    /**
     * Returns a cursor for direct store access by dense row index.
     * Use {@link BitEntity#getIndex()} to obtain the row index.
     *
     * @param type the component class (must be store-backed)
     * @return a typed cursor, or {@code null} if the type is not store-backed
     */
    @SuppressWarnings("unchecked")
    public <T extends EntityComponent> PackedEntitySet.PackedCursor<T> createCursor(Class<T> type) {
        int ti = typeIndex(type);
        if (ti < 0 || bridges[ti] == null) return null;
        return new PackedEntitySet.PackedCursor<>((ComponentCursorBridge<T>) bridges[ti], store);
    }

    /**
     * Creates a {@link DataCursor} over this set's packed store for a user-defined
     * projection class annotated with {@link io.github.zzuegg.jbinary.annotation.StoreField}.
     */
    public <T> DataCursor<T> createDataCursor(Class<T> projectionClass) {
        return DataCursor.of(store, projectionClass);
    }

    /**
     * Returns the underlying packed store for use with cursors.
     */
    public DataStore<?> store() {
        return store;
    }

    /**
     * Generates a multi-component projection cursor class at runtime.
     */
    public DataCursor<?> generateProjectionCursor() {
        return ProjectionCursorGenerator.generate(store, types, bridges);
    }

    // -----------------------------------------------------------------------
    // Helpers

    @SuppressWarnings("unchecked")
    private int typeIndex(Class<?> type) {
        for (int i = 0; i < types.length; i++) {
            if (types[i] == type) return i;
        }
        return -1;
    }
}
