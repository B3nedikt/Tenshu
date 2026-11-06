package app.tenshu.sdk

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

// End-to-end integration test for Recovery.claim against a real
// `:sync-relay-server` running at http://localhost:8080. `@Ignore`d by
// default; flip off when investigating integration drift.
//
// Today's coverage exercises only the `noBackupOnRelay` mapping (a
// fresh mnemonic with no realm on the relay). The success path would
// require:
//   1. Bootstrapping a realm via `Realm.create` (sets `(ownerId →
//      masterPublicKey)` on the relay).
//   2. Wiping local IDB so the recovering device is fresh.
//   3. Calling `Recovery.claim` with the same mnemonic.
//
// That setup needs more infra than this scaffold today — wire it in if
// the success-path integration regresses.
class RecoveryIntegrationTest {

    @Test
    @Ignore // requires `:sync-relay-server` running at localhost:8080
    fun claim_freshMnemonic_returnsNoBackupOnRelay() = runTest {
        TenshuSdk.configure("http://localhost:8080")
        TenshuSdk.awaitReady().await()

        // `Bip39.generateMnemonic()` produces a valid 24-word mnemonic
        // whose deterministic ownerId is overwhelmingly unlikely to
        // match any realm the local relay has seen — the relay
        // responds with 404 from `/sync/recovery-claim` (after verifying
        // the master signature; F8), which the Kotlin wrapper maps to
        // `noBackupOnRelay` (slice A4).
        val mnemonic = Bip39.generateMnemonic()
        val result = Recovery.claim(mnemonic, "RecoveryIntegrationTest").await()

        assertEquals(false, result.ok as Boolean)
        val error = result.error
        assertEquals(
            "noBackupOnRelay",
            error.kind as String,
            "An unregistered ownerId should surface as noBackupOnRelay; got=$error",
        )
    }
}
