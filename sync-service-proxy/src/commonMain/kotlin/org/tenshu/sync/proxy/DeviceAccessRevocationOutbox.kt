package org.tenshu.sync.proxy

import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.util.HlcStamp
import org.tenshu.core.common.util.toStamp
import org.tenshu.core.entity.DataId
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.DeviceAccessRevocation
import org.tenshu.sync.common.PendingDeviceAccessRevocation
import org.tenshu.sync.common.SyncRelay

/**
 * The durable outbox of signed `POST /sync/device-access/revoke` requests this device owes the relay
 * ([PendingDeviceAccessRevocation]; `sync-entity-identity.md` §5b, phase R2a).
 *
 * Fed by `RevokeDeviceImplementation`, which signs while the key is in hand, and by `DeviceRecords.oweDeliveryOf` —
 * a peer's signed removal, delivered from here too (§5b R6d); drained by
 * `SyncScheduler.drainDeviceAccessRevocations`, **before** the tombstone drain (route first, §5b), which
 * deletes an entry only on a terminal answer ([drain]).
 *
 * ⚠ Written through the `Database` API only, never `ExecutionContext.saveData`: no observer sees it and
 * nothing about it is ever synced. Stateless over the store, so every caller sees the same rows.
 */
class DeviceAccessRevocationOutbox(private val executionContext: ExecutionContext) {

    /**
     * One owed request, copied out of the row. ⚠ The stamp is a plain [HlcStamp]: a stored row is lazily
     * backed, and a composite read after a later removal replaced the row would answer the NEW stamp.
     */
    data class Entry(
        val ownerId: String,
        val deviceId: String,
        val authority: DeviceAccessRevocation.Authority,
        val authoredAt: HlcStamp,
        val signature: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Entry && ownerId == other.ownerId && deviceId == other.deviceId &&
                authority == other.authority && authoredAt == other.authoredAt &&
                signature.contentEquals(other.signature)

        override fun hashCode(): Int = deviceId.hashCode() * 31 + authoredAt.hashCode()
    }

    /** Owes the relay this signed removal. ⚠ Replaces an earlier entry for the same device. */
    fun enqueue(
        ownerId: String,
        deviceId: String,
        authority: DeviceAccessRevocation.Authority,
        authoredAt: HybridLogicalClock,
        signature: ByteArray,
    ) {
        val database = executionContext.database
        database.transaction {
            database.removeFromArray(ref(), rowId(deviceId))
            database.addToArray(
                ref(),
                PendingDeviceAccessRevocation.create(
                    executionContext.context, ownerId, deviceId, authority, authoredAt, signature,
                ),
            )
        }
    }

    /**
     * Every request still owed — resent on each drain — in enqueue order. ⚠ A row naming an authority this build does
     * not know is skipped, never sent. ⚠ A [PendingDeviceAccessRevocation.State.REFUSED] or `SUPERSEDED` row is not
     * owed: it stays only so the app can say the relay did not apply that removal ([statuses]).
     */
    fun pending(): List<Entry> =
        rows()
            .filter { it.relayState.get() in PendingDeviceAccessRevocation.State.RESENT }
            .mapNotNull { row ->
                val authority = DeviceAccessRevocation.Authority.fromWire(row.authority.get()) ?: return@mapNotNull null
                Entry(
                    ownerId = row.ownerId.get(),
                    deviceId = row.deviceId.get(),
                    authority = authority,
                    authoredAt = row.authoredAt.toStamp(),
                    signature = row.signature.get(),
                )
            }

    /** How many requests are still owed — `ForgetThisDevice` waits on this reaching zero (a refused one never would). */
    fun count(): Int = pending().size

    /** Whether the relay refused (or superseded) the removal naming [deviceId] — not owed, but not applied either. */
    fun notApplied(deviceId: String): Boolean = rows().any {
        it.deviceId.get() == deviceId && it.relayState.get() !in PendingDeviceAccessRevocation.State.RESENT
    }

    /** Every row, owed or not: what the relay has (not) done with each removal this device made. */
    fun statuses(): List<PendingDeviceAccessRevocation> = rows()

    private fun rows(): List<PendingDeviceAccessRevocation> =
        executionContext.database.getArrayData(ref())?.toList().orEmpty().filterIsInstance<PendingDeviceAccessRevocation>()

    /** Kept for display, not resent: the same signed bytes would get the same answer. */
    private fun refused(entry: Entry, detail: String) {
        println(
            "DeviceAccessRevocationOutbox -> ${entry.authority.wire} revocation refused by the relay " +
                "($detail); kept for display — the device keeps its access until it is removed again.",
        )
        mark(entry, PendingDeviceAccessRevocation.State.REFUSED, detail)
    }

    /**
     * Records the relay's answer on [entry]'s row, keeping its bytes. ⚠⚠ Only while the row still holds the stamp that
     * was sent — a later removal of the same device replaced it, and that one is owed afresh.
     */
    private fun mark(entry: Entry, state: String, detail: String) {
        val database = executionContext.database
        database.transaction {
            val now = pending().firstOrNull { it.deviceId == entry.deviceId } ?: return@transaction
            if (now.authoredAt != entry.authoredAt) return@transaction
            val existing = database.getData(rowId(entry.deviceId)) as? PendingDeviceAccessRevocation ?: return@transaction
            // ⚠ Rewritten IN PLACE (same id, same `authoredAt` child): a remove + add would move it to the end of the
            // array, and the drain sends in enqueue order.
            database.saveData(
                PendingDeviceAccessRevocation.create(
                    executionContext.context, entry.ownerId, entry.deviceId, entry.authority,
                    existing.authoredAt,
                    entry.signature,
                    relayState = state,
                    relayDetail = detail,
                ),
            )
        }
    }

    /**
     * Drops [settled] once the relay answered terminally. ⚠⚠ Only an entry that still holds the stamp
     * that was sent: a later removal of the same device, enqueued while the request was in flight,
     * replaced it with one the relay has not seen yet, and that one must stay owed.
     */
    fun remove(settled: Entry) {
        val database = executionContext.database
        database.transaction {
            val now = pending().firstOrNull { it.deviceId == settled.deviceId } ?: return@transaction
            if (now.authoredAt != settled.authoredAt) return@transaction
            val id = rowId(settled.deviceId)
            val row = database.getData(id)
            database.removeFromArray(ref(), id)
            // ⚠⚠ The row too, and its owned HLC child, not just the membership: an orphaned row reads as
            // nothing owed and accumulates silently (`core-local-store-orphan-gc.md` G1).
            if (row != null) executionContext.deleteRetiredBlock(row)
        }
    }

    /**
     * Send every owed request [include] admits, in order, and drop each on a terminal answer. Returns false when a
     * [SyncRelay.DeviceAccessRevokeOutcome.Retry] stopped the pass — that entry and the rest stay owed.
     *
     * ⚠ `Refused` and a stale `Done` are kept for display, never resent: the same signed bytes would get the same
     * answer. ⚠⚠ `Unsupported` (a `404`) and `UpgradeRequired` (a `426`) stop the pass and stay owed, like `Retry`:
     * since R2b the `Device` tombstone no longer revokes, so this request is the only access cut.
     */
    suspend fun drain(relay: SyncRelay, include: (Entry) -> Boolean = { true }): Boolean {
        for (entry in pending().filter(include)) {
            val outcome = relay.revokeDeviceAccess(
                ownerId = entry.ownerId,
                deviceId = entry.deviceId,
                authoredAt = entry.authoredAt.encode(),
                authority = entry.authority,
                signature = entry.signature,
            )
            val state = PendingDeviceAccessRevocation.State
            when (outcome) {
                is SyncRelay.DeviceAccessRevokeOutcome.Retry -> return false
                // ⚠⚠ R2b: KEPT, not dropped. This client is at protocol 2, so its `Device` tombstone no longer
                // revokes — this request is the only access cut. A 404 is a relay not yet upgraded (no route), or one
                // that no longer holds the realm (a reset, data loss); dropping it would lose the revocation for good.
                SyncRelay.DeviceAccessRevokeOutcome.Unsupported -> {
                    mark(entry, state.RELAY_UNKNOWN, "404")
                    return false
                }
                // Protocol skew mid-rollout: an update on either side clears it, and these bytes are then accepted.
                is SyncRelay.DeviceAccessRevokeOutcome.UpgradeRequired -> {
                    mark(entry, state.NEEDS_UPDATE, outcome.reason)
                    return false
                }
                // ⚠ Kept, not resent: the same signed bytes would get the same answer. A signing or encoding mismatch
                // between versions, or a forged request — either way the user must hear that it did not apply.
                // ⚠ A `self` request repeated after it applied is answered `NotRegistered` — the device's key is
                // already gone, so it is done. Only that token counts: a `self` 401 for a bad signature arrives as
                // `Refused`, the key still on record, and must be surfaced like any other refusal.
                is SyncRelay.DeviceAccessRevokeOutcome.NotRegistered ->
                    if (entry.authority == DeviceAccessRevocation.Authority.SELF) {
                        remove(entry)
                    } else {
                        refused(entry, outcome.reason)
                    }
                is SyncRelay.DeviceAccessRevokeOutcome.Refused -> refused(entry, "${outcome.status} ${outcome.reason}")
                is SyncRelay.DeviceAccessRevokeOutcome.Done -> if (outcome.applied) {
                    remove(entry)
                } else {
                    // A correct refusal: the relay holds a later admission — the device was re-admitted since.
                    mark(entry, state.SUPERSEDED, "")
                }
            }
        }
        return true
    }

    private fun rowId(deviceId: String): DataId = DataId.StringDataId.create(
        context = executionContext.context,
        type = PendingDeviceAccessRevocation.ID,
        keyValue = deviceId,
    )

    private fun ref() = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = PendingDeviceAccessRevocation.ID,
            relationId = SyncService.Collections.PENDING_DEVICE_ACCESS_REVOCATIONS,
        ),
        isEntity = false,
        elementSchemaId = PendingDeviceAccessRevocation.ID,
    )

}
