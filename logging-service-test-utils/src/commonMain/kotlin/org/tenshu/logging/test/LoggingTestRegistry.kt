package org.tenshu.logging.test

import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.logging.client.loggingServiceClientModule

fun createLoggingTestRegistry(): SchemaRegistry =
    createLoggingServiceCommonTestRegistry().apply {
        registerConfig(loggingServiceClientModule)
    }
