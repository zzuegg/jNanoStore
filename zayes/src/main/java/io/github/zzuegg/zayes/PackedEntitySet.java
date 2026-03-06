package io.github.zzuegg.zayes;

import com.simsilica.es.ComponentFilter;
import com.simsilica.es.Entity;
import com.simsilica.es.EntityChange;
import com.simsilica.es.EntityComponent;
import com.simsilica.es.EntityData;
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
import java.util.Collection;
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
 * shared {@link DataStore} (using {@link LayoutBuilder} + {@link ComponentCursorBridge}).  Types
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

        // ── Phase 3: build ComponentCursorBridges for storable types; allocate heap arrays for others ──
        this.bridges        = new ComponentCursorBridge[rawTypes.length];
        this.heapComponents = new EntityComponent[rawTypes.length][];
        for (int i = 0; i < rawTypes.length; i++) {
            if (canUseStore[i]) {
                ComponentCursorBridge<?> bridge = ComponentCursorBridge.tryCreate(store, rawTypes[i]);
                if (bridge != null) {
                    bridges[i] = bridge;
                } else {
                    // ComponentCursorBridge failed (e.g. no suitable constructor) — fall back to heap
                    heapComponents[i] = new EntityComponent[capacity];
                }
            } else {
                heapComponents[i] = new EntityComponent[capacity];
            }
        }

        this.indexedIds      = new IndexedEntityId[capacity];
        this.entities        = new IndexedEntity[capacity];

        loadInitialEntities();
    }

    /**
     * Alternative constructor for use when the initial entity set is already known
     * (e.g. by {@link BitKitEntityData}), avoiding a recursive {@code parent.getEntities()}
     * call during bootstrap.
     *
     * <p>The {@code parent} {@link EntityData} is still used for component reads
     * ({@link EntityData#getComponent}) and write-through ({@link IndexedEntity#set}).
     *
     * @param parent       the {@link EntityData} used for component reads and write-through
     * @param filter       optional component filter, or {@code null}
     * @param rawTypes     the component types tracked by this set
     * @param capacity     maximum number of entity slots in the packed store
     * @param bootstrapIds the initial entity IDs to populate the set with
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    PackedEntitySet(EntityData parent, ComponentFilter filter,
                    Class<?>[] rawTypes, int capacity,
                    Collection<EntityId> bootstrapIds) {
        this.parent   = parent;
        this.filter   = filter;
        this.capacity = capacity;

        this.types = new Class[rawTypes.length];
        for (int i = 0; i < rawTypes.length; i++) {
            this.types[i] = (Class<EntityComponent>) rawTypes[i];
        }

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

        this.store = DataStore.packed(capacity, storeClasses.toArray(new Class<?>[0]));
        this.presentAccessor = Accessors.boolFieldInStore(store, MembershipRecord.class, "present");

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

        this.indexedIds = new IndexedEntityId[capacity];
        this.entities   = new IndexedEntity[capacity];

        loadInitialEntitiesFrom(bootstrapIds);
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

    private void loadInitialEntitiesFrom(Collection<EntityId> entityIds) {
        for (EntityId eid : entityIds) {
            EntityComponent[] comps = loadComponentsFromParent(eid);
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
                    "PackedEntitySet capacity exceeded: " + capacity);
        }
        return nextIndex++;
    }

    private void putEntity(int index, EntityId eid, EntityComponent[] comps) {
        IndexedEntityId iid = new IndexedEntityId(eid, index);
        IndexedEntity   ie  = new IndexedEntity(iid, this, parent);

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
     * Writes component {@code c} directly into the store at {@code index}.
     * Called from {@link IndexedEntity#set} to keep the local store current
     * (matching DefaultEntityData's live-read contract for {@code entity.get()}).
     * Also used internally when loading component values during {@link #applyChanges}.
     */
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

    EntityComponent getComponentForIndex(int index, Class<?> type) {
        int ti = typeIndex((Class<EntityComponent>) type);
        if (ti < 0) return null;
        if (bridges[ti] != null) {
            return ((ComponentCursorBridge<EntityComponent>) bridges[ti]).read(store, index);
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
            if (bridges[ti] != null) {
                result[ti] = ((ComponentCursorBridge<EntityComponent>) bridges[ti]).read(store, index);
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
            boolean needsFilterCheck = filter != null
                    && filter.getComponentType() == changedType;

            // Fetch from parent to check for removal or filter failure,
            // and update store-backed / heap-backed components.
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

            // Persist the new value — store-backed or heap-backed
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
    // Cursor factory

    /**
     * Returns a {@link PackedCursor} that reads and writes components of type {@code T}
     * directly from/to the packed store by dense row index, bypassing the
     * {@link IndexedEntity#get}/{@link IndexedEntity#set} abstraction.
     *
     * <p>The cursor is created once and reused across many entities; it is not
     * thread-safe.  Use {@link IndexedEntity#getIndex()} to obtain the row index.
     *
     * @param type the component class (must be one of the types registered with this set
     *             <em>and</em> store-backed — i.e. {@link ComponentCursorBridge} was
     *             successfully built for it)
     * @return a typed cursor, or {@code null} if the type is not store-backed
     */
    @SuppressWarnings("unchecked")
    public <T extends EntityComponent> PackedCursor<T> createCursor(Class<T> type) {
        int ti = typeIndex(type);
        if (ti < 0 || bridges[ti] == null) return null;
        return new PackedCursor<>((ComponentCursorBridge<T>) bridges[ti], store);
    }

    /**
     * A thin, reusable accessor that reads or writes one component type directly from/to
     * the {@link PackedEntitySet}'s packed store by dense row index.
     *
     * <p>Obtain an instance via {@link PackedEntitySet#createCursor(Class)}; pass the
     * entity's row index (from {@link IndexedEntity#getIndex()}) to {@link #read} or
     * {@link #write}.
     *
     * <p>The cursor is not thread-safe and should not be shared across threads.
     * {@code bridge} and {@code store} are guaranteed non-null by the factory.
     *
     * @param <T> the component type
     */
    public static final class PackedCursor<T extends EntityComponent> {
        private final ComponentCursorBridge<T> bridge;
        private final DataStore<?>             store;

        PackedCursor(ComponentCursorBridge<T> bridge, DataStore<?> store) {
            this.bridge = bridge;
            this.store  = store;
        }

        /**
         * Reads the component at the given row index from the packed store and returns a
         * new component instance.  Uses the unboxed {@link java.lang.invoke.MethodHandle}
         * path — no {@code Object[]} allocation, no primitive boxing.
         *
         * <p>{@code index} must be a valid slot (i.e. an {@link IndexedEntity#getIndex()}
         * value for an entity currently in the set); behaviour is undefined for free slots.
         */
        public T read(int index) { return bridge.read(store, index); }

        /**
         * Writes the component at the given row index to the packed store.
         * <em>This does not propagate the change to the parent {@link com.simsilica.es.EntityData}</em>;
         * call {@link IndexedEntity#set} if authoritative storage and change notification
         * are required.
         *
         * <p>{@code component} must not be {@code null}; {@code index} must be a valid slot.
         */
        public void write(int index, T component) { bridge.write(store, index, component); }
    }

    // -----------------------------------------------------------------------
    // Multi-component DataCursor factory

    /**
     * Creates a {@link DataCursor} over this set's packed store for a user-defined projection
     * class annotated with {@link io.github.zzuegg.jbinary.annotation.StoreField}.
     *
     * <p>This allows reading (and writing) fields from <em>multiple</em> component types
     * in a single {@code load()} call — zero allocation, zero boxing.
     *
     * <p>Example:
     * <pre>{@code
     * class PhysicsProjection {
     *     @StoreField(component = Position.class, field = "x")    public float posX;
     *     @StoreField(component = Position.class, field = "y")    public float posY;
     *     @StoreField(component = Position.class, field = "z")    public float posZ;
     *     @StoreField(component = Orientation.class, field = "yaw")   public float yaw;
     *     @StoreField(component = Orientation.class, field = "pitch") public float pitch;
     *     @StoreField(component = Orientation.class, field = "roll")  public float roll;
     *     @StoreField(component = Speed.class, field = "value")       public float speed;
     * }
     *
     * DataCursor<PhysicsProjection> cursor = set.createDataCursor(PhysicsProjection.class);
     * for (var entity : set) {
     *     PhysicsProjection p = cursor.update(store(), ((IndexedEntity) entity).getIndex());
     *     // p.posX, p.yaw, p.speed etc. are all populated — one load() call
     * }
     * }</pre>
     *
     * @param projectionClass a class with {@link io.github.zzuegg.jbinary.annotation.StoreField}-annotated
     *                        public mutable fields
     * @param <T> the projection type
     * @return a reusable, allocation-free cursor bound to this set's store
     */
    public <T> DataCursor<T> createDataCursor(Class<T> projectionClass) {
        return DataCursor.of(store, projectionClass);
    }

    /**
     * Returns the underlying packed store for use with cursors obtained from
     * {@link #createDataCursor(Class)}.
     */
    public DataStore<?> store() {
        return store;
    }

    /**
     * Generates a multi-component projection cursor class at runtime using ByteBuddy,
     * spanning all store-backed component types in this set.  The generated class has one
     * public mutable field per component field, each annotated with
     * {@link io.github.zzuegg.jbinary.annotation.StoreField}.
     *
     * <p>A single {@link DataCursor#load} populates every field across all component types
     * — zero allocation, zero boxing, one call per row.
     *
     * <p>Field naming convention: {@code componentSimpleName_fieldName} (e.g.
     * {@code Position_x}, {@code Speed_value}).
     *
     * @return a {@code DataCursor} whose held instance has all component fields, or
     *         {@code null} if no component types are store-backed
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
