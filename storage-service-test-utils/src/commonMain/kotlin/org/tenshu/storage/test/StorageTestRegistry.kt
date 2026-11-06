package org.tenshu.storage.test

import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.storage.client.storageServiceClientModule

fun createStorageTestRegistry(): SchemaRegistry =
    createStorageServiceCommonTestRegistry().apply {
        registerConfig(storageServiceClientModule)
    }
