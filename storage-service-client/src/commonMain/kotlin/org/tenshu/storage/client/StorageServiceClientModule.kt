package org.tenshu.storage.client

import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.storage.CheckCapacity
import org.tenshu.storage.StorageService
import org.tenshu.storage.client.impl.CheckCapacityRo
import org.tenshu.storage.client.impl.StorageServiceRo

val storageServiceClientModule = tModule {

    operation(
        interfaceClass = CheckCapacity::class,
        protocolSchema = CheckCapacity.Schema,
        roFactory = ::CheckCapacityRo,
    )

    service(
        interfaceClass = StorageService::class,
        serviceSchema = StorageService.Schema,
        roFactory = ::StorageServiceRo,
    )
}
