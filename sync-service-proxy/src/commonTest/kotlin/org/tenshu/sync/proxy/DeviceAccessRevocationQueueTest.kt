package org.tenshu.sync.proxy

import org.tenshu.sync.common.PendingDeviceAccessRevocation

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.util.toStamp
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.toTString
import org.tenshu.identity.Device
import org.tenshu.identity.IdentityService
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.RevokeDevice
import org.tenshu.sync.common.DeviceAccessRevocation
import org.tenshu.sync.common.DeviceAccessRevocation.Authority
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.RevokeDeviceImplementation
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.tenshu.core.TenshuException
import org.tenshu.sync.RecoveryPhraseRequiredError
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `sync-entity-identity.md` §5b phase R2a: a device removal also queues a **signed**
 * `/sync/device-access/revoke`, durably, and the drain sends it.
 *
 * Pins:
 *  - the authority follows the keys in hand — A1 → `master`; no A1 and this device → `self`, signed
 *    with this device's own key; no A1 and another device → refused before anything changes (§5b decision A);
 *  - ⚠⚠ the queued request and the `Device` tombstone carry **one** authored HLC;
 *  - the drain keeps an entry only on `Retry`, and never drops one a later removal replaced.
 */
class DeviceAccessRevocationQueueTest {

    private lateinit var testProxy: TestProxy
    private lateinit var crypto: BootstrapSyncCryptoManager
    private lateinit var relay: RecordingRevokeRelay
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var emitter: StampRecordingEmitter

    private val masterPhrase = "abcdefghijklmnopqrstuvwxyz012345"

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        crypto = BootstrapSyncCryptoManager()
        relay = RecordingRevokeRelay(testProxy.tContext)
        syncManager = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), testProxy.tContext)
        emitter = StampRecordingEmitter()
    }

    private fun outbox() = DeviceAccessRevocationOutbox(testProxy.executionContext)

    private fun seedDevice(deviceId: String) {
        val device = Device.create(
            context = testProxy.tContext,
            deviceId = deviceId,
            name = deviceId,
            publicKey = byteArrayOf(0x01),
            isAuthorized = true,
            admittedByDeviceId = "",
            admissionPublicKey = ByteArray(32) { (deviceId.hashCode() + it).toByte() },
        )
        testProxy.executionContext.saveData(device)
        testProxy.executionContext.database.addToArray(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(testProxy.tContext, TList.ID, IdentityService.Relations.DEVICES),
                isEntity = true,
                elementSchemaId = Device.ID,
            ),
            device,
        )
    }

    private suspend fun revoke(deviceId: String) {
        RevokeDeviceImplementation(
            tombstoneEmitter = emitter,
            syncManager = syncManager,
            cryptoManager = crypto,
            syncRelay = relay,
            nowMs = { 4_000L },
        ).execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(RevokeDevice.Params.DEVICE_ID to deviceId.toTString(testProxy.tContext)),
        )
    }

    private fun joinWithoutA1() = syncManager.initializeWithKeys(
        SyncCryptoManager.DerivedKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
        ownerId = "host-owner",
    )

    // ── authoring ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun anA1Holder_queuesAMasterSignedRequest_atTheTombstonesStamp() = runTest {
        syncManager.initialize(masterPhrase)
        seedDevice("victim")

        revoke("victim")

        val entry = outbox().pending().single()
        assertEquals("victim", entry.deviceId)
        assertEquals(Authority.MASTER, entry.authority)
        assertEquals(syncManager.currentOwnerId, entry.ownerId)
        val master = crypto.deriveMasterKeyPair(masterPhrase)
        assertTrue(
            crypto.verifyEd25519(
                master.publicKey,
                DeviceAccessRevocation.message(Authority.MASTER, entry.ownerId, "victim", entry.authoredAt),
                entry.signature,
            ),
            "the request must verify under A1, over the stamp it carries",
        )
        // ⚠⚠ One stamp on both planes.
        val tombstone = emitter.stamped.single { it.revokedDeviceId == "victim" }
        assertEquals(entry.authoredAt, tombstone.authoredAt.toStamp(), "the tombstone and the request must share one HLC")
        assertTrue(emitter.unstamped.none { it == Device.ID }, "the Device tombstone must not tick its own stamp")
    }

    @Test
    fun aDeviceWithoutA1_queuesASelfSignedRequest_forItself() = runTest {
        joinWithoutA1()
        val self = syncManager.currentRealmContext()!!
        seedDevice(self.deviceId)

        revoke(self.deviceId)

        val entry = outbox().pending().single()
        assertEquals(Authority.SELF, entry.authority)
        assertTrue(
            crypto.verifyEd25519(
                self.deviceSigningKeyPair.publicKey,
                DeviceAccessRevocation.message(Authority.SELF, "host-owner", self.deviceId, entry.authoredAt),
                entry.signature,
            ),
            "a self request is signed with this device's own key",
        )
        assertEquals(entry.authoredAt, emitter.stamped.single().authoredAt.toStamp())
    }

    /**
     * ⚠⚠ Decision A (R2b): a device without A1 can sign neither form for another device, and its
     * tombstone no longer revokes — so it is refused before anything changes, never half-removed.
     */
    @Test
    fun aDeviceWithoutA1_removingAnother_isRefused_beforeAnythingChanges() = runTest {
        joinWithoutA1()
        seedDevice("victim")

        val error = assertFailsWith<TenshuException> { revoke("victim") }

        assertIs<RecoveryPhraseRequiredError>(error.error)
        assertTrue(outbox().pending().isEmpty(), "nothing queued")
        assertTrue(emitter.stamped.isEmpty() && emitter.unstamped.isEmpty(), "nothing emitted")
        assertNotNull(
            testProxy.database.getData(DataId.StringDataId.create(testProxy.tContext, Device.ID, "victim")),
            "the row is still there: the UI must not show a removal that did not happen",
        )
    }

    // ── draining ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theDrainSendsTheSignedBytes_andDropsTheEntryOnDone() = runTest {
        syncManager.initialize(masterPhrase)
        seedDevice("victim")
        revoke("victim")
        val queued = outbox().pending().single()

        assertTrue(outbox().drain(relay))

        val sent = relay.accessRevocations.single()
        assertEquals(queued.authoredAt.encode(), sent.authoredAt)
        assertEquals(Authority.MASTER, sent.authority)
        assertTrue(sent.signature.contentEquals(queued.signature))
        assertEquals(0, outbox().count())
    }

    @Test
    fun aRetry_keepsTheEntry_andStopsThePass() = runTest {
        syncManager.initialize(masterPhrase)
        seedDevice("a")
        seedDevice("b")
        revoke("a")
        revoke("b")
        relay.accessOutcomes.add(SyncRelay.DeviceAccessRevokeOutcome.Retry("offline"))

        assertFalse(outbox().drain(relay))

        assertEquals(1, relay.accessRevocations.size, "the pass stops at the first retry")
        assertEquals(listOf("a", "b"), outbox().pending().map { it.deviceId }, "both stay owed")

        assertTrue(outbox().drain(relay), "the next tick sends them")
        assertEquals(0, outbox().count())
    }

    @Test
    fun terminalAnswers_dropTheEntry() = runTest {
        syncManager.initialize(masterPhrase)
        listOf("a", "b", "c").forEach { seedDevice(it); revoke(it) }
        relay.accessOutcomes.addAll(
            listOf(
                SyncRelay.DeviceAccessRevokeOutcome.Done(applied = false),
                SyncRelay.DeviceAccessRevokeOutcome.Refused(401, "Invalid revocation signature"),
                SyncRelay.DeviceAccessRevokeOutcome.Done(applied = true),
            ),
        )

        assertTrue(outbox().drain(relay))

        assertEquals(3, relay.accessRevocations.size)
        assertEquals(0, outbox().count(), "done, stale and refused are all terminal")
        // ⚠⚠ But a removal the relay did not apply stays visible, never plainly "removed" (2026-11-12).
        assertEquals(
            mapOf("a" to PendingDeviceAccessRevocation.State.SUPERSEDED, "b" to PendingDeviceAccessRevocation.State.REFUSED),
            outbox().statuses().associate { it.deviceId.get() to it.relayState.get() },
            "the applied one leaves; the stale and the refused stay, for display",
        )
        assertEquals("401 Invalid revocation signature", outbox().statuses().single { it.deviceId.get() == "b" }.relayDetail.get())

        assertTrue(outbox().drain(relay))
        assertEquals(3, relay.accessRevocations.size, "a refused or stale removal is never resent: the same bytes, the same answer")
    }

    /**
     * A `self` request repeated after it applied is answered [SyncRelay.DeviceAccessRevokeOutcome.NotRegistered]: the
     * access is already gone, so it is done — never kept as "refused". ⚠⚠ A `self` 401 for a bad signature is NOT: the
     * key is still on record, so the removal did not apply and Forget must say so.
     */
    @Test
    fun aRepeatedSelfRemoval_isDone_butASelfBadSignature_isRefused() = runTest {
        syncManager.initialize(masterPhrase)
        val ownerId = syncManager.currentOwnerId!!
        val stamp = org.tenshu.core.time.HybridLogicalClock.create(testProxy.tContext, physicalTime = 5_000L, logicalCounter = 0L, deviceId = "me")
        outbox().enqueue(ownerId, "me", DeviceAccessRevocation.Authority.SELF, stamp, ByteArray(64))
        outbox().enqueue(ownerId, "me-too", DeviceAccessRevocation.Authority.SELF, stamp, ByteArray(64))
        outbox().enqueue(ownerId, "other", DeviceAccessRevocation.Authority.MASTER, stamp, ByteArray(64))
        relay.accessOutcomes.add(SyncRelay.DeviceAccessRevokeOutcome.NotRegistered("401 Unknown device"))
        relay.accessOutcomes.add(SyncRelay.DeviceAccessRevokeOutcome.Refused(401, "Invalid revocation signature"))
        relay.accessOutcomes.add(SyncRelay.DeviceAccessRevokeOutcome.NotRegistered("401 Unknown device"))

        assertTrue(outbox().drain(relay))

        assertEquals(setOf("me-too", "other"), outbox().statuses().map { it.deviceId.get() }.toSet(), "only the token self 401 left")
        assertFalse(outbox().notApplied("me"))
        assertTrue(outbox().notApplied("me-too"), "a bad self signature is surfaced, not read as done")
        assertTrue(outbox().notApplied("other"), "a master request is never done by NotRegistered")
    }

    /**
     * §5b R6d: a removal record that syncs in is delivered from THIS device too — its own outbox, the record's own
     * signed bytes — because the author's request may never have left. Once per stamp: a repeat ingest queues nothing
     * more, and a row the relay already answered (here: refused) keeps its answer rather than being owed afresh.
     */
    @Test
    fun anIngestedRemovalRecord_isOwedHereToo_oncePerStamp() = runTest {
        syncManager.initialize(masterPhrase)
        seedDevice("a"); revoke("a")
        val sent = outbox().pending().single()
        assertTrue(outbox().drain(relay), "the author's own request went through")
        assertEquals(0, outbox().count())

        val records = DeviceRecords(testProxy.executionContext, syncManager, crypto)
        val observer = DeviceRecordObserver(testProxy.executionContext, records)
        val record = records.removals().single()
        observer.onDataSaved(record, org.tenshu.core.common.proxy.WriteOrigin.SYNC_INGEST)

        assertEquals(listOf(sent), outbox().pending(), "the peer owes the very bytes the author signed")
        observer.onDataSaved(record, org.tenshu.core.common.proxy.WriteOrigin.SYNC_INGEST)
        assertEquals(1, outbox().count(), "a repeat ingest queues nothing more")

        relay.accessOutcomes.add(SyncRelay.DeviceAccessRevokeOutcome.Refused(400, "Bad Request"))
        assertTrue(outbox().drain(relay))
        observer.onDataSaved(record, org.tenshu.core.common.proxy.WriteOrigin.SYNC_INGEST)
        assertEquals(0, outbox().count(), "an answered stamp is not owed again")
        assertTrue(outbox().notApplied("a"))
    }

    /** A local save is this device's own removal, which `RevokeDevice` queued already — never queued twice. */
    @Test
    fun aLocallySavedRemovalRecord_isNotQueuedAgainByTheObserver() = runTest {
        syncManager.initialize(masterPhrase)
        seedDevice("a"); revoke("a")
        assertTrue(outbox().drain(relay))
        val records = DeviceRecords(testProxy.executionContext, syncManager, crypto)
        DeviceRecordObserver(testProxy.executionContext, records)
            .onDataSaved(records.removals().single(), org.tenshu.core.common.proxy.WriteOrigin.USER)
        assertEquals(0, outbox().count())
    }

    /** `426` is protocol skew mid-rollout: kept, owed, retried — an update on either side clears it. */
    @Test
    fun a426_keepsTheEntryOwed_marksItNeedsUpdate_andStopsThePass() = runTest {
        syncManager.initialize(masterPhrase)
        listOf("a", "b").forEach { seedDevice(it); revoke(it) }
        relay.accessOutcomes.add(SyncRelay.DeviceAccessRevokeOutcome.UpgradeRequired("426 Upgrade Required"))

        assertFalse(outbox().drain(relay))

        assertEquals(listOf("a", "b"), outbox().pending().map { it.deviceId }, "both stay owed")
        assertEquals(PendingDeviceAccessRevocation.State.NEEDS_UPDATE, outbox().statuses().first { it.deviceId.get() == "a" }.relayState.get())

        assertTrue(outbox().drain(relay), "after the update the same bytes go through")
        assertEquals(0, outbox().count())
        assertEquals(emptyList(), outbox().statuses(), "and nothing is left to show")
    }

    @Test
    fun removingARefusedDeviceAgain_owesItAfresh() = runTest {
        syncManager.initialize(masterPhrase)
        seedDevice("a"); revoke("a")
        relay.accessOutcomes.add(SyncRelay.DeviceAccessRevokeOutcome.Refused(400, "Bad Request"))
        assertTrue(outbox().drain(relay))
        assertEquals(0, outbox().count())

        seedDevice("a") // its row back (it still connects), and the user removes it again
        revoke("a")

        assertEquals(listOf("a"), outbox().pending().map { it.deviceId }, "a new removal replaces the refused one")
        assertEquals(PendingDeviceAccessRevocation.State.OWED, outbox().statuses().single().relayState.get())
    }

    /**
     * ⚠⚠ R2b: a `404` (a relay not yet upgraded, or one that does not hold the realm) KEEPS the
     * request. The tombstone no longer revokes, so dropping it would lose the access cut for good.
     */
    @Test
    fun a404_keepsTheEntry_andStopsThePass() = runTest {
        syncManager.initialize(masterPhrase)
        listOf("a", "b").forEach { seedDevice(it); revoke(it) }
        relay.accessOutcomes.add(SyncRelay.DeviceAccessRevokeOutcome.Unsupported)

        assertFalse(outbox().drain(relay))

        assertEquals(listOf("a", "b"), outbox().pending().map { it.deviceId }, "both stay owed")
        assertEquals(PendingDeviceAccessRevocation.State.RELAY_UNKNOWN, outbox().statuses().first { it.deviceId.get() == "a" }.relayState.get())
    }

    /**
     * The scheduler drains a removal naming THIS device only after its tombstone (the route cuts our
     * own JWT); the filter is what lets it hold that one back.
     */
    @Test
    fun aFilteredDrain_leavesTheExcludedEntriesOwed() = runTest {
        syncManager.initialize(masterPhrase)
        listOf("a", "b").forEach { seedDevice(it); revoke(it) }

        assertTrue(outbox().drain(relay) { it.deviceId != "b" })

        assertEquals(listOf("a"), relay.accessRevocations.map { it.deviceId })
        assertEquals(listOf("b"), outbox().pending().map { it.deviceId })
    }

    /** ⚠⚠ A later removal replaced the row while the first request was in flight: it stays owed. */
    @Test
    fun removingASettledEntry_keepsAReplacementForTheSameDevice() = runTest {
        syncManager.initialize(masterPhrase)
        seedDevice("victim")
        revoke("victim")
        val first = outbox().pending().single()

        val later = syncManager.hlcManager.tick()
        outbox().enqueue(first.ownerId, "victim", Authority.MASTER, later, ByteArray(64) { 7 })
        outbox().remove(first)

        val left = assertNotNull(outbox().pending().singleOrNull())
        assertEquals(later.toStamp(), left.authoredAt)
    }
}

/** Records which tombstones were emitted at a caller's stamp and which ticked their own. */
private class StampRecordingEmitter : TombstoneEmitter {
    data class Stamped(val type: String, val revokedDeviceId: String?, val authoredAt: HybridLogicalClock)

    val stamped = mutableListOf<Stamped>()

    /** The entity types of every emit that ticked its own stamp. */
    val unstamped = mutableListOf<String>()

    override fun emit(targetEntityIdHash: String, targetEntityType: String, revokedDeviceId: String?): String {
        unstamped += targetEntityType
        return "block-${stamped.size + unstamped.size}"
    }

    override fun emit(targetEntityId: DataId, targetEntityType: String, revokedDeviceId: String?): String =
        emit(targetEntityId.getDataIdHash().toString(), targetEntityType, revokedDeviceId)

    override fun emitAt(
        targetEntityId: DataId,
        targetEntityType: String,
        revokedDeviceId: String?,
        authoredAt: HybridLogicalClock,
    ): String {
        stamped += Stamped(targetEntityType, revokedDeviceId, authoredAt)
        return "block-${stamped.size + unstamped.size}"
    }
}
