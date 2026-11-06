package org.tenshu.health.test

import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.health.client.healthServiceClientModule

fun createHealthTestRegistry(): SchemaRegistry =
    createHealthServiceCommonTestRegistry().apply {
        registerConfig(healthServiceClientModule)
    }
