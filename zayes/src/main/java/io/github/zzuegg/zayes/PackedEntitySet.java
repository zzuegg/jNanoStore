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
import io.github.zzuegg.jbinary.accessor.BoolAccessor;
import io.github.zzuegg.jbinary.annotation.BoolField;

import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A self-managing {@link EntitySet} implementation that stores entity membership
 * using a BitKit {@link DataStore} keyed by a dense {@link IndexedEntityId#getIndex()}.
 *
 * <p>Instead of an internally-managed {@code HashMap<EntityId, Entity>} (like
 * {@code DefaultEntitySet}), this set maintains:
 * <ul>
 *   <li>A packed {@link DataStore} with a single {@code @BoolField present} bit per
 *       slot, enabling O(1) membership checks without hash-collision overhead.</li>
 *   <li>Flat {@code EntityComponent[][]} arrays — one per tracked component type —
 *       indexed by the dense entity index for O(1) component reads.</li>
 * </ul>
 *
 * <p>Change notifications arrive via {@link #entityChange(EntityChange)}, called by
 * the owning {@link PackedEntityData}.  Changes are queued and only applied (and the
 * change-delta sets populated) when {@link #applyChanges()} is invoked.
 */
public final class PackedEntitySet extends AbstractSet<Entity> implements EntitySet {

    // -----------------------------------------------------------------------
    // Internal membership record for the DataStore
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

    /** PackedDataStore with one membership bit per entity slot. */
    private final DataStore<MembershipRecord> store;
    private final BoolAccessor presentAccessor;

    /**
     * Per-component-type flat arrays. {@code components[ti][ei]} holds the
     * component object for type index {@code ti} at entity index {@code ei}.
     */
    private final EntityComponent[][] components;

    /** Dense index → IndexedEntityId (null if slot is free). */
    private final IndexedEntityId[] indexedIds;

    /** Dense index → IndexedEntity (null if slot is free). */
    private final IndexedEntity[] entities;

    /** EntityId (as long) → dense index. */
    private final HashMap<Long, Integer> idToIndex = new HashMap<>();

    private int size = 0;

    // Change sets (replaced on each applyChanges() call)
    private Set<Entity> addedEntities   = new HashSet<>();
    private Set<Entity> changedEntities = new HashSet<>();
    private Set<Entity> removedEntities = new HashSet<>();

    /** Thread-safe queue for incoming change notifications. */
    private final ConcurrentLinkedQueue<EntityChange> pendingChanges = new ConcurrentLinkedQueue<>();

    private boolean filtersChanged = false;
    private boolean released = false;

    // -----------------------------------------------------------------------
    // Constructor

    @SuppressWarnings("unchecked")
    PackedEntitySet(EntityData parent, ComponentFilter filter,
                    Class<?>[] rawTypes, int capacity) {
        this.parent   = parent;
        this.filter   = filter;
        this.capacity = capacity;

        this.types = new Class[rawTypes.length];
        for (int i = 0; i < rawTypes.length; i++) {
            this.types[i] = (Class<EntityComponent>) rawTypes[i];
        }

        this.store           = DataStore.packed(capacity, MembershipRecord.class);
        this.presentAccessor = Accessors.boolFieldInStore(store, MembershipRecord.class, "present");

        this.components = new EntityComponent[types.length][capacity];
        this.indexedIds = new IndexedEntityId[capacity];
        this.entities   = new IndexedEntity[capacity];

        // Bootstrap: load the initial matching entities from the parent
        loadInitialEntities();
    }

    // -----------------------------------------------------------------------
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
            components[ti][index] = comps[ti];
        }
        indexedIds[index] = iid;
        entities[index]   = ie;
        idToIndex.put(eid.getId(), index);
        size++;
    }

    private void removeEntity(int index) {
        presentAccessor.set(store, index, false);
        for (int ti = 0; ti < types.length; ti++) {
            components[ti][index] = null;
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

    EntityComponent getComponentForIndex(int index, Class<?> type) {
        int ti = typeIndex((Class<EntityComponent>) type);
        if (ti < 0) return null;
        return components[ti][index];
    }

    boolean isEntityComplete(int index) {
        for (int ti = 0; ti < types.length; ti++) {
            if (components[ti][index] == null) return false;
        }
        return true;
    }

    EntityComponent[] getComponentsForIndex(int index) {
        EntityComponent[] result = new EntityComponent[types.length];
        for (int ti = 0; ti < types.length; ti++) {
            result[ti] = components[ti][index];
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

        // Only react to changes that involve types we care about
        boolean relevant = typeIndex(changedType) >= 0
                || (filter != null && filter.getComponentType() == changedType);
        if (!relevant) return;

        Integer idx = idToIndex.get(eid.getId());

        if (idx != null) {
            // Entity is currently in the set — check if it still matches
            EntityComponent[] comps = loadComponentsFromParent(eid);
            IndexedEntity ie = entities[idx];
            if (comps != null) {
                // Still matches — update component data
                for (int ti = 0; ti < types.length; ti++) {
                    components[ti][idx] = comps[ti];
                }
                changedEntities.add(ie);
            } else {
                // No longer matches — remove it
                removedEntities.add(ie);
                idToIndex.remove(eid.getId());
                removeEntity(idx);
            }
        } else {
            // Entity is not in the set — check if it now matches
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
