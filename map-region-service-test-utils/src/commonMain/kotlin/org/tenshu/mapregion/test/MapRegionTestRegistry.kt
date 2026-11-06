package org.tenshu.mapregion.test

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.mapregion.client.mapRegionServiceClientModule
import org.tenshu.mapregion.common.mapRegionServiceCommonModule

fun createMapRegionServiceCommonTestRegistry(): SchemaRegistry =
    createCoreTestRegistry().apply {
        registerConfig(mapRegionServiceCommonModule)
    }

fun createMapRegionTestRegistry(): SchemaRegistry =
    createMapRegionServiceCommonTestRegistry().apply {
        registerConfig(mapRegionServiceClientModule)
    }
