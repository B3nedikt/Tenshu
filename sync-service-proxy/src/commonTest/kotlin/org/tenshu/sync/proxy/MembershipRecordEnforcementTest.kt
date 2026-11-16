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
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.IdentityService
import org.tenshu.identity.Member
import org.tenshu.identity.MembershipRecord
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.MembershipCapability
import org.tenshu.sync.common.MembershipRecordVerifier
import org.tenshu.sync.common.MembershipRevocation
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `shared-realm-membership-records.md` MR-2 — a shared realm's processor enforces its signed membership
 * records at the store:
 *  - a record is stored only if valid, never over a valid one, and never removed or kept out by a tombstone;
 *  - a revoked member's `Member` row is dropped at ingest, and taken out when the revocation arrives.
 */
class MembershipRecordEnforcementTest {

    private lateinit var testProxy: TestProxy
    private lateinit var processor: SyncProcessor
    private lateinit var crypto: SyncCryptoManager
    private lateinit var realmKeys: SyncCryptoManager.DerivedKeys
    private lateinit var master: SyncCryptoManager.KeyPair
    private lateinit var realmId: String
    private lateinit var wireIds: WireIdResolver
    private val bob = ByteArray(32) { (it + 40).toByte() }
    private val bobId = MembershipRecordVerifier.memberIdOf(bob)

    private fun CoroutineScope.setup() {
        testProxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().also {
                it.registerConfig(syncServiceCommonModule)
                it.registerConfig(identityServiceCommonModule)
            },
            coroutineScope = this,
        )
        crypto = BootstrapSyncCryptoManager()
        val syncManager = SyncManagerImpl(context = testProxy.tContext, cryptoManager = crypto, storageDelegate = InMemorySecureStorageDelegate())
        syncManager.initialize("membership-record-enforcement-phrase")
        master = crypto.generateDeviceSigningKeyPair()
        realmId = ownerIdForMasterPublicKey(master.publicKey)
        realmKeys = crypto.deriveSharedRealmKeys(crypto.generateSharedRealmMasterKey())
        wireIds = WireIdResolver(syncManager)
        processor = SyncProcessor(
            wireIdResolver = wireIds,
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(testProxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
            realmKeys = realmKeys,
        )
    }

    private fun syncTest(body: suspend TestScope.() -> Unit): TestResult = runTest { setup(); body() }

    private fun records() = MembershipRecords(testProxy.executionContext, crypto)

    private fun capability(signature: ByteArray? = null, issuedAt: Long = 1_000L): MembershipRecord {
        val sig = signature ?: crypto.signEd25519(master.privateKey, MembershipCapability.message(realmId, bob, 0L, issuedAt))
        return MembershipRecord.create(testProxy.tContext, realmId, MembershipRecord.Kind.CAPABILITY, bobId, bob, 0L, issuedAt, master.publicKey, sig)
    }

    private fun revocation(signature: ByteArray? = null, revokedAt: Long = 2_000L): MembershipRecord {
        val sig = signature ?: crypto.signEd25519(master.privateKey, MembershipRevocation.message(realmId, bob, 0L, revokedAt))
        return MembershipRecord.create(testProxy.tContext, realmId, MembershipRecord.Kind.REVOCATION, bobId, bob, 0L, revokedAt, master.publicKey, sig)
    }

    /** MR-3: the owner's row for Bob, signed by the realm master — or by [signer] / with [signature] to forge. */
    private fun bobRow(
        displayName: String = "Bob",
        signer: SyncCryptoManager.KeyPair? = master,
        signature: ByteArray? = null,
        signedAt: Long = 1_500L,
    ): Member {
        val sig = signature ?: signer?.let {
            crypto.signEd25519(it.privateKey, org.tenshu.sync.common.MemberRowAttestation.message(realmId, bobId, displayName, "", Member.Role.MEMBER, 1_500L, signedAt))
        }
        return Member.create(
            testProxy.tContext, realmId, bobId, displayName, "", Member.Role.MEMBER, joinedAt = 1_500L,
            signerPublicKey = signer?.publicKey, signature = sig, signedAt = signedAt,
        )
    }

    private var blockCounter = 0
    private fun ingest(data: Data, atMillis: Long = 9_000L) {
        val payload = org.tenshu.sync.DataBlockPayload.create(context = testProxy.tContext, propertyId = null, value = data)
        processor.processSyncBlock(
            EnvelopeAuth.sealSyncBlock(
                crypto = crypto,
                context = testProxy.tContext,
                blockId = "block-${blockCounter++}",
                ownerId = realmId,
                hlc = HybridLogicalClock.create(testProxy.tContext, physicalTime = atMillis, logicalCounter = 0L, deviceId = "some-member-device"),
                targetEntityIdHash = null,
                plaintext = BlockPayloadEnvelope.seal(BinarySerializer().serialize(payload), testProxy.versionStampBytes()),
                identityKey = realmKeys.sharedIdentityKey,
            ),
        )
    }

    private fun stored(data: Data) = testProxy.database.getData(testProxy.executionContext.getDataId(data)!!)

    private fun bobRowId() = DataId.StringDataId.create(testProxy.tContext, Member.ID, Member.compositeKey(realmId, bobId))

    @Test
    fun aValidRecord_isStored() = syncTest {
        ingest(capability())
        assertTrue(bobId in records().members(realmId).keys)
    }

    @Test
    fun anUnverifiableRecord_isNotStored() = syncTest {
        val forged = capability(signature = ByteArray(64) { 7 })
        ingest(forged)
        assertNull(stored(forged))
    }

    /** ⚠⚠ Last-writer-wins must not let a same-key garbage row replace a revocation and bring Bob back. */
    @Test
    fun aSameKeyGarbageRow_neverReplacesAValidRevocation() = syncTest {
        ingest(capability())
        ingest(revocation())
        ingest(revocation(signature = ByteArray(64) { 7 }), atMillis = 99_000L)
        assertTrue(records().isRevoked(realmId, bobId), "the valid revocation still stands")
    }

    /** ⚠⚠ Anyone with the realm key can author a tombstone; erasing a revocation would bring the member back. */
    @Test
    fun aTombstone_neverDeletesARecord() = syncTest {
        val record = revocation()
        ingest(record)
        processor.processDeleteBlock(
            EnvelopeAuth.tagTombstone(
                testProxy.tContext,
                DeleteBlock.create(
                    context = testProxy.tContext,
                    blockId = "delete-record",
                    ownerId = realmId,
                    hlc = HybridLogicalClock.create(testProxy.tContext, 99_000L, 0L, "some-member-device"),
                    targetEntityIdHash = wireIds.of(testProxy.executionContext.getDataId(record)!!),
                    targetEntityType = MembershipRecord.ID,
                ),
                realmKeys.sharedIdentityKey,
            ),
        )
        assertNotNull(stored(record), "the record survives a tombstone at its key")
    }

    @Test
    fun aRevokedMembersRow_isDroppedAtIngest() = syncTest {
        ingest(capability())
        ingest(revocation())
        ingest(bobRow())
        assertNull(testProxy.database.getData(bobRowId()), "a removed member does not reappear")
    }

    @Test
    fun aRowArrivingBeforeItsCapability_isKept() = syncTest {
        ingest(bobRow())
        assertNotNull(testProxy.database.getData(bobRowId()), "order-safe: not dropped for lack of a record yet")
    }

    @Test
    fun anArrivingRevocation_takesTheRowOutOfTheStoreAndTheRelation() = syncTest {
        val row = bobRow()
        testProxy.executionContext.saveData(row)
        testProxy.executionContext.database.addToArray(testProxy.executionContext.relationReference(IdentityService.Relations.MEMBERS), row)
        val observer = MembershipRecordObserver(testProxy.executionContext, records())
        val record = revocation()

        testProxy.executionContext.saveData(record)
        observer.onDataSaved(record, WriteOrigin.SYNC_INGEST)

        assertNull(testProxy.database.getData(bobRowId()))
        val listed = testProxy.database
            .getArrayData(testProxy.executionContext.relationReference(IdentityService.Relations.MEMBERS))
            ?.toList().orEmpty()
        assertTrue(listed.isEmpty(), "the member list no longer shows Bob")
    }

    @Test
    fun anUnverifiableRevocation_changesNothing() = syncTest {
        val row = bobRow()
        testProxy.executionContext.saveData(row)
        val forged = revocation(signature = ByteArray(64) { 7 })
        testProxy.executionContext.saveData(forged)
        MembershipRecordObserver(testProxy.executionContext, records()).onDataSaved(forged, WriteOrigin.SYNC_INGEST)
        assertNotNull(testProxy.database.getData(bobRowId()))
    }

    // ── MR-3: signed rows ───────────────────────────────────────────────────────────────────────

    @Test
    fun aMasterSignedRow_isStored() = syncTest {
        ingest(bobRow())
        assertNotNull(testProxy.database.getData(bobRowId()))
    }

    /** ⚠⚠ Any realm-key holder can push an unsigned row: it never lands. */
    @Test
    fun anUnsignedRow_isDropped() = syncTest {
        ingest(bobRow(signer = null))
        assertNull(testProxy.database.getData(bobRowId()))
    }

    @Test
    fun aRowSignedByAnyOtherKey_isDropped() = syncTest {
        ingest(bobRow(signer = crypto.generateDeviceSigningKeyPair()))
        assertNull(testProxy.database.getData(bobRowId()))
    }

    /** ⚠⚠ A member renaming another: the owner's signature no longer covers the name, so it never lands. */
    @Test
    fun aRenamedRow_neverOverwritesTheOwnersRow() = syncTest {
        val genuine = bobRow()
        ingest(genuine)
        val forged = bobRow(displayName = "Alice", signature = genuine.signature!!.get())
        ingest(forged, atMillis = 99_000L)
        assertEquals("Bob", (testProxy.database.getData(bobRowId()) as Member).displayName.get())
    }

    /**
     * ⚠⚠ MR-3 replay: an older owner-signed row, re-pushed as a fresh block by any realm-key holder, never undoes a
     * later edit by the owner — the rows are ordered by their signed `signedAt`, not by the carrying block's HLC.
     */
    @Test
    fun anOlderOwnerSignedRow_replayedLater_neverUndoesANewerOne() = syncTest {
        val old = bobRow(displayName = "Bob", signedAt = 1_500L)
        ingest(old, atMillis = 1_000L)
        ingest(bobRow(displayName = "Robert", signedAt = 2_500L), atMillis = 2_000L)

        ingest(old, atMillis = 99_000L) // the replay: newest block, oldest signing

        assertEquals("Robert", (testProxy.database.getData(bobRowId()) as Member).displayName.get())
    }

    // ── PR review: keys bound to content ─────────────────────────────────────────────────────────

    /** ⚠⚠ A validly signed row copied to another member's key hides that member by LWW — refused. */
    @Test
    fun aSignedRowAtAnotherMembersKey_isDropped() = syncTest {
        val genuine = bobRow()
        val squatted: Member = testProxy.tContext.create(
            type = Member.ID,
            fields = listOf(
                realmId, Member.compositeKey(realmId, "victim-member"), bobId, "Bob", "", Member.Role.MEMBER, 1_500L,
                genuine.signerPublicKey!!.get(), genuine.signature!!.get(), 1_500L,
            ),
        )
        ingest(squatted)
        assertNull(testProxy.database.getData(DataId.StringDataId.create(testProxy.tContext, Member.ID, Member.compositeKey(realmId, "victim-member"))))
    }

    /** ⚠⚠ A valid record planted at another record's key would make the append-only rule refuse the genuine one. */
    @Test
    fun aValidRecordAtAnotherRecordsKey_isRefused_andTheGenuineOneStillLands() = syncTest {
        val cap = capability()
        val revocationKey = MembershipRecord.keyOf(realmId, MembershipRecord.Kind.REVOCATION, bobId, 0L, 2_000L)
        val planted: MembershipRecord = testProxy.tContext.create(
            type = MembershipRecord.ID,
            fields = listOf(
                revocationKey, realmId, MembershipRecord.Kind.CAPABILITY, bob, 0L, 1_000L,
                master.publicKey, cap.signature.get(), ByteArray(0),
            ),
        )
        ingest(planted)
        ingest(revocation())
        assertTrue(records().isRevoked(realmId, bobId), "the genuine revocation landed at its key")
    }

    /** Device records belong to the personal realm only; a shared realm's stream never stores one. */
    @Test
    fun aDeviceRecordInASharedRealm_isDropped() = syncTest {
        val row = org.tenshu.identity.DeviceRecord.create(
            testProxy.tContext, org.tenshu.identity.DeviceRecord.Kind.REMOVAL, "some-device", "master", "1-0-x",
            master.publicKey, ByteArray(64),
        )
        ingest(row)
        assertNull(stored(row))
    }
}
