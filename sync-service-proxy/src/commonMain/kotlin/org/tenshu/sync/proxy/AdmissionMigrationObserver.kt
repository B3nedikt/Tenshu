package org.tenshu.sync.proxy

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.AdmissionCertGrant
import org.tenshu.identity.AdmissionKeyAnnouncement
import org.tenshu.identity.Device
import org.tenshu.sync.common.deviceIdMatchesKey
import org.tenshu.sync.common.AdmissionCapabilityCert
import org.tenshu.sync.common.AdmissionChainVerdict
import org.tenshu.sync.common.AdmissionChainVerifier
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.admissionKeyAnnouncementMessage
import org.tenshu.sync.common.decodeAdmissionCertChain
import org.tenshu.sync.common.encodeAdmissionCertChain

/**
 * P4 of `delegated-device-admission-authority.md` (§8, Q4 migration + Q2
 * re-root): closes the gap for devices that never received an A33 via the
 * pairing blob, and opportunistically flattens deep chains. Three duties, all
 * riding ordinary synced rows (no relay/protocol change; the A33 *private*
 * key never syncs — GR D9-b posture):
 *
 *  1. **Self-announce** (non-A1 device): a device with no admission capability
 *     mints an A33 keypair locally, persists the private half in its own
 *     custody (J3 joiner payload), and publishes an [AdmissionKeyAnnouncement]
 *     — the public key, signed with its A4 device key. A device holding a
 *     chain **deeper than 1** also announces, which is the re-root request.
 *  2. **Owner mint** (A1-holder): on observing an announcement, verify its A4
 *     signature against the [Device] row's registered key (an unsigned or
 *     mis-signed announcement could otherwise bind an attacker's A33 to
 *     another device's identity — T-DA-06), then mint a **depth-1** cert for
 *     the announced key at the current realm epoch and publish an
 *     [AdmissionCertGrant].
 *  3. **Install** (the announcing device): on observing a grant for its own
 *     deviceId, check the chain is for its key, roots at *this* realm's master
 *     (`ownerIdForMasterPublicKey(masterPub) == currentOwnerId` — the same
 *     binding the relay pins at genesis), verifies link-by-link, and is an
 *     improvement (no chain yet, shorter, or fresher leaf epoch) — then adopt.
 *
 * Convergence is event-driven both ways: the announce re-fires once per
 * session while the device still needs a cert (or holds a deep chain), so an
 * owner that unlocks *after* the announcement synced still gets a fresh save
 * event to react to within one session of both being online. Every step is
 * idempotent; a lost write self-heals on the next round.
 */
class AdmissionMigrationObserver(
    private val executionContext: ExecutionContext,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
) : DataObserver {

    /** Once-per-session guard for the self-announce (re-arms next process start). */
    private var announcedThisSession = false

    /** Once-per-(device, chain) guard so the owner doesn't re-mint on every echo. */
    private val mintedThisSession = mutableSetOf<String>()

    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        // Every ingest/write tick is a chance to catch up — cheap no-ops when
        // nothing is pending. Ordering: install before announce, so a grant that
        // lands in the same pull suppresses a redundant re-announce.
        when (typeIdOf(data)) {
            AdmissionCertGrant.ID -> deviceIdOf(data, AdmissionCertGrant.Fields.DEVICE_ID)
                ?.let(::maybeInstall)

            AdmissionKeyAnnouncement.ID -> deviceIdOf(data, AdmissionKeyAnnouncement.Fields.DEVICE_ID)
                ?.let(::maybeMint)
        }
        maybeSelfAnnounce()
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = Unit

    // ---- duty 1: self-announce (non-A1 device needing a cert or a re-root) ----

    private fun maybeSelfAnnounce() {
        if (announcedThisSession) return
        if (!syncManager.isEnabled()) return
        val ownDeviceId = syncManager.currentDeviceId
        // A1-holders admit with the master key and need no A33; a depth-1 chain is
        // already optimal. Both are stable for the rest of the session — settle the
        // guard so this stops probing (incl. the storage-backed phrase read) on
        // every subsequent save. While unlocked, a host always has the phrase in
        // session — the reliable host-vs-joiner discriminator.
        if (syncManager.currentMasterKeyPair != null ||
            syncManager.getRecoveryPhrase() != null ||
            syncManager.getAdmissionCertChain().size == 1
        ) {
            announcedThisSession = true
            return
        }

        val keyPair = syncManager.getAdmissionKeyPair() ?: run {
            // Pre-P3 joiner: mint locally, persist the private half in own custody
            // (empty chain = "awaiting grant"; still a non-admitter until it lands).
            val fresh = cryptoManager.generateAdmissionKeyPair()
            syncManager.installAdmissionMaterial(fresh, syncManager.getAdmissionCertChain())
            fresh
        }

        announcedThisSession = true
        val signature = cryptoManager.signEd25519(
            syncManager.getDeviceSigningKeyPair().privateKey,
            admissionKeyAnnouncementMessage(ownDeviceId, keyPair.publicKey),
        )
        executionContext.saveData(
            AdmissionKeyAnnouncement.create(
                context = executionContext.context,
                deviceId = ownDeviceId,
                publicKey = keyPair.publicKey,
                signature = signature,
            )
        )
    }

    // ---- duty 2: owner mints a depth-1 grant for a verified announcement ----

    /**
     * ⚠ Mints for any verified announcement of a device whose row this replica still holds — it can
     * know nothing else about removal: admission revocations live only in the relay's ledger (no
     * read route), and the `Device` tombstone, once applied, already deletes the row this reads. So
     * an A1 holder that has not yet applied a removed device's tombstone WILL mint it a fresh key;
     * the relay refuses that cert as an approver because its refusal is id-scoped for the approver
     * (`admission-revocation-names-the-capability.md` §7j.10.2). Do not "fix" this by refusing
     * re-keys here: the device's own rows are self-written, so any local signal is forgeable.
     */
    private fun maybeMint(deviceId: String) {
        val master = syncManager.currentMasterKeyPair ?: return
        val ownerId = syncManager.currentOwnerId ?: return
        if (deviceId in mintedThisSession) return

        val announcement = readAnnouncement(deviceId) ?: return
        val announcedKey = announcement.publicKey.get()

        // T-DA-06: only mint for a key the *device itself* vouched for — verify the
        // A4 signature against the Device row's registered signing key. No Device
        // row (not yet synced) → skip; the announce re-fires next session.
        val deviceRow = readDevice(deviceId) ?: return
        val a4 = deviceRow.publicKey.get()
        // ⚠⚠ Workstream B: the row is synced and unchecked on merge, so any admitted device could
        // write one pairing a VICTIM's id with its own A4 and announce under it. A1 would then mint
        // a cert whose link id is the victim's — and S1's removed-link check would test the wrong
        // id. The id must be the key's, or A1 vouches for nothing.
        if (!deviceIdMatchesKey(deviceId, a4)) return
        if (!cryptoManager.verifyEd25519(
                a4,
                admissionKeyAnnouncementMessage(deviceId, announcedKey),
                announcement.signature.get(),
            )
        ) {
            return
        }

        // Already granted for this exact key? Nothing to do (LWW row is current).
        val existing = readGrant(deviceId)
        if (existing != null) {
            val existingLeaf = runCatching { decodeAdmissionCertChain(existing.certChain.get()) }
                .getOrNull()?.firstOrNull()
            if (existingLeaf?.deviceA33PublicKey?.contentEquals(announcedKey) == true) {
                mintedThisSession.add(deviceId)
                return
            }
        }

        val epoch = syncManager.currentRealmEpoch
        val cert = AdmissionCapabilityCert(
            ownerId = ownerId,
            deviceA33PublicKey = announcedKey,
            deviceId = deviceId,
            mintedAtEpoch = epoch,
            signature = cryptoManager.signEd25519(
                master.privateKey,
                AdmissionCapabilityCert.message(ownerId, announcedKey, deviceId, epoch),
            ),
        )
        mintedThisSession.add(deviceId)
        executionContext.saveData(
            AdmissionCertGrant.create(
                context = executionContext.context,
                deviceId = deviceId,
                masterPublicKey = master.publicKey,
                certChain = encodeAdmissionCertChain(listOf(cert)),
            )
        )
    }

    // ---- duty 3: the announcing device installs its granted chain ----

    private fun maybeInstall(deviceId: String) {
        if (deviceId != syncManager.currentDeviceId) return
        val keyPair = syncManager.getAdmissionKeyPair() ?: return
        val grant = readGrant(deviceId) ?: return

        val chain = runCatching { decodeAdmissionCertChain(grant.certChain.get()) }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        // For our key, rooted at OUR realm's master (the ownerId ↔ masterPub binding
        // the relay pinned at genesis), and cryptographically intact.
        if (!chain.first().deviceA33PublicKey.contentEquals(keyPair.publicKey)) return
        val masterPub = grant.masterPublicKey.get()
        val ownerId = syncManager.currentOwnerId ?: return
        if (ownerIdForMasterPublicKey(masterPub) != ownerId) return
        if (AdmissionChainVerifier.verifyChain(
                masterPub, ownerId, chain, emptyList(), cryptoManager::verifyEd25519,
            ) != AdmissionChainVerdict.Admit
        ) {
            return
        }

        // Adopt only improvements: first chain, a flatter chain (re-root), or a
        // fresher leaf epoch. ⚠ A fresher epoch alone does not un-revoke: since F5b a
        // revocation names the A33 key, so only a re-mint under a NEW key clears it.
        val own = syncManager.getAdmissionCertChain()
        val better = own.isEmpty() ||
            chain.size < own.size ||
            chain.first().mintedAtEpoch > own.first().mintedAtEpoch
        if (!better) return

        syncManager.installAdmissionMaterial(keyPair, chain)
    }

    // ---- helpers ----

    /** The saved data's schema type id, for both direct DTOs and Entity wrappers. */
    private fun typeIdOf(data: Data): String? = when (data) {
        is AdmissionKeyAnnouncement -> AdmissionKeyAnnouncement.ID
        is AdmissionCertGrant -> AdmissionCertGrant.ID
        is Entity<*> -> data.type.get()
        else -> null
    }

    private fun deviceIdOf(data: Data, fieldRef: String): String? = when (data) {
        is AdmissionKeyAnnouncement -> data.deviceId.get()
        is AdmissionCertGrant -> data.deviceId.get()
        is Entity<*> -> data.getFieldValue<TString>(fieldRef).get()
        else -> null
    }

    private fun readAnnouncement(deviceId: String): AdmissionKeyAnnouncement? =
        executionContext.database.getData(
            DataId.StringDataId.create(executionContext.context, AdmissionKeyAnnouncement.ID, deviceId),
        ) as? AdmissionKeyAnnouncement

    private fun readGrant(deviceId: String): AdmissionCertGrant? =
        executionContext.database.getData(
            DataId.StringDataId.create(executionContext.context, AdmissionCertGrant.ID, deviceId),
        ) as? AdmissionCertGrant

    private fun readDevice(deviceId: String): Device? =
        executionContext.database.getData(
            DataId.StringDataId.create(executionContext.context, Device.ID, deviceId),
        ) as? Device
}
