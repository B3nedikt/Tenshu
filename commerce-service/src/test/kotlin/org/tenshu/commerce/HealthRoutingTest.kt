package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.tenshu.commerce.store.CommerceStore
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GET /health` must distinguish *the process is up* from *the service can serve* — the two states
 * that `GET /`, a static string, cannot tell apart. The unreachable-store case is the whole point of
 * the route, so it is the case with teeth here: delete the `runCatching` in `configureHealthRouting`
 * and [anUnreachableStoreIs503] fails (`StatusPages` turns the throw into a 500, not a 503).
 */
class HealthRoutingTest {

    private val ed = BouncyCastleEd25519Backend()
    private val config = CommerceConfig.fromEnv { null }

    /** Wraps a store so its liveness probe fails the way a severed connection would. */
    private class UnreachableStore(delegate: CommerceStore) : CommerceStore by delegate {
        override fun probe(): Unit = throw IllegalStateException("connection refused")
    }

    private fun appTest(store: CommerceStore, block: suspend (client: HttpClient) -> Unit) =
        testApplication {
            application {
                module(
                    minter = EntitlementMinter(config, IssuerKeypair(config.issuerSeed, ed)),
                    tokenMinter = EntitlementTokenMinter(config, IssuerKeypair(config.issuerSeed, ed)),
                    store = store,
                    emailSender = RecordingEmailSender(),
                )
            }
            block(client)
        }

    @Test
    fun aReachableStoreIs200() = appTest(InMemoryCommerceStore()) { client ->
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"ok\""), response.bodyAsText())
    }

    @Test
    fun anUnreachableStoreIs503() = appTest(UnreachableStore(InMemoryCommerceStore())) { client ->
        val response = client.get("/health")
        assertEquals(
            HttpStatusCode.ServiceUnavailable,
            response.status,
            "an unreachable store must read as 503, not 200 and not the StatusPages 500",
        )
        assertTrue(response.bodyAsText().contains("degraded"), response.bodyAsText())
    }

    /**
     * The body is fixed on purpose: `/health` is unauthenticated, so a prober must not learn the
     * shape of the failure. The exception's text goes to the server log, never the response.
     */
    @Test
    fun theFailureBodyLeaksNothingAboutTheCause() =
        appTest(UnreachableStore(InMemoryCommerceStore())) { client ->
            val body = client.get("/health").bodyAsText()
            assertFalse(body.contains("connection refused"), body)
            assertFalse(body.contains("IllegalStateException"), body)
        }

    /**
     * ⚠ Guards the split: `GET /` must stay a store-free static string. If someone "improves" it by
     * adding a probe, an unreachable database starts reading as a hard failure of the root route —
     * and the two signals collapse back into one.
     */
    @Test
    fun rootStaysUpEvenWhenTheStoreIsUnreachable() =
        appTest(UnreachableStore(InMemoryCommerceStore())) { client ->
            assertEquals(HttpStatusCode.OK, client.get("/").status)
        }
}
