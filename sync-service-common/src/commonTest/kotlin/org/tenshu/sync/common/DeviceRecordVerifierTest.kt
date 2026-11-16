package org.tenshu.sync.common

import org.koin.dsl.koinApplication
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.common.security.cryptoBackendsModule
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.common.util.HlcStamp
import org.tenshu.sync.common.DeviceAccessRevocation.Authority
import org.tenshu.sync.common.DeviceRecordVerifier.Kind
import org.tenshu.sync.common.DeviceRecordVerifier.Record
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `sync-entity-identity.md` §5b R6 — the device-record verifier: self-certifying records, newest wins,
 * a re-admission only from A1. Real Ed25519 (`cryptoBackendsModule()`).
 */
class DeviceRecordVerifierTest {

    private val ed25519: Ed25519Backend = koinApplication { modules(cryptoBackendsModule()) }.koin.get()
    private val verify: (ByteArray, ByteArray, ByteArray) -> Boolean = ed25519::verify

    private val masterSeed = ByteArray(32) { (it + 1).toByte() }
    private val masterPub = ed25519.derivePublicKey(masterSeed)
    private val ownerId = ownerIdForMasterPublicKey(masterPub)

    private val deviceSeed = ByteArray(32) { (it + 40).toByte() }
    private val devicePub = ed25519.derivePublicKey(deviceSeed)
    private val deviceId = deviceIdForPublicKey(devicePub)

    private val otherSeed = ByteArray(32) { (it + 90).toByte() }
    private val otherPub = ed25519.derivePublicKey(otherSeed)

    private fun at(ms: Long) = HlcStamp(ms, 0L, "author")

    private fun masterRemoval(device: String = deviceId, stamp: HlcStamp = at(5_000), seed: ByteArray = masterSeed, pub: ByteArray = masterPub) =
        Record(Kind.REMOVAL, device, Authority.MASTER, stamp, pub,
            ed25519.sign(seed, DeviceAccessRevocation.message(Authority.MASTER, ownerId, device, stamp)))

    private fun selfRemoval(device: String = deviceId, stamp: HlcStamp = at(5_000), seed: ByteArray = deviceSeed, pub: ByteArray = devicePub) =
        Record(Kind.REMOVAL, device, Authority.SELF, stamp, pub,
            ed25519.sign(seed, DeviceAccessRevocation.message(Authority.SELF, ownerId, device, stamp)))

    private fun readmission(stamp: HlcStamp, authority: Authority = Authority.MASTER, seed: ByteArray = masterSeed, pub: ByteArray = masterPub) =
        Record(Kind.READMISSION, deviceId, authority, stamp, pub,
            ed25519.sign(seed, DeviceReadmission.message(ownerId, deviceId, stamp)))

    private fun removed(vararg records: Record) = DeviceRecordVerifier.removedDevices(ownerId, records.toList(), verify)

    @Test
    fun anA1Removal_removes() = assertEquals(setOf(deviceId), removed(masterRemoval()))

    @Test
    fun aSelfRemoval_removesItsSigner() = assertEquals(setOf(deviceId), removed(selfRemoval()))

    /** ⚠⚠ A signer whose key does not hash to the realm id is not A1, however well it signs. */
    @Test
    fun aMasterRemovalByAnyOtherKey_isIgnored() =
        assertTrue(removed(masterRemoval(seed = otherSeed, pub = otherPub)).isEmpty())

    /** ⚠⚠ Membership is not permission: a device's own key names only that device. */
    @Test
    fun aSelfRemovalNamingAnotherDevice_isIgnored() {
        val victim = deviceIdForPublicKey(otherPub)
        assertTrue(removed(selfRemoval(device = victim)).isEmpty())
    }

    @Test
    fun aTamperedStamp_isIgnored() {
        val genuine = masterRemoval(stamp = at(5_000))
        val tampered = Record(genuine.kind, genuine.deviceId, genuine.authority, at(9_000), genuine.signerPublicKey, genuine.signature)
        assertTrue(removed(tampered).isEmpty())
    }

    @Test
    fun aNewerA1Readmission_liftsTheRemoval() =
        assertTrue(removed(masterRemoval(stamp = at(5_000)), readmission(at(6_000))).isEmpty())

    @Test
    fun anOlderReadmission_doesNot() =
        assertEquals(setOf(deviceId), removed(masterRemoval(stamp = at(5_000)), readmission(at(4_000))))

    @Test
    fun onEqualStamps_theRemovalWins() =
        assertEquals(setOf(deviceId), removed(masterRemoval(stamp = at(5_000)), readmission(at(5_000))))

    /** ⚠⚠ Nothing self-published may undo a removal: a device cannot re-admit itself. */
    @Test
    fun aSelfSignedReadmission_isIgnored() {
        val selfReadmit = readmission(at(9_000), authority = Authority.SELF, seed = deviceSeed, pub = devicePub)
        assertFalse(DeviceRecordVerifier.isValid(ownerId, selfReadmit, verify))
        assertEquals(setOf(deviceId), removed(masterRemoval(stamp = at(5_000)), selfReadmit))
    }

    /** Order-free: the answer is the same whatever order the records arrive in. */
    @Test
    fun arrivalOrder_doesNotMatter() {
        val records = listOf(masterRemoval(stamp = at(5_000)), readmission(at(6_000)), masterRemoval(stamp = at(7_000)))
        for (order in listOf(records, records.reversed(), listOf(records[1], records[2], records[0]))) {
            assertEquals(setOf(deviceId), DeviceRecordVerifier.removedDevices(ownerId, order, verify))
        }
    }

    /** A removal record's bytes are the relay route request's: one signature, both planes. */
    @Test
    fun aRemovalRecordVerifies_overExactlyTheRouteRequestsMessage() {
        val record = masterRemoval()
        assertTrue(
            verify(masterPub, DeviceAccessRevocation.message(Authority.MASTER, ownerId, deviceId, record.authoredAt), record.signature),
        )
    }
}
