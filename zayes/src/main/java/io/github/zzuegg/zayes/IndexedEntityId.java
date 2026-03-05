package io.github.zzuegg.zayes;

import com.simsilica.es.EntityId;

/**
 * Pairs a zay-es {@link EntityId} with a dense {@code int} index that is used as
 * the row key in the {@link PackedEntitySet}'s {@code PackedDataStore}.
 *
 * <p>{@link EntityId} is {@code final} and cannot be subclassed, so this class
 * acts as a companion rather than a subtype.</p>
 */
public final class IndexedEntityId {

    private final EntityId entityId;
    private final int index;

    IndexedEntityId(EntityId entityId, int index) {
        this.entityId = entityId;
        this.index    = index;
    }

    /** Returns the original zay-es entity id. */
    public EntityId getEntityId() { return entityId; }

    /**
     * Returns the dense, non-negative row index that this entity occupies in its
     * owning {@link PackedEntitySet}'s {@code PackedDataStore}.
     */
    public int getIndex() { return index; }

    @Override
    public String toString() {
        return "IndexedEntityId[id=" + entityId + ", index=" + index + "]";
    }
}
