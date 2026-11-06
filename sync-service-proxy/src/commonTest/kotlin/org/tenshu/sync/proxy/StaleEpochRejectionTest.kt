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
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression for `docs/sync/threat-model.md` T-K-02 — merge-time enforcement of
 * `admittedAtEpoch < currentRealmEpoch` rejection. Companion to:
 *
 *  - [RevokeDeviceImplementationTest] / [RevokeDevicePropagationTest] — the emit + wire
 *    side of revocation tombstones.
 *  - [SyncProcessorTombstoneTest] — the tombstone-vs-SyncBlock HLC interaction.
 *
 * This test asserts the receiver-side check itself: a `Device` block carrying a stale
 * `admittedAtEpoch` is dropped before it can land in the local DB, while equal-epoch and
 * future-epoch blocks pass through unchanged.
 */
class StaleEpochRejectionTest {

    private lateinit var testProxy: TestProxy
    private lateinit var processor: SyncProcessor
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var crypto: SyncCryptoManager
    private lateinit var tombstoneRegistry: TombstoneRegistry

    /**
     * ⚠ A `CoroutineScope` extension, not an `@BeforeTest`, so the [TestProxy] rides the caller's
     * `TestScope` and `runTest` cancels the loops `initSyncService` starts. `@AfterTest` cannot do
     * it: commonTest requires `Unit` there and has no `runBlocking` (`docs/tech-debt.md` fix-5).
     */
    private fun CoroutineScope.setup() {
        testProxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().also {
                it.registerConfig(syncServiceCommonModule)
                it.registerConfig(identityServiceCommonModule)
            },
            coroutineScope = this,
        )

        // Use BootstrapSyncCryptoManager (no Ed25519 backend required) — the test
        // exercises merge-time epoch rejection, not crypto.
        crypto = BootstrapSyncCryptoManager()
        syncManager = SyncManagerImpl(
            context = testProxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate()
        )
        syncManager.initialize("stale-epoch-test-phrase")

        tombstoneRegistry = TombstoneRegistry(testProxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull)
        processor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = tombstoneRegistry
        )

        // Wire the realm-epoch derivation the same way `initSyncService` does in
        // production — count of `Device`-targeted tombstones the registry has seen.
        syncManager.setRealmEpochProvider {
            tombstoneRegistry.tombstoneCountForType(Device.ID).toLong()
        }
    }

    /**
     * `runTest`, with [setup] already run. Every test in this class needs the fixture, so the
     * call belongs here once rather than as the first line of each body.
     *
     * ⚠⚠ It cannot be an `@BeforeTest`, and that is the whole point: [setup] takes a
     * `CoroutineScope` receiver so the [TestProxy] rides the **`TestScope`**, and `@BeforeTest`
     * runs outside `runTest` where no such scope exists. Nor can the teardown move to
     * `@AfterTest` — this is `commonTest`, where `@AfterTest` returns `Unit` and cannot suspend,
     * and `runBlocking` does not exist. Letting `runTest` own the proxy is what cancels the
     * `SyncScheduler` and 1-second `ScheduleDrainLoop` that `initSyncService` starts
     * (`docs/tech-debt.md` fix-item 5). Same shape as `:maps-shared-logic`'s `vmTest`.
     */
    private fun syncTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        setup()
        body()
    }

    private fun hlc(physicalTime: Long, deviceId: String = "device-remote") =
        HybridLogicalClock.create(
            context = testProxy.tContext,
            physicalTime = physicalTime,
            logicalCounter = 0L,
            deviceId = deviceId
        )

    private fun deviceEntity(deviceId: String, admittedAtEpoch: Long): Entity<Device> {
        val composite = Device.create(
            context = testProxy.tContext,
            deviceId = deviceId,
            name = "Test Device",
            publicKey = byteArrayOf(0x01),
            isAuthorized = true,
            admittedAtEpoch = admittedAtEpoch
        )
        return Entity.create(
            context = testProxy.tContext,
            composite = composite,
            properties = emptyMap()
        )
    }

    private fun syncBlockFor(data: Data, blockId: String, physicalTime: Long): SyncBlock {
        val serializer = BinarySerializer()
        val payload = org.tenshu.sync.DataBlockPayload.create(
            context = testProxy.tContext,
            propertyId = null,
            value = data
        )
        // Envelope bound as AAD (sync-envelope-authentication §3.2) — an unbound block would be
        // dropped for the wrong reason and make the rejection assertions vacuous.
        return EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = testProxy.tContext,
            blockId = blockId,
            ownerId = "user-1",
            hlc = hlc(physicalTime),
            targetEntityIdHash = null,
            plaintext = BlockPayloadEnvelope.seal(serializer.serialize(payload), testProxy.versionStampBytes()),
            identityKey = syncManager.getDerivedKeys()!!.sharedIdentityKey,
        )
    }

    /**
     * Bumps the receiver's `currentRealmEpoch` to 1 by ingesting a tombstone for an unrelated
     * Device — the same shape `RevokeDevice` produces in production.
     */
    private fun bumpRealmEpochToOne() {
        val victim = Device.create(
            context = testProxy.tContext,
            deviceId = "victim",
            name = "Victim",
            publicKey = byteArrayOf(0x09),
            isAuthorized = true,
            admittedAtEpoch = 0L
        )
        val victimDataId = testProxy.executionContext.getDataId(victim)!!
        val tombstone = EnvelopeAuth.tagTombstone(
            testProxy.tContext,
            DeleteBlock.create(
                context = testProxy.tContext,
                blockId = "delete-victim",
                ownerId = "user-1",
                hlc = hlc(50L),
                targetEntityIdHash = victimDataId.getDataIdHash().toString(),
                targetEntityType = Device.ID
            ),
            syncManager.getDerivedKeys()!!.sharedIdentityKey,
        )
        processor.processDeleteBlock(tombstone)
        check(syncManager.currentRealmEpoch == 1L) {
            "Sanity: currentRealmEpoch should be 1 after one Device tombstone, got " +
                    "${syncManager.currentRealmEpoch}"
        }
    }

    @Test
    fun rejectsDeviceBlock_withStaleAdmittedAtEpoch() = syncTest {
        bumpRealmEpochToOne()

        val staleEntity = deviceEntity(deviceId = "imposter", admittedAtEpoch = 0L)
        val block = syncBlockFor(staleEntity, blockId = "stale-block", physicalTime = 100L)

        processor.processSyncBlock(block)

        val staleDataId = DataId.StringDataId.create(
            testProxy.tContext, Device.ID, "imposter"
        )
        assertNull(
            testProxy.database.getData(staleDataId),
            "Device block with admittedAtEpoch=0 < currentRealmEpoch=1 must be dropped " +
                    "(T-K-02 merge-time enforcement)"
        )
    }

    @Test
    fun acceptsDeviceBlock_withCurrentEpoch() = syncTest {
        bumpRealmEpochToOne()

        val freshEntity = deviceEntity(deviceId = "fresh", admittedAtEpoch = 1L)
        val block = syncBlockFor(freshEntity, blockId = "fresh-block", physicalTime = 100L)

        processor.processSyncBlock(block)

        val freshDataId = DataId.StringDataId.create(
            testProxy.tContext, Device.ID, "fresh"
        )
        assertNotNull(
            testProxy.database.getData(freshDataId),
            "Device block with admittedAtEpoch == currentRealmEpoch must pass through"
        )
    }

    @Test
    fun acceptsDeviceBlock_withFutureEpoch() = syncTest {
        // Receiver at epoch 1, sender claims epoch 5 — accept. The threat model only rejects
        // strictly-stale blocks; a future-epoch claim is either legitimate (sender is ahead
        // on tombstones) or a forgery that the signature/tombstone chain will reject
        // through orthogonal defenses. This test pins the "only `<` is rejected" behaviour.
        bumpRealmEpochToOne()

        val futureEntity = deviceEntity(deviceId = "ahead", admittedAtEpoch = 5L)
        val block = syncBlockFor(futureEntity, blockId = "future-block", physicalTime = 100L)

        processor.processSyncBlock(block)

        val futureDataId = DataId.StringDataId.create(
            testProxy.tContext, Device.ID, "ahead"
        )
        assertNotNull(
            testProxy.database.getData(futureDataId),
            "Device block with admittedAtEpoch > currentRealmEpoch must NOT be rejected " +
                    "by the merge-time check (only strictly-stale `<` is rejected)"
        )
    }

    @Test
    fun acceptsDeviceBlock_atEpochZero_whenReceiverIsFreshlyRecovered() = syncTest {
        // Pre-OOB-fetch baseline: a freshly-recovered receiver with no tombstones AND
        // no seeded epoch has `currentRealmEpoch == 0`, so it accepts everything. Pins
        // the legacy behaviour for the case where the OOB fetch hasn't run yet (e.g.
        // `RecoveryClaimImplementation`'s code path before reaching the `seedCurrent
        // RealmEpoch` call). The companion test below proves that once the seed runs,
        // the merge-time check sharpens immediately — closing the second half of the
        // recovery-race carve-out.
        check(syncManager.currentRealmEpoch == 0L) {
            "Sanity: pre-tombstone receiver epoch should be 0"
        }

        val freshEntity = deviceEntity(deviceId = "newcomer", admittedAtEpoch = 0L)
        val block = syncBlockFor(freshEntity, blockId = "newcomer-block", physicalTime = 100L)

        processor.processSyncBlock(block)

        val newcomerDataId = DataId.StringDataId.create(
            testProxy.tContext, Device.ID, "newcomer"
        )
        assertNotNull(
            testProxy.database.getData(newcomerDataId),
            "Pre-OOB-seed receiver accepts Device blocks at epoch 0 — the open half of " +
                    "the carve-out, closed by [seedCurrentRealmEpoch] in the test below"
        )
    }

    @Test
    fun rejectsStaleDeviceBlock_whenReceiverHasSeededRealmEpoch() = syncTest {
        // Closes the second half of the recovery-race carve-out: a freshly-recovered
        // receiver has no tombstones yet, so without OOB seeding `currentRealmEpoch
        // == 0` and stale-epoch Device blocks land in the local DB. After the OOB
        // fetch (sync-relay-api.md §3.6) the receiver's epoch view is sharpened
        // immediately — even before tombstones drain — so the merge-time T-K-02
        // check rejects stale-epoch blocks the same way it would for a long-running
        // device that already has the tombstones locally.
        check(syncManager.currentRealmEpoch == 0L) {
            "Sanity: pre-seed receiver epoch should be 0"
        }

        // Simulate `RecoveryClaimImplementation` having just fetched epoch=2 from the
        // relay and seeded it. No actual tombstones yet on this receiver — the seed is
        // the whole point.
        syncManager.seedCurrentRealmEpoch(2L)
        check(syncManager.currentRealmEpoch == 2L) {
            "Sanity: seeded epoch should win when local provider returns lower"
        }

        val staleEntity = deviceEntity(deviceId = "stale-imposter", admittedAtEpoch = 0L)
        val block = syncBlockFor(staleEntity, blockId = "stale-after-seed", physicalTime = 100L)

        processor.processSyncBlock(block)

        val staleDataId = DataId.StringDataId.create(
            testProxy.tContext, Device.ID, "stale-imposter"
        )
        assertNull(
            testProxy.database.getData(staleDataId),
            "Seeded epoch must let merge-time T-K-02 enforcement work immediately, " +
                    "before tombstones drain — closes the recovery-race carve-out"
        )
    }
}
