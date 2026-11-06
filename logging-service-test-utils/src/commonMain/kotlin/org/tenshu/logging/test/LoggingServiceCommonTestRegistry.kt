package org.tenshu.logging.test

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.logging.common.loggingServiceCommonModule

fun createLoggingServiceCommonTestRegistry(): SchemaRegistry = createCoreTestRegistry().apply {
    registerConfig(loggingServiceCommonModule)
}
