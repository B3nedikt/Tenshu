package org.tenshu.sync.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1b of `entitlement-trust-anchor-distribution.md`: the client presents its entitlement credential
 * on `GET /sync/usage` and reads back the relay-reported limit.
 *
 * ⚠⚠ The headers are the substance here, not the parse. The relay keeps **no entitlement state of
 * its own** — it can only report the limit that the credential the caller presents grants — so a
 * client that fetches usage without sending one gets `usedBytes` and a permanently unknown limit.
 * That failure is invisible against a live relay (the response still looks well-formed), which is
 * why this asserts on what goes *out*.
 */
class RelayUsageClientTest {

    private fun clientWith(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        capture: MutableList<Map<String, String>>? = null,
    ): HttpClient = HttpClient(
        MockEngine { request ->
            capture?.add(request.headers.entries().associate { it.key to it.value.joinToString() })
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )

    @Test
    fun presentsBothEntitlementCredentials_alongsideTheDeviceJwt() = runTest {
        val seen = mutableListOf<Map<String, String>>()
        val client = RelayUsageClient(
            baseUrl = "https://relay.test",
            httpClient = clientWith("""{"usedBytes":10,"limitBytes":20}""", capture = seen),
            authTokenProvider = { "device-jwt" },
            entitlementTokenProvider = { "bearer-token" },
            entitlementProofProvider = { "signed-record.v1:abc" },
        )

        client.fetchUsage()

        val headers = seen.single()
        assertEquals("Bearer device-jwt", headers[HttpHeaders.Authorization])
        assertEquals("bearer-token", headers["X-Entitlement"])
        assertEquals("signed-record.v1:abc", headers["X-Entitlement-Proof"])
    }

    @Test
    fun readsTheRelayReportedLimit() = runTest {
        val client = RelayUsageClient(
            baseUrl = "https://relay.test",
            httpClient = clientWith("""{"usedBytes":4096,"limitBytes":53687091200}"""),
            authTokenProvider = { "device-jwt" },
        )

        val usage = client.fetchUsage()

        assertEquals(4096L, usage?.usedBytes)
        assertEquals(53_687_091_200L, usage?.limitBytes)
    }

    /**
     * ⚠ An older relay omits the field entirely. That must parse as unknown, not as a failure —
     * `usedBytes` is still useful on its own, and the whole point of the additive shape is that a
     * client can talk to a relay that predates it.
     */
    @Test
    fun absentLimit_parsesAsUnknown_notAsFailure() = runTest {
        val client = RelayUsageClient(
            baseUrl = "https://relay.test",
            httpClient = clientWith("""{"usedBytes":4096}"""),
            authTokenProvider = { "device-jwt" },
        )

        val usage = client.fetchUsage()

        assertEquals(4096L, usage?.usedBytes)
        assertNull(
            usage?.limitBytes,
            "an older relay's reply must yield unknown — never 0, which would read as 'no storage'",
        )
    }

    /**
     * ⚠ Reading usage must never require an entitlement: a caller with no credential still gets its
     * byte count. Pins that the providers are genuinely optional rather than merely defaulted.
     */
    @Test
    fun withoutCredentials_sendsNoEntitlementHeaders_andStillReadsUsage() = runTest {
        val seen = mutableListOf<Map<String, String>>()
        val client = RelayUsageClient(
            baseUrl = "https://relay.test",
            httpClient = clientWith("""{"usedBytes":7}""", capture = seen),
            authTokenProvider = { "device-jwt" },
        )

        val usage = client.fetchUsage()

        assertEquals(7L, usage?.usedBytes)
        val headers = seen.single()
        assertTrue("X-Entitlement" !in headers, "no token provider ⇒ no bearer header")
        assertTrue("X-Entitlement-Proof" !in headers, "no proof provider ⇒ no proof header")
    }

    @Test
    fun noDeviceToken_makesNoCallAtAll() = runTest {
        val seen = mutableListOf<Map<String, String>>()
        val client = RelayUsageClient(
            baseUrl = "https://relay.test",
            httpClient = clientWith("""{"usedBytes":7}""", capture = seen),
            authTokenProvider = { null },
        )

        assertNull(client.fetchUsage())
        assertTrue(seen.isEmpty(), "no realm-bound owner yet ⇒ don't even make the request")
    }

    @Test
    fun nonSuccess_collapsesToNull_ratherThanThrowing() = runTest {
        val client = RelayUsageClient(
            baseUrl = "https://relay.test",
            httpClient = clientWith("nope", status = HttpStatusCode.InternalServerError),
            authTokenProvider = { "device-jwt" },
        )

        assertNull(client.fetchUsage(), "usage is best-effort and must never throw into the caller")
    }
}
