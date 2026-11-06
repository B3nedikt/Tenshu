package org.tenshu.sync.relay

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
import org.tenshu.core.common.security.AeadBackend
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
 * The length floor, and why it is the strongest check available.
 *
 * Pins both halves: a truncated `PUT` is rejected, so no zero-length blob replaces real ciphertext
 * under a well-formed ref, and a truncated blob already stored is never served as a `200` with an
 * empty body.
 *
 * ⚠⚠ **`sha256(body) == ref` is unavailable, structurally.** A manifest's ref is
 * `sha256("manifest:$contentId")` — not a hash of its bytes — and manifests share the
 * `blob-<64 hex>` namespace with chunk refs, which *are* `sha256(ciphertext)`. The relay cannot tell
 * them apart, so verification would reject every manifest. The AEAD envelope minimum holds for both.
 */
class ContentBlobFloorTest {

    private val devicePrivateKey = "content-floor-device-key".encodeToByteArray()

    private fun freshToken(ownerId: String, deviceId: String): String {
        val nowSec = System.currentTimeMillis() / 1000
        return encodeJwt(JwtClaims(ownerId, deviceId, nowSec - 5, nowSec + 60)) { msg ->
            stubEd25519Signature(devicePrivateKey, msg)
        }
    }

    private fun devices() = KnownDevicesStore().apply { register("alice", "d1", devicePrivateKey) }

    private fun blobRef(label: String): String =
        "blob-" + label.encodeToByteArray().joinToString("") { "%02x".format(it) }.padEnd(64, '0').take(64)

    private val floor = AeadBackend.AEAD_MIN_CIPHERTEXT_SIZE

    @Test
    fun anEmptyBodyIsRefused_andNothingIsStored() = testApplication {
        val blobs = ContentBlobStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices(),
                contentBlobStore = blobs,
            )
        }
        val ref = blobRef("empty")

        val put = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer ${freshToken("alice", "d1")}")
            setBody(ByteArray(0))
        }

        assertEquals(HttpStatusCode.BadRequest, put.status, put.bodyAsText())
        assertTrue(put.bodyAsText().contains("truncated"), put.bodyAsText())
        assertEquals(0, blobs.blobCount("alice"), "a refused PUT must leave no row and no file")
    }

    /**
     * ⚠ The boundary is where a floor earns or loses its keep. One byte under an envelope cannot be
     * a blob; exactly one envelope is the smallest thing that can.
     */
    @Test
    fun oneByteUnderAnEnvelopeIsRefused_andExactlyOneEnvelopeIsAccepted() = testApplication {
        val blobs = ContentBlobStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices(),
                contentBlobStore = blobs,
            )
        }
        val token = freshToken("alice", "d1")

        val short = client.put("/sync/content/${blobRef("short")}") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer $token")
            setBody(ByteArray(floor - 1))
        }
        assertEquals(HttpStatusCode.BadRequest, short.status, short.bodyAsText())

        val exact = client.put("/sync/content/${blobRef("exact")}") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer $token")
            setBody(ByteArray(floor))
        }
        assertEquals(HttpStatusCode.NoContent, exact.status, exact.bodyAsText())
        assertEquals(1, blobs.blobCount("alice"), "only the well-formed one landed")
    }

    /**
     * ⚠⚠ The read half, and the one that makes the **existing** 278 legible. They are already stored,
     * so the `PUT` floor cannot reach them; serving them as `200` is what turned corruption into a
     * decrypt failure with no status code to blame.
     *
     * ⭐ `404`, not `5xx`: the client already treats a missing chunk as a clean failure and retries,
     * and a chunk is immutable and content-addressed, so a re-emit is idempotent. This routes silent
     * corruption onto the path that already heals.
     */
    @Test
    fun aBlobAlreadyStoredBelowTheFloorIsNotServedAsSuccess() = testApplication {
        val blobs = ContentBlobStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices(),
                contentBlobStore = blobs,
            )
        }
        val ref = blobRef("preexisting")
        // Injected through the store, exactly as the 278 already sit on production — the route
        // cannot create this state any more, which is why the fixture has to bypass it.
        blobs.put("alice", ref, ByteArray(0))
        assertEquals(1, blobs.blobCount("alice"), "the corrupt row exists before the read")

        val get = client.get("/sync/content/$ref") {
            header("Authorization", "Bearer ${freshToken("alice", "d1")}")
        }

        assertEquals(HttpStatusCode.NotFound, get.status, "never 200-with-an-empty-body")
    }

    @Test
    fun aWellFormedBlobStillRoundTripsVerbatim() = testApplication {
        val blobs = ContentBlobStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices(),
                contentBlobStore = blobs,
            )
        }
        val token = freshToken("alice", "d1")
        val ref = blobRef("roundtrip")
        val cipher = ByteArray(96) { i -> (i * 7 + 3).toByte() }

        val put = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer $token")
            setBody(cipher)
        }
        assertEquals(HttpStatusCode.NoContent, put.status)

        val get = client.get("/sync/content/$ref") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, get.status)
        assertContentEquals(cipher, get.bodyAsBytes(), "the relay stores and serves bytes verbatim")
    }
}
