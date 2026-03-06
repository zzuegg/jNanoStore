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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A standalone {@link EntityData} implementation designed from the ground up
 * for BitKit datastores.
 *
 * <p>Unlike {@link PackedEntityData}, this class does <em>not</em> wrap an
 * existing {@link EntityData}. It manages entity IDs, component storage,
 * change notifications, and entity sets entirely on its own. Component data
 * for the central store is kept in {@code ConcurrentHashMap}s; entity sets
 * returned by {@link #getEntities} use BitKit packed storage internally for
 * cache-friendly, allocation-free iteration.</p>
 *
 * <p>Implements {@link ObservableEntityData} so that external listeners
 * (and the entity sets themselves) can receive component change notifications.</p>
 */
public final class BitEntityData implements ObservableEntityData {

    /** Default capacity for each {@link BitEntitySet} (entity slots). */
    public static final int DEFAULT_CAPACITY = 10_000;

    private final AtomicLong nextEntityId = new AtomicLong(1);
    private final int capacity;

    /**
     * Central component storage: entityId (long) → (componentType → component).
     * Outer map is concurrent for thread safety; inner maps are synchronized on
     * themselves for atomicity of per-entity operations.
     */
    private final ConcurrentHashMap<Long, Map<Class<? extends EntityComponent>, EntityComponent>>
            components = new ConcurrentHashMap<>();

    /** All active (not yet released) entity sets. */
    private final List<BitEntitySet> entitySets = new CopyOnWriteArrayList<>();

    /** All active watched entities. */
    private final List<BitWatchedEntity> watchedEntities = new CopyOnWriteArrayList<>();

    /** External listeners registered via {@link #addEntityComponentListener}. */
    private final List<EntityComponentListener> listeners = new CopyOnWriteArrayList<>();

    /** In-memory string index. */
    private final MemStringIndex stringIndex = new MemStringIndex();

    // -----------------------------------------------------------------------
    // Constructors

    /**
     * Creates a {@code BitEntityData} with the {@link #DEFAULT_CAPACITY default capacity}
     * per entity set.
     */
    public BitEntityData() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a {@code BitEntityData} with a custom capacity per entity set.
     *
     * @param capacity maximum number of entities per {@link BitEntitySet}
     */
    public BitEntityData(int capacity) {
        this.capacity = capacity;
    }

    // -----------------------------------------------------------------------
    // ObservableEntityData

    @Override
    public void addEntityComponentListener(EntityComponentListener l) {
        listeners.add(l);
    }

    @Override
    public void removeEntityComponentListener(EntityComponentListener l) {
        listeners.remove(l);
    }

    // -----------------------------------------------------------------------
    // Change notification

    private void fireComponentChange(EntityChange change) {
        for (BitEntitySet set : entitySets) {
            if (!set.isReleased()) {
                set.entityChange(change);
            }
        }
        entitySets.removeIf(BitEntitySet::isReleased);

        for (BitWatchedEntity watched : watchedEntities) {
            if (!watched.isReleased()) {
                watched.entityChange(change);
            }
        }
        watchedEntities.removeIf(BitWatchedEntity::isReleased);

        for (EntityComponentListener l : listeners) {
            l.componentChange(change);
        }
    }

    // -----------------------------------------------------------------------
    // EntityData — entity lifecycle

    @Override
    public EntityId createEntity() {
        long id = nextEntityId.getAndIncrement();
        components.put(id, new ConcurrentHashMap<>());
        return new EntityId(id);
    }

    @Override
    public void removeEntity(EntityId id) {
        Map<Class<? extends EntityComponent>, EntityComponent> map = components.remove(id.getId());
        if (map != null) {
            for (var entry : map.entrySet()) {
                @SuppressWarnings("unchecked")
                Class<? extends EntityComponent> type = entry.getKey();
                fireComponentChange(new EntityChange(id, type));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Component CRUD

    @Override
    public <T extends EntityComponent> void setComponent(EntityId id, T component) {
        Map<Class<? extends EntityComponent>, EntityComponent> map =
                components.computeIfAbsent(id.getId(), k -> new ConcurrentHashMap<>());
        @SuppressWarnings("unchecked")
        Class<? extends EntityComponent> type =
                (Class<? extends EntityComponent>) component.getClass();
        map.put(type, component);
        fireComponentChange(new EntityChange(id, component));
    }

    @Override
    public void setComponents(EntityId id, EntityComponent... comps) {
        for (EntityComponent c : comps) {
            setComponent(id, c);
        }
    }

    @Override
    public <T extends EntityComponent> boolean removeComponent(EntityId id, Class<T> type) {
        Map<Class<? extends EntityComponent>, EntityComponent> map = components.get(id.getId());
        if (map == null) return false;
        EntityComponent old = map.remove(type);
        if (old != null) {
            fireComponentChange(new EntityChange(id, type));
            return true;
        }
        return false;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void removeComponents(EntityId id, Class... types) {
        for (Class type : types) {
            @SuppressWarnings("unchecked")
            Class<EntityComponent> t = type;
            removeComponent(id, t);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends EntityComponent> T getComponent(EntityId id, Class<T> type) {
        Map<Class<? extends EntityComponent>, EntityComponent> map = components.get(id.getId());
        if (map == null) return null;
        return (T) map.get(type);
    }

    // -----------------------------------------------------------------------
    // Entity views

    @Override
    @SuppressWarnings("rawtypes")
    public Entity getEntity(EntityId id, Class... types) {
        EntityComponent[] comps = new EntityComponent[types.length];
        for (int i = 0; i < types.length; i++) {
            @SuppressWarnings("unchecked")
            Class<EntityComponent> t = types[i];
            comps[i] = getComponent(id, t);
        }
        return new BitEntity(id, this, types, comps);
    }

    // -----------------------------------------------------------------------
    // Entity sets

    @Override
    @SuppressWarnings("rawtypes")
    public EntitySet getEntities(Class... types) {
        return getEntities(null, types);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public EntitySet getEntities(ComponentFilter filter, Class... types) {
        BitEntitySet set = new BitEntitySet(this, filter, types, capacity);
        entitySets.add(set);
        return set;
    }

    // -----------------------------------------------------------------------
    // find operations

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public EntityId findEntity(ComponentFilter filter, Class... types) {
        for (var entry : components.entrySet()) {
            long id = entry.getKey();
            Map<Class<? extends EntityComponent>, EntityComponent> map = entry.getValue();

            boolean hasAll = true;
            for (Class type : types) {
                if (!map.containsKey(type)) {
                    hasAll = false;
                    break;
                }
            }
            if (!hasAll) continue;

            if (filter != null) {
                EntityComponent fc = map.get(filter.getComponentType());
                if (fc == null || !filter.evaluate(fc)) continue;
            }

            return new EntityId(id);
        }
        return null;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Set<EntityId> findEntities(ComponentFilter filter, Class... types) {
        Set<EntityId> result = new HashSet<>();
        for (var entry : components.entrySet()) {
            long id = entry.getKey();
            Map<Class<? extends EntityComponent>, EntityComponent> map = entry.getValue();

            boolean hasAll = true;
            for (Class type : types) {
                if (!map.containsKey(type)) {
                    hasAll = false;
                    break;
                }
            }
            if (!hasAll) continue;

            if (filter != null) {
                EntityComponent fc = map.get(filter.getComponentType());
                if (fc == null || !filter.evaluate(fc)) continue;
            }

            result.add(new EntityId(id));
        }
        return Collections.unmodifiableSet(result);
    }

    // -----------------------------------------------------------------------
    // Watched entities

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public WatchedEntity watchEntity(EntityId id, Class... types) {
        Class<EntityComponent>[] typedTypes = new Class[types.length];
        for (int i = 0; i < types.length; i++) {
            typedTypes[i] = types[i];
        }
        EntityComponent[] comps = new EntityComponent[types.length];
        for (int i = 0; i < types.length; i++) {
            comps[i] = getComponent(id, typedTypes[i]);
        }
        BitWatchedEntity watched = new BitWatchedEntity(this, id, typedTypes, comps);
        watchedEntities.add(watched);
        return watched;
    }

    // -----------------------------------------------------------------------
    // StringIndex

    @Override
    public StringIndex getStrings() {
        return stringIndex;
    }

    // -----------------------------------------------------------------------
    // Lifecycle

    @Override
    public void close() {
        entitySets.clear();
        watchedEntities.clear();
        listeners.clear();
        components.clear();
    }

    // -----------------------------------------------------------------------
    // Package-private helpers for entity sets

    /**
     * Loads all tracked component values for {@code eid} from the central store.
     * Returns {@code null} if any required component is absent or filter fails.
     */
    @SuppressWarnings("unchecked")
    EntityComponent[] loadComponents(EntityId eid, Class<EntityComponent>[] types,
                                     ComponentFilter filter) {
        Map<Class<? extends EntityComponent>, EntityComponent> map =
                components.get(eid.getId());
        if (map == null) return null;

        EntityComponent[] comps = new EntityComponent[types.length];
        for (int i = 0; i < types.length; i++) {
            EntityComponent c = map.get(types[i]);
            if (c == null) return null;
            comps[i] = c;
        }

        if (filter != null) {
            Class<EntityComponent> ft = (Class<EntityComponent>) filter.getComponentType();
            EntityComponent fc = map.get(ft);
            if (fc == null || !filter.evaluate(fc)) return null;
        }

        return comps;
    }

    /**
     * Returns all entity IDs that have all the specified component types and pass the filter.
     */
    @SuppressWarnings("unchecked")
    Set<EntityId> findMatchingEntities(Class<EntityComponent>[] types, ComponentFilter filter) {
        Set<EntityId> result = new HashSet<>();
        for (var entry : components.entrySet()) {
            long id = entry.getKey();
            Map<Class<? extends EntityComponent>, EntityComponent> map = entry.getValue();

            boolean hasAll = true;
            for (Class<EntityComponent> type : types) {
                if (!map.containsKey(type)) {
                    hasAll = false;
                    break;
                }
            }
            if (!hasAll) continue;

            if (filter != null) {
                EntityComponent fc = map.get(filter.getComponentType());
                if (fc == null || !filter.evaluate(fc)) continue;
            }

            result.add(new EntityId(id));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Simple in-memory StringIndex

    private static final class MemStringIndex implements StringIndex {
        private final Map<String, Integer> stringToId = new HashMap<>();
        private final Map<Integer, String> idToString = new HashMap<>();
        private int nextId = 1;

        @Override
        public int getStringId(String s, boolean add) {
            Integer id = stringToId.get(s);
            if (id != null) return id;
            if (!add) return -1;
            int newId = nextId++;
            stringToId.put(s, newId);
            idToString.put(newId, s);
            return newId;
        }

        @Override
        public String getString(int id) {
            return idToString.get(id);
        }
    }
}
