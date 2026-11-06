package org.tenshu.sync.proxy

import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers `SyncManagerImpl.relaxToHeadless()` — the **"Encrypt on unlock only"**
 * hard-mode → headless transition (`control-keep-device-unlocked.md` §4.2, Ph-hard,
 * the inverse of `setUpUnlock`). Drops the wrappers so a subsequent cold start
 * autoloads the realm headless again, re-seeding the plaintext from the unlocked
 * session first so we never strand the realm.
 */
class RelaxToHeadlessTest {

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
    fun enableThenRelax_sameSession_reseedsPlaintext_andAutoloadsHeadlessOnRestart() {
        val sm = newSyncManager()
        sm.initialize(LEGAL_TEST_PHRASE)

        // Enable hard mode: wraps + clears the plaintext.
        assertTrue(sm.setUpUnlock("hunter2", null))
        assertNotNull(storage.getSecret("sync_unlock_wrapper"), "wrapper persisted")
        assertNull(storage.getSecret("sync_recovery_phrase"), "plaintext cleared by setUpUnlock")

        // Disable in the SAME session: the plaintext slot is gone, so relax must
        // re-seed from the in-memory phrase before dropping the wrapper.
        assertTrue(sm.relaxToHeadless(), "relax succeeds while unlocked")
        assertNull(storage.getSecret("sync_unlock_wrapper"), "passphrase wrapper dropped")
        assertNull(storage.getSecret("sync_biometric_wrapper"), "biometric wrapper dropped")
        assertNotNull(
            storage.getSecret("sync_recovery_phrase"),
            "plaintext re-seeded so cold-start autoload works",
        )

        // Cold start: no wrapper + plaintext present ⇒ headless autoload.
        val restart = newSyncManager()
        assertNotNull(
            restart.currentMasterKeyPair,
            "headless autoload after relax — no app-level unlock needed",
        )
        assertFalse(restart.getUnlockStatus().isLocked, "realm boots enabled")
        assertFalse(restart.getUnlockStatus().isSetUp, "no wrapper ⇒ not sealed")
    }

    @Test
    fun relaxWhileLocked_refused_wrappersUntouched() {
        val first = newSyncManager()
        first.initialize(LEGAL_TEST_PHRASE)
        assertTrue(first.setUpUnlock("hunter2", null))

        // Cold start ⇒ sealed/locked, no in-memory keys.
        val locked = newSyncManager()
        assertTrue(locked.getUnlockStatus().isLocked, "sealed after restart")

        assertFalse(locked.relaxToHeadless(), "refuse: no plaintext, no in-memory keys to re-seed from")
        assertNotNull(
            storage.getSecret("sync_unlock_wrapper"),
            "wrapper left intact so the realm stays recoverable",
        )
    }

    @Test
    fun relaxAfterFreshUnlock_dropsWrapper_andAutoloadsHeadless() {
        val first = newSyncManager()
        first.initialize(LEGAL_TEST_PHRASE)
        assertTrue(first.setUpUnlock("hunter2", null))

        // Fresh unlock re-seeds the plaintext (applyDecodedPayload → initialize).
        val unlocked = newSyncManager()
        assertTrue(unlocked.unlockLocal("hunter2"))
        assertTrue(unlocked.relaxToHeadless())

        val restart = newSyncManager()
        assertNotNull(restart.currentMasterKeyPair, "headless autoload after relax")
    }

    private fun newSyncManager(): SyncManagerImpl = SyncManagerImpl(
        cryptoManager = BootstrapSyncCryptoManager(),
        storageDelegate = storage,
        context = testProxy.tContext,
        autoLoadDispatcher = Dispatchers.Unconfined,
    )

    private companion object {
        const val LEGAL_TEST_PHRASE =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon art"
    }
}
