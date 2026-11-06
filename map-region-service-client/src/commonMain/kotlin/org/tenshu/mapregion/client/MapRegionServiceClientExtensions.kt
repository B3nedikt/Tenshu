package org.tenshu.mapregion.client

import org.tenshu.core.common.TenshuClient
import org.tenshu.core.mapregion.MapRegionService

val TenshuClient.mapRegionService: MapRegionService
    get() = getService(MapRegionService.ID)
