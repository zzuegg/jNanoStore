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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A ground-up zay-es {@link EntityData} implementation designed to take full advantage of
 * BitKit {@link io.github.zzuegg.jbinary.PackedDataStore} — without wrapping an existing
 * {@code EntityData} (unlike {@link PackedEntityData}).
 *
 * <h3>Component storage</h3>
 * <p>Components are stored in a {@link ConcurrentHashMap} keyed by component type, with a
 * nested map from entity-id {@code long} to component instance.  This gives O(1) reads and
 * writes for individual component access, and allows the candidate set for
 * {@link #getEntities}/{@link #findEntities} queries to be computed without any external
 * data source.
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
     * Per-type component storage: {@code componentType → (entityId.getId() → component)}.
     * <p>A map entry for a given type exists only once the first component of that type
     * has been stored.  Removal shrinks the per-entity inner map but never removes the
     * outer map entry (harmless, consistent with standard ECS patterns).
     */
    private final ConcurrentHashMap<Class<?>, ConcurrentHashMap<Long, EntityComponent>>
            componentMaps = new ConcurrentHashMap<>();

    /** Registered global change listeners (e.g. {@link DefaultWatchedEntity} instances). */
    private final List<EntityComponentListener> listeners = new CopyOnWriteArrayList<>();

    /** All active (un-released) {@link PackedEntitySet} instances needing change notifications. */
    private final List<PackedEntitySet> entitySets = new CopyOnWriteArrayList<>();

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
        long rawId = id.getId();
        for (var entry : componentMaps.entrySet()) {
            if (entry.getValue().remove(rawId) != null) {
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
        getOrCreateMap(component.getClass()).put(id.getId(), component);
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
        ConcurrentHashMap<Long, EntityComponent> map = componentMaps.get(type);
        if (map == null || map.remove(id.getId()) == null) return false;
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
        ConcurrentHashMap<Long, EntityComponent> map = componentMaps.get(type);
        if (map == null) return null;
        return type.cast(map.get(id.getId()));
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
        componentMaps.clear();
    }

    // -----------------------------------------------------------------------
    // Internal helpers

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<Long, EntityComponent> getOrCreateMap(Class<?> type) {
        return componentMaps.computeIfAbsent(type, k -> new ConcurrentHashMap<>());
    }

    /**
     * Dispatches a component-change event to all registered listeners and
     * to all active {@link PackedEntitySet} instances.
     */
    private void fireChange(EntityChange change) {
        for (EntityComponentListener listener : listeners) {
            listener.componentChange(change);
        }
        for (PackedEntitySet set : entitySets) {
            if (!set.isReleased()) {
                set.entityChange(change);
            }
        }
        entitySets.removeIf(PackedEntitySet::isReleased);
    }

    /**
     * Returns the entity IDs that have all of the given component types.
     * Uses intersection of per-type key sets.
     */
    @SuppressWarnings("rawtypes")
    private Collection<EntityId> findCandidates(Class... types) {
        if (types.length == 0) return Collections.emptyList();

        Set<Long> ids = null;
        for (Class<?> type : types) {
            ConcurrentHashMap<Long, EntityComponent> map = componentMaps.get(type);
            if (map == null || map.isEmpty()) return Collections.emptyList();
            if (ids == null) {
                ids = new HashSet<>(map.keySet());
            } else {
                ids.retainAll(map.keySet());
                if (ids.isEmpty()) return Collections.emptyList();
            }
        }

        if (ids == null) return Collections.emptyList();

        List<EntityId> result = new ArrayList<>(ids.size());
        for (Long id : ids) result.add(new EntityId(id));
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
