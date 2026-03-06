package io.github.zzuegg.zayes;

import com.simsilica.es.EntityData;

/**
 * Runs the full {@link AbstractEntityDataTest} suite against
 * {@link BitEntityData}, the standalone BitKit-native EntityData implementation.
 *
 * <p>This validates that {@link BitEntityData} (and the {@link BitEntitySet}
 * it returns) conform to the same specification as the reference
 * {@link com.simsilica.es.base.DefaultEntityData} implementation.</p>
 */
class BitEntityDataTest extends AbstractEntityDataTest {

    @Override
    protected EntityData createEntityData() {
        return new BitEntityData();
    }
}
