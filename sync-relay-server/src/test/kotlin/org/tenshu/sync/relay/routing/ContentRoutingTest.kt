package org.tenshu.sync.relay.routing

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.common.security.JwtClaims
import org.tenshu.core.common.security.encodeJwt
import org.tenshu.core.common.util.sha256
import kotlinx.serialization.json.Json
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for `PUT` / `GET /sync/content/{ref}` per
 * `docs/adr/0017-file-content-two-plane-channel.md` Phase **P3a**. These tests
 * pin the wire shape the production `RestContentBlobStore`
 * (`:sync-service-proxy`) talks to:
 *
 *   - PUT round-trips the exact ciphertext bytes under a content-derived ref
 *   - PUT is idempotent (same ref, same bytes → 200 either way)
 *   - GET serves opaque ciphertext back without inspection
 *   - JWT auth gates both verbs in production mode
 *   - Malformed refs / oversized bodies / missing blobs each get the
 *     right 4xx
 *
 * The body bytes here are random — the relay never decrypts (House Rule
 * #1 / `sync/threat-model.md` T-R-09) so it doesn't matter what's in
 * them; the test asserts byte-equality of the round-trip, which is the
 * whole contract.
 */
class ContentRoutingTest {

    private val devicePrivateKey = "device-content-key".encodeToByteArray()
    private val devicePublicKey = devicePrivateKey // stub-level: public == private

    private fun signToken(claims: JwtClaims): String =
        encodeJwt(claims) { msg -> stubEd25519Signature(devicePrivateKey, msg) }

    private fun refFor(ciphertext: ByteArray): String =
        "blob-" + sha256(ciphertext).joinToString("") {
            ((it.toInt() and 0xFF).toString(16)).padStart(2, '0')
        }

    @Test
    fun devMode_putThenGet_roundTripsByteIdentical() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }
        val ciphertext = ByteArray(4096) { (it * 31 and 0xFF).toByte() }
        val ref = refFor(ciphertext)

        val putResponse = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.NoContent, putResponse.status)
        putResponse.bodyAsBytes()

        val getResponse = client.get("/sync/content/$ref") {
            header("X-Dev-Owner", "alice")
        }
        assertEquals(HttpStatusCode.OK, getResponse.status)
        assertContentEquals(ciphertext, getResponse.bodyAsBytes())
    }

    @Test
    fun devMode_putIsIdempotent_sameRefSameBytes() = testApplication {
        // The content-derived ref makes two PUTs for the same ref carry
        // byte-identical ciphertext. Both succeed; the second is a
        // no-op overwrite. This is the retry-safety property the
        // proxy-side ContentBlobStore KDoc relies on.
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }
        val ciphertext = ByteArray(1024) { (it and 0x7F).toByte() }
        val ref = refFor(ciphertext)

        repeat(2) {
            val response = client.put("/sync/content/$ref") {
                contentType(ContentType.Application.OctetStream)
                header("X-Dev-Owner", "alice")
                setBody(ciphertext)
            }
            assertEquals(HttpStatusCode.NoContent, response.status, "PUT #$it expected 204")
            response.bodyAsBytes()
        }
        val getResponse = client.get("/sync/content/$ref") {
            header("X-Dev-Owner", "alice")
        }
        assertEquals(HttpStatusCode.OK, getResponse.status)
        assertContentEquals(ciphertext, getResponse.bodyAsBytes())
    }

    @Test
    fun devMode_get_missingBlob_returns404() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }
        val response = client.get(
            "/sync/content/blob-${"0".repeat(64)}"
        ) {
            header("X-Dev-Owner", "alice")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun devMode_put_malformedRef_returns400() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }
        val ciphertext = byteArrayOf(1, 2, 3)
        val response = client.put("/sync/content/not-a-blob-ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun devMode_get_malformedRef_returns400() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }
        val response = client.get("/sync/content/garbage") {
            header("X-Dev-Owner", "alice")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun devMode_put_oversizedBody_returns413() = testApplication {
        // 9 MiB > MAX_CONTENT_BLOB_BYTES (8 MiB). The Content-Length
        // path rejects before reading; the body-size path is the
        // belt-and-braces second line of defense. We send a real body
        // here so the Content-Length header is correct.
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }
        val oversized = ByteArray(9 * 1024 * 1024) { 0 }
        val ref = refFor(oversized)
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            setBody(oversized)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun production_put_noAuth_returns401() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }
        val ciphertext = byteArrayOf(7, 8, 9, 10)
        val ref = refFor(ciphertext)
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun production_get_noAuth_returns401() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }
        val response = client.get("/sync/content/blob-${"a".repeat(64)}")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun production_putThenGetWithJwt_roundTripsByteIdentical() = testApplication {
        // End-to-end JWT-authed round-trip — matches what a real
        // RestContentBlobStore client does against a production relay.
        val store = KnownDevicesStore().apply {
            register("alice-content", "device-content", devicePublicKey)
        }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store
            )
        }
        val ciphertext = ByteArray(2048) { (it xor 0x5A).toByte() }
        val ref = refFor(ciphertext)
        val nowSec = System.currentTimeMillis() / 1000
        val token =
            signToken(JwtClaims("alice-content", "device-content", nowSec - 5, nowSec + 60))

        val putResponse = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer $token")
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.NoContent, putResponse.status)
        putResponse.bodyAsBytes()

        val getResponse = client.get("/sync/content/$ref") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, getResponse.status)
        assertContentEquals(ciphertext, getResponse.bodyAsBytes())
    }

    @Test
    fun devMode_usage_reportsOwnBytes_scopedToCaller() = testApplication {
        // retention-quota-model.md P1 (D5): GET /sync/usage returns the *caller's own*
        // stored byte count and never another owner's. Alice PUTs 4096 + 1024 bytes; her
        // usage is their sum; Bob (who stored nothing) reads 0 on the very same relay.
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }
        val first = ByteArray(4096) { (it * 7 and 0xFF).toByte() }
        val second = ByteArray(1024) { (it xor 0x33).toByte() }
        for (b in listOf(first, second)) {
            val put = client.put("/sync/content/${refFor(b)}") {
                contentType(ContentType.Application.OctetStream)
                header("X-Dev-Owner", "alice")
                setBody(b)
            }
            assertEquals(HttpStatusCode.NoContent, put.status)
            put.bodyAsBytes()
        }

        val aliceUsage = client.get("/sync/usage") { header("X-Dev-Owner", "alice") }
        assertEquals(HttpStatusCode.OK, aliceUsage.status)
        assertEquals(
            (4096 + 1024).toLong(),
            Json.decodeFromString(UsageResponse.serializer(), aliceUsage.bodyAsText()).usedBytes
        )

        val bobUsage = client.get("/sync/usage") { header("X-Dev-Owner", "bob") }
        assertEquals(HttpStatusCode.OK, bobUsage.status)
        assertEquals(
            0L,
            Json.decodeFromString(UsageResponse.serializer(), bobUsage.bodyAsText()).usedBytes,
            "usage must be scoped to the caller — Bob never sees Alice's bytes"
        )
    }

    @Test
    fun production_usage_withJwt_reportsOwnBytes_noCrossOwnerPeek() = testApplication {
        // Two distinct authed owners on one relay: alice-content stores bytes, bob-content
        // stores none. Each JWT-authed /sync/usage is scoped strictly to its own token owner.
        val store = KnownDevicesStore().apply {
            register("alice-content", "device-alice", devicePublicKey)
            register("bob-content", "device-bob", devicePublicKey)
        }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store
            )
        }
        val nowSec = System.currentTimeMillis() / 1000
        val aliceToken =
            signToken(JwtClaims("alice-content", "device-alice", nowSec - 5, nowSec + 60))
        val bobToken =
            signToken(JwtClaims("bob-content", "device-bob", nowSec - 5, nowSec + 60))

        val ciphertext = ByteArray(2048) { (it and 0x5F).toByte() }
        val put = client.put("/sync/content/${refFor(ciphertext)}") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer $aliceToken")
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.NoContent, put.status)
        put.bodyAsBytes()

        val aliceUsage = client.get("/sync/usage") { header("Authorization", "Bearer $aliceToken") }
        assertEquals(HttpStatusCode.OK, aliceUsage.status)
        assertEquals(
            2048L,
            Json.decodeFromString(UsageResponse.serializer(), aliceUsage.bodyAsText()).usedBytes
        )

        val bobUsage = client.get("/sync/usage") { header("Authorization", "Bearer $bobToken") }
        assertEquals(HttpStatusCode.OK, bobUsage.status)
        assertEquals(
            0L,
            Json.decodeFromString(UsageResponse.serializer(), bobUsage.bodyAsText()).usedBytes
        )
    }

    @Test
    fun production_usage_noAuth_returns401() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }
        val response = client.get("/sync/usage")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun store_pinsTheBlobByRef_acrossManyEntries() = testApplication {
        // White-box assertion: the relay-side store actually accumulates
        // entries keyed by ref, with no cross-talk between blobs. Pins
        // the in-memory store's shape (ConcurrentHashMap<ref, bytes>)
        // independent of route plumbing.
        val store = ContentBlobStore()
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                contentBlobStore = store
            )
        }
        val blobs = (0..3).map { i ->
            ByteArray(64) { (it + i * 100).toByte() }
        }
        for (b in blobs) {
            val response = client.put("/sync/content/${refFor(b)}") {
                contentType(ContentType.Application.OctetStream)
                header("X-Dev-Owner", "alice")
                setBody(b)
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
            response.bodyAsBytes()
        }
        // White-box: every PUT was scoped to the authenticated owner
        // ("alice" via X-Dev-Owner header), so the per-owner store shows
        // exactly 4 entries under that owner, each retrievable by ref.
        // Cross-realm denial has its own dedicated test in the sibling
        // `ContentRoutingTest` (per-owner scoping ⇒ a different ownerId
        // gets a clean miss on the same ref).
        assertEquals(4, store.blobCount("alice"))
        for (b in blobs) {
            assertContentEquals(b, store.get("alice", refFor(b)))
        }
    }
}
