package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.sync.SyncHealthStatus
import org.tenshu.sync.SyncService

/**
 * Maintains [SyncService.Relations.SYNC_HEALTH] as a **single-row** derived view
 * of the [SyncHealthHolder]'s figures (`docs/proposals/drive-connectivity-service.md`).
 * Each figures change upserts the one [SyncHealthStatus] row (keyed on
 * [SyncHealthStatus.SINGLETON_KEY]) so the client chain — and Drive's banner —
 * observe the current health reactively.
 *
 * Structurally identical to `initStorageService`: an external flow ([holder]'s
 * `StateFlow`) collected on [scope], remove-then-add upsert inside a transaction.
 * The row is a local derivation, never synced off-device.
 */
fun ExecutionContext.initSyncHealth(
    scope: CoroutineScope,
    holder: SyncHealthHolder,
) {
    val ref = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = context,
            type = TList.ID,
            relationId = SyncService.Relations.SYNC_HEALTH,
        ),
        isEntity = false,
        elementSchemaId = SyncHealthStatus.ID,
    )
    val singletonId = DataId.StringDataId.create(
        context = context,
        type = SyncHealthStatus.ID,
        keyValue = SyncHealthStatus.SINGLETON_KEY,
    )
    scope.launch {
        holder.observe().collect { figures ->
          guardDerivedViewTick(SyncService.Relations.SYNC_HEALTH) {
            val status = SyncHealthStatus.create(
                context = context,
                // Entitlement-blocked takes priority over plain online/offline: the relay is
                // reachable but rejecting this device's writes (402), so the banner must say
                // "needs attention on this device", not "Offline". (Track A.)
                connectivity = when {
                    // Locked outranks everything: with no derived keys the loop never reaches the
                    // relay, so online/offline is meaningless, and the null owner also zeroes the
                    // storage figures — leaving Drive to mis-read the sealed realm as an
                    // unsubscribed one unless it is told the real reason.
                    figures.locked -> SyncHealthStatus.CONNECTIVITY_LOCKED
                    // Above entitlement, and above online/offline: the relay refuses this device's
                    // identity, so its writes never reach the entitlement check at all, and a `401`
                    // means the relay *answered* (see CONNECTIVITY_DEVICE_UNRECOGNIZED).
                    figures.deviceUnrecognized -> SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED
                    figures.entitlementBlocked -> SyncHealthStatus.CONNECTIVITY_NEEDS_ATTENTION
                    // Below the two identity/credential states — both refuse writes before the
                    // quota check is ever reached, so naming fullness there would report a
                    // consequence rather than the cause — but ABOVE `deferred` and online/offline:
                    // a full realm is why nothing is uploading, and "Waiting for Wi-Fi" or
                    // "Syncing…" over the top of it is the indefinite non-answer this replaces.
                    figures.storageRejected -> SyncHealthStatus.CONNECTIVITY_STORAGE_FULL
                    // Deferred (WifiOnly on metered, backlog waiting) outranks online/offline: the
                    // relay may be reachable, but the user's own policy is holding the push, so the
                    // banner must say "Waiting for Wi-Fi", not "Syncing…"/"Offline" (sub-slice A).
                    figures.deferred -> SyncHealthStatus.CONNECTIVITY_DEFERRED
                    // Below `deferred` (Wi-Fi-only holds the content, so no PUT is failing) and
                    // above online/offline: the relay answers, but refuses content (§5.7).
                    figures.contentRetrying -> SyncHealthStatus.CONNECTIVITY_RELAY_RETRYING
                    figures.online -> SyncHealthStatus.CONNECTIVITY_ONLINE
                    else -> SyncHealthStatus.CONNECTIVITY_OFFLINE
                },
                isSyncing = figures.isSyncing,
                pendingBlockCount = figures.pendingBlockCount.toLong(),
                lastSyncedAtMillis = figures.lastSyncedAtMillis,
            )
            database.transaction {
                database.removeFromArray(ref, singletonId)
                database.addToArray(ref, status)
            }
          }
        }
    }
}
