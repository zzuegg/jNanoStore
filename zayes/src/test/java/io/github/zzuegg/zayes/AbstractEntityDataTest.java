package io.github.zzuegg.zayes;

import com.simsilica.es.Entity;
import com.simsilica.es.EntityComponent;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import com.simsilica.es.EntitySet;
import com.simsilica.es.Filters;
import com.simsilica.es.WatchedEntity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract base test suite for {@link EntityData} implementations.
 * <p>
 * Subclasses provide the concrete implementation under test by overriding
 * {@link #createEntityData()}.  Every test in this class will be run
 * automatically for each concrete subclass, making it trivial to validate
 * a new {@code EntityData} implementation against the same specification.
 * </p>
 *
 * <pre>
 * class MyEntityDataTest extends AbstractEntityDataTest {
 *     {@literal @}Override
 *     protected EntityData createEntityData() { return new MyEntityData(); }
 * }
 * </pre>
 */
public abstract class AbstractEntityDataTest {

    // -------------------------------------------------------------------------
    // Test components

    /** Simple position component with immutable value semantics. */
    static final class Position implements EntityComponent {
        final float x;
        final float y;
        final float z;

        Position(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Position p)) return false;
            return Float.compare(p.x, x) == 0
                    && Float.compare(p.y, y) == 0
                    && Float.compare(p.z, z) == 0;
        }

        @Override
        public int hashCode() {
            return Float.hashCode(x) * 31 * 31 + Float.hashCode(y) * 31 + Float.hashCode(z);
        }

        @Override
        public String toString() {
            return "Position[" + x + ", " + y + ", " + z + "]";
        }
    }

    /** Simple health component. */
    static final class Health implements EntityComponent {
        final int value;

        Health(int value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Health h)) return false;
            return h.value == value;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(value);
        }

        @Override
        public String toString() {
            return "Health[" + value + "]";
        }
    }

    /** Simple name/label component. */
    /** Simple tag/marker component (no data fields). */
    record Tag() implements EntityComponent {}

    // -------------------------------------------------------------------------
    // Lifecycle

    /** The instance under test, created fresh before every test method. */
    protected EntityData ed;

    /**
     * Factory method: subclasses must return a fresh, empty {@link EntityData}
     * instance.
     */
    protected abstract EntityData createEntityData();

    @BeforeEach
    void setUp() {
        ed = createEntityData();
    }

    @AfterEach
    void tearDown() {
        ed.close();
    }

    // =========================================================================
    // Entity CRUD
    // =========================================================================

    @Test
    void createEntity_returnsUniqueIds() {
        EntityId a = ed.createEntity();
        EntityId b = ed.createEntity();
        EntityId c = ed.createEntity();

        assertNotNull(a);
        assertNotNull(b);
        assertNotNull(c);
        assertNotEquals(a, b);
        assertNotEquals(b, c);
        assertNotEquals(a, c);
    }

    @Test
    void setAndGetSingleComponent() {
        EntityId id = ed.createEntity();
        ed.setComponent(id, new Position(1, 2, 3));

        Position p = ed.getComponent(id, Position.class);

        assertNotNull(p);
        assertEquals(1f, p.x);
        assertEquals(2f, p.y);
        assertEquals(3f, p.z);
    }

    @Test
    void setMultipleComponentsOnSameEntity() {
        EntityId id = ed.createEntity();
        ed.setComponent(id, new Position(10, 20, 30));
        ed.setComponent(id, new Health(100));

        assertEquals(new Position(10, 20, 30), ed.getComponent(id, Position.class));
        assertEquals(new Health(100), ed.getComponent(id, Health.class));
    }

    @Test
    void setComponents_varargs() {
        EntityId id = ed.createEntity();
        ed.setComponents(id, new Position(5, 6, 7), new Health(50));

        assertEquals(new Position(5, 6, 7), ed.getComponent(id, Position.class));
        assertEquals(new Health(50), ed.getComponent(id, Health.class));
    }

    @Test
    void updateComponent_overwritesPreviousValue() {
        EntityId id = ed.createEntity();
        ed.setComponent(id, new Position(1, 1, 1));
        ed.setComponent(id, new Position(9, 9, 9));

        Position p = ed.getComponent(id, Position.class);
        assertEquals(new Position(9, 9, 9), p);
    }

    @Test
    void removeComponent_removesItFromEntity() {
        EntityId id = ed.createEntity();
        ed.setComponent(id, new Position(1, 2, 3));
        ed.setComponent(id, new Health(80));

        boolean removed = ed.removeComponent(id, Position.class);

        assertTrue(removed);
        assertNull(ed.getComponent(id, Position.class));
        assertNotNull(ed.getComponent(id, Health.class));
    }

    @Test
    void removeComponent_returnsFalseIfAbsent() {
        EntityId id = ed.createEntity();

        boolean removed = ed.removeComponent(id, Position.class);

        assertFalse(removed);
    }

    @Test
    void removeComponents_varargs() {
        EntityId id = ed.createEntity();
        ed.setComponents(id, new Position(1, 2, 3), new Health(50));

        ed.removeComponents(id, Position.class);

        assertNull(ed.getComponent(id, Position.class));
        assertNotNull(ed.getComponent(id, Health.class));
    }

    @Test
    void removeEntity_removesAllComponents() {
        EntityId id = ed.createEntity();
        ed.setComponents(id, new Position(0, 0, 0), new Health(100));

        ed.removeEntity(id);

        assertNull(ed.getComponent(id, Position.class));
        assertNull(ed.getComponent(id, Health.class));
    }

    @Test
    void getComponent_returnsNullForMissingComponent() {
        EntityId id = ed.createEntity();

        assertNull(ed.getComponent(id, Position.class));
    }

    @Test
    void getEntity_returnsEntityWithRequestedComponents() {
        EntityId id = ed.createEntity();
        ed.setComponents(id, new Position(3, 4, 5), new Health(70));

        Entity entity = ed.getEntity(id, Position.class, Health.class);

        assertNotNull(entity);
        assertEquals(id, entity.getId());
        assertEquals(new Position(3, 4, 5), entity.get(Position.class));
        assertEquals(new Health(70), entity.get(Health.class));
    }

    @Test
    void getEntity_isCompleteWhenAllComponentsPresent() {
        EntityId id = ed.createEntity();
        ed.setComponents(id, new Position(1, 2, 3), new Health(50));

        Entity entity = ed.getEntity(id, Position.class, Health.class);

        assertTrue(entity.isComplete());
    }

    // =========================================================================
    // EntitySet – basic membership
    // =========================================================================

    @Test
    void getEntities_emptyWhenNoEntitiesMatch() {
        EntitySet set = ed.getEntities(Position.class);
        try {
            assertTrue(set.isEmpty());
        } finally {
            set.release();
        }
    }

    @Test
    void getEntities_containsEntityWithMatchingComponent() {
        EntityId id = ed.createEntity();
        ed.setComponent(id, new Position(1, 2, 3));

        EntitySet set = ed.getEntities(Position.class);
        try {
            assertEquals(1, set.size());
            assertTrue(set.containsId(id));
        } finally {
            set.release();
        }
    }

    @Test
    void getEntities_doesNotContainEntityMissingComponent() {
        EntityId withPos = ed.createEntity();
        EntityId withoutPos = ed.createEntity();
        ed.setComponent(withPos, new Position(1, 2, 3));
        ed.setComponent(withoutPos, new Health(50));

        EntitySet set = ed.getEntities(Position.class);
        try {
            assertEquals(1, set.size());
            assertTrue(set.containsId(withPos));
            assertFalse(set.containsId(withoutPos));
        } finally {
            set.release();
        }
    }

    @Test
    void getEntities_multipleComponents_requiresAll() {
        EntityId both = ed.createEntity();
        EntityId posOnly = ed.createEntity();
        EntityId healthOnly = ed.createEntity();

        ed.setComponents(both, new Position(0, 0, 0), new Health(100));
        ed.setComponent(posOnly, new Position(1, 1, 1));
        ed.setComponent(healthOnly, new Health(50));

        EntitySet set = ed.getEntities(Position.class, Health.class);
        try {
            assertEquals(1, set.size());
            assertTrue(set.containsId(both));
            assertFalse(set.containsId(posOnly));
            assertFalse(set.containsId(healthOnly));
        } finally {
            set.release();
        }
    }

    @Test
    void getEntities_multipleMatchingEntities() {
        EntityId a = ed.createEntity();
        EntityId b = ed.createEntity();
        EntityId c = ed.createEntity();
        ed.setComponent(a, new Position(1, 0, 0));
        ed.setComponent(b, new Position(2, 0, 0));
        ed.setComponent(c, new Health(10)); // no Position

        EntitySet set = ed.getEntities(Position.class);
        try {
            assertEquals(2, set.size());
            assertTrue(set.containsId(a));
            assertTrue(set.containsId(b));
            assertFalse(set.containsId(c));
        } finally {
            set.release();
        }
    }

    // =========================================================================
    // EntitySet – applyChanges / live updates
    // =========================================================================

    @Test
    void entitySet_addedEntities_detectedOnApplyChanges() {
        EntitySet set = ed.getEntities(Position.class);
        try {
            // baseline – empty
            set.applyChanges();

            EntityId id = ed.createEntity();
            ed.setComponent(id, new Position(5, 5, 5));

            boolean changed = set.applyChanges();

            assertTrue(changed);
            assertTrue(set.hasChanges());
            assertFalse(set.getAddedEntities().isEmpty());
            assertTrue(set.containsId(id));
        } finally {
            set.release();
        }
    }

    @Test
    void entitySet_removedEntities_detectedOnApplyChanges() {
        EntityId id = ed.createEntity();
        ed.setComponent(id, new Position(1, 2, 3));

        EntitySet set = ed.getEntities(Position.class);
        try {
            set.applyChanges();
            assertTrue(set.containsId(id));

            ed.removeComponent(id, Position.class);

            boolean changed = set.applyChanges();

            assertTrue(changed);
            assertFalse(set.containsId(id));
            assertFalse(set.getRemovedEntities().isEmpty());
        } finally {
            set.release();
        }
    }

    @Test
    void entitySet_changedEntities_detectedOnApplyChanges() {
        EntityId id = ed.createEntity();
        ed.setComponent(id, new Position(1, 2, 3));

        EntitySet set = ed.getEntities(Position.class);
        try {
            set.applyChanges();

            ed.setComponent(id, new Position(9, 9, 9));

            boolean changed = set.applyChanges();

            assertTrue(changed);
            assertFalse(set.getChangedEntities().isEmpty());

            Entity entity = set.getEntity(id);
            assertNotNull(entity);
            assertEquals(new Position(9, 9, 9), entity.get(Position.class));
        } finally {
            set.release();
        }
    }

    @Test
    void entitySet_clearChangeSets_clearsAddedChangedRemoved() {
        EntityId id = ed.createEntity();
        ed.setComponent(id, new Position(0, 0, 0));

        EntitySet set = ed.getEntities(Position.class);
        try {
            set.applyChanges();

            ed.setComponent(id, new Position(1, 1, 1));
            set.applyChanges();

            assertTrue(set.hasChanges());
            set.clearChangeSets();

            assertFalse(set.hasChanges());
            assertTrue(set.getAddedEntities().isEmpty());
            assertTrue(set.getChangedEntities().isEmpty());
            assertTrue(set.getRemovedEntities().isEmpty());
        } finally {
            set.release();
        }
    }

    @Test
    void entitySet_getEntityIds_returnsAllIds() {
        EntityId a = ed.createEntity();
        EntityId b = ed.createEntity();
        ed.setComponent(a, new Position(1, 0, 0));
        ed.setComponent(b, new Position(2, 0, 0));

        EntitySet set = ed.getEntities(Position.class);
        try {
            Set<EntityId> ids = set.getEntityIds();
            assertEquals(2, ids.size());
            assertTrue(ids.contains(a));
            assertTrue(ids.contains(b));
        } finally {
            set.release();
        }
    }

    @Test
    void entitySet_hasType_returnsCorrectAnswer() {
        EntitySet set = ed.getEntities(Position.class, Health.class);
        try {
            assertTrue(set.hasType(Position.class));
            assertTrue(set.hasType(Health.class));
            assertFalse(set.hasType(Tag.class));
        } finally {
            set.release();
        }
    }

    @Test
    void entitySet_release_stopsUpdates() {
        EntitySet set = ed.getEntities(Position.class);
        set.applyChanges();
        set.release();

        // Adding an entity after release should not be observable via the set
        EntityId id = ed.createEntity();
        ed.setComponent(id, new Position(1, 1, 1));

        // We just verify the set doesn't throw; it should not reflect the new entity.
        assertFalse(set.containsId(id));
    }

    // =========================================================================
    // EntitySet – filtering
    // =========================================================================

    @Test
    void getEntitiesWithFilter_fieldEquals_matchesExactValue() {
        EntityId match = ed.createEntity();
        EntityId noMatch = ed.createEntity();

        ed.setComponent(match, new Health(100));
        ed.setComponent(noMatch, new Health(50));

        EntitySet set = ed.getEntities(Filters.fieldEquals(Health.class, "value", 100), Health.class);
        try {
            assertEquals(1, set.size());
            assertTrue(set.containsId(match));
            assertFalse(set.containsId(noMatch));
        } finally {
            set.release();
        }
    }

    @Test
    void getEntitiesWithFilter_noMatches() {
        EntityId id = ed.createEntity();
        ed.setComponent(id, new Health(50));

        EntitySet set = ed.getEntities(Filters.fieldEquals(Health.class, "value", 999), Health.class);
        try {
            assertTrue(set.isEmpty());
        } finally {
            set.release();
        }
    }

    @Test
    void getEntitiesWithFilter_intField_matchesExactValue() {
        EntityId hero    = ed.createEntity();
        EntityId villain = ed.createEntity();

        ed.setComponent(hero,    new Health(100));
        ed.setComponent(villain, new Health(50));

        EntitySet set = ed.getEntities(Filters.fieldEquals(Health.class, "value", 100), Health.class);
        try {
            assertEquals(1, set.size());
            assertTrue(set.containsId(hero));
        } finally {
            set.release();
        }
    }

    // =========================================================================
    // findEntity / findEntities
    // =========================================================================

    @Test
    void findEntity_returnsMatchingEntityId() {
        EntityId id = ed.createEntity();
        ed.setComponent(id, new Health(42));

        EntityId found = ed.findEntity(Filters.fieldEquals(Health.class, "value", 42), Health.class);

        assertEquals(id, found);
    }

    @Test
    void findEntity_returnsNullWhenNoMatch() {
        EntityId id = ed.createEntity();
        ed.setComponent(id, new Health(10));

        EntityId found = ed.findEntity(Filters.fieldEquals(Health.class, "value", 999), Health.class);

        assertNull(found);
    }

    @Test
    void findEntities_returnsAllMatching() {
        EntityId a = ed.createEntity();
        EntityId b = ed.createEntity();
        EntityId c = ed.createEntity();

        ed.setComponent(a, new Health(100));
        ed.setComponent(b, new Health(100));
        ed.setComponent(c, new Health(50));

        Set<EntityId> found = ed.findEntities(Filters.fieldEquals(Health.class, "value", 100), Health.class);

        assertEquals(2, found.size());
        assertTrue(found.contains(a));
        assertTrue(found.contains(b));
        assertFalse(found.contains(c));
    }

    @Test
    void findEntities_emptySetWhenNoMatch() {
        EntityId id = ed.createEntity();
        ed.setComponent(id, new Health(1));

        Set<EntityId> found = ed.findEntities(Filters.fieldEquals(Health.class, "value", 999), Health.class);

        assertNotNull(found);
        assertTrue(found.isEmpty());
    }

    // =========================================================================
    // WatchedEntity
    // =========================================================================

    @Test
    void watchEntity_initiallyHasComponentValues() {
        EntityId id = ed.createEntity();
        ed.setComponents(id, new Position(1, 2, 3), new Health(80));

        WatchedEntity watched = ed.watchEntity(id, Position.class, Health.class);
        try {
            assertEquals(id, watched.getId());
            assertEquals(new Position(1, 2, 3), watched.get(Position.class));
            assertEquals(new Health(80), watched.get(Health.class));
        } finally {
            watched.release();
        }
    }

    @Test
    void watchEntity_applyChanges_reflectsUpdate() {
        EntityId id = ed.createEntity();
        ed.setComponent(id, new Position(0, 0, 0));

        WatchedEntity watched = ed.watchEntity(id, Position.class);
        try {
            watched.applyChanges();

            ed.setComponent(id, new Position(7, 8, 9));

            boolean changed = watched.applyChanges();

            assertTrue(changed);
            assertEquals(new Position(7, 8, 9), watched.get(Position.class));
        } finally {
            watched.release();
        }
    }

    @Test
    void watchEntity_hasChanges_trueAfterComponentChange() {
        EntityId id = ed.createEntity();
        ed.setComponent(id, new Health(50));

        WatchedEntity watched = ed.watchEntity(id, Health.class);
        try {
            watched.applyChanges();

            ed.setComponent(id, new Health(25));

            assertTrue(watched.hasChanges());
        } finally {
            watched.release();
        }
    }

    @Test
    void watchEntity_release_stopsUpdates() {
        EntityId id = ed.createEntity();
        ed.setComponent(id, new Health(100));

        WatchedEntity watched = ed.watchEntity(id, Health.class);
        watched.applyChanges();
        watched.release();

        ed.setComponent(id, new Health(1));

        assertFalse(watched.hasChanges());
    }

    // =========================================================================
    // Large-scale / stress scenarios
    // =========================================================================

    @Test
    void manyEntities_allRetrievable() {
        final int count = 500;
        EntityId[] ids = new EntityId[count];
        for (int i = 0; i < count; i++) {
            ids[i] = ed.createEntity();
            ed.setComponent(ids[i], new Health(i));
        }

        for (int i = 0; i < count; i++) {
            Health h = ed.getComponent(ids[i], Health.class);
            assertNotNull(h, "Missing health for entity " + i);
            assertEquals(i, h.value, "Wrong health for entity " + i);
        }
    }

    @Test
    void entitySet_manyAddsAndRemovals_countIsConsistent() {
        EntitySet set = ed.getEntities(Tag.class);
        try {
            set.applyChanges();
            assertEquals(0, set.size());

            final int count = 100;
            EntityId[] ids = new EntityId[count];
            for (int i = 0; i < count; i++) {
                ids[i] = ed.createEntity();
                ed.setComponent(ids[i], new Tag());
            }
            set.applyChanges();
            assertEquals(count, set.size());

            for (int i = 0; i < count / 2; i++) {
                ed.removeComponent(ids[i], Tag.class);
            }
            set.applyChanges();
            assertEquals(count / 2, set.size());
        } finally {
            set.release();
        }
    }

    @Test
    void entitySet_resetFilter_changesVisibleEntities() {
        EntityId lowHealth = ed.createEntity();
        EntityId highHealth = ed.createEntity();
        ed.setComponent(lowHealth, new Health(10));
        ed.setComponent(highHealth, new Health(90));

        EntitySet set = ed.getEntities(Filters.fieldEquals(Health.class, "value", 10), Health.class);
        try {
            set.applyChanges();
            assertEquals(1, set.size());
            assertTrue(set.containsId(lowHealth));

            set.resetFilter(Filters.fieldEquals(Health.class, "value", 90));
            set.applyChanges();

            assertEquals(1, set.size());
            assertTrue(set.containsId(highHealth));
            assertFalse(set.containsId(lowHealth));
        } finally {
            set.release();
        }
    }

    // =========================================================================
    // StringIndex (basic smoke-test; implementations may return null)
    // =========================================================================

    @Test
    void getStrings_doesNotThrow() {
        // Some implementations may return null; this test simply verifies no exception.
        assertDoesNotThrow(() -> ed.getStrings());
    }

    // =========================================================================
    // EntityId equality contract
    // =========================================================================

    @Test
    void entityId_equalityAndHashCode() {
        EntityId a = ed.createEntity();
        // An EntityId should equal another EntityId wrapping the same long
        EntityId copy = new EntityId(a.getId());
        assertEquals(a, copy);
        assertEquals(a.hashCode(), copy.hashCode());
        assertNotEquals(EntityId.NULL_ID, a);
    }
}
