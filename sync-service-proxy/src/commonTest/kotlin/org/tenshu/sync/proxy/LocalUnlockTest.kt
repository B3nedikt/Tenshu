package org.tenshu.sync.proxy

import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the local-unlock primitive on `SyncManagerImpl` per
 * `docs/proposals/control-onboarding-ux.md` §3 / §4b.5 (Track C #11).
 *
 * The contract under test:
 *  - `setUpLocalUnlock(passphrase)` wraps the persisted plaintext
 *    recovery phrase under a passphrase-derived key, persists the
 *    wrapper, and deletes the plaintext.
 *  - `unlockLocal(passphrase)` decrypts the wrapper, re-derives the
 *    master keys, and re-populates `currentMasterKeyPair`.
 *  - The `init` block of a freshly-constructed `SyncManagerImpl`
 *    auto-loads from plaintext when no wrapper exists (legacy path)
 *    and stays sealed when a wrapper is present (post-setup path).
 *  - `getUnlockStatus` reflects the (isSetUp, isLocked) shape used by
 *    the landing-state router.
 */
class LocalUnlockTest {

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
    fun freshInstall_reportsNotSetUpAndUnlocked() {
        val sm = newSyncManager()
        val status = sm.getUnlockStatus()
        assertFalse(status.isSetUp, "fresh install: no wrapper")
        assertFalse(status.isLocked, "fresh install: nothing to lock")
    }

    @Test
    fun legacyPlaintextInstall_autoLoadsOnConstruction() {
        // Simulate a pre-#11 install: plaintext phrase already present.
        storage.storeSecret("sync_recovery_phrase", LEGAL_TEST_PHRASE)
        val sm = newSyncManager()
        assertNotNull(sm.currentMasterKeyPair, "legacy: keys auto-loaded")
        val status = sm.getUnlockStatus()
        assertFalse(status.isSetUp)
        assertFalse(status.isLocked)
    }

    @Test
    fun setUpThenRestart_staysLocked_untilUnlock() {
        val first = newSyncManager()
        first.initialize(LEGAL_TEST_PHRASE)
        assertTrue(first.setUpLocalUnlock("hunter2"), "setUp should succeed")
        assertNull(
            storage.getSecret("sync_recovery_phrase"),
            "plaintext deleted after setUp",
        )
        assertNotNull(
            storage.getSecret("sync_unlock_wrapper"),
            "wrapper persisted after setUp",
        )

        // Simulate process restart by constructing a fresh SyncManagerImpl
        // against the same SecureStorage.
        val second = newSyncManager()
        assertNull(
            second.currentMasterKeyPair,
            "post-restart with wrapper: stays sealed",
        )
        val sealedStatus = second.getUnlockStatus()
        assertTrue(sealedStatus.isSetUp, "wrapper exists")
        assertTrue(sealedStatus.isLocked, "keys not loaded")

        // Unlock with the right passphrase
        assertTrue(second.unlockLocal("hunter2"), "unlock with correct passphrase")
        assertNotNull(second.currentMasterKeyPair, "keys re-loaded after unlock")
        val unsealedStatus = second.getUnlockStatus()
        assertTrue(unsealedStatus.isSetUp)
        assertFalse(unsealedStatus.isLocked)
    }

    @Test
    fun unlockWithWrongPassphrase_returnsFalse_keysStayNull() {
        val first = newSyncManager()
        first.initialize(LEGAL_TEST_PHRASE)
        assertTrue(first.setUpLocalUnlock("real-passphrase"))

        val second = newSyncManager()
        assertNull(second.currentMasterKeyPair)

        assertFalse(second.unlockLocal("wrong-passphrase"))
        assertNull(
            second.currentMasterKeyPair,
            "wrong passphrase must not load keys",
        )
        assertTrue(second.getUnlockStatus().isLocked, "still locked")
    }

    @Test
    fun getRecoveryPhrase_survivesSetUp_inSameSession() {
        // Regression: onboarding generates + persists the phrase via
        // `initialize`, then `setUpLocalUnlock` wraps it and deletes the
        // on-disk plaintext. Settings → Recovery Phrase must still reveal the
        // code in the same (unlocked) session — before any lock/unlock cycle.
        val sm = newSyncManager()
        sm.initialize(LEGAL_TEST_PHRASE)
        assertTrue(sm.setUpLocalUnlock("hunter2"))

        assertNull(
            storage.getSecret("sync_recovery_phrase"),
            "plaintext deleted from disk after setUp",
        )
        assertEquals(
            LEGAL_TEST_PHRASE,
            sm.getRecoveryPhrase(),
            "phrase still revealable from the unlocked session",
        )
    }

    @Test
    fun getRecoveryPhrase_isNull_whenSealedAfterRestart_thenReturnsAfterUnlock() {
        val first = newSyncManager()
        first.initialize(LEGAL_TEST_PHRASE)
        assertTrue(first.setUpLocalUnlock("hunter2"))

        // Fresh process: wrapper present, keys sealed, no session phrase.
        val second = newSyncManager()
        assertNull(
            second.getRecoveryPhrase(),
            "sealed device must not reveal the phrase before unlock",
        )

        assertTrue(second.unlockLocal("hunter2"))
        assertEquals(
            LEGAL_TEST_PHRASE,
            second.getRecoveryPhrase(),
            "phrase revealable again after unlock re-derives it",
        )
    }

    @Test
    fun setUpFails_whenNoPhrasePersisted() {
        // No `initialize()` called → no plaintext phrase to wrap.
        val sm = newSyncManager()
        assertFalse(sm.setUpLocalUnlock("hunter2"))
        assertNull(storage.getSecret("sync_unlock_wrapper"))
    }

    @Test
    fun statusReportsBothFields() {
        val sm = newSyncManager()
        sm.initialize(LEGAL_TEST_PHRASE)
        sm.setUpLocalUnlock("hunter2")

        val unsealed = sm.getUnlockStatus()
        assertEquals(
            SyncManager.UnlockStatus(isSetUp = true, isLocked = false, hasPassphrase = true),
            unsealed,
        )

        val restarted = newSyncManager()
        val sealed = restarted.getUnlockStatus()
        assertEquals(
            SyncManager.UnlockStatus(isSetUp = true, isLocked = true, hasPassphrase = true),
            sealed,
        )
    }

    // `Unconfined` so the constructor's (now off-thread) legacy auto-load
    // runs inline — `initialize()` is non-suspending, so the launched
    // coroutine completes synchronously within construction. This preserves
    // the synchronous-auto-load contract these tests assert; production uses
    // the default `Dispatchers.Default` (off the Android main thread).
    private fun newSyncManager(): SyncManagerImpl = SyncManagerImpl(
        cryptoManager = BootstrapSyncCryptoManager(),
        storageDelegate = storage,
        context = testProxy.tContext,
        autoLoadDispatcher = Dispatchers.Unconfined,
    )

    private companion object {
        // 24 BIP-39 English words — happens to be the canonical "abandon" test
        // vector. The SyncManager tolerates any space-separated phrase that
        // matches the BIP-39 word-count constraint at unlock time
        // (`looksLikeMnemonic`).
        const val LEGAL_TEST_PHRASE =
            "abandon abandon abandon abandon abandon abandon " +
                    "abandon abandon abandon abandon abandon abandon " +
                    "abandon abandon abandon abandon abandon abandon " +
                    "abandon abandon abandon abandon abandon art"
    }
}
