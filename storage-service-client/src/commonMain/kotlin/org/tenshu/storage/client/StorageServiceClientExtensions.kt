package org.tenshu.storage.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.getOrNull
import org.tenshu.storage.StorageService
import org.tenshu.storage.StorageStatus

val TenshuClient.storageService: StorageService
    get() = getService(StorageService.ID)

/**
 * Tier-1 reactive read (`docs/proposals/storage-capacity-gating.md` §2): the
 * single [StorageStatus] projected from the single-row [StorageService.statuses]
 * relation. `null` until the proxy has derived a status (no provider bound /
 * not yet observed). Drive subscribes to this once and gates the share/upload
 * entry points on the cached value.
 */
fun StorageService.status(): Flow<StorageStatus?> =
    statuses.flow().map { result ->
        result.getOrNull()?.toList()?.firstOrNull()
    }
