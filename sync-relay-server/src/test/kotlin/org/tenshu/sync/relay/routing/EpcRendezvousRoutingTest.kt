package org.tenshu.sync.relay.routing

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlinx.serialization.json.Json
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire-shape coverage for `configureEpcRendezvousRouting`
 * (`ios-proxy-architecture.md` §5, Phase 3a) — the contract the Phase-3b
 * relay-backed transport will talk. Dev mode supplies the owner via
 * `X-Dev-Owner` so these exercise routing + store semantics without minting
 * JWTs (the [InviteRoutingTest] shape):
 *
 *  - provision (JWT-gated) → duplex append/fetch (anonymous) round-trip,
 *  - cursor advance (empty fetch leaves `nextSince` at `since`),
 *  - admission: anonymous frame traffic 404s without a provisioned session,
 *  - per-owner session cap 429, owner-mismatch 409, teardown owner-scoped,
 *  - oversized frame 413, bad id/direction 400.
 */
class EpcRendezvousRoutingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sessionId(suffix: Int = 0): String =
        (suffix.toString(16).padStart(2, '0')).repeat(16)

    private fun frameBody(bytes: ByteArray): String =
        """{"frame":"${Base64.getEncoder().encodeToString(bytes)}"}"""

    @Test
    fun provision_duplexRoundTrip() = testApplication {
        application { testModule() }
        val id = sessionId(1)

        // Proxy (a realm device) provisions — JWT-gated via dev owner.
        val provision = client.post("/sync/epc/$id") { header("X-Dev-Owner", "alice") }
        assertEquals(HttpStatusCode.NoContent, provision.status)

        // App appends a client→proxy frame — anonymous.
        val c2p = client.post("/sync/epc/$id/c2p") {
            contentType(ContentType.Application.Json)
            setBody(frameBody(byteArrayOf(1, 2, 3)))
        }
        assertEquals(HttpStatusCode.NoContent, c2p.status)

        // Proxy reads it from the c2p direction.
        val fetchC2p = client.get("/sync/epc/$id/c2p?since=0")
        assertEquals(HttpStatusCode.OK, fetchC2p.status)
        val body = json.decodeFromString(FetchFramesResponse.serializer(), fetchC2p.bodyAsText())
        assertEquals(1, body.frames.size)
        assertEquals(1L, body.frames.single().seq)
        assertEquals(1L, body.nextSince)
        assertEquals("AQID", body.frames.single().frame) // base64 of {1,2,3}

        // Proxy replies on proxy→client; app reads it.
        assertEquals(
            HttpStatusCode.NoContent,
            client.post("/sync/epc/$id/p2c") {
                contentType(ContentType.Application.Json)
                setBody(frameBody(byteArrayOf(9)))
            }.status,
        )
        val fetchP2c = json.decodeFromString(
            FetchFramesResponse.serializer(),
            client.get("/sync/epc/$id/p2c?since=0").bodyAsText(),
        )
        assertEquals(1, fetchP2c.frames.size)
        assertEquals("CQ==", fetchP2c.frames.single().frame)
    }

    @Test
    fun cursorAdvance_emptyFetchHoldsCursor() = testApplication {
        application { testModule() }
        val id = sessionId(2)
        client.post("/sync/epc/$id") { header("X-Dev-Owner", "alice") }
        client.post("/sync/epc/$id/c2p") {
            contentType(ContentType.Application.Json)
            setBody(frameBody(byteArrayOf(7)))
        }
        // Fetch past the only frame → empty, cursor stays at `since`.
        val resp = json.decodeFromString(
            FetchFramesResponse.serializer(),
            client.get("/sync/epc/$id/c2p?since=1").bodyAsText(),
        )
        assertTrue(resp.frames.isEmpty())
        assertEquals(1L, resp.nextSince)
    }

    @Test
    fun anonymousTraffic_requiresProvisionedSession() = testApplication {
        application { testModule() }
        val id = sessionId(3)
        // No provision → append + fetch both 404 (admission gate: only a realm
        // device can create a session; anonymous traffic can't conjure one).
        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/sync/epc/$id/c2p") {
                contentType(ContentType.Application.Json)
                setBody(frameBody(byteArrayOf(1)))
            }.status,
        )
        assertEquals(HttpStatusCode.NotFound, client.get("/sync/epc/$id/c2p?since=0").status)
    }

    @Test
    fun perOwnerSessionCap_429() = testApplication {
        application {
            testModule(
                config = RelayConfig(
                    devMode = true,
                    bindHost = "127.0.0.1",
                    port = 8080,
                    maxOpenRendezvousSessionsPerOwner = 2,
                ),
            )
        }
        assertEquals(HttpStatusCode.NoContent, client.post("/sync/epc/${sessionId(1)}") { header("X-Dev-Owner", "alice") }.status)
        assertEquals(HttpStatusCode.NoContent, client.post("/sync/epc/${sessionId(2)}") { header("X-Dev-Owner", "alice") }.status)
        assertEquals(HttpStatusCode.TooManyRequests, client.post("/sync/epc/${sessionId(3)}") { header("X-Dev-Owner", "alice") }.status)
        // A different owner has their own budget.
        assertEquals(HttpStatusCode.NoContent, client.post("/sync/epc/${sessionId(4)}") { header("X-Dev-Owner", "bob") }.status)
    }

    @Test
    fun provision_ownerMismatch_409() = testApplication {
        application { testModule() }
        val id = sessionId(5)
        assertEquals(HttpStatusCode.NoContent, client.post("/sync/epc/$id") { header("X-Dev-Owner", "alice") }.status)
        assertEquals(HttpStatusCode.Conflict, client.post("/sync/epc/$id") { header("X-Dev-Owner", "bob") }.status)
        // Idempotent re-provision by the same owner is fine.
        assertEquals(HttpStatusCode.NoContent, client.post("/sync/epc/$id") { header("X-Dev-Owner", "alice") }.status)
    }

    @Test
    fun teardown_ownerScoped() = testApplication {
        application { testModule() }
        val id = sessionId(6)
        client.post("/sync/epc/$id") { header("X-Dev-Owner", "alice") }
        // Foreign owner can't tear it down.
        assertEquals(HttpStatusCode.Forbidden, client.delete("/sync/epc/$id") { header("X-Dev-Owner", "bob") }.status)
        // Owner can; afterwards anonymous traffic 404s (session gone).
        assertEquals(HttpStatusCode.NoContent, client.delete("/sync/epc/$id") { header("X-Dev-Owner", "alice") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/sync/epc/$id/c2p?since=0").status)
        // Tearing down an unknown session is 404.
        assertEquals(HttpStatusCode.NotFound, client.delete("/sync/epc/${sessionId(7)}") { header("X-Dev-Owner", "alice") }.status)
    }

    @Test
    fun oversizedFrame_413() = testApplication {
        application { testModule() }
        val id = sessionId(8)
        client.post("/sync/epc/$id") { header("X-Dev-Owner", "alice") }
        val tooBig = ByteArray(MAX_RENDEZVOUS_FRAME_BYTES + 1)
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            client.post("/sync/epc/$id/c2p") {
                contentType(ContentType.Application.Json)
                setBody(frameBody(tooBig))
            }.status,
        )
    }

    @Test
    fun badIdOrDirection_400() = testApplication {
        application { testModule() }
        // Non-hex / wrong-length id.
        assertEquals(HttpStatusCode.BadRequest, client.post("/sync/epc/not-a-valid-id") { header("X-Dev-Owner", "alice") }.status)
        // Valid id, bad direction.
        val id = sessionId(9)
        client.post("/sync/epc/$id") { header("X-Dev-Owner", "alice") }
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/sync/epc/$id/sideways") {
                contentType(ContentType.Application.Json)
                setBody(frameBody(byteArrayOf(1)))
            }.status,
        )
    }
}
