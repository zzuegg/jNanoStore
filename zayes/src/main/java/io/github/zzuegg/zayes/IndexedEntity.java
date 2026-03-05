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
        // Write directly into the set's store so it is immediately visible on the
        // current frame and processChange can skip the round-trip to the parent.
        owner.writeComponentDirect(indexedId.getIndex(), c);
        // Propagate to the parent for authoritative storage and change notification.
        ed.setComponent(indexedId.getEntityId(), c);
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
