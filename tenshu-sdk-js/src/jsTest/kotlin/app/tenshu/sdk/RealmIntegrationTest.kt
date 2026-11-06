package app.tenshu.sdk

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Date
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// End-to-end integration test for Realm.create against a real
// `:sync-relay-server` running at http://localhost:8080. Manually
// `@Ignore`d because CI doesn't run a relay; flip the annotation off
// when investigating integration drift.
//
// Start the relay first with `./gradlew :sync-relay-server:run` (it
// binds 0.0.0.0:8080), then `./gradlew :tenshu-sdk-js:jsTest`. Each run
// uses a fresh BIP-39 mnemonic via `Bip39.generateMnemonic()` so the
// relay sees a new ownerId and the realm-create succeeds without 409
// "already exists."
//
// Failure modes worth watching for:
//   - 400 "ownerId does not match the deterministic derivation": the
//     master-key derivation drifted between client and relay (or the
//     base64 encoding got swapped to base64url, etc.).
//   - 400 "invalid signature": Ed25519 sign/verify round-trip broken.
//   - network/CORS: karma's headless Chrome blocked from localhost:8080.
class RealmIntegrationTest {

    @Test
    @Ignore // requires `:sync-relay-server` running at localhost:8080
    fun create_againstLocalRelay_succeeds() = runTest {
        TenshuSdk.configure("http://localhost:8080")
        TenshuSdk.awaitReady().await()

        // Fresh mnemonic per run so the relay gets a unique ownerId.
        // Bip39.generateMnemonic() defaults to 32 bytes entropy = 24 words.
        val mnemonic = Bip39.generateMnemonic()
        val deviceName = "RealmIntegrationTest-${Date.now().toLong()}"

        val result = Realm.create(mnemonic, deviceName).await()

        assertEquals(
            true,
            result.ok as Boolean,
            "Realm.create should succeed against the local relay; got error=${result.error}",
        )
    }

    @Test
    @Ignore // requires `:sync-relay-server` running at localhost:8080
    fun create_thenDetectSession_shows_locked_until_unlock() = runTest {
        TenshuSdk.configure("http://localhost:8080")
        TenshuSdk.awaitReady().await()

        // Clear any prior session record so the assertion below isn't
        // contaminated by a previous test run.
        Session.clear(null).await()

        val mnemonic = Bip39.generateMnemonic()
        val deviceName = "DetectFlowTest-${Date.now().toLong()}"

        val createResult = Realm.create(mnemonic, deviceName).await()
        assertEquals(true, createResult.ok as Boolean)

        // Per UX §2 / Track A slice 5: deviceName persists on success,
        // but no session token has been written yet → `locked`.
        val state = Session.detect().await()
        assertEquals("locked", state.kind as String)
        assertTrue(
            (state.deviceName as String) == deviceName,
            "Session.detect should surface the persisted device name; got ${state.deviceName}",
        )
    }
}
