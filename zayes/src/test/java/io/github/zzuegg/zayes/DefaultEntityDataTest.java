package io.github.zzuegg.zayes;

import com.simsilica.es.EntityData;
import com.simsilica.es.base.DefaultEntityData;

/**
 * Runs the full {@link AbstractEntityDataTest} suite against
 * {@link DefaultEntityData}, the in-memory reference implementation
 * bundled with zay-es.
 */
class DefaultEntityDataTest extends AbstractEntityDataTest {

    @Override
    protected EntityData createEntityData() {
        return new DefaultEntityData();
    }
}
