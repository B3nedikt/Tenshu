package org.tenshu.place.test

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.place.common.placeServiceCommonModule

fun createPlaceServiceCommonTestRegistry(): SchemaRegistry = createCoreTestRegistry().apply {
    registerConfig(placeServiceCommonModule)
}
