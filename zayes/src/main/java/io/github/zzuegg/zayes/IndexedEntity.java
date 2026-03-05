package io.github.zzuegg.zayes;

import com.simsilica.es.Entity;
import com.simsilica.es.EntityComponent;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;

/**
 * An {@link Entity} implementation that stores all component data inside the owning
 * {@link PackedEntitySet}'s flat component arrays, keyed by
 * {@link IndexedEntityId#getIndex()}.
 *
 * <p>Write-through calls ({@link #set}) are forwarded directly to the parent
 * {@link EntityData} so that changes propagate through the normal zay-es
 * change-notification pipeline.</p>
 */
public final class IndexedEntity implements Entity {

    private final IndexedEntityId indexedId;
    /** Reference to the owning set for O(1) component reads. */
    private final PackedEntitySet owner;
    /** Reference to the parent EntityData for write-through. */
    private final EntityData ed;

    IndexedEntity(IndexedEntityId indexedId, PackedEntitySet owner, EntityData ed) {
        this.indexedId = indexedId;
        this.owner     = owner;
        this.ed        = ed;
    }

    /** Returns the dense index into the owning set's component arrays. */
    public int getIndex() { return indexedId.getIndex(); }

    /** Returns the wrapped {@link IndexedEntityId}. */
    public IndexedEntityId getIndexedId() { return indexedId; }

    // -----------------------------------------------------------------------
    // Entity interface

    @Override
    public EntityId getId() { return indexedId.getEntityId(); }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends EntityComponent> T get(Class<T> type) {
        return (T) owner.getComponentForIndex(indexedId.getIndex(), type);
    }

    @Override
    public void set(EntityComponent c) {
        int index = indexedId.getIndex();
        // Write directly into the set's store so entity.get() reflects the value
        // immediately (matching DefaultEntityData's live-read contract).
        owner.writeComponentDirect(index, c);
        // Record the local change so applyChanges() can populate changedEntities.
        owner.markLocalChange(index);
        // Suppress the listener callback for this set — the local store is already
        // current — while still propagating to the parent for authoritative storage
        // (and notification of *other* EntitySets).
        owner.suppressNotification = true;
        try {
            ed.setComponent(indexedId.getEntityId(), c);
        } finally {
            owner.suppressNotification = false;
        }
    }

    @Override
    public boolean isComplete() {
        return owner.isEntityComplete(indexedId.getIndex());
    }

    @Override
    public EntityComponent[] getComponents() {
        return owner.getComponentsForIndex(indexedId.getIndex());
    }

    // -----------------------------------------------------------------------
    // Object

    @Override
    public String toString() {
        return "IndexedEntity[" + indexedId + "]";
    }
}
