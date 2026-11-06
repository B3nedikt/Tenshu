package org.tenshu.sync.relay.routing

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Per-IP rate limiting on the unauthenticated `POST /sync/realm` bootstrap
 * (`RelayConfig.genesisRateLimitPerHour`; docs/how-to/deploy-relay-digitalocean.md §"DDoS &
 * abuse"). The limiter counts on route entry, before the handler — so the cap holds regardless of
 * whether the body would have produced a 201/400/409. We send minimal bodies and only assert the
 * gate: requests within the budget pass through to the handler (here: 400 missing-field), the next
 * is rejected with 429.
 */
class GenesisRateLimitTest {

    private fun config(limit: Int) = RelayConfig(
        devMode = false,
        bindHost = "127.0.0.1",
        port = 8080,
        genesisRateLimitPerHour = limit
    )

    private suspend fun io.ktor.client.HttpClient.postRealm() = post("/sync/realm") {
        contentType(ContentType.Application.Json)
        setBody("""{"ownerId": "x"}""") // missing fields → handler would 400; fine, we test the gate
    }

    @Test
    fun overBudget_returns429() = testApplication {
        application {
            testModule(
                config = config(limit = 2),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore()
            )
        }

        // First two consume the per-IP budget; they reach the handler (400 missing-field), not 429.
        repeat(2) {
            val r = client.postRealm()
            assertNotEquals(HttpStatusCode.TooManyRequests, r.status, "request ${it + 1} should be within budget")
            r.bodyAsText()
        }

        // Third over the budget → rejected by the limiter before the handler.
        val limited = client.postRealm()
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        limited.bodyAsText()
    }

    @Test
    fun disabled_neverRateLimited() = testApplication {
        // limit <= 0 disables the limiter entirely (no plugin, route mounted unwrapped).
        application {
            testModule(
                config = config(limit = 0),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore()
            )
        }

        repeat(10) {
            val r = client.postRealm()
            assertTrue(
                r.status != HttpStatusCode.TooManyRequests,
                "with the limiter disabled no request should be throttled (was ${r.status} on ${it + 1})"
            )
            r.bodyAsText()
        }
    }
}
