package org.tenshu.storage.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.storage.StorageService
import org.tenshu.storage.StorageStatus

/**
 * Initialises the storage service's proxy-side derivation.
 *
 * Collects the [StorageProvider]'s figures and maintains
 * [StorageService.Relations.STATUSES] as a **single-row** derived view: each
 * emission upserts the one [StorageStatus] row (keyed on
 * [StorageStatus.SINGLETON_KEY]) so the client chain — and Drive's Tier-1 gate —
 * observe the current figures reactively.
 *
 * Unlike `initPreferenceService` (a synchronous [org.tenshu.core.common.proxy.DataObserver]
 * fed by `saveData`), the source here is an external [kotlinx.coroutines.flow.Flow],
 * so the collection runs on [scope]. `StorageStatus` is a local derivation, not a
 * synced primitive — the row never leaves this device's DB.
 */
fun ExecutionContext.initStorageService(
    scope: CoroutineScope,
    provider: StorageProvider,
    capacityHolder: StorageCapacityHolder? = null,
) {
    val ref = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = context,
            type = TList.ID,
            relationId = StorageService.Relations.STATUSES,
        ),
        isEntity = false,
        elementSchemaId = StorageStatus.ID,
    )
    val singletonId = DataId.StringDataId.create(
        context = context,
        type = StorageStatus.ID,
        keyValue = StorageStatus.SINGLETON_KEY,
    )
    scope.launch {
        provider.observe().collect { figures ->
            // Feed the Tier-2 enforcement latch (op impls read this) alongside
            // the Tier-1 single-row relation (Drive's reactive read).
            capacityHolder?.figures = figures
            val status = StorageStatus.create(
                context = context,
                hasStorage = figures.hasStorage,
                usedBytes = figures.usedBytes,
                limitBytes = figures.limitBytes,
            )
            database.transaction {
                database.removeFromArray(ref, singletonId)
                database.addToArray(ref, status)
            }
        }
    }
}
