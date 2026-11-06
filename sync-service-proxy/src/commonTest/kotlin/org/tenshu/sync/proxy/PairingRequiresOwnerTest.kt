package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Regression for the production "talks to the relay as `user-1`" bug.
 *
 * Pre-fix, [SyncManagerImpl.generatePairingContext] fell back to a hardcoded `"user-1"`
 * dev owner when no realm-bound owner was set (and the relay token providers did the
 * same). Against a dev relay the `X-Dev-Owner` bypass hid this; against the production
 * relay — which enforces owner-binding — the host then polled `/sync/pairing/requests`
 * and pushed blocks under a placeholder owner, getting 401/403 that the UI mislabeled as
 * "can't reach the relay".
 *
 * The contract now: a pairing session requires a configured owner. Hosting is gated at
 * [org.tenshu.sync.proxy.impl.StartPairingImplementation]; reaching
 * `generatePairingContext` without an owner is a programming error rather than a silent
 * placeholder-owner session.
 */
class PairingRequiresOwnerTest {

    private fun syncManager(): SyncManagerImpl {
        val testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
        }
        return SyncManagerImpl(
            cryptoManager = BootstrapSyncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
        )
    }

    @Test
    fun withNoOwner_generatePairingContextFailsInsteadOfMintingUser1() {
        val sm = syncManager() // deliberately NOT initialize()'d → currentOwnerId is null

        assertNull(sm.currentOwnerId, "precondition: no realm-bound owner")

        // Pre-fix this returned a context with ownerId = "user-1"; now it fails loudly so a
        // placeholder-owner session can never reach the relay.
        assertFailsWith<IllegalStateException> { sm.generatePairingContext() }

        assertNull(sm.activePairingContext, "no placeholder-owner pairing session is created")
        assertNull(sm.getPairingString(), "no pairing code without a configured owner")
    }
}
