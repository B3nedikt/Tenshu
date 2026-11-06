package org.tenshu.storage.proxy

import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.storage.CheckCapacity
import org.tenshu.storage.proxy.impl.CheckCapacityImplementation

/**
 * Maps storage-service operation ids to their proxy implementations
 * (`docs/proposals/storage-capacity-gating.md` §6 Phase 4). [storageCapacityHolder]
 * is the same latch [initStorageService] feeds; the pre-flight reads it.
 */
fun storageServiceProxyImplementationFactory(
    id: String,
    storageCapacityHolder: StorageCapacityHolder? = null,
): OperationImplementation? = when (id) {
    CheckCapacity.ID -> CheckCapacityImplementation(storageCapacityHolder)
    else -> null
}
