package app.tenshu.sdk

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Smoke-tests the @JsExport Realm.create wrapper end-to-end through the
// JsRuntime op chain. Doesn't stand up a real relay — points the runtime
// at an unused port so the HTTP request fails, then asserts that the
// failure surfaces as `{ ok: false, error: <non-empty string> }` instead
// of e.g. an unhandled Promise rejection.
//
// The relay is pinned to the unreachable [TEST_RELAY_SINK_URL] (localhost:9,
// RFC 863 Discard) by [SdkRuntimeTest]'s `@BeforeTest`, before this — or any
// other runtime suite — builds the sync chain. So `Realm.create` dispatches
// against an unreachable relay and the network failure surfaces as a typed
// `{ ok: false, error }` result rather than an unhandled rejection.
class RealmTest : SdkRuntimeTest() {

    @Test
    fun create_failsGracefullyOnNetworkError() = runTest {
        TenshuSdk.awaitReady().await()

        val result = Realm
            .create("realm-test mnemonic ${Date.now().toLong()}", "test-device")
            .await()

        val ok = result.ok as Boolean
        assertEquals(false, ok, "Realm.create should fail against an unreachable relay")
        val error = result.error as String
        assertTrue(error.isNotEmpty(), "Failure result should carry a non-empty error string")
    }
}
