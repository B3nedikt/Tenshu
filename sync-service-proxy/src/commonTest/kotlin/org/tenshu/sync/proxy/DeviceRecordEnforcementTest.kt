package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.common.util.HlcStamp
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.Device
import org.tenshu.identity.DeviceRecord
import org.tenshu.identity.IdentityService
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.DeviceAccessRevocation
import org.tenshu.sync.common.DeviceAccessRevocation.Authority
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.DeleteBlock
import org.tenshu.core.core.create
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `sync-entity-identity.md` §5b R6a — the realm's signed device records are the client's authority for
 * "removed":
 *  - an incoming `Device` row for a removed id is **dropped at ingest** (no resurrection by LWW);
 *  - a removal record arriving takes the row out of the store and the `DEVICES` relation;
 *  - a row that does not verify — anyone with the realm key can write one — changes nothing.
 *
 * R6b: an A1 re-admission at a newer stamp lifts a removal; the record set is append-only (a tombstone
 * neither deletes a record nor keeps one out); a re-emit sends only valid rows.
 */
class DeviceRecordEnforcementTest {

    private lateinit var testProxy: TestProxy
    private lateinit var processor: SyncProcessor
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var crypto: SyncCryptoManager
    private val phrase = "device-record-enforcement-phrase"

    private fun CoroutineScope.setup() {
        testProxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().also {
                it.registerConfig(syncServiceCommonModule)
                it.registerConfig(identityServiceCommonModule)
            },
            coroutineScope = this,
        )
        crypto = BootstrapSyncCryptoManager()
        syncManager = SyncManagerImpl(context = testProxy.tContext, cryptoManager = crypto, storageDelegate = InMemorySecureStorageDelegate())
        syncManager.initialize(phrase)
        processor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(testProxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
        )
    }

    private fun syncTest(body: suspend TestScope.() -> Unit): TestResult = runTest { setup(); body() }

    private fun records() = DeviceRecords(testProxy.executionContext, syncManager, crypto)

    private fun removalRecord(deviceId: String, signature: ByteArray? = null): DeviceRecord {
        val ownerId = syncManager.currentOwnerId!!
        val master = crypto.deriveMasterKeyPair(phrase)
        val stamp = HlcStamp(5_000L, 0L, "owner-device")
        val sig = signature ?: crypto.signEd25519(
            master.privateKey,
            DeviceAccessRevocation.message(Authority.MASTER, ownerId, deviceId, stamp),
        )
        return DeviceRecord.create(
            context = testProxy.tContext,
            kind = DeviceRecord.Kind.REMOVAL,
            deviceId = deviceId,
            authority = Authority.MASTER.wire,
            authoredAt = stamp.encode(),
            signerPublicKey = master.publicKey,
            signature = sig,
        )
    }

    private fun deviceEntity(deviceId: String): Entity<Device> = Entity.create(
        context = testProxy.tContext,
        composite = Device.create(
            context = testProxy.tContext,
            deviceId = deviceId,
            name = "renamed after removal",
            publicKey = byteArrayOf(0x01),
            isAuthorized = true,
        ),
        properties = emptyMap(),
    )

    private fun ingestBlock(data: Data, blockId: String): SyncBlock {
        val payload = org.tenshu.sync.DataBlockPayload.create(context = testProxy.tContext, propertyId = null, value = data)
        return EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = testProxy.tContext,
            blockId = blockId,
            ownerId = syncManager.currentOwnerId!!,
            hlc = HybridLogicalClock.create(testProxy.tContext, physicalTime = 9_000L, logicalCounter = 0L, deviceId = "removed-device"),
            targetEntityIdHash = null,
            plaintext = BlockPayloadEnvelope.seal(BinarySerializer().serialize(payload), testProxy.versionStampBytes()),
            identityKey = syncManager.getDerivedKeys()!!.sharedIdentityKey,
        )
    }

    private fun deviceRow(deviceId: String) =
        testProxy.database.getData(DataId.StringDataId.create(testProxy.tContext, Device.ID, deviceId))

    private fun tombstoneAt(data: Data, atMillis: Long): DeleteBlock = EnvelopeAuth.tagTombstone(
        testProxy.tContext,
        DeleteBlock.create(
            context = testProxy.tContext,
            blockId = "delete-record-$atMillis",
            ownerId = syncManager.currentOwnerId!!,
            hlc = HybridLogicalClock.create(testProxy.tContext, atMillis, 0L, "realm-key-holder"),
            targetEntityIdHash = testProxy.executionContext.getDataId(data)!!.wireIdIn(syncManager),
            targetEntityType = DeviceRecord.ID,
        ),
        syncManager.getDerivedKeys()!!.sharedIdentityKey,
    )

    private fun recordRow(data: Data) = testProxy.database.getData(testProxy.executionContext.getDataId(data)!!)

    @Test
    fun aValidA1Record_marksTheDeviceRemoved() = syncTest {
        testProxy.executionContext.saveData(removalRecord("removed-device"))
        assertTrue(records().isRemoved("removed-device"))
        assertFalse(records().isRemoved("other-device"))
    }

    /** ⚠⚠ The resurrection: a rename the removed device pushed before its cut must not bring it back. */
    @Test
    fun anIncomingDeviceRow_forARemovedDevice_isDroppedAtIngest() = syncTest {
        testProxy.executionContext.saveData(removalRecord("removed-device"))

        processor.processSyncBlock(ingestBlock(deviceEntity("removed-device"), "late-rename"))

        assertNull(deviceRow("removed-device"), "a removed device does not reappear")
    }

    @Test
    fun anIncomingDeviceRow_forAnyOtherDevice_isUnaffected() = syncTest {
        testProxy.executionContext.saveData(removalRecord("removed-device"))

        processor.processSyncBlock(ingestBlock(deviceEntity("live-device"), "live-row"))

        assertNotNull(deviceRow("live-device"))
    }

    /** ⚠⚠ Anyone with the realm key can write a row; only the A1 signature makes it a record. */
    @Test
    fun aRecordThatDoesNotVerify_changesNothing() = syncTest {
        testProxy.executionContext.saveData(removalRecord("live-device", signature = ByteArray(64) { 7 }))

        assertFalse(records().isRemoved("live-device"))
        processor.processSyncBlock(ingestBlock(deviceEntity("live-device"), "live-row"))
        assertNotNull(deviceRow("live-device"))
    }

    @Test
    fun anArrivingRecord_takesTheRowOutOfTheStoreAndTheRelation() = syncTest {
        val row = deviceEntity("removed-device")
        testProxy.executionContext.saveData(row)
        testProxy.executionContext.database.addToArray(testProxy.executionContext.relationReference(IdentityService.Relations.DEVICES), row)
        val observer = DeviceRecordObserver(testProxy.executionContext, records())
        val record = removalRecord("removed-device")

        testProxy.executionContext.saveData(record)
        observer.onDataSaved(record, WriteOrigin.SYNC_INGEST)

        assertNull(deviceRow("removed-device"))
        val listed = testProxy.database
            .getArrayData(testProxy.executionContext.relationReference(IdentityService.Relations.DEVICES))
            ?.toList().orEmpty()
        assertTrue(listed.isEmpty(), "the device list no longer shows it")
    }

    // ── R6b ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aNewerA1Readmission_liftsTheRemoval_andTheRowIsAcceptedAgain() = syncTest {
        testProxy.executionContext.saveData(removalRecord("removed-device"))
        records().saveReadmission("removed-device", HlcStamp(6_000L, 0L, "owner-device").encode(), crypto.deriveMasterKeyPair(phrase))

        assertFalse(records().isRemoved("removed-device"))
        processor.processSyncBlock(ingestBlock(deviceEntity("removed-device"), "after-readmission"))
        assertNotNull(deviceRow("removed-device"), "a re-admitted device is listed again")
    }

    @Test
    fun anOlderReadmission_doesNotLiftIt() = syncTest {
        testProxy.executionContext.saveData(removalRecord("removed-device"))
        records().saveReadmission("removed-device", HlcStamp(4_000L, 0L, "owner-device").encode(), crypto.deriveMasterKeyPair(phrase))

        assertTrue(records().isRemoved("removed-device"))
    }

    /** ⚠⚠ Anyone with the realm key can author a tombstone; erasing a removal would bring the device back. */
    @Test
    fun aTombstone_neverDeletesARecord() = syncTest {
        val record = removalRecord("removed-device")
        processor.processSyncBlock(ingestBlock(record, "record"))
        assertNotNull(recordRow(record))

        processor.processDeleteBlock(tombstoneAt(record, atMillis = 99_000L))

        assertNotNull(recordRow(record), "the record survives a tombstone at its key")
        assertTrue(records().isRemoved("removed-device"))
    }

    @Test
    fun aRecordArriving_underARecordedTombstone_isStillApplied() = syncTest {
        val record = removalRecord("removed-device")
        processor.processDeleteBlock(tombstoneAt(record, atMillis = 99_000L))

        processor.processSyncBlock(ingestBlock(record, "record-after-tombstone"))

        assertNotNull(recordRow(record), "a newer tombstone cannot keep a record out")
        assertTrue(records().isRemoved("removed-device"))
    }

    @Test
    fun reEmitAll_sendsOnlyValidRecords() = syncTest {
        testProxy.executionContext.saveData(removalRecord("removed-device"))
        testProxy.executionContext.saveData(removalRecord("forged-device", signature = ByteArray(64) { 7 }))

        assertEquals(1, records().reEmitAll())
    }

    /** ⚠⚠ Last-writer-wins must not let a same-key garbage row replace a valid removal. */
    @Test
    fun aSameKeyRowWithABadSignature_neverReplacesAValidRecord() = syncTest {
        val genuine = removalRecord("removed-device")
        processor.processSyncBlock(ingestBlock(genuine, "genuine"))

        processor.processSyncBlock(ingestBlock(removalRecord("removed-device", signature = ByteArray(64) { 7 }), "overwrite"))

        assertTrue(records().isRemoved("removed-device"), "the valid record still stands")
    }

    @Test
    fun anUnverifiableIncomingRecord_isNotStored() = syncTest {
        val forged = removalRecord("live-device", signature = ByteArray(64) { 7 })
        processor.processSyncBlock(ingestBlock(forged, "forged"))
        assertNull(recordRow(forged))
    }

    // ── R6c ──────────────────────────────────────────────────────────────────────────────────────

    /** Manage devices' "Removed" section: the deciding removal per removed device, nothing else. */
    @Test
    fun removals_listTheDecidingRemoval_andDropAReadmittedDevice() = syncTest {
        testProxy.executionContext.saveData(removalRecord("removed-device"))
        testProxy.executionContext.saveData(removalRecord("readmitted-device"))
        records().saveReadmission("readmitted-device", HlcStamp(6_000L, 0L, "owner-device").encode(), crypto.deriveMasterKeyPair(phrase))

        val removals = records().removals()

        assertEquals(listOf("removed-device"), removals.map { it.deviceId.get() })
        assertEquals(DeviceRecord.Kind.REMOVAL, removals.single().kind.get())
    }

    /** PR review: a valid removal planted at another record's key (here: a re-admission's) is not a record. */
    @Test
    fun aValidRecordAtAnotherRecordsKey_isNotAValidRecord() = syncTest {
        val genuine = removalRecord("removed-device")
        val planted: DeviceRecord = testProxy.tContext.create(
            type = DeviceRecord.ID,
            fields = listOf(
                DeviceRecord.keyOf(DeviceRecord.Kind.READMISSION, "removed-device", genuine.authoredAt.get()),
                genuine.kind.get(), genuine.deviceId.get(), genuine.authority.get(), genuine.authoredAt.get(),
                genuine.signerPublicKey.get(), genuine.signature.get(),
            ).map { if (it is String) it.toTString(testProxy.tContext) else (it as ByteArray).toTByteArray(testProxy.tContext) },
        )
        processor.processSyncBlock(ingestBlock(planted, "planted"))
        assertFalse(records().isRemoved("removed-device"))
    }
}
