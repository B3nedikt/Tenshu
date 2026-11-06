package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.GetSyncStatusImplementation
import kotlin.coroutines.CoroutineContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `GetSyncStatus.ownerId` must answer "does this device belong to a realm?" **durably** —
 * including during the seconds a cold start spends loading the keys back.
 *
 * ## What this pins, and why it is not paranoia
 *
 * `SyncManagerImpl.init` starts the legacy autoload on `autoLoadScope` because the Argon2id
 * stretch inside `initialize` would otherwise run on Android's main thread inside
 * `ProxyService.onCreate`. Everything in the sync loop tolerates that gap — but
 * `LandingStateRepository.detect()` does not: it maps "no owner" to `FirstTime`, which is the
 * **"Start fresh on this device"** onboarding screen, and it decides **once** per launch.
 * With `ownerId` sourced from the in-memory `currentOwnerId`, Control-on-Android opened on
 * that screen on roughly every cold start (measured on a OnePlus CPH2581, 2026-08-25: the
 * fresh-install screen at a 0.5 s and a 1.5 s probe, Home at 3 s). `PairingWizardViewModel`
 * reads the same field to pick the *offerer* role, i.e. "install keys onto this blank device".
 *
 * ⚠⚠ Every other `SyncManagerImpl` test injects `Dispatchers.Unconfined`, which runs the
 * autoload inline and so **cannot** observe this window at all — that is exactly why the whole
 * suite stayed green through the defect. [PausedDispatcher] holds the autoload where a real
 * cold start holds it, so these assertions are made *inside* the window; each one asserts
 * `isEnabled == false` to prove it is still there.
 */
class ColdStartOwnerIdTest {

    private lateinit var testProxy: TestProxy
    private lateinit var storage: SecureStorageDelegate

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        storage = InMemorySecureStorageDelegate()
    }

    @Test
    fun coldStart_reportsOwnerId_whileTheKeyLoadIsStillInFlight() {
        val provisioned = newSyncManager(Dispatchers.Unconfined)
        provisioned.initialize(LEGAL_TEST_PHRASE)
        val ownerId = assertNotNull(provisioned.currentOwnerId, "genesis derives an ownerId")

        // Cold start with the autoload parked — the state a phone is in for ~2-3 s.
        val paused = PausedDispatcher()
        val restart = newSyncManager(paused)
        assertNull(restart.currentOwnerId, "precondition: the in-memory owner is not loaded yet")
        assertFalse(restart.isEnabled(), "precondition: still inside the key-load window")

        val status = statusOf(restart)
        assertEquals(
            ownerId,
            status.ownerId?.get(),
            "a provisioned device must not read as ownerless while its keys load — " +
                "that is the 'Start fresh on this device' screen",
        )
        assertFalse(
            status.isEnabled.get(),
            "isEnabled stays the honest 'can I sync right now' flag",
        )

        // ...and the answer does not change once the autoload lands.
        paused.runQueued()
        assertEquals(ownerId, statusOf(restart).ownerId?.get(), "same owner after the load")
    }

    @Test
    fun genuinelyFreshDevice_reportsNoOwner() {
        val fresh = newSyncManager(PausedDispatcher())
        assertNull(
            statusOf(fresh).ownerId?.get(),
            "a device that never had a realm must still route to onboarding",
        )
    }

    @Test
    fun forgottenDevice_reportsNoOwner_evenBeforeTheNextRestart() {
        val sm = newSyncManager(Dispatchers.Unconfined)
        sm.initialize(LEGAL_TEST_PHRASE)
        assertNotNull(statusOf(sm).ownerId?.get(), "precondition: a realm exists")

        // Leaving the realm purges the custody bucket, OWNER_ID included; the durable read
        // must follow it down, or a wiped device could never reach onboarding again.
        sm.forgetThisDevice()
        assertNull(statusOf(sm).ownerId?.get(), "forgetting the realm clears the marker")
        assertNull(
            newSyncManager(PausedDispatcher()).let { statusOf(it).ownerId?.get() },
            "and the next cold start agrees",
        )
    }

    private fun statusOf(syncManager: SyncManagerImpl): SyncStatus =
        GetSyncStatusImplementation(syncManager).execute(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = OperationImplementationFactory { null },
            caller = null,
            params = null,
        )

    private fun newSyncManager(dispatcher: CoroutineDispatcher): SyncManagerImpl = SyncManagerImpl(
        cryptoManager = BootstrapSyncCryptoManager(),
        storageDelegate = storage,
        context = testProxy.tContext,
        autoLoadDispatcher = dispatcher,
    )

    /** Queues everything and runs nothing until told — a cold start's autoload, held open. */
    private class PausedDispatcher : CoroutineDispatcher() {
        private val queued = mutableListOf<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued += block
        }

        fun runQueued() {
            val pending = queued.toList()
            queued.clear()
            pending.forEach { it.run() }
        }
    }

    private companion object {
        const val LEGAL_TEST_PHRASE =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon art"
    }
}
