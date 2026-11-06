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
import org.tenshu.sync.relay.mailbox.InviteStore
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire-shape coverage for `configureInviteRouting`
 * (`contact-invitation-handshake.md` §6) — the contract the CI-3
 * `RestInviteRelayClient` talks to. The [AdmitDeviceRoutingTest] /
 * [ShareRoutingTest] shape: dev mode supplies the owner via `X-Dev-Owner`
 * (JWT-vs-dev resolution is `requireOwnerId`'s, covered elsewhere), so these
 * tests exercise routing + store semantics without minting JWTs:
 *
 *  - register / duplicate `409` / per-owner cap `409` / 7-day expiry clamp,
 *  - revoke is owner-scoped (foreign owner sees `404`), redeeming a revoked
 *    invite is `410`,
 *  - response: anonymous, single slot (`409` second post), `410` expired,
 *    `404` unknown, `413` oversized,
 *  - responses listing is per-owner,
 *  - ack lifecycle: `409` before any response, post → anonymous `GET` returns
 *    the sealed bytes, `done` frees all state.
 */
class InviteRoutingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun inviteId(suffix: Int = 0): String =
        (suffix.toString(16).padStart(2, '0')).repeat(16)

    private fun registerBody(inviteId: String, expiresAtMillis: Long? = null): String = buildString {
        append("{\"inviteId\":\"$inviteId\"")
        if (expiresAtMillis != null) append(",\"expiresAtMillis\":$expiresAtMillis")
        append("}")
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun responseBody(inviteId: String, sealed: ByteArray): String =
        """{"inviteId":"$inviteId","sealedResponse":"${b64(sealed)}"}"""

    private fun ackBody(inviteId: String, sealed: ByteArray): String =
        """{"inviteId":"$inviteId","sealedAck":"${b64(sealed)}"}"""

    @Test
    fun fullMailboxLifecycle_register_respond_ack_done() = testApplication {
        val store = InviteStore()
        application { testModule(inviteStore = store) }

        val id = inviteId(1)
        val sealedResponse = byteArrayOf(1, 2, 3, 4)
        val sealedAck = byteArrayOf(9, 8, 7)

        // Inviter registers (authed).
        val register = client.post("/sync/invite") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", "alice")
            setBody(registerBody(id))
        }
        assertEquals(HttpStatusCode.Created, register.status)

        // Invitee posts the sealed response — anonymous.
        val respond = client.post("/sync/invite/response") {
            contentType(ContentType.Application.Json)
            setBody(responseBody(id, sealedResponse))
        }
        assertEquals(HttpStatusCode.NoContent, respond.status)

        // Inviter polls pending responses (authed) and sees the blob.
        val responses = client.get("/sync/invite/responses") {
            header("X-Dev-Owner", "alice")
        }
        assertEquals(HttpStatusCode.OK, responses.status)
        val parsed = json.decodeFromString<PendingInviteResponses>(responses.bodyAsText())
        assertEquals(1, parsed.responses.size)
        assertEquals(id, parsed.responses[0].inviteId)
        assertTrue(
            Base64.getDecoder().decode(parsed.responses[0].sealedResponse)
                .contentEquals(sealedResponse)
        )

        // No ack yet: the invitee's poll keeps getting 404.
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/sync/invite/ack?inviteId=$id").status
        )

        // Inviter posts the sealed ack (authed, owner-scoped).
        val ack = client.post("/sync/invite/ack") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", "alice")
            setBody(ackBody(id, sealedAck))
        }
        assertEquals(HttpStatusCode.NoContent, ack.status)

        // The acked invite no longer shows in the pending list.
        val drained = json.decodeFromString<PendingInviteResponses>(
            client.get("/sync/invite/responses") { header("X-Dev-Owner", "alice") }.bodyAsText()
        )
        assertEquals(0, drained.responses.size)

        // Invitee fetches the ack — anonymous; gets the sealed bytes back.
        val fetched = client.get("/sync/invite/ack?inviteId=$id")
        assertEquals(HttpStatusCode.OK, fetched.status)
        val ackParsed = json.decodeFromString<InviteAckResponse>(fetched.bodyAsText())
        assertTrue(Base64.getDecoder().decode(ackParsed.sealedAck).contentEquals(sealedAck))

        // done frees all relay state.
        val done = client.post("/sync/invite/done") {
            contentType(ContentType.Application.Json)
            setBody("""{"inviteId":"$id"}""")
        }
        assertEquals(HttpStatusCode.NoContent, done.status)
        assertEquals(0, store.recordCount())
        assertEquals(HttpStatusCode.NotFound, client.get("/sync/invite/ack?inviteId=$id").status)
    }

    @Test
    fun register_duplicateId_is409_andCapIsEnforced() = testApplication {
        val cap = 3
        application {
            testModule(
                config = RelayConfig(
                    devMode = true, bindHost = "127.0.0.1", port = 8080,
                    maxOpenInvitesPerOwner = cap,
                )
            )
        }

        val first = client.post("/sync/invite") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", "alice")
            setBody(registerBody(inviteId(1)))
        }
        assertEquals(HttpStatusCode.Created, first.status)

        // Same id again → 409, regardless of owner.
        val dup = client.post("/sync/invite") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", "alice")
            setBody(registerBody(inviteId(1)))
        }
        assertEquals(HttpStatusCode.Conflict, dup.status)

        // Fill up to the cap, then one more → 409. A different owner is
        // unaffected (the cap is per-owner).
        (2..cap).forEach { n ->
            assertEquals(
                HttpStatusCode.Created,
                client.post("/sync/invite") {
                    contentType(ContentType.Application.Json)
                    header("X-Dev-Owner", "alice")
                    setBody(registerBody(inviteId(n)))
                }.status
            )
        }
        assertEquals(
            HttpStatusCode.Conflict,
            client.post("/sync/invite") {
                contentType(ContentType.Application.Json)
                header("X-Dev-Owner", "alice")
                setBody(registerBody(inviteId(cap + 1)))
            }.status
        )
        assertEquals(
            HttpStatusCode.Created,
            client.post("/sync/invite") {
                contentType(ContentType.Application.Json)
                header("X-Dev-Owner", "bob")
                setBody(registerBody(inviteId(cap + 2)))
            }.status
        )
    }

    @Test
    fun register_clampsExpiryToSevenDays_andRejectsPastExpiry() = testApplication {
        val store = InviteStore()
        application { testModule(inviteStore = store) }

        val now = System.currentTimeMillis()
        val id = inviteId(1)
        val farFuture = now + 30L * 24 * 60 * 60 * 1_000
        val register = client.post("/sync/invite") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", "alice")
            setBody(registerBody(id, expiresAtMillis = farFuture))
        }
        assertEquals(HttpStatusCode.Created, register.status)
        val echoed = json.decodeFromString<RegisterInviteResponse>(register.bodyAsText())
        assertTrue(
            echoed.expiresAtMillis <= System.currentTimeMillis() + MAX_INVITE_TTL_MS,
            "expiry not clamped: ${echoed.expiresAtMillis}"
        )

        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/sync/invite") {
                contentType(ContentType.Application.Json)
                header("X-Dev-Owner", "alice")
                setBody(registerBody(inviteId(2), expiresAtMillis = now - 1_000))
            }.status
        )

        // Malformed inviteId (not 16 bytes hex) is rejected.
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/sync/invite") {
                contentType(ContentType.Application.Json)
                header("X-Dev-Owner", "alice")
                setBody(registerBody("not-hex!"))
            }.status
        )
    }

    @Test
    fun response_singleSlot_unknown_expired_oversized() = testApplication {
        val store = InviteStore()
        application { testModule(inviteStore = store) }

        val id = inviteId(1)
        client.post("/sync/invite") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", "alice")
            setBody(registerBody(id))
        }

        // Unknown invite → 404.
        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/sync/invite/response") {
                contentType(ContentType.Application.Json)
                setBody(responseBody(inviteId(9), byteArrayOf(1)))
            }.status
        )

        // Oversized sealed blob → 413 (the §6 ~4 KiB cap).
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            client.post("/sync/invite/response") {
                contentType(ContentType.Application.Json)
                setBody(responseBody(id, ByteArray(MAX_INVITE_BLOB_BYTES + 1)))
            }.status
        )

        // First response takes the slot; the second gets 409 — the burn
        // signal that makes pre-emptive redemption loud (T-CI-01).
        assertEquals(
            HttpStatusCode.NoContent,
            client.post("/sync/invite/response") {
                contentType(ContentType.Application.Json)
                setBody(responseBody(id, byteArrayOf(1)))
            }.status
        )
        assertEquals(
            HttpStatusCode.Conflict,
            client.post("/sync/invite/response") {
                contentType(ContentType.Application.Json)
                setBody(responseBody(id, byteArrayOf(2)))
            }.status
        )

        // Expired invite → 410. Drive expiry via the store directly.
        val expiredId = inviteId(3)
        store.register(
            inviteId = expiredId, ownerId = "alice",
            expiresAtMs = System.currentTimeMillis() - 1, nowMs = System.currentTimeMillis() - 2,
            maxOpenPerOwner = 32,
        )
        assertEquals(
            HttpStatusCode.Gone,
            client.post("/sync/invite/response") {
                contentType(ContentType.Application.Json)
                setBody(responseBody(expiredId, byteArrayOf(1)))
            }.status
        )
    }

    @Test
    fun revoke_isOwnerScoped_andRevokedInviteIsGone() = testApplication {
        val store = InviteStore()
        application { testModule(inviteStore = store) }

        val id = inviteId(1)
        client.post("/sync/invite") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", "alice")
            setBody(registerBody(id))
        }

        // A different owner cannot revoke — 404, existence-leak-proof.
        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("/sync/invite/$id") { header("X-Dev-Owner", "mallory") }.status
        )

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/sync/invite/$id") { header("X-Dev-Owner", "alice") }.status
        )

        // Redeeming a revoked link → 410; ack poll → 410.
        assertEquals(
            HttpStatusCode.Gone,
            client.post("/sync/invite/response") {
                contentType(ContentType.Application.Json)
                setBody(responseBody(id, byteArrayOf(1)))
            }.status
        )
        assertEquals(HttpStatusCode.Gone, client.get("/sync/invite/ack?inviteId=$id").status)
    }

    @Test
    fun ack_requiresPendingResponse_andOwnership() = testApplication {
        val store = InviteStore()
        application { testModule(inviteStore = store) }

        val id = inviteId(1)
        client.post("/sync/invite") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", "alice")
            setBody(registerBody(id))
        }

        // No response yet → 409.
        assertEquals(
            HttpStatusCode.Conflict,
            client.post("/sync/invite/ack") {
                contentType(ContentType.Application.Json)
                header("X-Dev-Owner", "alice")
                setBody(ackBody(id, byteArrayOf(1)))
            }.status
        )

        client.post("/sync/invite/response") {
            contentType(ContentType.Application.Json)
            setBody(responseBody(id, byteArrayOf(1)))
        }

        // A different owner cannot ack someone else's invite — 404.
        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/sync/invite/ack") {
                contentType(ContentType.Application.Json)
                header("X-Dev-Owner", "mallory")
                setBody(ackBody(id, byteArrayOf(1)))
            }.status
        )

        assertEquals(
            HttpStatusCode.NoContent,
            client.post("/sync/invite/ack") {
                contentType(ContentType.Application.Json)
                header("X-Dev-Owner", "alice")
                setBody(ackBody(id, byteArrayOf(1)))
            }.status
        )
    }

    @Test
    fun authedRoutes_require_auth_inProduction() = testApplication {
        application {
            testModule(config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080))
        }

        // The four authed routes 401 without a JWT...
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/sync/invite") {
                contentType(ContentType.Application.Json)
                setBody(registerBody(inviteId(1)))
            }.status
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.delete("/sync/invite/${inviteId(1)}").status
        )
        assertEquals(HttpStatusCode.Unauthorized, client.get("/sync/invite/responses").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/sync/invite/ack") {
                contentType(ContentType.Application.Json)
                setBody(ackBody(inviteId(1), byteArrayOf(1)))
            }.status
        )

        // ...while the capability routes stay anonymous (reach the handler —
        // 404, not 401): the invitee is protocol-anonymous (T-CI-06).
        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/sync/invite/response") {
                contentType(ContentType.Application.Json)
                setBody(responseBody(inviteId(1), byteArrayOf(1)))
            }.status
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/sync/invite/ack?inviteId=${inviteId(1)}").status
        )
        assertEquals(
            HttpStatusCode.NoContent,
            client.post("/sync/invite/done") {
                contentType(ContentType.Application.Json)
                setBody("""{"inviteId":"${inviteId(1)}"}""")
            }.status
        )
    }
}
