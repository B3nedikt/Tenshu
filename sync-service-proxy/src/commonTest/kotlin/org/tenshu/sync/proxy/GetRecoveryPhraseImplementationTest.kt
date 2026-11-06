package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.get
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.GetRecoveryPhraseImplementation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GetRecoveryPhraseImplementationTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var impl: GetRecoveryPhraseImplementation

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
        }
        syncManager = SyncManagerImpl(
            cryptoManager = DummyCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext
        )

        impl = GetRecoveryPhraseImplementation(syncManager)
    }

    @Test
    fun failure_whenNoPhrasePersisted() = runTest {
        // Phase 3 of `docs/proposals/core-error-channel.md`: impl throws
        // `TenshuException`; the LocalTaskExecutor catch-all unwraps to
        // `TResult.Failure(error)` at the dispatch boundary.
        assertFailsWith<TenshuException> {
            impl.execute<TString>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = { null },
                caller = null,
                params = emptyMap(),
            )
        }
    }

    @Test
    fun success_returnsPersistedPhrase() = runTest {
        val stored = "test recovery phrase placeholder words here"
        syncManager.initialize(stored)

        val result = impl.execute<TString>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = emptyMap(),
        )

        assertEquals(stored, result.get())
    }

    @Test
    fun twoCalls_returnSamePersistedPhrase() = runTest {
        val stored = "phrase one two three four five six seven eight"
        syncManager.initialize(stored)

        val first = impl.execute<TString>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = emptyMap(),
        )
        val second = impl.execute<TString>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = emptyMap(),
        )

        assertEquals(first.get(), second.get())
    }
}

private class DummyCryptoManager : SyncCryptoManager {
    override fun deriveKeys(recoveryPhrase: String): SyncCryptoManager.DerivedKeys =
        SyncCryptoManager.DerivedKeys(
            sharedIdentityKey = ByteArray(32),
            sharedVolumeKey = ByteArray(32),
        )

    override fun encrypt(payload: ByteArray, key: ByteArray): ByteArray = payload
    override fun decrypt(encryptedPayload: ByteArray, key: ByteArray): ByteArray = encryptedPayload
    override fun generateKeyPair(): SyncCryptoManager.KeyPair = error("not used")
    override fun calculateSharedSecret(
        privateKey: ByteArray,
        otherPublicKey: ByteArray,
    ): ByteArray = error("not used")

    override fun signEd25519(privateKey: ByteArray, message: ByteArray): ByteArray =
        error("not used")

    override fun verifyEd25519(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean = error("not used")

    override fun deriveMasterKeyPair(recoveryPhrase: String): SyncCryptoManager.KeyPair =
        SyncCryptoManager.KeyPair(publicKey = ByteArray(32), privateKey = ByteArray(32))
}
