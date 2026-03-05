package io.github.zzuegg.zayes;

import com.simsilica.es.ComponentFilter;
import com.simsilica.es.Entity;
import com.simsilica.es.EntityChange;
import com.simsilica.es.EntityComponent;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import com.simsilica.es.EntitySet;

import io.github.zzuegg.jbinary.Accessors;
import io.github.zzuegg.jbinary.DataStore;
import io.github.zzuegg.jbinary.RowView;
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
 * A self-managing {@link EntitySet} that stores entity membership and component data
 * using a BitKit {@link DataStore} keyed by a dense {@link IndexedEntityId#getIndex()}.
 *
 * <h3>Storage strategy (decided once at construction time)</h3>
 * <p>For each tracked component type, the constructor attempts to register it in a
 * shared {@link DataStore} (using {@link LayoutBuilder} + {@link RowView}).  Types
 * whose layout can be inferred — records or plain classes with primitive / unannotated
 * fields — are stored bit-packed in the store.  Types that cannot be represented
 * (e.g. zero-field markers, classes with {@link String} fields without
 * {@link io.github.zzuegg.jbinary.annotation.StringField}, or plain classes lacking
 * a no-arg constructor) fall back to a plain {@code EntityComponent[]} heap array.
 * This decision is made <em>once</em> during construction and never re-evaluated at
 * runtime.
 *
 * <h3>Change tracking</h3>
 * <p>Change notifications arrive via {@link #entityChange(EntityChange)}, pushed by the
 * owning {@link PackedEntityData}, and are applied — populating the added/changed/removed
 * change-delta sets — only when {@link #applyChanges()} is invoked.
 */
public final class PackedEntitySet extends AbstractSet<Entity> implements EntitySet {

    // -----------------------------------------------------------------------
    // Membership marker (1 bit per entity slot in the shared store)
    record MembershipRecord(@BoolField boolean present) {}

    // -----------------------------------------------------------------------
    // Fields

    private final EntityData parent;
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
     * Per-type RowView. {@code rowViews[ti] != null} iff component type {@code ti} is
     * stored in the packed store; {@code null} means heap fallback.
     */
    @SuppressWarnings("rawtypes")
    private final RowView[] rowViews;

    /**
     * Heap-fallback arrays. {@code heapComponents[ti] != null} iff component type
     * {@code ti} could <em>not</em> be stored in the packed store.
     */
    private final EntityComponent[][] heapComponents;

    /** Dense index → IndexedEntityId (null if slot is free). */
    private final IndexedEntityId[] indexedIds;

    /** Dense index → IndexedEntity (null if slot is free). */
    private final IndexedEntity[] entities;

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
    PackedEntitySet(EntityData parent, ComponentFilter filter,
                    Class<?>[] rawTypes, int capacity) {
        this.parent   = parent;
        this.filter   = filter;
        this.capacity = capacity;

        this.types = new Class[rawTypes.length];
        for (int i = 0; i < rawTypes.length; i++) {
            this.types[i] = (Class<EntityComponent>) rawTypes[i];
        }

        // ── Phase 1: determine which component types can be stored in the DataStore ──
        // Check once at construction; results drive all subsequent operations.
        boolean[] canUseStore = new boolean[rawTypes.length];
        List<Class<?>> storeClasses = new ArrayList<>();
        storeClasses.add(MembershipRecord.class);
        for (int i = 0; i < rawTypes.length; i++) {
            try {
                LayoutBuilder.layout(rawTypes[i]);     // throws if layout cannot be inferred
                canUseStore[i] = true;
                storeClasses.add(rawTypes[i]);
            } catch (Exception ignored) {
                canUseStore[i] = false;
            }
        }

        // ── Phase 2: build the single shared DataStore ──
        this.store = DataStore.packed(capacity, storeClasses.toArray(new Class<?>[0]));
        this.presentAccessor = Accessors.boolFieldInStore(store, MembershipRecord.class, "present");

        // ── Phase 3: build RowViews for storable types; allocate heap arrays for others ──
        this.rowViews       = new RowView[rawTypes.length];
        this.heapComponents = new EntityComponent[rawTypes.length][];
        for (int i = 0; i < rawTypes.length; i++) {
            if (canUseStore[i]) {
                try {
                    rowViews[i] = RowView.of(store, rawTypes[i]);
                } catch (Exception ignored) {
                    // RowView construction failed (e.g. no no-arg constructor for plain class)
                    // — fall back to heap for this type
                    rowViews[i] = null;
                    heapComponents[i] = new EntityComponent[capacity];
                }
            } else {
                heapComponents[i] = new EntityComponent[capacity];
            }
        }

        this.indexedIds = new IndexedEntityId[capacity];
        this.entities   = new IndexedEntity[capacity];

        loadInitialEntities();
    }

    // -----------------------------------------------------------------------
    // Bootstrap
    // Bootstrap helpers

    @SuppressWarnings("unchecked")
    private void loadInitialEntities() {
        EntitySet bootstrap;
        if (filter == null) {
            bootstrap = parent.getEntities(types);
        } else {
            bootstrap = parent.getEntities(filter, types);
        }
        try {
            bootstrap.applyChanges();
            for (Entity e : bootstrap) {
                EntityId eid = e.getId();
                EntityComponent[] comps = loadComponentsFromParent(eid);
                if (comps != null) {
                    int index = allocateIndex();
                    putEntity(index, eid, comps);
                }
            }
        } finally {
            bootstrap.release();
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
                    "PackedEntitySet capacity exceeded: " + capacity);
        }
        return nextIndex++;
    }

    private void putEntity(int index, EntityId eid, EntityComponent[] comps) {
        IndexedEntityId iid = new IndexedEntityId(eid, index);
        IndexedEntity   ie  = new IndexedEntity(iid, this, parent);

        presentAccessor.set(store, index, true);
        for (int ti = 0; ti < types.length; ti++) {
            if (rowViews[ti] != null) {
                @SuppressWarnings("unchecked")
                RowView<EntityComponent> rv = (RowView<EntityComponent>) rowViews[ti];
                rv.set(store, index, comps[ti]);
            } else {
                heapComponents[ti][index] = comps[ti];
            }
        }
        indexedIds[index] = iid;
        entities[index]   = ie;
        idToIndex.put(eid.getId(), index);
        size++;
    }

    private void removeEntity(int index) {
        presentAccessor.set(store, index, false);
        for (int ti = 0; ti < types.length; ti++) {
            if (heapComponents[ti] != null) {
                heapComponents[ti][index] = null;
            }
            // store-backed slots need no explicit clear; next putEntity overwrites them
        }
        indexedIds[index] = null;
        entities[index]   = null;
        freeIndices.add(index);
        size--;
    }

    /**
     * Loads all tracked component values for {@code eid} from the parent EntityData.
     * Returns {@code null} if any required component is absent (entity does not fully match).
     */
    private EntityComponent[] loadComponentsFromParent(EntityId eid) {
        EntityComponent[] comps = new EntityComponent[types.length];
        for (int ti = 0; ti < types.length; ti++) {
            EntityComponent c = parent.getComponent(eid, types[ti]);
            if (c == null) return null;
            comps[ti] = c;
        }
        // Check filter
        if (filter != null) {
            @SuppressWarnings("unchecked")
            Class<EntityComponent> ft = (Class<EntityComponent>) filter.getComponentType();
            int fti = typeIndex(ft);
            EntityComponent fc = (fti >= 0) ? comps[fti] : parent.getComponent(eid, ft);
            if (fc == null || !filter.evaluate(fc)) return null;
        }
        return comps;
    }

    // -----------------------------------------------------------------------
    // Package-private accessors for IndexedEntity

    /**
     * Writes a single component directly into the store/heap for the given dense index.
     * Called from {@link IndexedEntity#set} before the parent's change notification fires,
     * so the store is already up to date when {@link #processChange} runs.
     */
    @SuppressWarnings("unchecked")
    void writeComponentDirect(int index, EntityComponent c) {
        int ti = typeIndex(c.getClass());
        if (ti < 0) return;
        if (rowViews[ti] != null) {
            ((RowView<EntityComponent>) rowViews[ti]).set(store, index, c);
        } else {
            heapComponents[ti][index] = c;
        }
    }

    EntityComponent getComponentForIndex(int index, Class<?> type) {
        int ti = typeIndex((Class<EntityComponent>) type);
        if (ti < 0) return null;
        if (rowViews[ti] != null) {
            return (EntityComponent) rowViews[ti].get(store, index);
        }
        return heapComponents[ti][index];
    }

    boolean isEntityComplete(int index) {
        // An entity is complete iff it is present in the set.
        // putEntity writes all component slots before setting the present bit, so
        // presence implies all components (both store-backed and heap-backed) are populated.
        return presentAccessor.get(store, index);
    }

    EntityComponent[] getComponentsForIndex(int index) {
        EntityComponent[] result = new EntityComponent[types.length];
        for (int ti = 0; ti < types.length; ti++) {
            if (rowViews[ti] != null) {
                result[ti] = (EntityComponent) rowViews[ti].get(store, index);
            } else {
                result[ti] = heapComponents[ti][index];
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Change notification (called by PackedEntityData)

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

        // Handle filter changes first
        if (filtersChanged) {
            filtersChanged = false;
            applyFilterChange();
        }

        // Drain pending change notifications
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

        // Only react to changes that involve types we track or that affect the active filter
        boolean relevant = changedTi >= 0
                || (filter != null && filter.getComponentType() == changedType);
        if (!relevant) return;

        Integer idx = idToIndex.get(eid.getId());

        if (idx != null) {
            // Entity is already in the set.
            //
            // Fast path: if this is a tracked, store-backed type and there is no active
            // filter on it, the store was already written by IndexedEntity.set() before
            // the notification was queued — just mark changed and return.
            boolean needsFilterCheck = filter != null
                    && filter.getComponentType() == changedType;
            if (changedTi >= 0 && rowViews[changedTi] != null && !needsFilterCheck) {
                changedEntities.add(entities[idx]);
                return;
            }

            // Slower path: fetch from parent to check for removal or filter failure,
            // and update heap-backed components.
            EntityComponent newComp = parent.getComponent(eid, changedType);

            if (newComp == null) {
                // A required tracked component was removed — entity leaves the set
                removedEntities.add(entities[idx]);
                idToIndex.remove(eid.getId());
                removeEntity(idx);
                return;
            }

            // Re-evaluate filter if the changed component is the filter component
            if (needsFilterCheck && !filter.evaluate(newComp)) {
                removedEntities.add(entities[idx]);
                idToIndex.remove(eid.getId());
                removeEntity(idx);
                return;
            }

            // For heap-backed tracked types, persist the new value
            if (changedTi >= 0 && heapComponents[changedTi] != null) {
                heapComponents[changedTi][idx] = newComp;
            }
            changedEntities.add(entities[idx]);
        } else {
            // Entity is not in the set — load all components to see if it now qualifies
            EntityComponent[] comps = loadComponentsFromParent(eid);
            if (comps != null) {
                int index = allocateIndex();
                putEntity(index, eid, comps);
                addedEntities.add(entities[index]);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyFilterChange() {
        // Remove entities that no longer match the new filter
        for (int i = 0; i < nextIndex; i++) {
            if (entities[i] == null) continue;
            EntityId eid = indexedIds[i].getEntityId();
            EntityComponent[] comps = loadComponentsFromParent(eid);
            if (comps == null) {
                removedEntities.add(entities[i]);
                idToIndex.remove(eid.getId());
                removeEntity(i);
            }
        }

        // Find entities that now match but are not yet in the set
        EntitySet bootstrap;
        if (filter == null) {
            bootstrap = parent.getEntities(types);
        } else {
            bootstrap = parent.getEntities(filter, types);
        }
        try {
            bootstrap.applyChanges();
            for (Entity e : bootstrap) {
                EntityId eid = e.getId();
                if (idToIndex.containsKey(eid.getId())) continue;
                EntityComponent[] comps = loadComponentsFromParent(eid);
                if (comps != null) {
                    int index = allocateIndex();
                    putEntity(index, eid, comps);
                    addedEntities.add(entities[index]);
                }
            }
        } finally {
            bootstrap.release();
        }
    }

    @Override
    public void resetFilter(ComponentFilter newFilter) {
        this.filter = newFilter;
        this.filtersChanged = true;
    }

    @Override
    public boolean containsId(EntityId id) {
        Integer idx = idToIndex.get(id.getId());
        if (idx == null) return false;
        return presentAccessor.get(store, idx);
    }

    @Override
    public Set<EntityId> getEntityIds() {
        Set<EntityId> result = new HashSet<>(size);
        for (int i = 0; i < nextIndex; i++) {
            if (indexedIds[i] != null) {
                result.add(indexedIds[i].getEntityId());
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
    // Set<Entity> interface (AbstractSet covers the rest)

    @Override
    public int size() { return size; }

    @Override
    public boolean contains(Object o) {
        if (o instanceof IndexedEntity ie) {
            return containsId(ie.getId());
        }
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
        private IndexedEntity next;

        EntityIterator() { advance(); }

        private void advance() {
            next = null;
            while (cursor < nextIndex) {
                IndexedEntity ie = entities[cursor++];
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
    // Helpers

    @SuppressWarnings("unchecked")
    private int typeIndex(Class<?> type) {
        for (int i = 0; i < types.length; i++) {
            if (types[i] == type) return i;
        }
        return -1;
    }
}
