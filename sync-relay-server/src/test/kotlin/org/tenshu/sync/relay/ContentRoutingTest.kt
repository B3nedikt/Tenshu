package org.tenshu.sync.relay

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
import org.tenshu.core.common.security.JwtClaims
import org.tenshu.core.common.security.encodeJwt
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTTP-surface coverage for the content plane (`sync-relay-api.md` §3.7;
 * `docs/adr/0017-file-content-two-plane-channel.md`). These tests pin the relay server to the
 * **client wire contract** in
 * `sync-service-proxy/.../RestContentBlobStore.kt`, reproduced here exactly:
 *
 *  - `PUT  /sync/content/{ref}` with `Content-Type: application/octet-stream`,
 *    `Authorization: Bearer <jwt>`, body = opaque ciphertext bytes; the client
 *    treats any 2xx / 201 / 204 as success.
 *  - `GET  /sync/content/{ref}` with `Authorization: Bearer <jwt>`, body = the
 *    exact stored ciphertext bytes.
 *
 * Auth + per-owner scoping mirrors `SyncPushAuthTest` (the `/sync/push`
 * pattern): JWT-authed, `iss` → ownerId, unauth rejected, cross-realm denied.
 * The relay never decrypts (House Rule #1) — verified by the byte-for-byte
 * round-trip of an opaque payload it never interprets.
 */
class ContentRoutingTest {

    private val devicePrivateKey = "content-plane-device-key".encodeToByteArray()
    private val devicePublicKey = devicePrivateKey // stub-level: public == private

    private fun signToken(claims: JwtClaims): String =
        encodeJwt(claims) { msg -> stubEd25519Signature(devicePrivateKey, msg) }

    private fun freshToken(ownerId: String, deviceId: String): String {
        val nowSec = System.currentTimeMillis() / 1000
        return signToken(JwtClaims(ownerId, deviceId, nowSec - 5, nowSec + 60))
    }

    /**
     * Pad a human-readable label out to the `blob-<64-hex>` shape `REF_PATTERN`
     * (in `ContentRouting.kt`) requires. The label is hex-encoded then right-
     * padded with `0`. Keeps each test's intent readable in the call site
     * (`ref("cross-realm")`) while satisfying the relay's ref-shape defence
     * — the production shape is `blob-<sha256(ciphertext) hex>`; tests don't
     * need the hash, only a deterministic well-formed ref.
     */
    private fun blobRef(label: String): String {
        val hex = label.encodeToByteArray().joinToString("") { "%02x".format(it) }
        return "blob-" + hex.padEnd(64, '0').take(64)
    }

    /**
     * A stand-in for one AEAD envelope: `nonce(24) || ciphertext || tag(16)`.
     *
     * ⚠ Never a 1–6 byte array, which no real content blob can be — every blob the store holds is
     * AEAD output, and `MIN_CONTENT_BLOB_BYTES` refuses a body shorter than an envelope as a
     * **truncation**, so the fixtures have to be as long as the thing they stand for. Distinct
     * [seed]s give distinct bytes, so round-trip assertions stay meaningful.
     */
    private fun cipherEnvelope(seed: Int, size: Int = 48): ByteArray =
        ByteArray(size) { i -> (seed * 31 + i).toByte() }

    @Test
    fun production_putThenGet_roundTripsOpaqueBytes() = testApplication {
        val store = KnownDevicesStore().apply {
            register("alice", "device-1", devicePublicKey)
        }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store
            )
        }
        val token = freshToken("alice", "device-1")
        // Arbitrary opaque "ciphertext" the relay must store + serve verbatim.
        val cipher = cipherEnvelope(1)
        val ref = blobRef("deadbeef")

        val put = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer $token")
            setBody(cipher)
        }
        // RestContentBlobStore.putChunk accepts any 2xx / 201 / 204.
        assertEquals(HttpStatusCode.NoContent, put.status)

        val get = client.get("/sync/content/$ref") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, get.status)
        assertContentEquals(
            cipher,
            get.bodyAsBytes(),
            "GET must return the exact opaque bytes (relay never decrypts/inspects)"
        )
    }

    @Test
    fun production_get_missingRef_returns404() = testApplication {
        val store = KnownDevicesStore().apply {
            register("alice", "device-1", devicePublicKey)
        }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store
            )
        }
        val get = client.get("/sync/content/${blobRef("never-stored")}") {
            header("Authorization", "Bearer ${freshToken("alice", "device-1")}")
        }
        // RestContentBlobStore.getChunk maps non-2xx to a clean TResult.Failure
        // the manifest applier gates on (file doesn't surface; retry later).
        assertEquals(HttpStatusCode.NotFound, get.status)
    }

    @Test
    fun production_put_noAuth_returns401() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }
        val put = client.put("/sync/content/${blobRef("x")}") {
            contentType(ContentType.Application.OctetStream)
            setBody(cipherEnvelope(2))
        }
        assertEquals(HttpStatusCode.Unauthorized, put.status)
    }

    @Test
    fun production_get_noAuth_returns401() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }
        val get = client.get("/sync/content/${blobRef("x")}")
        assertEquals(HttpStatusCode.Unauthorized, get.status)
    }

    @Test
    fun production_crossRealm_cannotReadAnotherOwnersBlob() = testApplication {
        // Alice and Bob are distinct realms. Alice PUTs a blob; Bob — fully
        // authenticated in his OWN realm — must not be able to GET it even
        // under the identical ref. The storage key is (authenticated-owner,
        // ref); ownerId always comes from the verified token, never the
        // request. This is the content-plane analogue of
        // SyncPushAuthTest.production_pushWithMismatchedOwner.
        val store = KnownDevicesStore().apply {
            register("alice", "device-a", devicePublicKey)
            register("bob", "device-b", devicePublicKey)
        }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store
            )
        }
        val ref = blobRef("cross-realm")
        val aliceCipher = cipherEnvelope(3)

        val alicePut = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer ${freshToken("alice", "device-a")}")
            setBody(aliceCipher)
        }
        assertEquals(HttpStatusCode.NoContent, alicePut.status)

        // Bob, authed in his own realm, asks for the same ref -> 404 (his
        // namespace has no such blob; Alice's is invisible to him).
        val bobGet = client.get("/sync/content/$ref") {
            header("Authorization", "Bearer ${freshToken("bob", "device-b")}")
        }
        assertEquals(
            HttpStatusCode.NotFound,
            bobGet.status,
            "Bob must not read Alice's blob even under an identical ref"
        )

        // Sanity: Alice can still read her own.
        val aliceGet = client.get("/sync/content/$ref") {
            header("Authorization", "Bearer ${freshToken("alice", "device-a")}")
        }
        assertEquals(HttpStatusCode.OK, aliceGet.status)
        assertContentEquals(aliceCipher, aliceGet.bodyAsBytes())
    }

    @Test
    fun devMode_xDevOwnerHeader_roundTrips() = testApplication {
        // Dev-mode legacy fixture parity (matches SyncPushAuthTest's dev path).
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }
        val cipher = cipherEnvelope(4)
        val put = client.put("/sync/content/${blobRef("dev")}") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            setBody(cipher)
        }
        assertEquals(HttpStatusCode.NoContent, put.status)
        val get = client.get("/sync/content/${blobRef("dev")}") {
            header("X-Dev-Owner", "alice")
        }
        assertEquals(HttpStatusCode.OK, get.status)
        assertContentEquals(cipher, get.bodyAsBytes())
    }

    @Test
    fun endToEnd_putEmitThenGetReadComposes_withGcOfDeletedFileBlob() = testApplication {
        // The proof emit and read compose: an emit PUTs a chunk
        // ciphertext; a read GETs it back identically (this is exactly
        // what RestContentBlobStore.putChunk/getChunk drive against a real
        // relay store — same paths, method, headers, octet-stream body). Then
        // the deleted-file path: once no device GETs the blob anymore (file
        // deleted, manifest tombstoned + drained), the content GC reclaims it
        // after the retention window — while a *still-referenced* blob (kept
        // GET-touched) survives the same sweep.
        val injectedStore = ContentBlobStore(contentRetentionWindowMs = 1_000L)
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                contentBlobStore = injectedStore
            )
        }

        // Two chunks emitted. chunk-1 belongs to a file that will be
        // deleted; chunk-2 belongs to a file that stays referenced.
        val deletedFileChunk = cipherEnvelope(5)
        val liveFileChunk = cipherEnvelope(6)
        val deletedRef = blobRef("deleted")
        val liveRef = blobRef("live")
        for ((ref, body) in listOf(deletedRef to deletedFileChunk, liveRef to liveFileChunk)) {
            val put = client.put("/sync/content/$ref") {
                contentType(ContentType.Application.OctetStream)
                header("X-Dev-Owner", "alice")
                setBody(body)
            }
            assertEquals(HttpStatusCode.NoContent, put.status)
        }

        // Read: both fetch back byte-identical (the round-trip proof).
        val readDeleted = client.get("/sync/content/$deletedRef") {
            header("X-Dev-Owner", "alice")
        }
        assertContentEquals(deletedFileChunk, readDeleted.bodyAsBytes())
        val readLive = client.get("/sync/content/$liveRef") {
            header("X-Dev-Owner", "alice")
        }
        assertContentEquals(liveFileChunk, readLive.bodyAsBytes())

        // Time passes. The "live" file keeps being opened -> its blob keeps
        // getting GET-touched (the live-manifest proxy). The "deleted" file's
        // manifest was tombstoned + drained everywhere, so nobody GETs its
        // chunk anymore. The route's PUT/GET stamp liveness with real
        // System.currentTimeMillis(); the sweep takes an explicit nowMs (the
        // test seam, same shape as SyncMailbox.gcSweep(nowMs=...)). Anchor the
        // simulated "later" sweep time to real wall-clock so it is strictly
        // after the route-side touches above.
        // Both route-side GETs above stamped liveness at ~real wall-clock
        // (deletedRef via readDeleted, liveRef via readLive). Model "the
        // deleted file is never opened again, the live file keeps being
        // opened": advance a simulated clock far past the window, and re-touch
        // ONLY liveRef right before the sweep (a device opening the live file
        // again). deletedRef is left at its stale route-GET time.
        val sweepNow = System.currentTimeMillis() + 60_000L
        injectedStore.get("alice", liveRef, nowMs = sweepNow)

        // Sweep at sweepNow: liveRef was just touched (survives); deletedRef
        // is >60s stale vs the 1_000ms window (reclaimed).
        val dropped = injectedStore.gcSweep(nowMs = sweepNow)
        assertEquals(1, dropped, "exactly the deleted-file blob is GC'd")

        val afterGcDeleted = client.get("/sync/content/$deletedRef") {
            header("X-Dev-Owner", "alice")
        }
        assertEquals(
            HttpStatusCode.NotFound,
            afterGcDeleted.status,
            "deleted-file blob reclaimed once unreferenced + retention elapsed"
        )
        // The shared/live blob is still served byte-for-byte.
        val afterGcLive = client.get("/sync/content/$liveRef") {
            header("X-Dev-Owner", "alice")
        }
        assertEquals(HttpStatusCode.OK, afterGcLive.status)
        assertContentEquals(liveFileChunk, afterGcLive.bodyAsBytes())
    }
}
