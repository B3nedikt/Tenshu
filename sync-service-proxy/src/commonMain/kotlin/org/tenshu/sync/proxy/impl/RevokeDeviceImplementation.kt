package org.tenshu.sync.proxy.impl

import org.tenshu.core.TResult
import org.tenshu.core.messageOrNull
import org.tenshu.core.Data
import org.tenshu.core.NotFoundError
import org.tenshu.core.TenshuException
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.Device
import org.tenshu.identity.IdentityService
import org.tenshu.sync.RevokeDevice
import org.tenshu.sync.common.AdmissionRevocation
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.sync.common.decodeAdmissionCertChain
import org.tenshu.identity.AdmissionCertGrant
import org.tenshu.identity.AdmissionKeyAnnouncement
import org.tenshu.identity.DeviceLastSeen
import org.tenshu.sync.common.DeviceSchemaVersion
import org.tenshu.identity.DeviceHardware

/**
 * Per `docs/adr/0007-sync-device-management.md` + `docs/adr/0006-sync-tombstones.md`: deletes the local
 * `Device` entity for [RevokeDevice.Params.DEVICE_ID] and emits a `DeleteBlock`
 * tombstone via [TombstoneEmitter]. Other realm devices, on receiving the tombstone,
 * short-circuit any subsequent SyncBlock for the same entity at HLC ≤ tombstone.hlc;
 * the relay treats the tombstone as a GC marker.
 *
 * P5 (`delegated-device-admission-authority.md` §5): removal also strips the removed
 * device's **own admission capability** — one master-signed [AdmissionRevocation] per A33
 * key this replica knows it held, POSTed to `/sync/admission/revoke`. Best-effort (an
 * offline relay doesn't block the removal; the relay also refuses a removed approver by its
 * F2′ ledger and by the poster binding, `admission-revocation-names-the-capability.md`
 * §7j.9). Requires A1 — a delegated (A33-only) admitter can remove a device but cannot
 * master-sign revocations.
 *
 * ⚠⚠ **Only the removed device's own keys — never its lineage subtree** (§7j.10, 2026-11-11,
 * retiring Q3's conservative subtree strip). A device is either a member (it can admit) or
 * removed (it can do nothing); there is no "member that cannot admit" state. The devices
 * the removed one admitted are listed in the removal dialog (§7j.4) and the user decides per
 * device: a kept one stays a full member, an unticked one is removed in the same action by
 * its own `RevokeDevice` call (tombstone, F2′, its own keys). Re-adding a subtree walk here
 * strips kept devices silently — pinned by
 * `RevokeDeviceAdmissionRevocationTest.a1Holder_revokesOnlyTheRemovedDevice_neverTheDevicesItAdmitted`.
 *
 * **A missing local `Device` row does not block the revocation** (2026-08-28). The row is the
 * descriptive half of membership; the tombstone is the revocation. When the row is absent the
 * eviction still proceeds — provided [wasRealmMember] finds independent evidence that the id
 * really named a member — and the relay strip names whatever A33 keys the member's synced
 * `AdmissionKeyAnnouncement` / `AdmissionCertGrant` rows still carry (F5b: there is no epoch rule
 * to degrade to any more). Only a wholly unvouched-for id is refused, and it is refused with a typed
 * [NotFoundError] so the recorded `ErrorEvent` names the reason. Before this, the hard
 * precondition made the whole operation a no-op on a degraded roster, which is what
 * `docs/tech-debt.md` → *"A failed `revokeDevice` leaves the roster asserting a device the relay
 * has already stopped accepting"* was filed for.
 *
 * Note: epoch rotation + relay-side JWT rejection of revoked-device pushes are tracked
 * separately (see `docs/adr/0006-sync-tombstones.md` Phase 4 and the `admittedAtEpoch` semantics in
 * `docs/adr/0007-sync-device-management.md`). This implementation lands the local-DB delete + the
 * outbound tombstone — the rest follows from the existing tombstone pipeline.
 */
internal class RevokeDeviceImplementation(
    private val tombstoneEmitter: TombstoneEmitter? = null,
    private val syncManager: SyncManager? = null,
    private val cryptoManager: SyncCryptoManager? = null,
    private val syncRelay: SyncRelay? = null,
    private val nowMs: () -> Long = { 0L },
    /**
     * Called when the best-effort relay admission strip fails. ⚠ Purely an observation channel —
     * the strip stays best-effort and this never changes the outcome of a revocation. `ForgetThisDevice`
     * uses it to stop waiting for a tombstone push that the same relay is unlikely to accept.
     */
    private val onRelayStripFailed: () -> Unit = {},
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val deviceId: String = params.read<TString>(RevokeDevice.Params.DEVICE_ID).get()

        val deviceDataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = Device.ID,
            keyValue = deviceId
        )

        // The local `Device` row is the DESCRIPTIVE half of membership; the tombstone below is the
        // revocation itself, and it is keyed by the entity-id hash — it needs no row. So a missing
        // row must not fail the eviction: doing that failed the security-critical step because an
        // enrichment step (key-scoped revocation) was unavailable, and it is why
        // a wiped/degraded roster meant nothing could be revoked at all.
        //
        // ⚠⚠ But NOT unconditionally. `currentRealmEpoch` is derived from the count of `Device`
        // tombstones, so emitting one for an id that names no member raises the realm epoch for
        // **every** device, and every `Device` block admitted at a lower epoch is then dropped as
        // stale (T-K-02). That is what the original hard precondition was really protecting.
        // (Admission capabilities no longer follow the epoch: since F5b a revocation names the
        // A33 key.) Require independent local
        // evidence that the id is (or was) a real realm member instead — see [wasRealmMember].
        val localRow = executionContext.database.getData(deviceDataId)
        if (localRow == null) {
            if (!wasRealmMember(executionContext, deviceId)) {
                throw TenshuException(
                    NotFoundError.create(
                        executionContext.context,
                        "No local Device row for $deviceId and no evidence it was a realm member; " +
                            "nothing to revoke"
                    )
                )
            }
            // ⚠ Degraded, and deliberately loud: without the row there is no captured A33 key, so
            // the relay strip below can only name keys from the synced announcement / grant rows.
            println(
                "RevokeDevice: no local Device row for $deviceId — revoking by id. " +
                    "Captured A33 key unavailable; naming announced/granted keys only."
            )
        }

        // F1′: read the A33 keys HERE — step 2 below deletes the revoked device's row, and its
        // captured key would be gone by the time the revocations are minted.
        val capabilityKeys: List<ByteArray> = admissionKeysOf(executionContext, deviceId)

        // 1. Remove from the synced DEVICES relation array so observers see the device
        // disappear from `IdentityService.Relations.DEVICES` immediately.
        val devicesRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = executionContext.context,
                type = TList.ID,
                relationId = IdentityService.Relations.DEVICES
            ),
            isEntity = true,
            elementSchemaId = Device.ID
        )
        executionContext.database.removeFromArray(reference = devicesRef, dataId = deviceDataId)

        // 2. Delete the entity from the local DB. SyncObserver.onDataDeleted is a no-op
        // by design — tombstones are emitted explicitly below.
        executionContext.deleteData(deviceDataId)

        // 2b. Retire the rows this device published ABOUT ITSELF — its hardware model and its
        // schema-version statement. Nothing else ever deletes them (the device was their only
        // writer), so without this every replica keeps a revoked device's descriptors for ever.
        // ⚠ Before the `Device` tombstone, so that one stays the last block a revocation emits.
        // ⚠⚠ Plain tombstones (no `revokedDeviceId`): only the `Device` one is the revocation the
        // relay acts on, and the realm epoch counts `Device` tombstones alone, so these move
        // neither. Emitted even with no local row — a peer may hold one this replica never got.
        retireSelfPublishedRows(executionContext, deviceId)

        // 3. Emit a DeleteBlock so other realm devices reject any future stale
        // SyncBlocks for this entity at HLC ≤ tombstone.hlc (docs/adr/0006-sync-tombstones.md).
        // Pass deviceId via revokedDeviceId so the relay can extract it on receipt
        // and revoke the device's KnownDevicesStore registration — closes T-K-02
        // push rejection (threat-model §8.5).
        tombstoneEmitter?.emit(
            targetEntityId = deviceDataId,
            targetEntityType = Device.ID,
            revokedDeviceId = deviceId
        )

        // 4. P5: strip the removed device's own minting power on the relay — its keys only,
        // never the devices it admitted (§7j.10, class KDoc). Needs A1 (revocations are
        // master-signed) — resolve like the approve path does; a delegated admitter simply
        // skips. Best-effort: a failed POST never blocks the removal itself.
        val masterKeyPair = syncManager?.let { sm ->
            sm.currentMasterKeyPair
                ?: sm.getRecoveryPhrase()?.let { cryptoManager?.deriveMasterKeyPair(it) }
        }
        val ownerId = syncManager?.currentOwnerId
        if (masterKeyPair != null && ownerId != null && cryptoManager != null && syncRelay != null) {
            val epoch = syncManager.currentRealmEpoch
            val revokedAt = nowMs()
            // ⚠⚠ Raise the realm epoch past the one we are about to revoke at.
            //
            // Originally this was for the ADMISSION path: under the retired epoch rule a device
            // re-admitted at the revoked epoch was born superseded (`N >= N`, production
            // 2026-08-18). F5b removed that rule — revocations name keys now — so admission no
            // longer reads this. ⚠ It stays because `Device.admittedAtEpoch` still does: T-K-02's
            // merge check (`SyncProcessor.isStaleDeviceBlock`) drops a Device row stamped below a
            // peer's epoch, and a device re-admitted after this removal must be stamped above the
            // tombstone (`admission-revocation-names-the-capability.md` §7e.2, F5c).
            //
            // ⚠ Seeding only ever raises (`seedCurrentRealmEpoch`), so this cannot rewind a realm
            // whose tombstone count is already higher.
            syncManager.seedCurrentRealmEpoch(epoch + 1)

            // F1′/F5b: a revocation names a capability KEY — there is no device-scoped form any
            // more (§7g). One revocation per key this replica knows the device held; a device with
            // none is skipped, loudly, rather than revoked by an epoch rule that no longer exists.
            // ⚠ The skip fails OPEN for its A33 alone — its data access is still cut by the
            // tombstone above, and the relay refuses it as an approver by F2′'s ledger and the
            // poster binding (§7j.9).
            if (capabilityKeys.isEmpty()) {
                println(
                    "RevokeDevice: no known A33 key for $deviceId — relay admission strip " +
                        "skipped (F5b: a keyless revocation cannot be expressed)"
                )
            }
            for (capabilityKey in capabilityKeys) {
                revokeAdmissionKey(ownerId, deviceId, epoch, revokedAt, capabilityKey, masterKeyPair)
            }
        }

        return null as T
    }

    private suspend fun revokeAdmissionKey(
        ownerId: String,
        member: String,
        epoch: Long,
        revokedAt: Long,
        capabilityKey: ByteArray,
        masterKeyPair: SyncCryptoManager.KeyPair,
    ) {
        val cryptoManager = cryptoManager ?: return
        val syncRelay = syncRelay ?: return
        val signature = cryptoManager.signEd25519(
            masterKeyPair.privateKey,
            AdmissionRevocation.message(ownerId, member, epoch, revokedAt, capabilityKey),
        )
        // Deliberately best-effort (class KDoc / step-4 comment): the tombstone above
        // is the primary revocation, and the relay refuses a removed approver on its own. But best-effort must still be VISIBLE — a dropped Failure here was
        // indistinguishable from success (tech-debt "TResult ops discardable").
        val result = syncRelay.revokeAdmission(
            ownerId = ownerId,
            deviceId = member,
            epoch = epoch,
            revokedAt = revokedAt,
            signature = signature,
            deviceA33PublicKey = capabilityKey,
        )
        if (result is TResult.Failure) {
            println(
                "RevokeDevice: relay admission strip for $member failed " +
                    "(${result.error.messageOrNull() ?: result.error.schema.id}) — " +
                    "local tombstone revocation stands",
            )
            onRelayStripFailed()
        }
    }

    /**
     * Independent local evidence that [deviceId] is (or was) a member of this realm, used only
     * when its `Device` row is gone.
     *
     * ⚠⚠ **This gate is about the realm epoch, not about tidiness.** A `Device` tombstone raises
     * `currentRealmEpoch` for everyone, so revoking a *bogus* id is not a harmless no-op — every
     * `Device` block admitted below the new epoch is then dropped as stale (T-K-02). Every source below is a row only a real
     * member could have caused to exist:
     *  - [AdmissionKeyAnnouncement] — the device announced its own A33 key;
     *  - [AdmissionCertGrant] — an admitter minted a depth-1 grant for it;
     *  - [DeviceLastSeen] — this replica has ingested a block authored by it.
     *
     * ⚠ Not a complete repair for a **fully** wiped store: these rows live in the same store as
     * the `Device` row, so a wipe takes them too. That case needs the roster to be reconstructable
     * (`SyncProxyInitialization`'s tier-1 self-heal covers only this device's own row) and is
     * tracked in `docs/tech-debt.md` → *"`Device` rows have no regeneration path"*.
     */
    private fun wasRealmMember(
        executionContext: ExecutionContext,
        deviceId: String,
    ): Boolean = listOf(
        AdmissionKeyAnnouncement.ID,
        AdmissionCertGrant.ID,
        DeviceLastSeen.ID,
    ).any { type ->
        executionContext.database.getData(
            DataId.StringDataId.create(executionContext.context, type, deviceId),
        ) != null
    }

    private fun retireSelfPublishedRows(executionContext: ExecutionContext, deviceId: String) {
        val context = executionContext.context
        val hardwareId = DataId.StringDataId.create(context, DeviceHardware.ID, deviceId)
        executionContext.database.removeFromArray(
            reference = ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = context,
                    type = TList.ID,
                    relationId = IdentityService.Relations.DEVICE_HARDWARE,
                ),
                isEntity = true,
                elementSchemaId = DeviceHardware.ID,
            ),
            dataId = hardwareId,
        )
        for ((rowId, type) in listOf(
            hardwareId to DeviceHardware.ID,
            DataId.StringDataId.create(context, DeviceSchemaVersion.ID, deviceId) to DeviceSchemaVersion.ID,
        )) {
            if (executionContext.database.getData(rowId) != null) executionContext.deleteData(rowId)
            tombstoneEmitter?.emit(targetEntityId = rowId, targetEntityType = type, revokedDeviceId = null)
        }
    }

    /**
     * Every A33 capability key this replica knows [deviceId] to hold, distinct, in priority order:
     *
     *  1. the key captured on its `Device` row at admission (F0 / A′) — written by the admitter at
     *     the moment it mints the key, so present for every paired device;
     *  2. the key in its `AdmissionKeyAnnouncement` — the only source for an A1-rooted device that
     *     was never admitted by anyone (its row carries no captured key), and for a device that
     *     re-keyed after losing custody;
     *  3. the leaf of its `AdmissionCertGrant` — the key an A1-holder actually certified.
     *
     * ⚠ (2) and (3) are synced rows that may not have arrived — R1 measured that on real hardware,
     * which is why (1) leads. Naming every known key is safe in the over-revocation direction: a
     * revocation is `deviceId`-scoped *and* key-exact, so it can only ever strip this device's own
     * certs. Before F5b a missing key fell back to the epoch rule; now it means fewer revocations.
     */
    private fun admissionKeysOf(
        executionContext: ExecutionContext,
        deviceId: String,
    ): List<ByteArray> {
        fun read(type: String) = executionContext.database.getData(
            DataId.StringDataId.create(executionContext.context, type, deviceId),
        )
        val captured = (read(Device.ID) as? Device)?.admissionPublicKey?.get()
        val announced = (read(AdmissionKeyAnnouncement.ID) as? AdmissionKeyAnnouncement)?.publicKey?.get()
        val granted = (read(AdmissionCertGrant.ID) as? AdmissionCertGrant)?.certChain?.get()?.let { chain ->
            runCatching { decodeAdmissionCertChain(chain) }.getOrNull()?.firstOrNull()?.deviceA33PublicKey
        }
        val keys = mutableListOf<ByteArray>()
        for (key in listOfNotNull(captured, announced, granted)) {
            if (key.isNotEmpty() && keys.none { it.contentEquals(key) }) keys.add(key)
        }
        return keys
    }
}
