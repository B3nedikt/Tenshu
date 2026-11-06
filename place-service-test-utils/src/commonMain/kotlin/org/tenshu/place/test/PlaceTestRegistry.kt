package org.tenshu.place.test

import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.place.client.placeServiceClientModule

fun createPlaceTestRegistry(): SchemaRegistry =
    createPlaceServiceCommonTestRegistry().apply {
        registerConfig(placeServiceClientModule)
    }
