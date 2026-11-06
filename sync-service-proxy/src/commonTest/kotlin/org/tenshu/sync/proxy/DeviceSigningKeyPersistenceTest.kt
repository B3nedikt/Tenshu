package org.tenshu.sync.proxy

import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The device's Ed25519 signing key (asset A4) is persisted at rest so it survives a process
 * restart — previously it was regenerated on every start, churning a fresh relay registration each
 * launch (and, under the iOS in-process collapse, would fork the device identity between Control
 * and Drive). See `SyncManagerImpl.getDeviceSigningKeyPair()` + threat-model A4.
 *
 * NB the test crypto (`BootstrapSyncCryptoManager`) generates a *deterministic* device key, so a
 * naive "two restarts → same key" assertion would pass even WITHOUT persistence. These tests instead
 * pin the storage blob directly (round-trip + load-precedence + malformed-recovery), which is
 * independent of the generator's randomness.
 */
@OptIn(ExperimentalEncodingApi::class)
class DeviceSigningKeyPersistenceTest {

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
    fun firstCall_generatesAndPersists_andTheBlobRoundTripsToTheSameKey() {
        val key = newSyncManager().getDeviceSigningKeyPair()

        val stored = storage.getSecret(KEY)
        assertNotNull(stored, "A4 written to the secret bucket on generation")

        // The persisted blob decodes back to exactly the key handed out — proves the
        // encode/decode round-trip, not merely that *something* was written.
        val parts = stored.split("|")
        assertEquals(2, parts.size, "blob is Base64(pub)|Base64(priv)")
        assertContentEquals(key.publicKey, Base64.decode(parts[0]))
        assertContentEquals(key.privateKey, Base64.decode(parts[1]))
    }

    @Test
    fun aPrePersistedKey_isLoadedInsteadOfGeneratingAFreshOne() {
        // Seed a distinct, known keypair (not what the generator would produce), then assert the
        // manager returns THAT key — proving the load path takes precedence over generation.
        val pub = ByteArray(32) { 7 }
        val priv = ByteArray(64) { 9 }
        storage.storeSecret(KEY, Base64.encode(pub) + "|" + Base64.encode(priv))

        val loaded = newSyncManager().getDeviceSigningKeyPair()
        assertContentEquals(pub, loaded.publicKey)
        assertContentEquals(priv, loaded.privateKey)
    }

    @Test
    fun aMalformedBlob_regeneratesRatherThanCrashing() {
        storage.storeSecret(KEY, "not-a-valid-blob")

        val key = newSyncManager().getDeviceSigningKeyPair()

        assertTrue(key.publicKey.isNotEmpty(), "produced a usable key despite the bad blob")
        val rewritten = storage.getSecret(KEY)
        assertNotNull(rewritten)
        assertTrue(rewritten.contains("|"), "the bad blob was replaced with a valid one")
    }

    private fun newSyncManager(): SyncManagerImpl = SyncManagerImpl(
        cryptoManager = BootstrapSyncCryptoManager(),
        storageDelegate = storage,
        context = testProxy.tContext,
        autoLoadDispatcher = Dispatchers.Unconfined,
    )

    private companion object {
        const val KEY = "sync_device_signing_key"
    }
}
