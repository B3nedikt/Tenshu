package org.tenshu.health.test

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.health.common.healthServiceCommonModule

fun createHealthServiceCommonTestRegistry(): SchemaRegistry = createCoreTestRegistry().apply {
    registerConfig(healthServiceCommonModule)
}
