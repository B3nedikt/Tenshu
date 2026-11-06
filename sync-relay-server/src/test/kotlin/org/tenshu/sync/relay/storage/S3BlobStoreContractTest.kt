package org.tenshu.sync.relay.storage

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [S3BlobStore] against the same contract [FileBlobStore] passes, over an in-process fake S3.
 *
 * ⚠⚠ **What this proves and what it does not.** It exercises the real AWS SDK client — request
 * shapes, path-style addressing, key handling, and the absent-vs-empty distinction that the SPI
 * turns on. It does **not** verify SigV4 (the fake ignores signatures) and it cannot catch a
 * store-specific rejection. In particular the flexible-checksum trailers pinned off in
 * [S3BlobStore.clientFor] are exactly the kind of thing a permissive fake accepts and a real
 * Hetzner or R2 bucket refuses. **The target store is verified at deploy time, not here** — the
 * `hosting-provider-economics.md` D10 S4 cutover slice is where that happens.
 *
 * Docker is unavailable in the environment this was written in, so MinIO (which `sync-relay-
 * persistence.md` §6 names as the local stand-in) could not be used. If you have a daemon, running
 * this suite against MinIO instead is strictly better — point [newStore] at it.
 */
class S3BlobStoreContractTest : BlobStoreContractTest() {

    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private val objects = ConcurrentHashMap<String, ByteArray>()
    private val deleteObjectsCalls = java.util.concurrent.atomic.AtomicInteger(0)

    override fun newStore(): BlobStore {
        objects.clear()
        deleteObjectsCalls.set(0)
        server = embeddedServer(Netty, port = 0) {
            routing {
                // Path-style addressing: /<bucket>/<key...>, where the key keeps its slashes.
                put("/$BUCKET/{key...}") {
                    objects[keyOf()] = call.receiveStream().readBytes()
                    call.respond(HttpStatusCode.OK)
                }
                get("/$BUCKET/{key...}") {
                    val bytes = objects[keyOf()]
                    if (bytes == null) call.respondNoSuchKey() else call.respondBytes(bytes)
                }
                head("/$BUCKET/{key...}") {
                    // A HEAD 404 carries no body, so there is no error code for the SDK to read —
                    // which is why S3BlobStore.exists also handles a bare 404 status.
                    // Real S3 answers a HEAD with the object's Content-Length (S3BlobStore.sizeOf reads it).
                    val bytes = objects[keyOf()]
                    if (bytes != null) {
                        call.respond(object : io.ktor.http.content.OutgoingContent.NoContent() {
                            override val contentLength: Long = bytes.size.toLong()
                        })
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
                delete("/$BUCKET/{key...}") {
                    // Real S3 answers 204 whether or not the key was there; the SPI relies on it.
                    objects.remove(keyOf())
                    call.respond(HttpStatusCode.NoContent)
                }
                // `POST /<bucket>?delete` — the DeleteObjects batch API. Implemented here (rather
                // than left to 404) so the fake can COUNT requests, which is the only way to prove
                // deleteAll batches instead of looping.
                post("/$BUCKET") {
                    deleteObjectsCalls.incrementAndGet()
                    val body = call.receiveStream().readBytes().decodeToString()
                    KEY_ELEMENT.findAll(body).forEach { objects.remove(it.groupValues[1]) }
                    call.respondBytes(
                        """<?xml version="1.0" encoding="UTF-8"?><DeleteResult></DeleteResult>""".toByteArray(),
                        contentType = ContentType.Application.Xml,
                    )
                }
            }
        }.start(wait = false)

        val port = runBlockingPort()
        return S3BlobStore(
            client = S3BlobStore.clientFor(
                endpoint = "http://127.0.0.1:$port",
                region = "us-east-1",
                accessKey = "test-access",
                secretKey = "test-secret",
            ),
            bucket = BUCKET,
        )
    }

    @AfterTest
    fun stopServer() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }

    @Test
    fun deleteAll_issuesOneRequestPerThousandKeys_ratherThanOnePerKey() {
        // ⚠⚠ The reason BlobStore.deleteAll exists. Serially, reclaiming a 2 TiB owner is ~1M
        // round-trips inside a request handler — hours. Batched it is ~1,049. This pins the ratio,
        // not just that the keys vanish, because "it still works" is exactly what a regression back
        // to a per-key loop would also report.
        val store = newStore()
        val keys = (1..1_500).map { "alice/blob-$it" }
        keys.forEach { store.put(it, byteArrayOf(1)) }

        store.deleteAll(keys)

        assertEquals(2, deleteObjectsCalls.get(), "1,500 keys should be 2 batches, not 1,500 requests")
        assertTrue(objects.keys.none { it.startsWith("alice/blob-") }, "leftovers: ${objects.keys.take(5)}")
    }

    @Test
    fun keysKeepTheirSlashes_ratherThanBeingEncoded() {
        // FileBlobStore base64s the key into one flat filename; S3 takes it verbatim. Pinned
        // because it is the difference between a browsable bucket and an opaque one, and because
        // a future presigned-URL path (folder-link D9) would expose these keys.
        val store = newStore()
        store.put("alice/blob-1", byteArrayOf(1))
        assertTrue(objects.containsKey("alice/blob-1"), "stored under ${objects.keys}")
    }

    @Test
    fun keyPrefix_isolatesTwoRelaysInOneBucket() {
        val store = newStore()
        val prefixed = S3BlobStore(
            client = S3BlobStore.clientFor(
                endpoint = "http://127.0.0.1:${runBlockingPort()}",
                region = "us-east-1",
                accessKey = "a",
                secretKey = "b",
            ),
            bucket = BUCKET,
            keyPrefix = "staging",
        )
        store.put("alice/blob-1", byteArrayOf(1))
        prefixed.put("alice/blob-1", byteArrayOf(2))
        assertEquals(2, objects.size, "prefixed and unprefixed collided: ${objects.keys}")
        assertTrue(objects.containsKey("staging/alice/blob-1"))
    }

    private fun runBlockingPort(): Int = kotlinx.coroutines.runBlocking {
        server.engine.resolvedConnectors().first().port
    }

    private companion object {
        const val BUCKET = "tenshu-relay-test"

        /** Enough to read a DeleteObjects body without an XML parser in a test fake. */
        val KEY_ELEMENT = Regex("<Key>(.*?)</Key>")

        fun io.ktor.server.routing.RoutingContext.keyOf(): String =
            call.parameters.getAll("key")?.joinToString("/").orEmpty()

        suspend fun io.ktor.server.application.ApplicationCall.respondNoSuchKey() {
            // The error code is what lets the SDK raise NoSuchKeyException rather than a generic
            // S3Exception — which is what S3BlobStore.get maps to the SPI's null.
            respondBytes(
                """<?xml version="1.0" encoding="UTF-8"?><Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message></Error>"""
                    .toByteArray(),
                contentType = ContentType.Application.Xml,
                status = HttpStatusCode.NotFound,
            )
        }
    }
}
