package org.tenshu.sync.relay

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.sync.relay.auth.AuthFailureLimiter
import org.tenshu.sync.relay.auth.KnownDevicesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server-side half of storm resistance.
 *
 * ⚠⚠ The client-side backoff is **not** a control — an attacker does not run our client. That was
 * the point of this limiter: a peer that cannot authenticate should stop costing the relay an
 * Ed25519 verification per request. See [AuthFailureLimiter] for the shape and its NAT caveat.
 */
class AuthFailureLimiterTest {

    // ---- the bucket itself -------------------------------------------------------------------

    @Test
    fun spendsItsBudgetThenReportsOverBudget() {
        var now = 0L
        val limiter = AuthFailureLimiter(limitPerHour = 3, nowMs = { now })

        repeat(3) {
            assertTrue(!limiter.isOverBudget("ip"), "must not trip before the budget is spent")
            limiter.recordFailure("ip")
        }

        assertTrue(limiter.isOverBudget("ip"), "a spent budget must report over")
    }

    @Test
    fun refillsOverTimeSoAQuietClientRecoversWithoutIntervention() {
        var now = 0L
        val limiter = AuthFailureLimiter(limitPerHour = 60, nowMs = { now })
        repeat(60) { limiter.recordFailure("ip") }
        assertTrue(limiter.isOverBudget("ip"))

        // One minute at 60/hour refills exactly one token.
        now += 60_000
        assertTrue(!limiter.isOverBudget("ip"), "the bucket must refill on its own")
    }

    @Test
    fun budgetsAreIndependentPerKey() {
        var now = 0L
        val limiter = AuthFailureLimiter(limitPerHour = 2, nowMs = { now })
        repeat(2) { limiter.recordFailure("noisy") }

        assertTrue(limiter.isOverBudget("noisy"))
        assertTrue(!limiter.isOverBudget("quiet"), "one client must not spend another's budget")
    }

    @Test
    fun aZeroLimitDisablesItEntirely() {
        val limiter = AuthFailureLimiter(limitPerHour = 0, nowMs = { 0L })
        repeat(1_000) { limiter.recordFailure("ip") }

        assertTrue(!limiter.enabled)
        assertTrue(!limiter.isOverBudget("ip"), "0 must mean off, not 'instantly throttled'")
    }

    @Test
    fun trackedKeysAreBoundedSoRotatingSourcesCannotGrowItWithoutLimit() {
        var now = 0L
        val limiter = AuthFailureLimiter(limitPerHour = 1, nowMs = { now }, maxTrackedKeys = 8)
        // The key is attacker-chosen, so an unbounded map would turn a memory-safe rejection path
        // into a memory-exhaustion one. Eviction is LRU, so the oldest key is the one forgotten.
        repeat(64) { limiter.recordFailure("ip-$it") }

        assertTrue(limiter.isOverBudget("ip-63"), "the most recent key must still be tracked")
        assertTrue(!limiter.isOverBudget("ip-0"), "the oldest key must have been evicted")
    }

    // ---- the wiring, through the real HTTP layer -----------------------------------------------

    private fun appWith(limit: Int): io.ktor.server.application.Application.() -> Unit = {
        testModule(
            config = RelayConfig(
                devMode = false,
                bindHost = "127.0.0.1",
                port = 8080,
                authFailureLimitPerHour = limit,
            ),
            knownDevices = KnownDevicesStore(),
        )
    }

    /**
     * A request that genuinely fails *authentication*.
     *
     * ⚠ Deliberately no `Authorization` header rather than a junk one: a malformed token is a
     * **400**, not a 401, and so is not an authentication failure the budget should charge for.
     * Getting that backwards is how a limiter ends up counting the wrong thing — the accounting
     * path only sees 401s, which is what the storm was made of.
     */
    private suspend fun io.ktor.client.HttpClient.unauthenticatedPull(): HttpResponse =
        post("/sync/log") {
            contentType(ContentType.Application.OctetStream)
            url { parameters.append("since", "0") }
            setBody(byteArrayOf())
        }

    @Test
    fun pastTheBudgetTheAnswerIsStill401_notA429() = testApplication {
        application(appWith(limit = 3))

        repeat(6) { client.unauthenticatedPull() }
        val throttled = client.unauthenticatedPull()

        // ⚠⚠ THE load-bearing assertion. The client keys its backoff, its "device not recognised"
        // state and its self-repair off seeing a 401 (`isRelayUnauthorized`). Answering 429 here
        // would silence all three — i.e. throttling the storm would re-create the storm, and the
        // device would report a network outage instead of an identity problem. If this ever needs
        // to become a 429, the client's classifier has to learn it FIRST.
        assertEquals(HttpStatusCode.Unauthorized, throttled.status)
        assertNotEquals(HttpStatusCode.TooManyRequests, throttled.status)
        assertEquals("60", throttled.headers[HttpHeaders.RetryAfter], "advisory Retry-After")
    }

    @Test
    fun belowTheBudgetNothingIsThrottled() = testApplication {
        application(appWith(limit = 100))

        val response = client.unauthenticatedPull()

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertNull(
            response.headers[HttpHeaders.RetryAfter],
            "a normal rejection must not carry Retry-After — that is the throttled signal",
        )
    }

    @Test
    fun aThrottledClientCanStillReachTheUnauthenticatedPairingRoutes() = testApplication {
        application(appWith(limit = 2))

        repeat(8) { client.unauthenticatedPull() }

        // ⚠⚠ Load-bearing, and the reason the limiter lives INSIDE JwtAuthenticator rather than as
        // a route-level plugin: a device being throttled here is precisely a device whose
        // registration the relay has dropped, and the only way back is to pair again. Gating the
        // pairing mailbox on the same budget would make that state permanently unrecoverable —
        // the exact "401s forever with no escape" trap.
        val pairing = client.post("/sync/pairing/request") {
            contentType(ContentType.Application.OctetStream)
            setBody(byteArrayOf())
        }

        assertNotEquals(
            HttpStatusCode.Unauthorized,
            pairing.status,
            "the unauthenticated recovery path must stay open to a throttled client",
        )
    }
}
