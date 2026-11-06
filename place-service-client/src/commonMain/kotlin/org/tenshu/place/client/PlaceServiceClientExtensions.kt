package org.tenshu.place.client

import org.tenshu.core.common.TenshuClient
import org.tenshu.core.place.PlaceService

val TenshuClient.placeService: PlaceService
    get() = getService(PlaceService.ID)
