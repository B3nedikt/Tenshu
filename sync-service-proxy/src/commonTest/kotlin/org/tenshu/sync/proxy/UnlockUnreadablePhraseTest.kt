package org.tenshu.sync.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TenshuException
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.util.Bip39InvalidMnemonicException
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.RecoveryPhraseUnreadableError
import org.tenshu.sync.UnlockLocal
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.UnlockLocalImplementation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * A right passphrase over a stored phrase this build cannot read is its own outcome.
 *
 * ⚠⚠ **Why this needs a typed error rather than `false`.** A wrong passphrase already returns
 * `false` — the AEAD auth tag fails and the unwrap yields nothing — and the UI tells the user to
 * try again, which is advice they can act on. Reporting an undecodable *stored* phrase the same
 * way sends them to retype a passphrase that was correct the first time, for ever. Left untyped it
 * would not even reach them as a failure they could name: `LocalTaskExecutor.wrapException` turns
 * any untyped throw into `InternalErrorDto(internalDetail = e.message)`, so it would arrive as an
 * opaque internal error and be recorded in the error log as if it were a bug.
 *
 * ⚠ **How the fixture reaches a state the current code refuses to create.** The wrap sites decline
 * a payload that would not decode, so `setUpLocalUnlock` cannot produce this wrapper — which is
 * the point: the only way in is a wrapper written by an *earlier* build. The
 * stub below reproduces that split exactly, because the two halves consult different things: the
 * wrap gate calls `Bip39.decode` directly, while unlock decodes through the crypto manager's
 * `deriveKeys`. A phrase that passes the first and fails the second *is* the inherited wrapper.
 */
class UnlockUnreadablePhraseTest {

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
    fun anUnreadableStoredPhraseFailsWithItsOwnTypedError() = runTest {
        seedLegacyWrapper()
        val impl = UnlockLocalImplementation(newSyncManager(RefusesToDecodeCryptoManager()))

        val ex = assertFailsWith<TenshuException> { unlock(impl, "hunter2") }

        assertIs<RecoveryPhraseUnreadableError>(
            ex.error,
            "the caller must be able to name this failure; as InternalError it is " +
                "indistinguishable from a bug and logged as one",
        )
        assertEquals(
            "tenshu/sync/service/error/recoveryPhraseUnreadable",
            ex.error.schema.id.get(),
            "the id is wire-stable and derives the copy key `error_recovery_phrase_unreadable`",
        )
    }

    /**
     * ⚠⚠ The anti-vacuity half, and the reason `false` was never an acceptable fix: a wrong
     * credential must stay a plain `false`, or the new error would swallow the case the user *can*
     * act on and both would render the same.
     */
    @Test
    fun aWrongPassphraseStillReturnsFalse() = runTest {
        seedLegacyWrapper()
        val impl = UnlockLocalImplementation(newSyncManager(RefusesToDecodeCryptoManager()))

        val result = unlock(impl, "not-the-passphrase")

        assertEquals(
            false, (result as TBoolean).get(),
            "a wrong passphrase is a retry the user can win — it must not raise the typed error",
        )
    }

    @Test
    fun aReadablePhraseStillUnlocks() = runTest {
        seedLegacyWrapper()
        val impl = UnlockLocalImplementation(newSyncManager(BootstrapSyncCryptoManager()))

        val result = unlock(impl, "hunter2")

        assertEquals(true, (result as TBoolean).get(), "the success path is untouched")
    }

    /** Wrap a decodable phrase, then hand the unlock a manager that cannot decode it. */
    private fun seedLegacyWrapper() {
        storage.storeSecret("sync_recovery_phrase", LEGAL_TEST_PHRASE)
        val setUp = newSyncManager(BootstrapSyncCryptoManager())
        check(setUp.setUpLocalUnlock("hunter2")) { "fixture: the wrapper must be written" }
    }

    private fun unlock(impl: UnlockLocalImplementation, passphrase: String): Data? =
        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = OperationImplementationFactory { null },
            caller = null,
            params = mapOf(
                UnlockLocal.Params.PASSPHRASE to passphrase.toTString(testProxy.tContext),
            ),
        )

    private fun newSyncManager(crypto: SyncCryptoManager): SyncManagerImpl = SyncManagerImpl(
        cryptoManager = crypto,
        storageDelegate = storage,
        context = testProxy.tContext,
        autoLoadDispatcher = Dispatchers.Unconfined,
    )

    /**
     * Stands in for a build whose wordlist no longer decodes a phrase an earlier build wrote. It
     * throws the same typed exception `DefaultSyncCryptoManager` would — `Bip39.decode`'s, raised
     * from inside `deriveKeys` — which is the shape the op boundary catches.
     */
    private class RefusesToDecodeCryptoManager :
        SyncCryptoManager by BootstrapSyncCryptoManager() {
        override fun deriveKeys(recoveryPhrase: String): SyncCryptoManager.DerivedKeys =
            throw Bip39InvalidMnemonicException.ChecksumMismatch()
    }

    private companion object {
        const val LEGAL_TEST_PHRASE =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon art"
    }
}
