package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.util.generateId
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.DeltaBatchPayload
import org.tenshu.sync.SyncDelta
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.SyncBlock
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.RetiredRealmKeys
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * GR-7 retained keyring (`shared-realm-membership-grant.md` §13.4): after a
 * member-removal rotation a shared realm's current key is `Kr′`, but blocks written
 * under the old `Kr` stay encrypted under it (ADR-0017, no bulk re-encryption). A
 * processor registered with `derivedKeys = Kr′` and `retiredKeys = [Kr]`
 * must still decrypt + apply an old-`Kr` block (trial-decrypt), while a block under a
 * key in *neither* list is dropped (no skeleton key).
 *
 * ⭐ D-c (`sync-envelope-authentication.md` §3.5): an old-`Kr` block is accepted only if its HLC
 * predates `Kr`'s retirement (+5 min skew) — a removed member keeps no authoring power.
 *
 * Mirrors [MultiRealmPullIsolationTest]'s pull harness; the only delta is that the
 * block is encrypted under the *retired* key while the realm is registered under the
 * *current* one with the retired key in the keyring.
 */
class RetainedKeyringTrialDecryptTest {

    private val oldKeys = SyncCryptoManager.DerivedKeys(
        sharedIdentityKey = ByteArray(32) { 0x11 },
        sharedVolumeKey = ByteArray(32) { 0x22 },
    )
    private val newKeys = SyncCryptoManager.DerivedKeys(
        sharedIdentityKey = ByteArray(32) { 0x55 },
        sharedVolumeKey = ByteArray(32) { 0x66 },
    )
    private val strangerKeys = SyncCryptoManager.DerivedKeys(
        sharedIdentityKey = ByteArray(32) { 0x77 },
        sharedVolumeKey = ByteArray(32) { 0x78 },
    )

    @Test
    fun blockUnderRetiredKey_appliesViaKeyring() = runTest {
        val f = fixture(encryptUnder = oldKeys, historical = listOf(oldKeys))

        f.components.realmRegistry.register(f.sharedRealm, f.relay)
        f.components.scheduler.tickOnce()

        val applied = f.proxy.executionContext.database.getData(f.dataId) as? SyntheticTestData
        assertNotNull(applied, "a block written under the retired Kr must decrypt via the keyring")
        assertEquals("rotated.txt", applied.name.get())
        assertEquals(42L, applied.value.get())
    }

    /** ⭐ D-c: the removed member writes under the key it still holds, AFTER the rotation retired it. */
    @Test
    fun blockUnderRetiredKey_stampedAfterRetirement_isDropped() = runTest {
        val f = fixture(encryptUnder = oldKeys, historical = listOf(oldKeys), retiredAgoMs = 60 * 60 * 1000L)

        f.components.realmRegistry.register(f.sharedRealm, f.relay)
        f.components.scheduler.tickOnce()

        assertNull(
            f.proxy.executionContext.database.getData(f.dataId),
            "a retired key must not author anything stamped after its retirement",
        )
    }

    /** D-c's skew allowance: a write a member's clock stamped just past the retirement still lands. */
    @Test
    fun blockUnderRetiredKey_withinTheSkewWindow_applies() = runTest {
        val f = fixture(encryptUnder = oldKeys, historical = listOf(oldKeys), retiredAgoMs = 60 * 1000L)

        f.components.realmRegistry.register(f.sharedRealm, f.relay)
        f.components.scheduler.tickOnce()

        assertNotNull(f.proxy.executionContext.database.getData(f.dataId) as? SyntheticTestData)
    }

    /** ⭐ The relay re-stamps a genuine retired-key block into the past: the bound envelope refuses it. */
    @Test
    fun retiredKeyBlock_backdatedByTheRelay_isDropped() = runTest {
        val f = fixture(
            encryptUnder = oldKeys, historical = listOf(oldKeys), retiredAgoMs = 60 * 60 * 1000L,
            relayBackdatesTo = 1_000L,
        )

        f.components.realmRegistry.register(f.sharedRealm, f.relay)
        f.components.scheduler.tickOnce()

        assertNull(f.proxy.executionContext.database.getData(f.dataId))
    }

    /**
     * ⭐ A removed member backdates the CONTAINER but carries a post-retirement
     * delta inside it. Each delta merges at its own HLC, so the inner clock must pass the cutoff too.
     */
    @Test
    fun retiredKeyDeltaBatch_withAPostRetirementInnerDelta_isDropped() = runTest {
        val f = fixture(
            encryptUnder = oldKeys, historical = listOf(oldKeys), retiredAgoMs = 60 * 60 * 1000L,
            deltaBatchOuterPhys = 1_000L,
        )

        f.components.realmRegistry.register(f.sharedRealm, f.relay)
        f.components.scheduler.tickOnce()

        assertNull(f.proxy.executionContext.database.getData(f.dataId))
    }

    /** ...while a retired-key batch whose every clock predates the retirement still applies. */
    @Test
    fun retiredKeyDeltaBatch_entirelyBeforeRetirement_applies() = runTest {
        val f = fixture(
            encryptUnder = oldKeys, historical = listOf(oldKeys), retiredAgoMs = 60 * 60 * 1000L,
            deltaBatchOuterPhys = 1_000L, innerDeltaPhys = 1_000L,
        )

        f.components.realmRegistry.register(f.sharedRealm, f.relay)
        f.components.scheduler.tickOnce()

        assertNotNull(f.proxy.executionContext.database.getData(f.dataId) as? SyntheticTestData)
    }

    @Test
    fun blockUnderCurrentKey_stillApplies_withKeyringPresent() = runTest {
        // Fast path unchanged: the current key decrypts even with a keyring present.
        val f = fixture(encryptUnder = newKeys, historical = listOf(oldKeys))

        f.components.realmRegistry.register(f.sharedRealm, f.relay)
        f.components.scheduler.tickOnce()

        assertNotNull(
            f.proxy.executionContext.database.getData(f.dataId) as? SyntheticTestData,
            "a block under the current Kr′ must still apply",
        )
    }

    @Test
    fun blockUnderUnknownKey_isDropped() = runTest {
        // A key in neither the current slot nor the keyring must not decrypt — the
        // keyring is not a skeleton key.
        val f = fixture(encryptUnder = strangerKeys, historical = listOf(oldKeys))

        f.components.realmRegistry.register(f.sharedRealm, f.relay)
        f.components.scheduler.tickOnce()

        assertNull(
            f.proxy.executionContext.database.getData(f.dataId),
            "a block under a key absent from current+keyring must be dropped",
        )
    }

    // ---- fixture ---------------------------------------------------------

    private class Fixture(
        val proxy: TestProxy,
        val components: SyncProxyComponents,
        val sharedRealm: RealmContext,
        val relay: MockSyncRelay,
        val dataId: DataId,
    )

    private suspend fun CoroutineScope.fixture(
        encryptUnder: SyncCryptoManager.DerivedKeys,
        historical: List<SyncCryptoManager.DerivedKeys>,
        /** How long before the block's HLC the retired keys were retired; negative = after it. */
        retiredAgoMs: Long = -60 * 60 * 1000L,
        /** When set, the relay swaps the block's HLC physical time for this one (bytes untouched). */
        relayBackdatesTo: Long? = null,
        /**
         * When set, the AUTHOR sends a `DeltaBatchPayload` whose envelope HLC is this (backdated) value
         * while its one delta carries [innerDeltaPhys] (default: the real, current clock).
         */
        deltaBatchOuterPhys: Long? = null,
        innerDeltaPhys: Long? = null,
    ): Fixture {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.additionalImplementations[GetChanges.ID] = emptyChanges()
        proxy.additionalImplementations[GetTombstones.ID] = emptyTombstones()

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        syncManager.initialize("personal phrase placeholder words go here now")
        val components = proxy.executionContext.initSyncService(
            syncManager,
            crypto,
            // Stepped by hand via `tickOnce`, so the loops are never started rather than started
            // and then stopped — a loop that never starts has no first tick to race
            // (`SyncHealthReportingTest.fixture`). Hygiene here: 3/3 green under forcing either way.
            startBackgroundLoops = false,
            platformScheduler = NoOpPlatformScheduler,
        )

        val hlc = syncManager.hlcManager.tick()
        val retiredAt = hlc.physicalTime.get() - retiredAgoMs
        // Register under the CURRENT key (Kr′) with the retired key in the keyring.
        val sharedRealm = RealmContext(
            ownerId = "shared-rot",
            deviceId = syncManager.currentDeviceId,
            derivedKeys = newKeys,
            deviceSigningKeyPair = crypto.generateDeviceSigningKeyPair(),
            masterKeyPair = null,
            retiredKeys = historical.map { RetiredRealmKeys(it, retiredAt) },
        )

        val entity = Entity.create<SyntheticTestData>(
            context = proxy.tContext,
            composite = SyntheticTestData.Factory.create(proxy.tContext, name = "rotated.txt", value = 42L),
            properties = emptyMap(),
        )
        val dataId = proxy.executionContext.getDataId(entity)!!
        val payload: org.tenshu.core.Data = if (deltaBatchOuterPhys == null) {
            DataBlockPayload.create(proxy.tContext, propertyId = null, value = entity)
        } else {
            val innerHlc = innerDeltaPhys?.let { HybridLogicalClock.create(proxy.tContext, it, 0L, hlc.deviceId.get()) } ?: hlc
            DeltaBatchPayload.create(
                context = proxy.tContext,
                deltas = listOf(SyncDelta.create(proxy.tContext, innerHlc, entity, deltaId = "d-retired")),
            )
        }
        val envelopeHlc = deltaBatchOuterPhys
            ?.let { HybridLogicalClock.create(proxy.tContext, it, 0L, hlc.deviceId.get()) } ?: hlc
        val serialized = BlockPayloadEnvelope.seal(BinarySerializer().serialize(payload), proxy.versionStampBytes())
        // Encrypt under whichever key this case exercises, envelope bound (sync-envelope-authentication §3.2).
        val sealed = EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = proxy.tContext,
            blockId = generateId(),
            ownerId = "shared-rot",
            hlc = envelopeHlc,
            targetEntityIdHash = if (deltaBatchOuterPhys == null) dataId.getDataIdHash().toString() else null,
            plaintext = serialized,
            identityKey = encryptUnder.sharedIdentityKey,
        )
        val block = relayBackdatesTo?.let { phys ->
            SyncBlock.create(
                context = proxy.tContext,
                blockId = sealed.blockId.get(),
                ownerId = sealed.ownerId.get(),
                hlc = HybridLogicalClock.create(proxy.tContext, phys, hlc.logicalCounter.get(), hlc.deviceId.get()),
                encryptedPayload = sealed.encryptedPayload.get(),
                targetEntityIdHash = sealed.targetEntityIdHash?.get(),
            )
        } ?: sealed

        val relay = MockSyncRelay(proxy.tContext)
        relay.pushBlock(block)
        return Fixture(proxy, components, sharedRealm, relay, dataId)
    }

    private fun emptyChanges() = object : org.tenshu.core.common.proxy.executor.SyncOperationImplementation {
        override fun <T : org.tenshu.core.Data?> execute(
            executionContext: org.tenshu.core.common.proxy.ExecutionContext,
            operationContext: org.tenshu.core.common.proxy.OperationContext?,
            implementationFactory: org.tenshu.core.common.proxy.executor.OperationImplementationFactory,
            caller: Any?,
            params: Map<String, org.tenshu.core.Data>?,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return org.tenshu.sync.ChangesResponse.create(
                executionContext.context, emptyList(), emptyList(), 0L,
            ) as T
        }
    }

    private fun emptyTombstones() = object : org.tenshu.core.common.proxy.executor.SyncOperationImplementation {
        override fun <T : org.tenshu.core.Data?> execute(
            executionContext: org.tenshu.core.common.proxy.ExecutionContext,
            operationContext: org.tenshu.core.common.proxy.OperationContext?,
            implementationFactory: org.tenshu.core.common.proxy.executor.OperationImplementationFactory,
            caller: Any?,
            params: Map<String, org.tenshu.core.Data>?,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return org.tenshu.sync.TombstonesResponse.create(
                executionContext.context, emptyList(), 0L,
            ) as T
        }
    }
}
