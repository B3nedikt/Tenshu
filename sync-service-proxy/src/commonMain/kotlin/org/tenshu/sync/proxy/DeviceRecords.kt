package org.tenshu.sync.proxy

import org.tenshu.core.common.util.toStamp
import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.util.decodeHlcStamp
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.Device
import org.tenshu.identity.DeviceRecord
import org.tenshu.identity.IdentityService
import org.tenshu.sync.common.DeviceAccessRevocation
import org.tenshu.sync.common.DeviceRecordVerifier
import org.tenshu.sync.common.SyncManager

/**
 * This device's view of the realm's signed [DeviceRecord]s (`sync-entity-identity.md` §5b R6a): which
 * devices are **removed**, by the newest valid record, through `DeviceRecordVerifier`.
 *
 * ⚠ Read on demand over the relation. The set is a handful of rows in a realm's whole life, and
 * reading it fresh means a record ingested a moment ago counts at once, with no cache to go stale.
 * ⚠ Personal realm only in R6a: the rows live in the personal store and verify against the personal
 * realm's id. Shared realms use the same verifier with their own id (RO-3).
 */
internal class DeviceRecords(
    private val executionContext: ExecutionContext,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
) {

    /** The device ids the personal realm's valid records say are removed. Empty with no realm. */
    fun removedDevices(): Set<String> {
        val ownerId = syncManager.currentOwnerId ?: return emptySet()
        return DeviceRecordVerifier.removedDevices(ownerId, records(), cryptoManager::verifyEd25519)
    }

    fun isRemoved(deviceId: String): Boolean = deviceId in removedDevices()

    /**
     * The removal record that decides each currently removed device, as stored — what Manage devices
     * lists under "Removed" (§5b R6c). ⚠ The `Device` row is gone by then, so this is the only place
     * the device still appears.
     */
    fun removals(): List<DeviceRecord> {
        val ownerId = syncManager.currentOwnerId ?: return emptyList()
        // ⚠ `Record` has identity equality, which is what maps a deciding record back to its row.
        val rowsByRecord = executionContext.database.getArrayData(ref())?.toList().orEmpty()
            .mapNotNull { row -> toRecord(row)?.let { it to (row as DeviceRecord) } }
            .toMap()
        return DeviceRecordVerifier.decidingRecords(ownerId, rowsByRecord.keys, cryptoManager::verifyEd25519)
            .values
            .filter { it.kind == DeviceRecordVerifier.Kind.REMOVAL }
            .mapNotNull { rowsByRecord[it] }
    }

    /**
     * Owe the relay the removal of [deviceId] that a **peer** signed — the same bytes, from this device's own outbox
     * (§5b R6d). The record carries everything the credential-less route needs, so any device holding it can deliver
     * it: the author's own request may never have left (its outbox purged by a Forget, its device lost or offline for
     * good, the relay unreachable then), and nothing else would ever cut that device's access.
     *
     * ⚠⚠ Safe to send from every device, any number of times — and so safe against a hostile sender too: the relay
     * applies a removal only when it is newer than the admission on record, so a repeat is a no-op and a replay can
     * never undo a later re-admission.
     * ⚠ Only the record that currently decides [deviceId] (valid and newest), and only once per stamp: a row already in
     * the outbox for that stamp — owed, refused or superseded — is left as it is.
     * Returns whether a request was queued.
     */
    fun oweDeliveryOf(deviceId: String): Boolean {
        val ownerId = syncManager.currentOwnerId ?: return false
        val row = removals().firstOrNull { it.deviceId.get() == deviceId } ?: return false
        val authority = DeviceAccessRevocation.Authority.fromWire(row.authority.get()) ?: return false
        val stamp = decodeHlcStamp(row.authoredAt.get()) ?: return false
        val outbox = DeviceAccessRevocationOutbox(executionContext)
        if (outbox.statuses().any { it.deviceId.get() == deviceId && it.authoredAt.toStamp() == stamp }) return false
        outbox.enqueue(
            ownerId = ownerId,
            deviceId = deviceId,
            authority = authority,
            authoredAt = org.tenshu.core.time.HybridLogicalClock.create(
                executionContext.context,
                physicalTime = stamp.physicalTime,
                logicalCounter = stamp.logicalCounter,
                deviceId = stamp.deviceId,
            ),
            signature = row.signature.get(),
        )
        return true
    }

    /** Record a removal this device just signed (the same bytes it queued for the relay route). */
    fun saveRemoval(
        deviceId: String,
        authority: DeviceAccessRevocation.Authority,
        authoredAt: String,
        signerPublicKey: ByteArray,
        signature: ByteArray,
    ) {
        executionContext.saveData(
            DeviceRecord.create(
                context = executionContext.context,
                kind = DeviceRecord.Kind.REMOVAL,
                deviceId = deviceId,
                authority = authority.wire,
                authoredAt = authoredAt,
                signerPublicKey = signerPublicKey,
                signature = signature,
            ),
        )
    }

    /**
     * Sign and record an A1 **re-admission** of [deviceId] at [authoredAt] (`HlcStamp.encode()`, the
     * same stamp the relay registered it with) — §5b R6b. ⚠ A1 only, by construction: the verifier
     * ignores any other signer. Harmless for a device that was never removed (its newest record is
     * then this one, and it reads as admitted).
     */
    fun saveReadmission(deviceId: String, authoredAt: String, masterKeyPair: SyncCryptoManager.KeyPair) {
        val ownerId = syncManager.currentOwnerId ?: return
        val stamp = decodeHlcStamp(authoredAt) ?: return
        executionContext.saveData(
            DeviceRecord.create(
                context = executionContext.context,
                kind = DeviceRecord.Kind.READMISSION,
                deviceId = deviceId,
                authority = DeviceAccessRevocation.Authority.MASTER.wire,
                authoredAt = authoredAt,
                signerPublicKey = masterKeyPair.publicKey,
                signature = cryptoManager.signEd25519(
                    masterKeyPair.privateKey,
                    org.tenshu.sync.common.DeviceReadmission.message(ownerId, deviceId, stamp),
                ),
            ),
        )
    }

    /**
     * The `onReadmitted` for `RealmRegistrar.reregisterThisDevice`: this device, re-admitted by A1 — then its own
     * `Device` row re-sent **after** the record. ⚠⚠ A peer that held the removal dropped any earlier copy of the
     * row at ingest and will not replay it; without this re-send the device stays missing from that peer's list
     * until it next rewrites its row. (No row locally ⇒ `reconcileOwnDeviceRow` writes one at the next start.)
     */
    fun readmitSelf(masterKeyPair: SyncCryptoManager.KeyPair): (String) -> Unit = { stamp ->
        saveReadmission(syncManager.currentDeviceId, stamp, masterKeyPair)
        executionContext.database
            .getData(DataId.StringDataId.create(executionContext.context, Device.ID, syncManager.currentDeviceId))
            ?.let { executionContext.saveData(it) }
    }

    /**
     * Re-save every **valid** record row, so each goes out again as a fresh block — §5b R6b. The relay
     * drops a block's body once every registered device has pulled it, and a device that joins after
     * that would otherwise never learn of a removal. Returns how many rows went out.
     *
     * ⚠ Valid rows only: re-emitting an unverifiable row would spread whatever a realm-key holder wrote.
     * ⚠ The record's meaning is its signed `authoredAt`, never the block's HLC, so a re-emit at a new HLC
     * changes nothing about which record is newest.
     */
    fun reEmitAll(): Int {
        val ownerId = syncManager.currentOwnerId ?: return 0
        var reEmitted = 0
        for (row in executionContext.database.getArrayData(ref())?.toList().orEmpty()) {
            val record = toRecord(row) ?: continue
            if (!DeviceRecordVerifier.isValid(ownerId, record, cryptoManager::verifyEd25519)) continue
            executionContext.saveData(row)
            reEmitted++
        }
        return reEmitted
    }

    /**
     * Whether an incoming record row may be stored — §5b R6b. ⚠⚠ Two refusals, both because anyone with
     * the realm key can push a row at any key: an unverifiable row is never stored (it would mean
     * nothing anyway), and a valid row already stored is never overwritten — else a same-key row with a
     * garbage signature would replace a removal by last-writer-wins and quietly un-remove the device.
     */
    fun acceptsIncoming(incoming: Data): Boolean {
        val ownerId = syncManager.currentOwnerId ?: return false
        val record = toRecord(incoming) ?: return false
        if (!DeviceRecordVerifier.isValid(ownerId, record, cryptoManager::verifyEd25519)) return false
        val id = executionContext.getDataId(incoming) ?: return false
        val stored = executionContext.database.getData(id)?.let(::toRecord) ?: return true
        return !DeviceRecordVerifier.isValid(ownerId, stored, cryptoManager::verifyEd25519)
    }

    private fun records(): List<DeviceRecordVerifier.Record> =
        executionContext.database.getArrayData(ref())?.toList().orEmpty().mapNotNull { row -> toRecord(row) }

    private fun toRecord(row: Data): DeviceRecordVerifier.Record? {
        val record = row as? DeviceRecord ?: return null
        // ⚠⚠ The key must be the one the content names, or a valid record planted at another record's key would
        // make the append-only rule refuse the genuine one for good.
        if (record.key.get() != DeviceRecord.keyOf(record.kind.get(), record.deviceId.get(), record.authoredAt.get())) return null
        val kind = when (record.kind.get()) {
            DeviceRecord.Kind.REMOVAL -> DeviceRecordVerifier.Kind.REMOVAL
            DeviceRecord.Kind.READMISSION -> DeviceRecordVerifier.Kind.READMISSION
            else -> return null // a kind this build does not know: ignored, never acted on
        }
        return DeviceRecordVerifier.Record(
            kind = kind,
            deviceId = record.deviceId.get(),
            authority = DeviceAccessRevocation.Authority.fromWire(record.authority.get()) ?: return null,
            authoredAt = decodeHlcStamp(record.authoredAt.get()) ?: return null,
            signerPublicKey = record.signerPublicKey.get(),
            signature = record.signature.get(),
        )
    }

    private fun ref() = executionContext.relationReference(IdentityService.Relations.DEVICE_RECORDS)
}

/**
 * Enforces a verified removal on this replica: when a [DeviceRecord] arrives (own or ingested) and the
 * device it names is now removed, its `Device` row leaves the store and the `DEVICES` relation, so every
 * reader of the device list agrees without being taught about records.
 *
 * ⚠ The other half is `SyncProcessor`'s ingest guard, which drops an incoming `Device` row for a removed
 * id — so a removed device that pushed a rename before its access was cut cannot reappear through
 * last-writer-wins.
 */
internal class DeviceRecordObserver(
    private val executionContext: ExecutionContext,
    private val records: DeviceRecords,
) : DataObserver {

    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        val deviceId = when {
            data is DeviceRecord -> data.deviceId.get()
            data is Entity<*> && data.type.get() == DeviceRecord.ID ->
                data.getFieldValue<org.tenshu.core.TString>(DeviceRecord.Fields.DEVICE_ID).get()
            else -> return
        }
        if (!records.isRemoved(deviceId)) return
        // ⭐ §5b R6d: a removal signed elsewhere is delivered from here too. ⚠ Ingested rows only — a local save is
        // this device's own removal, which `RevokeDevice` queued already.
        if (origin == WriteOrigin.SYNC_INGEST) records.oweDeliveryOf(deviceId)
        val rowId = DataId.StringDataId.create(executionContext.context, Device.ID, deviceId)
        executionContext.database.removeFromArray(
            reference = executionContext.relationReference(IdentityService.Relations.DEVICES),
            dataId = rowId,
        )
        // ⚠ A local delete, as the tombstone path's is: this replica's view, not a new emit.
        if (executionContext.database.getData(rowId) != null) {
            executionContext.deleteData(rowId, origin = WriteOrigin.SYNC_INGEST)
        }
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = Unit
}
