package org.tenshu.storage.test

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.storage.common.storageServiceCommonModule

fun createStorageServiceCommonTestRegistry(): SchemaRegistry = createCoreTestRegistry().apply {
    registerConfig(storageServiceCommonModule)
}
