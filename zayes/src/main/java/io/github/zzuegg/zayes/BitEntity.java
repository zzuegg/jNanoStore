package io.github.zzuegg.zayes;

import com.simsilica.es.Entity;
import com.simsilica.es.EntityComponent;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;

/**
 * A lightweight {@link Entity} implementation for {@link BitEntityData}.
 *
 * <p>Used in two contexts:
 * <ol>
 *   <li>As a snapshot entity returned by {@link BitEntityData#getEntity} — holds
 *       components in a plain array, propagates {@link #set} back to the EntityData.</li>
 *   <li>As an indexed entity within a {@link BitEntitySet} — stores components in
 *       the set's packed {@link io.github.zzuegg.jbinary.DataStore} via dense row index.</li>
 * </ol>
 */
public final class BitEntity implements Entity {

    private final EntityId id;
    private final EntityData ed;
    @SuppressWarnings("rawtypes")
    private final Class[] types;
    private final EntityComponent[] components;

    // Index-mode fields (used when part of a BitEntitySet)
    private final int index;
    private final BitEntitySet ownerSet;

    /**
     * Snapshot constructor — used by {@link BitEntityData#getEntity}.
     */
    BitEntity(EntityId id, EntityData ed, @SuppressWarnings("rawtypes") Class[] types,
              EntityComponent[] components) {
        this.id         = id;
        this.ed         = ed;
        this.types      = types;
        this.components = components;
        this.index      = -1;
        this.ownerSet   = null;
    }

    /**
     * Indexed constructor — used within a {@link BitEntitySet}.
     */
    BitEntity(EntityId id, BitEntityData ed, BitEntitySet ownerSet, int index) {
        this.id         = id;
        this.ed         = ed;
        this.types      = null;
        this.components = null;
        this.index      = index;
        this.ownerSet   = ownerSet;
    }

    /** Returns the dense index into the owning set's component arrays, or -1 if snapshot. */
    public int getIndex() { return index; }

    @Override
    public EntityId getId() { return id; }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends EntityComponent> T get(Class<T> type) {
        if (ownerSet != null) {
            return (T) ownerSet.getComponentForIndex(index, type);
        }
        // Snapshot mode
        for (int i = 0; i < types.length; i++) {
            if (types[i] == type) return (T) components[i];
        }
        return null;
    }

    @Override
    public void set(EntityComponent c) {
        if (ownerSet != null) {
            ownerSet.writeComponentDirect(index, c);
        }
        ed.setComponent(id, c);
    }

    @Override
    public boolean isComplete() {
        if (ownerSet != null) {
            return ownerSet.isEntityComplete(index);
        }
        for (EntityComponent c : components) {
            if (c == null) return false;
        }
        return true;
    }

    @Override
    public EntityComponent[] getComponents() {
        if (ownerSet != null) {
            return ownerSet.getComponentsForIndex(index);
        }
        return components.clone();
    }

    @Override
    public String toString() {
        return "BitEntity[id=" + id + (index >= 0 ? ", index=" + index : "") + "]";
    }
}
