package org.tenshu.sync.common

import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.common.util.HlcStamp

/**
 * The A1-signed **device records** a realm keeps about its devices (`sync-entity-identity.md` §5b, R6;
 * maintainer decision C): an append-only, order-free set, each record self-certifying. Two kinds today
 * — a removal and a re-admission — and the read-only-members work (RO-3) adds a downgrade through this
 * same verifier.
 *
 * ## The rule
 *
 * For each device id, take every record that verifies. The device is **removed** iff the newest of them
 * (HLC order, [HlcStamp.compareTo]) is a removal; on a tie the removal wins. It is a maximum over a set,
 * so arrival order cannot matter and nothing can fork.
 *
 * ## Self-certifying, with no stored key
 *
 * Each record carries the public key it is signed with, and the verifier checks that key against an
 * identifier it is bound to by construction:
 *  - [DeviceAccessRevocation.Authority.MASTER] (A1) — `ownerIdForMasterPublicKey(key) == ownerId`: a
 *    realm's id **is** the hash of its master public key. Works for a personal realm and for a shared
 *    realm (its own random master) alike.
 *  - [DeviceAccessRevocation.Authority.SELF] — `deviceIdForPublicKey(key) == deviceId`: a device's id is
 *    the hash of its key, so a self-signed record can only ever name its signer.
 *
 * ⚠⚠ **A re-admission is A1 only.** Membership is not permission, and nothing self-published may undo a
 * removal: only the two A1 paths that can bring a removed id back at all (registration repair, recovery
 * claim — the relay's removal ledger refuses it on every pairing flow) sign one.
 *
 * ⚠ A **removal** record's signed bytes are exactly the `/sync/device-access/revoke` request's
 * ([DeviceAccessRevocation.message]): one signature serves the relay's access cut and every peer's
 * device list, so the two planes cannot disagree about the stamp.
 */
object DeviceRecordVerifier {

    enum class Kind { REMOVAL, READMISSION }

    /** One record as read off its row. [signerPublicKey] is what [signature] verifies under. */
    class Record(
        val kind: Kind,
        val deviceId: String,
        val authority: DeviceAccessRevocation.Authority,
        val authoredAt: HlcStamp,
        val signerPublicKey: ByteArray,
        val signature: ByteArray,
    )

    /** Whether [record] is authentic for [ownerId]'s realm. */
    fun isValid(
        ownerId: String,
        record: Record,
        verify: (publicKey: ByteArray, message: ByteArray, signature: ByteArray) -> Boolean,
    ): Boolean {
        val keyBound = when (record.authority) {
            DeviceAccessRevocation.Authority.MASTER ->
                ownerIdForMasterPublicKey(record.signerPublicKey) == ownerId
            DeviceAccessRevocation.Authority.SELF ->
                // ⚠⚠ A re-admission has no self form.
                record.kind == Kind.REMOVAL && deviceIdMatchesKey(record.deviceId, record.signerPublicKey)
        }
        if (!keyBound) return false
        val message = when (record.kind) {
            Kind.REMOVAL -> DeviceAccessRevocation.message(record.authority, ownerId, record.deviceId, record.authoredAt)
            Kind.READMISSION -> DeviceReadmission.message(ownerId, record.deviceId, record.authoredAt)
        }
        return verify(record.signerPublicKey, message, record.signature)
    }

    /**
     * Per device, the valid record that decides its state — the newest, a removal outranking a
     * re-admission on equal stamps. Invalid records are ignored.
     */
    fun decidingRecords(
        ownerId: String,
        records: Iterable<Record>,
        verify: (publicKey: ByteArray, message: ByteArray, signature: ByteArray) -> Boolean,
    ): Map<String, Record> =
        records.filter { isValid(ownerId, it, verify) }
            .groupBy { it.deviceId }
            .mapValues { (_, recordsForDevice) ->
                recordsForDevice.maxWith(
                    compareBy<Record> { it.authoredAt }.thenBy { if (it.kind == Kind.REMOVAL) 1 else 0 },
                )
            }

    /** The device ids [records] say are removed from [ownerId]'s realm. Invalid records are ignored. */
    fun removedDevices(
        ownerId: String,
        records: Iterable<Record>,
        verify: (publicKey: ByteArray, message: ByteArray, signature: ByteArray) -> Boolean,
    ): Set<String> =
        decidingRecords(ownerId, records, verify).filterValues { it.kind == Kind.REMOVAL }.keys
}

/**
 * The A1-signed statement that a previously removed device id is admitted again (R6b writes it; the
 * verifier reads it now). ⚠ A distinct tag: a re-admission must never verify as a removal or as anything
 * else A1 signs (`CrossFamilySignatureTest`).
 *
 * ```
 * message = LP("tenshu-device-readmit-v1") ‖ LP(ownerId) ‖ LP(deviceId)
 *         ‖ LP(authored.physicalTime) ‖ LP(authored.logicalCounter) ‖ LP(authored.deviceId)
 * ```
 */
object DeviceReadmission {
    /** Never reuse for another layout; a new layout gets `-v2`. */
    const val DOMAIN_TAG = "tenshu-device-readmit-v1"

    fun message(ownerId: String, deviceId: String, authoredAt: HlcStamp): ByteArray =
        lengthPrefixedSignedMessage(
            DOMAIN_TAG,
            signedField(ownerId),
            signedField(deviceId),
            signedField(authoredAt.physicalTime),
            signedField(authoredAt.logicalCounter),
            signedField(authoredAt.deviceId),
        )
}
