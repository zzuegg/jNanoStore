package io.github.zzuegg.zayes;

import com.simsilica.es.EntityChange;
import com.simsilica.es.EntityComponent;
import com.simsilica.es.EntityId;
import com.simsilica.es.WatchedEntity;

import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A {@link WatchedEntity} implementation for {@link BitEntityData} that tracks
 * changes to a single entity's components.
 */
final class BitWatchedEntity implements WatchedEntity {

    private final BitEntityData ed;
    private final EntityId id;
    @SuppressWarnings("unchecked")
    private final Class<EntityComponent>[] types;
    private final EntityComponent[] components;

    private final ConcurrentLinkedQueue<EntityChange> pendingChanges = new ConcurrentLinkedQueue<>();
    private boolean released = false;

    @SuppressWarnings("unchecked")
    BitWatchedEntity(BitEntityData ed, EntityId id,
                     Class<EntityComponent>[] types, EntityComponent[] components) {
        this.ed         = ed;
        this.id         = id;
        this.types      = types;
        this.components = components.clone();
    }

    void entityChange(EntityChange change) {
        if (!released && change.getEntityId().equals(id)) {
            // Only queue changes for component types we are watching
            for (Class<EntityComponent> type : types) {
                if (type == change.getComponentType()) {
                    pendingChanges.add(change);
                    return;
                }
            }
        }
    }

    boolean isReleased() { return released; }

    // -----------------------------------------------------------------------
    // Entity interface

    @Override
    public EntityId getId() { return id; }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends EntityComponent> T get(Class<T> type) {
        for (int i = 0; i < types.length; i++) {
            if (types[i] == type) return (T) components[i];
        }
        return null;
    }

    @Override
    public void set(EntityComponent c) {
        ed.setComponent(id, c);
    }

    @Override
    public boolean isComplete() {
        for (EntityComponent c : components) {
            if (c == null) return false;
        }
        return true;
    }

    @Override
    public EntityComponent[] getComponents() {
        return components.clone();
    }

    // -----------------------------------------------------------------------
    // WatchedEntity interface

    @Override
    public boolean hasChanges() {
        return !pendingChanges.isEmpty();
    }

    @Override
    public boolean applyChanges() {
        boolean changed = false;
        EntityChange change;
        while ((change = pendingChanges.poll()) != null) {
            @SuppressWarnings("unchecked")
            Class<EntityComponent> changedType =
                    (Class<EntityComponent>) change.getComponentType();
            for (int i = 0; i < types.length; i++) {
                if (types[i] == changedType) {
                    EntityComponent newComp = ed.getComponent(id, changedType);
                    components[i] = newComp;
                    changed = true;
                    break;
                }
            }
        }
        return changed;
    }

    @Override
    public boolean applyChanges(Set<EntityChange> changes) {
        boolean changed = applyChanges();
        if (changes != null) {
            for (EntityChange c : changes) {
                if (!c.getEntityId().equals(id)) continue;
                @SuppressWarnings("unchecked")
                Class<EntityComponent> changedType =
                        (Class<EntityComponent>) c.getComponentType();
                for (int i = 0; i < types.length; i++) {
                    if (types[i] == changedType) {
                        EntityComponent newComp = ed.getComponent(id, changedType);
                        components[i] = newComp;
                        changed = true;
                        break;
                    }
                }
            }
        }
        return changed;
    }

    @Override
    public void release() {
        released = true;
        pendingChanges.clear();
    }

    @Override
    public String toString() {
        return "BitWatchedEntity[id=" + id + "]";
    }
}
