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

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An {@link EntityData} implementation that wraps any existing (parent)
 * {@link EntityData} and returns {@link PackedEntitySet} instances from
 * {@link #getEntities}.
 *
 * <p>Every {@code EntityData} method except {@link #getEntities} is delegated
 * directly to the parent.  For {@code getEntities}, a {@link PackedEntitySet} is
 * created, bootstrapped from the parent's current state, and then kept up-to-date
 * via the {@link EntityComponentListener} mechanism.</p>
 *
 * <p><strong>Requirement:</strong> the {@code parent} must implement
 * {@link ObservableEntityData} so that this class can register as a component
 * change listener.  An {@link IllegalArgumentException} is thrown if the parent
 * does not support this interface.</p>
 *
 * <pre>{@code
 * EntityData base    = new DefaultEntityData();
 * EntityData packed  = new PackedEntityData(base);
 *
 * EntitySet set = packed.getEntities(MyBitKitComponent.class);
 * }</pre>
 */
public final class PackedEntityData implements EntityData, EntityComponentListener {

    /** Default capacity for each {@link PackedEntitySet} (entity slots). */
    public static final int DEFAULT_CAPACITY = 10_000;

    private final EntityData parent;
    private final ObservableEntityData observable;
    private final int capacity;

    /** All active (not yet released) entity sets. */
    private final List<PackedEntitySet> entitySets = new CopyOnWriteArrayList<>();

    // -----------------------------------------------------------------------
    // Constructors

    /**
     * Creates a {@code PackedEntityData} wrapping {@code parent} with the
     * {@link #DEFAULT_CAPACITY default capacity} per entity set.
     *
     * @param parent the backing {@link EntityData}; must implement
     *               {@link ObservableEntityData}
     * @throws IllegalArgumentException if {@code parent} does not implement
     *                                  {@link ObservableEntityData}
     */
    public PackedEntityData(EntityData parent) {
        this(parent, DEFAULT_CAPACITY);
    }

    /**
     * Creates a {@code PackedEntityData} wrapping {@code parent} with a custom
     * capacity per entity set.
     *
     * @param parent   the backing {@link EntityData}; must implement
     *                 {@link ObservableEntityData}
     * @param capacity maximum number of entities per {@link PackedEntitySet}
     * @throws IllegalArgumentException if {@code parent} does not implement
     *                                  {@link ObservableEntityData}
     */
    public PackedEntityData(EntityData parent, int capacity) {
        if (!(parent instanceof ObservableEntityData)) {
            throw new IllegalArgumentException(
                    "parent EntityData must implement ObservableEntityData to support " +
                    "change notifications; found: " + parent.getClass().getName());
        }
        this.parent     = parent;
        this.observable = (ObservableEntityData) parent;
        this.capacity   = capacity;
        this.observable.addEntityComponentListener(this);
    }

    // -----------------------------------------------------------------------
    // EntityComponentListener — receives component changes from the parent

    @Override
    public void componentChange(EntityChange change) {
        for (PackedEntitySet set : entitySets) {
            if (!set.isReleased()) {
                set.entityChange(change);
            }
        }
        // Prune released sets opportunistically (not strictly necessary)
        entitySets.removeIf(PackedEntitySet::isReleased);
    }

    // -----------------------------------------------------------------------
    // EntityData — getEntities is overridden; everything else delegates

    @Override
    @SuppressWarnings("rawtypes")
    public EntitySet getEntities(Class... types) {
        PackedEntitySet set = new PackedEntitySet(parent, null, types, capacity);
        entitySets.add(set);
        return set;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public EntitySet getEntities(ComponentFilter filter, Class... types) {
        PackedEntitySet set = new PackedEntitySet(parent, filter, types, capacity);
        entitySets.add(set);
        return set;
    }

    // -----------------------------------------------------------------------
    // Pure delegation — all remaining EntityData methods

    @Override
    public EntityId createEntity() {
        return parent.createEntity();
    }

    @Override
    public void removeEntity(EntityId id) {
        parent.removeEntity(id);
    }

    @Override
    public <T extends EntityComponent> void setComponent(EntityId id, T component) {
        parent.setComponent(id, component);
    }

    @Override
    public void setComponents(EntityId id, EntityComponent... components) {
        parent.setComponents(id, components);
    }

    @Override
    public <T extends EntityComponent> boolean removeComponent(EntityId id, Class<T> type) {
        return parent.removeComponent(id, type);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void removeComponents(EntityId id, Class... types) {
        parent.removeComponents(id, types);
    }

    @Override
    public <T extends EntityComponent> T getComponent(EntityId id, Class<T> type) {
        return parent.getComponent(id, type);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Entity getEntity(EntityId id, Class... types) {
        return parent.getEntity(id, types);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public EntityId findEntity(ComponentFilter filter, Class... types) {
        return parent.findEntity(filter, types);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Set<EntityId> findEntities(ComponentFilter filter, Class... types) {
        return parent.findEntities(filter, types);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public WatchedEntity watchEntity(EntityId id, Class... types) {
        return parent.watchEntity(id, types);
    }

    @Override
    public StringIndex getStrings() {
        return parent.getStrings();
    }

    @Override
    public void close() {
        observable.removeEntityComponentListener(this);
        entitySets.clear();
        parent.close();
    }
}
