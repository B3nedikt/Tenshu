package org.tenshu.health.client

import org.tenshu.core.common.TenshuClient
import org.tenshu.core.health.HealthService

val TenshuClient.healthService: HealthService
    get() = getService(HealthService.ID)
