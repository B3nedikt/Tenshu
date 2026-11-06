package org.tenshu.sync.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.common.TestProxy
import org.tenshu.core.messageOrNull
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How a content `PUT` classifies the relay's refusals — the seam that decides whether the sync
 * loop retries or backs off.
 *
 * ⚠⚠ A relay refusal that is *not* marked is treated as a transient network failure and retried at
 * tick cadence forever. That has now happened three times, each time in production and each time
 * invisible until someone looked at a device: `402` (~6 rejects/sec), `401` (~8k rejects/hour), and
 * `507` (~30 rejects/sec for 80+ minutes, measured 2026-09-06). The marker is the only thing
 * standing between a refusal and a storm, so each status the relay can refuse with owns a test
 * here.
 */
class RestContentBlobStoreRejectionTest {

    private val testProxy = TestProxy()

    private fun storeWith(status: HttpStatusCode): RestContentBlobStore {
        val engine = MockEngine {
            respond("", status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return RestContentBlobStore(
            baseUrl = "https://relay.example",
            httpClient = HttpClient(engine) { install(ContentNegotiation) { json() } },
            context = testProxy.tContext,
            authTokenProvider = { "device-jwt" },
        )
    }

    private suspend fun putFailureMessage(status: HttpStatusCode): String {
        val result = storeWith(status).putChunk("blob-a", ByteArray(16))
        assertTrue(result is TResult.Failure, "$status must not read as success")
        return (result as TResult.Failure).error.messageOrNull().orEmpty()
    }

    @Test
    fun overQuota507_isMarkedAsAStorageRejection_soTheDrainCanStop() = runTest {
        // The relay answers `507` + `{"error":"over_quota"}` when the owner is at their tier
        // ceiling (`ContentRouting`). Nothing the device does can make this write fit.
        val message = putFailureMessage(HttpStatusCode.InsufficientStorage)

        assertTrue(
            message.contains(STORAGE_REJECTION_MARKER),
            "a 507 must be recognisable as doomed, not left to look transient: $message",
        )
    }

    @Test
    fun entitlement402_keepsItsOwnMarker_andIsNotConfusedWithBeingFull() = runTest {
        // ⚠ The two clear on different events — a 402 when *this device* gets a credential, a 507
        // when the *owner* frees bytes — so they drive separate backoffs and must not collapse
        // into one marker.
        val message = putFailureMessage(HttpStatusCode.PaymentRequired)

        assertTrue(message.contains(ENTITLEMENT_REJECTION_MARKER), message)
        assertFalse(message.contains(STORAGE_REJECTION_MARKER), message)
    }

    @Test
    fun anOrdinaryServerError_isMarkedNeither_soItStillRetries() = runTest {
        // The negative that keeps the markers meaningful: a 500 is exactly the transient case the
        // journal SHOULD retry next tick. Marking it would strand uploads that would have worked.
        val message = putFailureMessage(HttpStatusCode.InternalServerError)

        assertFalse(message.contains(STORAGE_REJECTION_MARKER), message)
        assertFalse(message.contains(ENTITLEMENT_REJECTION_MARKER), message)
    }
}
