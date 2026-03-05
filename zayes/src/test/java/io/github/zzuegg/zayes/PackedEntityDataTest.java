package io.github.zzuegg.zayes;

import com.simsilica.es.EntityData;
import com.simsilica.es.base.DefaultEntityData;

/**
 * Runs the full {@link AbstractEntityDataTest} suite against
 * {@link PackedEntityData} wrapping {@link DefaultEntityData}.
 *
 * <p>This validates that {@link PackedEntityData} (and the {@link PackedEntitySet}
 * it returns) conform to the same specification as the reference implementation.</p>
 */
class PackedEntityDataTest extends AbstractEntityDataTest {

    @Override
    protected EntityData createEntityData() {
        return new PackedEntityData(new DefaultEntityData());
    }
}
