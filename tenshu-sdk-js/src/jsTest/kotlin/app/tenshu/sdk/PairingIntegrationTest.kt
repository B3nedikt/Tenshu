package app.tenshu.sdk

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.util.PairingUri
import kotlin.js.Date
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

// End-to-end integration test for Pairing.submit against a real
// `:sync-relay-server` running at http://localhost:8080. `@Ignore`d by
// default; flip off when investigating integration drift.
//
// Probing the InitiatePairing relay round-trip without actually running
// a Control device on the other end. We synthesise a valid bech32-
// encoded pairing payload (pubkey || secret bytes), POST it via
// `Pairing.submit`, and assert the session reaches `awaiting` — that's
// the moment InitiatePairing succeeded on the relay and the polling
// loop started. We then `cancel()` so the polling Job doesn't bleed
// into subsequent tests.
class PairingIntegrationTest {

    @Test
    @Ignore // requires `:sync-relay-server` running at localhost:8080
    fun submit_validUri_returnsAwaitingSession() = runTest {
        TenshuSdk.configure("http://localhost:8080")
        TenshuSdk.awaitReady().await()

        val ownerId = "pairing-int-test-owner-${Date.now().toLong()}"
        val deviceId = "pairing-int-test-deviceA-${Date.now().toLong()}"
        val pairingUri = synthesizePairingUri(ownerId = ownerId, deviceId = deviceId)

        val result = Pairing.submit(pairingUri, "TestDeviceB").await()

        assertEquals(true, result.ok as Boolean, "Pairing.submit should succeed; got=$result")
        val session = result.value

        // Initial state on subscribe should be `awaiting` — the relay
        // accepted the InitiatePairing request and the polling loop is
        // running but hasn't seen Device-A's ApprovePairingRequest yet.
        var firstStatus: dynamic = null
        val unsubscribe = session.observe { status: dynamic ->
            if (firstStatus == null) firstStatus = status
        } as () -> Unit

        // Give the observe callback its synchronous initial fire, then
        // tear down before the 5s polling tick.
        assertEquals("awaiting", firstStatus.kind as String)

        unsubscribe()
        session.cancel().unsafeCast<kotlin.js.Promise<Unit>>().await()
    }
}

// Builds a canonical `tenshu://join?o=…&d=…&k=…&s=…` URI via the shared
// `:core-common` PairingUri helper — the same generator Control's
// `SyncManagerImpl.getPairingString()` uses.
private fun synthesizePairingUri(ownerId: String, deviceId: String): String {
    val pubKey = ByteArray(32) { (it + 1).toByte() }
    val secret = "pairing-int-test-secret-${Date.now().toLong()}"
    return PairingUri.joinUri(
        ownerId = ownerId,
        hostDeviceId = deviceId,
        hostPublicKey = pubKey,
        pairingSecret = secret.encodeToByteArray(),
    )
}
