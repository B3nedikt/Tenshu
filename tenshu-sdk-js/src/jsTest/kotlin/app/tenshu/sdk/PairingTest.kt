package app.tenshu.sdk

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Unit-level smoke tests for `Pairing.submit` against the contract from
// `web/sdk/src/onboarding.ts`. Doesn't stand up a real relay or QR
// payload — exercises the URI-parsing failure path, which the apex
// surfaces directly without a network round-trip per the contract's
// `PairingSubmitError.invalidUri` kind.
//
// Shares the JsRuntime singleton with `JsRuntimeTest` / `RealmTest`. The
// reordered factory chain (sync last, see Track A slice 2 commit) means
// file ops haven't constructed the relay; `RealmTest` already configured
// the URL to localhost:9 if it ran first. We don't dispatch any network
// op in this file so neither matters.
class PairingTest : SdkRuntimeTest() {

    @Test
    fun submit_invalidUri_returnsInvalidUriKind() = runTest {
        TenshuSdk.awaitReady().await()

        val result = Pairing.submit("not a tenshu pairing uri", "test-device").await()

        assertEquals(false, result.ok as Boolean)
        val error = result.error
        assertEquals(
            "invalidUri",
            error.kind as String,
            "Garbage URIs should surface as `invalidUri` per the onboarding contract",
        )
    }

    @Test
    fun submit_malformedTenshuUri_returnsInvalidUriKind() = runTest {
        TenshuSdk.awaitReady().await()

        // Right role host but missing required params (no `d=`, `k=`, `s=`).
        val result = Pairing.submit("tenshu://join?wrong=value", "test-device").await()

        assertEquals(false, result.ok as Boolean)
        val error = result.error
        assertEquals("invalidUri", error.kind as String)
    }

    @Test
    fun submit_retiredLegacyPairUri_returnsInvalidUriKind() = runTest {
        TenshuSdk.awaitReady().await()

        // The exact pre-flag-day emitter shape — retired with no shim
        // (sync-pairing-inverted-direction.md §6 P1); the apex's pre-submit
        // gate owns the "generate a new code" copy, the SDK just refuses.
        val result = Pairing.submit(
            "tenshu://pair?v=1&d=tenshu1abc&ownerId=owner-1&deviceId=device-2",
            "test-device",
        ).await()

        assertEquals(false, result.ok as Boolean)
        assertEquals("invalidUri", result.error.kind as String)
    }
}
