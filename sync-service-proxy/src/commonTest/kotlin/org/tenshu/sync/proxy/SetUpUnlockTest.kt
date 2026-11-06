package org.tenshu.sync.proxy

import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.util.nextSecureRandomBytes
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
 * Unit tests for the atomic passphrase-floor setup `setUpUnlock(passphrase,
 * biometricWrapKey?)` on `SyncManagerImpl` (`docs/proposals/desktop-masterkeystore.md`).
 *
 * Contract: wraps the SAME plaintext under the passphrase (always) + the
 * biometric wrap-key (when given), then clears the plaintext once — so both
 * credentials coexist and biometric never exists without its passphrase floor.
 */
class SetUpUnlockTest {

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
    fun withBoth_writesBothWrappers_clearsPlaintext_andEitherCredentialUnlocks() {
        val wrapKey = nextSecureRandomBytes(32)
        val first = newSyncManager()
        first.initialize(LEGAL_TEST_PHRASE)

        assertTrue(first.setUpUnlock("hunter2", wrapKey))
        assertNotNull(storage.getSecret("sync_unlock_wrapper"), "passphrase wrapper written")
        assertNotNull(storage.getSecret("sync_biometric_wrapper"), "biometric wrapper written")
        assertNull(storage.getSecret("sync_recovery_phrase"), "plaintext cleared once, at the end")

        // Either credential unlocks a fresh (restarted) instance.
        val viaPassphrase = newSyncManager()
        assertTrue(viaPassphrase.getUnlockStatus().isLocked)
        assertTrue(viaPassphrase.unlockLocal("hunter2"))
        assertNotNull(viaPassphrase.currentMasterKeyPair)

        val viaBiometric = newSyncManager()
        assertTrue(viaBiometric.unlockBiometric(wrapKey))
        assertNotNull(viaBiometric.currentMasterKeyPair)
    }

    @Test
    fun passphraseOnly_writesOnlyTheUnlockWrapper() {
        val sm = newSyncManager()
        sm.initialize(LEGAL_TEST_PHRASE)

        assertTrue(sm.setUpUnlock("hunter2", biometricWrapKey = null))
        assertNotNull(storage.getSecret("sync_unlock_wrapper"))
        assertNull(storage.getSecret("sync_biometric_wrapper"), "no biometric wrapper for passphrase-only")
        assertNull(storage.getSecret("sync_recovery_phrase"), "plaintext cleared")

        val restart = newSyncManager()
        assertTrue(restart.unlockLocal("hunter2"))
    }

    @Test
    fun failsWhenNoPhrasePersisted_leavesNoWrappers() {
        val sm = newSyncManager()
        assertFalse(sm.setUpUnlock("hunter2", nextSecureRandomBytes(32)))
        assertNull(storage.getSecret("sync_unlock_wrapper"))
        assertNull(storage.getSecret("sync_biometric_wrapper"))
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
