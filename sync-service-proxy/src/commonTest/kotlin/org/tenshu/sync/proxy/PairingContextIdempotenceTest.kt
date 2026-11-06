package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression for the "Confirm on your other device, but no dialog on the host" bug.
 *
 * Symptom: the host shows a pairing code, the joiner enters it and lands on "Confirm
 * on your other device", but the host's approve dialog never appears. Root cause was
 * host-side: re-opening the pairing dialog (hide → reopen, a natural workflow) called
 * StartPairing again, which minted a **fresh** secret and overwrote the active pairing
 * context. The joiner's already-submitted request carries the *old* secret, so the
 * host poll — which filters requests by `pairingSecretHash == activeContext.secret`
 * (GetPendingPairingRequestsImplementation) — matched nothing and the dialog stayed shut.
 *
 * The fix makes [SyncManager.generatePairingContext] idempotent while a session is in
 * flight: a second call returns the same context (same secret) until [SyncManager.stopPairing]
 * clears it. These tests pin that contract.
 */
class PairingContextIdempotenceTest {

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
    fun reopeningPairing_reusesTheSameContextAndSecret() {
        val sm = syncManager()
        sm.initialize("test recovery phrase placeholder words here")

        val first = sm.generatePairingContext()
        val firstString = sm.getPairingString()

        // Simulate hide-then-reopen: a second StartPairing while the session is live.
        val second = sm.generatePairingContext()

        assertSame(first, second, "an in-flight pairing session must be reused, not regenerated")
        assertEquals(first.secret, second.secret, "the secret the joiner scanned must survive a reopen")
        assertEquals(
            firstString,
            sm.getPairingString(),
            "the pairing code/QR must be stable across a reopen so a scanned code stays valid",
        )
    }

    @Test
    fun afterStopPairing_aNewSessionMintsAFreshSecret() {
        val sm = syncManager()
        sm.initialize("test recovery phrase placeholder words here")

        val first = sm.generatePairingContext()
        sm.stopPairing()
        assertNull(sm.activePairingContext, "stopPairing clears the session")

        val second = sm.generatePairingContext()
        assertNotEquals(
            first.secret,
            second.secret,
            "a genuinely new session after cancel/approve/deny must get a fresh secret",
        )
        assertNotNull(sm.activePairingContext)
    }

    @Test
    fun stopPairing_zeroesTheEphemeralPrivateKey() {
        val sm = syncManager()
        sm.initialize("test recovery phrase placeholder words here")

        val context = sm.generatePairingContext()
        // Retain the underlying buffer; stopPairing nulls the context but mutates this same
        // array in place (control-pairing-ux.md §3.5 / §5 on-teardown zeroing).
        val ephemeralKey = context.privateKey
        assertTrue(ephemeralKey.any { it != 0.toByte() }, "the ephemeral key must start non-zero")

        sm.stopPairing()

        assertTrue(
            ephemeralKey.all { it == 0.toByte() },
            "stopPairing must zero the ephemeral X25519 private key, not just drop the reference",
        )
        assertNull(sm.activePairingContext)
    }
}
