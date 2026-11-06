package org.tenshu.sync.relay.routing

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.sync.relay.mailbox.RecoveryChallengeStore
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The per-IP cap on `GET /sync/recovery-challenge` — threat model T-RC-08 residual (a). F8 made the
 * challenge issuable to anyone into a store bounded at 10 000, so without a per-source cap one
 * address could fill it and stall every real recovery for a TTL.
 *
 * Through the real HTTP layer, keyed the way production keys it: by the `X-Forwarded-For` Caddy
 * sets, which `XForwardedHeaders` turns into `request.origin.remoteAddress`.
 */
class RecoveryChallengeRateLimitTest {

    private fun app(limitPerMinute: Int, challengeStore: RecoveryChallengeStore = RecoveryChallengeStore()):
        io.ktor.server.application.Application.() -> Unit = {
        testModule(
            config = RelayConfig(
                devMode = false,
                bindHost = "127.0.0.1",
                port = 8080,
                recoveryChallengeRateLimitPerMinute = limitPerMinute,
            ),
            knownDevices = KnownDevicesStore(),
            realmStore = RealmStore(),
            challengeStore = challengeStore,
        )
    }

    private suspend fun HttpClient.challengeFrom(ip: String): HttpResponse =
        get("/sync/recovery-challenge") { header("X-Forwarded-For", ip) }

    @Test
    fun overThePerIpBudget_is429WithRetryAfter() = testApplication {
        application(app(limitPerMinute = 3))

        repeat(3) { assertEquals(HttpStatusCode.OK, client.challengeFrom(IP_A).status, "request ${it + 1}") }
        val throttled = client.challengeFrom(IP_A)

        assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
        // 3 a minute → the next token in 20 s.
        assertEquals("20", throttled.headers[HttpHeaders.RetryAfter])
        // ⚠ Never echo the address back (or into a log): relay output carries no client IPs.
        assertFalse(IP_A in throttled.bodyAsText())
    }

    @Test
    fun separateIpsHaveSeparateBudgets() = testApplication {
        application(app(limitPerMinute = 2))

        repeat(2) { client.challengeFrom(IP_A) }
        assertEquals(HttpStatusCode.TooManyRequests, client.challengeFrom(IP_A).status)
        assertEquals(HttpStatusCode.OK, client.challengeFrom(IP_B).status, "one address must not spend another's budget")
    }

    @Test
    fun oneIpv6Slash64SharesOneBudget() = testApplication {
        application(app(limitPerMinute = 2))

        assertEquals(HttpStatusCode.OK, client.challengeFrom("2001:db8:1:2::1").status)
        assertEquals(HttpStatusCode.OK, client.challengeFrom("2001:db8:1:2::2").status)
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.challengeFrom("2001:db8:1:2::3").status,
            "rotating within a /64 must not mint a fresh budget",
        )
        assertEquals(HttpStatusCode.OK, client.challengeFrom("2001:db8:1:3::1").status)
    }

    @Test
    fun aThrottledCallerHoldsNoSlotInTheStore() = testApplication {
        // The limiter runs before the store: a flooder's refused requests must not use up the
        // bounded set a real recovery needs.
        application(app(limitPerMinute = 1, challengeStore = RecoveryChallengeStore(maxOutstanding = 2)))

        assertEquals(HttpStatusCode.OK, client.challengeFrom(IP_A).status)
        repeat(20) { assertEquals(HttpStatusCode.TooManyRequests, client.challengeFrom(IP_A).status) }
        assertEquals(HttpStatusCode.OK, client.challengeFrom(IP_B).status, "the second slot is still free")
    }

    @Test
    fun withTheLimitDisabled_nothingIsThrottled() = testApplication {
        application(app(limitPerMinute = 0))

        repeat(20) { assertEquals(HttpStatusCode.OK, client.challengeFrom(IP_A).status) }
    }

    @Test
    fun theDefaultIsOn() {
        assertEquals(
            RelayConfig.DEFAULT_RECOVERY_CHALLENGE_RATE_LIMIT_PER_MINUTE,
            RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080).recoveryChallengeRateLimitPerMinute,
        )
        assertEquals(6, RelayConfig.fromEnv { null }.recoveryChallengeRateLimitPerMinute)
        assertEquals(
            0,
            RelayConfig.fromEnv { if (it == "RECOVERY_CHALLENGE_RATE_LIMIT_PER_MINUTE") "0" else null }
                .recoveryChallengeRateLimitPerMinute,
        )
    }

    private companion object {
        const val IP_A = "203.0.113.7"
        const val IP_B = "198.51.100.9"
    }
}
