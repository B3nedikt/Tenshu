package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.util.sha256
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.RetainedRealmKey
import org.tenshu.identity.SharedRealmMembership
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * GR-7: once a rotation retired a realm key, no ingested `SharedRealmMembership` can make it current again.
 *
 * The case it pins: the relay echoes this device's own pre-rotation membership row back. Its HLC is newer
 * than anything this device has *ingested* for that row, so LWW alone would let it overwrite the
 * rotation's local write — and new content would go out under the key the removed member still holds.
 */
@OptIn(ExperimentalStdlibApi::class)
class RetiredRealmKeyIngestTest {

    private lateinit var testProxy: TestProxy
    private lateinit var processor: SyncProcessor
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var crypto: SyncCryptoManager
    private val realmId = "shared-realm-under-rotation"

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
        syncManager.initialize("retired-realm-key-ingest-phrase")
        processor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(testProxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
        )
    }

    private fun syncTest(body: suspend TestScope.() -> Unit): TestResult = runTest { setup(); body() }

    private var blockCounter = 0
    private fun ingest(data: Data, atMillis: Long) {
        val payload = org.tenshu.sync.DataBlockPayload.create(context = testProxy.tContext, propertyId = null, value = data)
        processor.processSyncBlock(
            EnvelopeAuth.sealSyncBlock(
                crypto = crypto,
                context = testProxy.tContext,
                blockId = "block-${blockCounter++}",
                ownerId = syncManager.currentOwnerId!!,
                hlc = HybridLogicalClock.create(testProxy.tContext, physicalTime = atMillis, logicalCounter = 0L, deviceId = "this-device"),
                targetEntityIdHash = null,
                plaintext = BlockPayloadEnvelope.seal(BinarySerializer().serialize(payload), testProxy.versionStampBytes()),
                identityKey = syncManager.getDerivedKeys()!!.sharedIdentityKey,
            ),
        )
    }

    private fun membership(wrapped: ByteArray) =
        SharedRealmMembership.create(testProxy.tContext, realmId, wrapped, SharedRealmMembership.Role.OWNER)

    private fun currentWrappedKey(): ByteArray =
        (testProxy.database.getData(DataId.StringDataId.create(testProxy.tContext, SharedRealmMembership.ID, realmId)) as SharedRealmMembership)
            .wrappedMasterKey.get()

    /** What `SharedRealmGrantManager.adoptNewRealmKey` leaves behind: the old key retired, the new one current. */
    private suspend fun rotate(from: ByteArray, to: ByteArray) {
        testProxy.executionContext.saveData(
            RetainedRealmKey.create(testProxy.tContext, realmId, sha256(from).toHexString(), from, retiredAt = 5_000L),
        )
        testProxy.executionContext.saveData(membership(to))
    }

    private val oldWrapped = ByteArray(48) { 1 }
    private val newWrapped = ByteArray(48) { 2 }

    @Test
    fun anEchoOfTheRowARotationRetired_doesNotReinstateTheRetiredKey() = syncTest {
        ingest(membership(oldWrapped), atMillis = 1_000L)
        rotate(from = oldWrapped, to = newWrapped)

        ingest(membership(oldWrapped), atMillis = 4_000L) // the relay's late echo — newer than the last ingest

        assertContentEquals(newWrapped, currentWrappedKey())
    }

    @Test
    fun aRowCarryingAKeyNeverRetired_stillAppliesByLww() = syncTest {
        ingest(membership(oldWrapped), atMillis = 1_000L)
        rotate(from = oldWrapped, to = newWrapped)
        val newerWrapped = ByteArray(48) { 3 } // another of this owner's devices rotated again

        ingest(membership(newerWrapped), atMillis = 6_000L)

        assertContentEquals(newerWrapped, currentWrappedKey())
    }

    /** A rotation cut short between its two saves leaves only the retired-key row to sync: a device with no
     *  membership yet must still take the realm, on that key, rather than lose it. */
    @Test
    fun withNoMembershipStored_aRetiredKeyRowStillLetsTheMembershipIn() = syncTest {
        testProxy.executionContext.saveData(
            RetainedRealmKey.create(testProxy.tContext, realmId, sha256(oldWrapped).toHexString(), oldWrapped, retiredAt = 5_000L),
        )

        ingest(membership(oldWrapped), atMillis = 1_000L)

        assertContentEquals(oldWrapped, currentWrappedKey())
    }
}
