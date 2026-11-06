package org.tenshu.sync.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `reconcileOwnDeviceRow` — the tier-1 self-heal for the "`Device` rows have no regeneration
 * path" tech-debt entry. A store wipe (core-version mismatch, cleared app data, corruption)
 * strips this device's own `Device` row with nothing to re-emit it, which empties the realm
 * roster **and** breaks `RevokeDevice` (it resolves the local entity before tombstoning).
 *
 * The guards are the interesting part: the repair must never manufacture membership, never
 * clobber a truer row that arrived from a peer, and never resurrect a revoked device.
 */
class OwnDeviceRowSelfHealTest {

    private lateinit var testProxy: TestProxy
    private lateinit var storage: InMemorySecureStorageDelegate
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var tombstoneRegistry: TombstoneRegistry

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        storage = InMemorySecureStorageDelegate()
        syncManager = SyncManagerImpl(
            cryptoManager = BootstrapSyncCryptoManager(),
            storageDelegate = storage,
            context = testProxy.tContext,
            autoLoadDispatcher = Dispatchers.Unconfined,
        )
        // The repair gates on real realm membership (owner id + derived keys), so the manager has
        // to actually hold a realm — merely "not locked" is not enough, and must not be.
        syncManager.initialize("mock phrase")
        tombstoneRegistry = TombstoneRegistry(
            wireIdFor = { WireIdResolver(syncManager).ofOrNull(it) },
            executionContext = testProxy.executionContext,
            localEventHlc = { syncManager.hlcManager.tick() }
        )
    }

    private fun ownDataId(): DataId = DataId.StringDataId.create(
        testProxy.tContext,
        Device.ID,
        syncManager.currentDeviceId
    )

    private fun ownRow(): Device? = testProxy.database.getData(ownDataId()) as? Device

    private fun reconcile(): Boolean =
        testProxy.executionContext.reconcileOwnDeviceRow(syncManager, tombstoneRegistry)

    @Test
    fun missingRow_isSelfHealedFromLocallyHeldIdentity() {
        assertNull(ownRow(), "precondition: the wiped store has no own Device row")

        assertTrue(reconcile(), "a missing row should be repaired")

        val healed = assertNotNull(ownRow(), "the own Device row should exist after the repair")
        assertEquals(syncManager.currentDeviceId, healed.deviceId.get())
        assertContentEquals(
            syncManager.getDeviceSigningKeyPair().publicKey,
            healed.publicKey.get(),
            "the row must carry this device's real A4 signing key, not a placeholder"
        )
        assertTrue(healed.isAuthorized.get(), "a provisioned device re-asserts itself as authorised")
        // Lineage is genuinely unrecoverable — empty is the documented root/unknown marker, and
        // is exactly correct for an A1-rooted genesis device.
        assertEquals("", healed.admittedByDeviceId?.get())
    }

    @Test
    fun repairIsIdempotent_secondRunIsANoOp() {
        assertTrue(reconcile())
        assertFalse(reconcile(), "the row now exists; a second run must not write again")
    }

    @Test
    fun existingRow_isNeverOverwritten() {
        // A row that arrived from a peer carries true lineage + epoch. The repair must lose to it.
        testProxy.executionContext.saveData(
            Device.create(
                context = testProxy.tContext,
                deviceId = syncManager.currentDeviceId,
                name = "Paired name",
                publicKey = byteArrayOf(0x7A),
                isAuthorized = true,
                admittedAtEpoch = 42L,
                admittedByDeviceId = "admitter-device",
            )
        )

        assertFalse(reconcile(), "an existing row must not be repaired over")

        val row = assertNotNull(ownRow())
        assertEquals("admitter-device", row.admittedByDeviceId?.get(), "true lineage must survive")
        assertEquals(42L, row.admittedAtEpoch.get(), "true admission epoch must survive")
        assertEquals("Paired name", row.name.get())
    }

    @Test
    fun revokedDevice_doesNotResurrectItself() {
        // Model a revocation: the row exists, then is deleted with the tombstone registry
        // observing — exactly the path `RevokeDevice`/`SyncProcessor.processDeleteBlock` drive.
        testProxy.executionContext.addDataObserver(tombstoneRegistry)
        testProxy.executionContext.saveData(
            Device.create(
                context = testProxy.tContext,
                deviceId = syncManager.currentDeviceId,
                name = "Revoked device",
                publicKey = byteArrayOf(0x01),
            )
        )
        testProxy.executionContext.deleteData(ownDataId())
        assertNull(ownRow(), "precondition: the revoked row is gone")

        assertFalse(reconcile(), "a tombstoned device must not resurrect itself")
        assertNull(ownRow(), "no row may be written for a revoked device")
    }

    /**
     * The guard against the repair being silently inert: it must actually be *reached* by the one
     * seam every production wiring funnels through (Android / desktop / iOS `PlatformProxyModule`
     * + the JS runtime all call `initSyncService`), not merely work when called directly.
     */
    @Test
    fun initSyncService_repairsTheMissingOwnDeviceRow() = runTest {
        // ⚠ Rebuilt on THIS scope before the call below. `@BeforeTest` has no `TestScope` to give,
        // so its proxy is detached — and this is the only test in the class that runs
        // `initSyncService`, which starts a `SyncScheduler` and a 1-second `ScheduleDrainLoop`.
        // Detached, those outlive the class and throw into whatever `runTest` runs next, blaming an
        // unrelated test (`docs/tech-debt.md` fix-item 5). The other six tests here start no loops,
        // which is why the `@BeforeTest` stays as it is for them.
        testProxy = TestProxy(
            schemaRegistry = createCoreTestRegistry(),
            coroutineScope = this,
        ).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        val crypto = BootstrapSyncCryptoManager()
        val manager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
            autoLoadDispatcher = Dispatchers.Unconfined,
        )
        manager.initialize("mock phrase")

        val dataId = DataId.StringDataId.create(testProxy.tContext, Device.ID, manager.currentDeviceId)
        assertNull(testProxy.database.getData(dataId), "precondition: wiped store, no own row")

        testProxy.executionContext.initSyncService(manager, crypto, platformScheduler = NoOpPlatformScheduler)

        assertNotNull(
            testProxy.database.getData(dataId) as? Device,
            "initSyncService must repair the missing own Device row"
        )
        testProxy.clientRegistry.shutdown()
    }

    @Test
    fun lockedRealm_isLeftAlone() {
        // isLocked = isSetUp && derivedKeys == null — an unlock wrapper with no loaded keys.
        storage.storeSecret(SyncManagerImpl.Keys.UNLOCK_WRAPPER, "wrapper-blob")
        val locked = SyncManagerImpl(
            cryptoManager = BootstrapSyncCryptoManager(),
            storageDelegate = storage,
            context = testProxy.tContext,
            autoLoadDispatcher = Dispatchers.Unconfined,
        )
        assertTrue(locked.getUnlockStatus().isLocked, "precondition: realm reports locked")

        assertFalse(
            testProxy.executionContext.reconcileOwnDeviceRow(locked, tombstoneRegistry),
            "a locked realm has no keys to reconstruct from; the unlock edge repairs instead"
        )
        assertNull(ownRow())
    }

    /**
     * Regression guard for the bug `DelegatedAdmissionSystemTest` caught: gating on
     * `!isLocked` let a device that had never joined a realm self-assert `isAuthorized = true`.
     * That phantom row then raced the admitter's real one under whole-entity LWW, erasing
     * `admittedByDeviceId` — which silently shrank the revoke subtree, the one thing §4.3 of
     * `core-relation-system.md` says must be preserved. An unpaired device reports **unlocked**
     * (no wrapper ⇒ `isSetUp = false`), so "not locked" is emphatically not "is a member".
     */
    @Test
    fun unpairedDevice_neverManufacturesMembership() {
        val unpaired = SyncManagerImpl(
            cryptoManager = BootstrapSyncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
            autoLoadDispatcher = Dispatchers.Unconfined,
        )
        assertFalse(unpaired.getUnlockStatus().isLocked, "precondition: a fresh device reads unlocked")
        assertNull(unpaired.currentOwnerId, "precondition: but it belongs to no realm")

        assertFalse(
            testProxy.executionContext.reconcileOwnDeviceRow(unpaired, tombstoneRegistry),
            "a device that never joined a realm must not write itself an authorised Device row"
        )
        val dataId = DataId.StringDataId.create(testProxy.tContext, Device.ID, unpaired.currentDeviceId)
        assertNull(testProxy.database.getData(dataId), "no phantom row may exist")
    }
}
