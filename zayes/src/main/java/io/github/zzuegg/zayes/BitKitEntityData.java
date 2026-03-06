package io.github.zzuegg.zayes;

import com.simsilica.es.ComponentFilter;
import com.simsilica.es.Entity;
import com.simsilica.es.EntityChange;
import com.simsilica.es.EntityComponent;
import com.simsilica.es.EntityComponentListener;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import com.simsilica.es.EntitySet;
import com.simsilica.es.ObservableEntityData;
import com.simsilica.es.StringIndex;
import com.simsilica.es.WatchedEntity;
import com.simsilica.es.base.DefaultWatchedEntity;
import com.simsilica.es.base.DefaultEntity;
import com.simsilica.es.base.MemStringIndex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A ground-up zay-es {@link EntityData} implementation designed to take full advantage of
 * BitKit {@link io.github.zzuegg.jbinary.PackedDataStore} — without wrapping an existing
 * {@code EntityData} (unlike {@link PackedEntityData}).
 *
 * <h3>Component storage</h3>
 * <p>Components are stored in a {@code HashMap<Class, EntityComponent[]>} where each array
 * is indexed directly by the numeric entity ID (dense integers assigned by an
 * {@link java.util.concurrent.atomic.AtomicLong} counter starting at 1).  This gives O(1)
 * reads and writes with no boxing and no hash-collision overhead, and allows the candidate
 * set for {@link #getEntities}/{@link #findEntities} queries to be computed without any
 * external data source.
 *
 * <h3>Entity sets</h3>
 * <p>{@link #getEntities} returns a {@link PackedEntitySet} whose in-store packed
 * representation is identical to the one returned by {@link PackedEntityData}.  The only
 * difference is the bootstrap path: instead of calling {@code parent.getEntities()} (which
 * would recurse back into this class), the initial set of matching entity IDs is computed
 * directly from the component maps and supplied via the alternative
 * {@link PackedEntitySet#PackedEntitySet(EntityData, ComponentFilter, Class[], int, Collection)}
 * constructor.
 *
 * <h3>Change notification</h3>
 * <p>This class implements {@link ObservableEntityData}; external listeners (including
 * {@link DefaultWatchedEntity} instances) register here and receive component-change events.
 * Active {@link PackedEntitySet} instances receive changes via the package-private
 * {@link PackedEntitySet#entityChange} method.
 *
 * <h3>Drop-in replacement</h3>
 * <p>All tests in {@link AbstractEntityDataTest} pass against this implementation.
 * Additional allocation-free access paths are available through the
 * {@link PackedEntitySet} API ({@link PackedEntitySet#createCursor},
 * {@link PackedEntitySet#createDataCursor}, {@link PackedEntitySet#generateProjectionCursor}).
 *
 * <pre>{@code
 * EntityData ed = new BitKitEntityData();
 *
 * EntityId id = ed.createEntity();
 * ed.setComponents(id, new Position(1, 2, 3), new Velocity(0.5f, 0, 0));
 *
 * EntitySet set = ed.getEntities(Position.class, Velocity.class);
 * set.applyChanges();
 * for (var entity : set) { // ...
 * }
 * }</pre>
 */
public final class BitKitEntityData implements EntityData, ObservableEntityData {

    /** Default entity-slot capacity for each {@link PackedEntitySet}. */
    public static final int DEFAULT_CAPACITY = 10_000;

    private final AtomicLong entityIdGen = new AtomicLong(0);
    private final int capacity;

    /**
     * Per-type component storage: {@code componentType → EntityComponent[entityId]}.
     *
     * <p>Entity IDs are assigned by {@link #createEntity()} via an {@link AtomicLong}
     * counter starting at 1, so {@code entityId} is always a dense integer in {@code [1, capacity]}.
     * Direct array access by {@code (int) entityId} is O(1) with no boxing.
     *
     * <p>An array entry is {@code null} until the first component of that type is stored,
     * and the entry at a given index is set to {@code null} on removal.
     */
    private final HashMap<Class<?>, EntityComponent[]> componentArrays = new HashMap<>();

    /** Registered global change listeners (e.g. {@link DefaultWatchedEntity} instances). */
    private final List<EntityComponentListener> listeners = new ArrayList<>();

    /** All active (un-released) {@link PackedEntitySet} instances needing change notifications. */
    private final List<PackedEntitySet> entitySets = new ArrayList<>();

    private final StringIndex strings = new MemStringIndex();

    // -----------------------------------------------------------------------
    // Constructors

    /** Creates a {@code BitKitEntityData} with the {@link #DEFAULT_CAPACITY default capacity}. */
    public BitKitEntityData() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a {@code BitKitEntityData} with a custom per-set entity capacity.
     *
     * @param capacity maximum number of entities per {@link PackedEntitySet}
     */
    public BitKitEntityData(int capacity) {
        this.capacity = capacity;
    }

    // -----------------------------------------------------------------------
    // ObservableEntityData

    @Override
    public void addEntityComponentListener(EntityComponentListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeEntityComponentListener(EntityComponentListener listener) {
        listeners.remove(listener);
    }

    // -----------------------------------------------------------------------
    // Entity lifecycle

    @Override
    public EntityId createEntity() {
        return new EntityId(entityIdGen.incrementAndGet());
    }

    @Override
    public void removeEntity(EntityId id) {
        int rawId = (int) id.getId();
        for (var entry : componentArrays.entrySet()) {
            EntityComponent[] arr = entry.getValue();
            if (arr != null && rawId < arr.length && arr[rawId] != null) {
                arr[rawId] = null;
                @SuppressWarnings("unchecked")
                Class<? extends EntityComponent> type =
                        (Class<? extends EntityComponent>) entry.getKey();
                fireChange(new EntityChange(id, type));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Component write operations

    @Override
    public <T extends EntityComponent> void setComponent(EntityId id, T component) {
        getOrCreateArray(component.getClass())[(int) id.getId()] = component;
        fireChange(new EntityChange(id, component));
    }

    @Override
    public void setComponents(EntityId id, EntityComponent... components) {
        for (EntityComponent c : components) {
            setComponent(id, c);
        }
    }

    @Override
    public <T extends EntityComponent> boolean removeComponent(EntityId id, Class<T> type) {
        EntityComponent[] arr = componentArrays.get(type);
        int rawId = (int) id.getId();
        if (arr == null || rawId >= arr.length || arr[rawId] == null) return false;
        arr[rawId] = null;
        fireChange(new EntityChange(id, type));
        return true;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void removeComponents(EntityId id, Class... types) {
        for (Class type : types) {
            removeComponent(id, type);
        }
    }

    // -----------------------------------------------------------------------
    // Component read operations

    @Override
    public <T extends EntityComponent> T getComponent(EntityId id, Class<T> type) {
        EntityComponent[] arr = componentArrays.get(type);
        if (arr == null) return null;
        int rawId = (int) id.getId();
        if (rawId >= arr.length) return null;
        return type.cast(arr[rawId]);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Entity getEntity(EntityId id, Class... types) {
        EntityComponent[] comps = new EntityComponent[types.length];
        for (int i = 0; i < types.length; i++) {
            comps[i] = getComponent(id, (Class<EntityComponent>) types[i]);
        }
        return new DefaultEntity(this, id, comps, types);
    }

    // -----------------------------------------------------------------------
    // Query operations

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public EntityId findEntity(ComponentFilter filter, Class... types) {
        for (EntityId eid : findCandidates(types)) {
            EntityComponent fc = getComponent(eid,
                    (Class<EntityComponent>) filter.getComponentType());
            if (fc != null && filter.evaluate(fc)) return eid;
        }
        return null;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Set<EntityId> findEntities(ComponentFilter filter, Class... types) {
        Set<EntityId> result = new HashSet<>();
        for (EntityId eid : findCandidates(types)) {
            EntityComponent fc = getComponent(eid,
                    (Class<EntityComponent>) filter.getComponentType());
            if (fc != null && filter.evaluate(fc)) result.add(eid);
        }
        return Collections.unmodifiableSet(result);
    }

    // -----------------------------------------------------------------------
    // Entity set creation

    @Override
    @SuppressWarnings("rawtypes")
    public EntitySet getEntities(Class... types) {
        Collection<EntityId> initial = findCandidates(types);
        PackedEntitySet set = new PackedEntitySet(this, null, types, capacity, initial);
        entitySets.add(set);
        return set;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public EntitySet getEntities(ComponentFilter filter, Class... types) {
        Collection<EntityId> initial = findFilteredCandidates(filter, types);
        PackedEntitySet set = new PackedEntitySet(this, filter, types, capacity, initial);
        entitySets.add(set);
        return set;
    }

    // -----------------------------------------------------------------------
    // WatchedEntity / StringIndex / close

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public WatchedEntity watchEntity(EntityId id, Class... types) {
        return new DefaultWatchedEntity(this, id, (Class<EntityComponent>[]) types);
    }

    @Override
    public StringIndex getStrings() {
        return strings;
    }

    @Override
    public void close() {
        entitySets.clear();
        listeners.clear();
        componentArrays.clear();
    }

    // -----------------------------------------------------------------------
    // Internal helpers

    private EntityComponent[] getOrCreateArray(Class<?> type) {
        EntityComponent[] arr = componentArrays.get(type);
        if (arr == null) {
            arr = new EntityComponent[capacity + 1];   // index 0 unused; IDs start at 1
            componentArrays.put(type, arr);
        }
        return arr;
    }

    /**
     * Dispatches a component-change event to all registered listeners and
     * to all active {@link PackedEntitySet} instances.
     *
     * <p><strong>Note:</strong> this method is not re-entrant.  Listener or
     * entity-set callbacks must not add or remove listeners / entity sets
     * during this call.
     */
    private void fireChange(EntityChange change) {
        for (int i = 0, n = listeners.size(); i < n; i++) {
            listeners.get(i).componentChange(change);
        }
        // Backwards iteration: safe to remove at index i while iterating.
        for (int i = entitySets.size() - 1; i >= 0; i--) {
            PackedEntitySet set = entitySets.get(i);
            if (set.isReleased()) {
                entitySets.remove(i);
            } else {
                set.entityChange(change);
            }
        }
    }

    /**
     * Returns the entity IDs that have all of the given component types.
     * Scans the array for the first type, then checks subsequent types.
     */
    @SuppressWarnings("rawtypes")
    private Collection<EntityId> findCandidates(Class... types) {
        if (types.length == 0) return Collections.emptyList();

        EntityComponent[] firstArr = componentArrays.get(types[0]);
        if (firstArr == null) return Collections.emptyList();

        List<EntityId> result = new ArrayList<>();
        for (int i = 1; i < firstArr.length; i++) {
            if (firstArr[i] == null) continue;
            // check remaining types
            boolean hasAll = true;
            for (int j = 1; j < types.length; j++) {
                EntityComponent[] arr = componentArrays.get(types[j]);
                if (arr == null || i >= arr.length || arr[i] == null) {
                    hasAll = false;
                    break;
                }
            }
            if (hasAll) result.add(new EntityId(i));
        }
        return result;
    }

    /**
     * Returns entity IDs that have all of the given types AND pass the provided filter.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Collection<EntityId> findFilteredCandidates(ComponentFilter filter, Class... types) {
        Collection<EntityId> candidates = findCandidates(types);
        if (candidates.isEmpty()) return Collections.emptyList();

        List<EntityId> result = new ArrayList<>();
        for (EntityId eid : candidates) {
            EntityComponent fc = getComponent(eid,
                    (Class<EntityComponent>) filter.getComponentType());
            if (fc != null && filter.evaluate(fc)) result.add(eid);
        }
        return result;
    }
}
