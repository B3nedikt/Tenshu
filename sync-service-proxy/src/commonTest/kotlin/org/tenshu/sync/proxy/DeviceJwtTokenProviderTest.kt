package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.security.decodeJwt
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.SyncManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A relay JWT names the id and is signed by the key of **one** identity snapshot
 * (threat model A4, *lifecycle*): "Forget this device" swaps the pair in a running process, and a
 * token built from two separate reads could pair one identity's id with the other's key.
 */
class DeviceJwtTokenProviderTest {

    @Test
    fun theTokenNamesAndIsSignedByOneIdentitySnapshot() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val crypto = proxy.tContext.syncCryptoManager()
        val real = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), proxy.tContext)
        real.initialize("mock phrase")
        val snapshot = real.currentDeviceIdentity()
        // The separate accessors answer a DIFFERENT identity — what a read across a forget sees.
        val stranger = crypto.generateDeviceSigningKeyPair()
        val torn = object : SyncManager by real {
            override val currentDeviceId: String get() = "someone-else"
            override fun getDeviceSigningKeyPair() = stranger
            override fun currentDeviceIdentity() = snapshot
        }

        val token = assertNotNull(deviceJwtTokenProvider(torn, crypto, nowEpochSec = { 1_000L })())

        val claims = decodeJwt(token, currentTimeSec = 1_000L) { message, signature ->
            crypto.verifyEd25519(snapshot.signingKeyPair.publicKey, message, signature)
        }
        assertEquals(snapshot.deviceId, claims.deviceId, "the id is the snapshot's, never a second read")
    }
}
