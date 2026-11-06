package org.tenshu.sync.relay.routing

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire-shape coverage for `configureShareRouting`
 * (`docs/file-sharing.md` #17). Pins the contract
 * the production `RestShareRelayClient` (`:sync-service-proxy`) talks to + the
 * anonymous recipient surface the web viewer hits:
 *
 *  - the sharer round-trip: `POST /share` → `PUT chunk` → `GET descriptor` (bumps
 *    opens) → `GET chunk` (opaque ciphertext back) → `DELETE` → recipient 410s,
 *  - the three sharer verbs are JWT-required (401 without auth in production),
 *  - recipient `GET`s are anonymous (reach the route — 410 for absent, not 401),
 *  - `maxOpens` is enforced (410 once the cap is spent).
 *
 * Dev mode supplies the owner via `X-Dev-Owner` (mirrors `ContentRoutingTest`),
 * so these tests exercise the routing + store wiring without minting JWTs.
 */
class ShareRoutingTest {

    private fun descriptorJson(
        shareId: String,
        chunkCount: Int = 1,
        maxOpens: Int? = null,
        sealedMeta: String? = SEALED_META,
    ): String = buildString {
        append("{")
        append("\"shareId\":\"$shareId\",")
        if (sealedMeta != null) append("\"sealedMeta\":\"$sealedMeta\",")
        append("\"chunkCount\":$chunkCount,")
        append("\"chunkHashes\":[],")
        append("\"expiresAtMillis\":null,")
        append("\"maxOpens\":${maxOpens ?: "null"}")
        append("}")
    }

    @Test
    fun devMode_post_put_get_delete_roundTrip() = testApplication {
        application { testModule(knownDevices = KnownDevicesStore()) }

        val shareId = "share-roundtrip"
        val cipher = byteArrayOf(0x01, 0x02, 0x03, 0x7F, -0x80)

        val post = client.post("/share") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", "alice")
            setBody(descriptorJson(shareId))
        }
        assertEquals(HttpStatusCode.Created, post.status)

        val putChunk = client.put("/share/$shareId/chunk/0") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            setBody(cipher)
        }
        assertEquals(HttpStatusCode.NoContent, putChunk.status)
        putChunk.bodyAsBytes()

        // Anonymous descriptor fetch (the recipient surface). F3: the sealed blob comes back
        // verbatim and there is no clear name / MIME / size field at all.
        val getDescriptor = client.get("/share/$shareId")
        assertEquals(HttpStatusCode.OK, getDescriptor.status)
        val descriptor = Json.parseToJsonElement(getDescriptor.bodyAsText()).jsonObject
        assertEquals(shareId, descriptor.getValue("shareId").jsonPrimitive.content)
        assertEquals(SEALED_META, descriptor.getValue("sealedMeta").jsonPrimitive.content)
        assertEquals(
            setOf("shareId", "sealedMeta", "chunkCount", "chunkHashes", "expiresAtMillis", "opensRemaining"),
            descriptor.keys,
            "the descriptor must carry no clear file metadata",
        )

        // Anonymous chunk fetch returns the opaque ciphertext byte-for-byte.
        val getChunk = client.get("/share/$shareId/chunk/0")
        assertEquals(HttpStatusCode.OK, getChunk.status)
        assertContentEquals(cipher, getChunk.bodyAsBytes())

        // Revoke (owner-scoped) -> recipient reads 410.
        val del = client.delete("/share/$shareId") { header("X-Dev-Owner", "alice") }
        assertEquals(HttpStatusCode.NoContent, del.status)

        assertEquals(HttpStatusCode.Gone, client.get("/share/$shareId").status)
        assertEquals(HttpStatusCode.Gone, client.get("/share/$shareId/chunk/0").status)
    }

    @Test
    fun devMode_post_withAShareIdTheTakedownRouteCouldNotName_isRejected() = testApplication {
        application { testModule(knownDevices = KnownDevicesStore()) }
        for (bad in listOf("has.dot", "has~tilde", "x".repeat(129), "has space", "ünïcode")) {
            val post = client.post("/share") {
                contentType(ContentType.Application.Json)
                header("X-Dev-Owner", "alice")
                setBody(descriptorJson(bad))
            }
            assertEquals(HttpStatusCode.BadRequest, post.status, bad)
        }
    }

    @Test
    fun devMode_putChunk_pastTheDeclaredCount_isRejected() = testApplication {
        application { testModule(knownDevices = KnownDevicesStore()) }
        client.post("/share") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", "alice")
            setBody(descriptorJson("share-bounded", chunkCount = 1))
        }
        // Neither a viewer, the GC nor a takedown (all `0 until chunkCount`) would ever reach index 1.
        val past = client.put("/share/share-bounded/chunk/1") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            setBody(byteArrayOf(1))
        }
        assertEquals(HttpStatusCode.BadRequest, past.status)
        assertEquals(HttpStatusCode.Gone, client.get("/share/share-bounded/chunk/1").status)
    }

    @Test
    fun devMode_post_withoutSealedMeta_isRejected() = testApplication {
        application { testModule(knownDevices = KnownDevicesStore()) }
        for (bad in listOf(null, "", "!!not-base64!!")) {
            val post = client.post("/share") {
                contentType(ContentType.Application.Json)
                header("X-Dev-Owner", "alice")
                setBody(descriptorJson("share-bad", sealedMeta = bad))
            }
            assertEquals(HttpStatusCode.BadRequest, post.status, "sealedMeta=$bad must be refused")
        }
        val oversized = "A".repeat((MAX_SEALED_META_BYTES + 3) / 3 * 4 + 4)
        val post = client.post("/share") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", "alice")
            setBody(descriptorJson("share-big", sealedMeta = oversized))
        }
        assertEquals(HttpStatusCode.BadRequest, post.status, "an oversized sealedMeta must be refused")
    }

    @Test
    fun devMode_get_absentShare_returns410() = testApplication {
        application { testModule(knownDevices = KnownDevicesStore()) }
        // Anonymous + absent: reaches the route (not 401), maps to 410 Gone.
        assertEquals(HttpStatusCode.Gone, client.get("/share/does-not-exist").status)
    }

    @Test
    fun devMode_maxOpens_enforced() = testApplication {
        application { testModule(knownDevices = KnownDevicesStore()) }
        val shareId = "share-capped"
        client.post("/share") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", "alice")
            setBody(descriptorJson(shareId, maxOpens = 1))
        }
        // First descriptor GET spends the single open.
        assertEquals(HttpStatusCode.OK, client.get("/share/$shareId").status)
        // Second is over cap -> 410.
        assertEquals(HttpStatusCode.Gone, client.get("/share/$shareId").status)
    }

    @Test
    fun production_post_noAuth_returns401() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
            )
        }
        val response = client.post("/share") {
            contentType(ContentType.Application.Json)
            setBody(descriptorJson("share-x"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun production_putChunk_noAuth_returns401() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
            )
        }
        val response = client.put("/share/share-x/chunk/0") {
            contentType(ContentType.Application.OctetStream)
            setBody(byteArrayOf(1, 2, 3))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun production_delete_noAuth_returns401() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/share/share-x").status)
    }

    @Test
    fun production_anonymousDescriptorGet_reachesRoute_not401() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
            )
        }
        // The recipient GET is anonymous even in production (capability-as-URL):
        // an absent share is 410, NOT 401 — proving the auth interlock is
        // deliberately not applied to the public read.
        assertEquals(HttpStatusCode.Gone, client.get("/share/absent").status)
    }

    private companion object {
        /** Stand-in for a sealed blob — opaque to the relay, so any base64url works. */
        const val SEALED_META = "c2VhbGVkLW1ldGE"
    }
}
