package org.tenshu.logging.client

import org.tenshu.core.common.TenshuClient
import org.tenshu.logging.LoggingService

val TenshuClient.loggingService: LoggingService
    get() = getService(LoggingService.ID)
