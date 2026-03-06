package io.github.zzuegg.zayes;

import com.simsilica.es.EntityData;

/**
 * Runs the full {@link AbstractEntityDataTest} suite against {@link BitKitEntityData},
 * the ground-up BitKit-native zay-es implementation.
 *
 * <p>All tests must pass, confirming that {@link BitKitEntityData} is a drop-in
 * replacement for {@link com.simsilica.es.base.DefaultEntityData}.</p>
 */
class BitKitEntityDataTest extends AbstractEntityDataTest {

    @Override
    protected EntityData createEntityData() {
        return new BitKitEntityData();
    }
}
