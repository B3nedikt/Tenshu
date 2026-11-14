package org.tenshu.sync.relay.storage

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import software.amazon.awssdk.services.s3.S3Client
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /** The newest store's bucket in [FakeS3]; every assertion below reads the store it just made. */
    private lateinit var bucket: FakeS3.Bucket
    private val objects get() = bucket.objects
    private val deleteObjectsCalls get() = bucket.deleteObjectsCalls

    override fun newStore(): BlobStore {
        bucket = FakeS3.newBucket()
        return S3BlobStore(client = FakeS3.client(), bucket = bucket.name)
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
        val prefixed = S3BlobStore(client = FakeS3.client(), bucket = bucket.name, keyPrefix = "staging")
        store.put("alice/blob-1", byteArrayOf(1))
        prefixed.put("alice/blob-1", byteArrayOf(2))
        assertEquals(2, objects.size, "prefixed and unprefixed collided: ${objects.keys}")
        assertTrue(objects.containsKey("staging/alice/blob-1"))
    }

    @Test
    fun aForeignLoopbackListener_cannotShareTheFakesPort() {
        // Stands in for the Gradle daemon (or any local dev tool) holding 127.0.0.1 on the port the
        // fake was given. If this bind succeeds, the kernel routes the client's loopback requests
        // to the decoy instead of the fake, which is the intermittent 404 this suite used to throw.
        // Only discriminating on macOS/BSD: Linux refuses this bind either way.
        val store = newStore()
        val port = FakeS3.port
        val decoy = java.net.ServerSocket()
        try {
            decoy.reuseAddress = true
            val bound = try {
                decoy.bind(java.net.InetSocketAddress(FakeS3.LOOPBACK, port))
                true
            } catch (_: java.net.BindException) {
                false
            }
            assertFalse(bound, "another listener bound ${FakeS3.LOOPBACK}:$port alongside the fake and would receive its requests")
        } finally {
            decoy.close()
        }
        store.put("alice/blob-1", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), store.get("alice/blob-1"))
    }

    @Test
    fun everyStoreInThisJvm_sharesOneFakeServer() {
        // The speed half of the shared fixture; BlobStoreContractTest's
        // aFreshStore_sharesNothingWithAnEarlierOne is the isolation half.
        newStore()
        newStore()
        assertEquals(1, FakeS3.starts.get(), "the fake S3 was started more than once in this JVM")
    }
}

/**
 * One in-process fake S3 per test JVM, started on first use. Its state is kept per bucket and every
 * store gets a bucket no earlier store touched, so a test sees its own objects and its own
 * `DeleteObjects` count and nothing else — which is what lets one server replace one per test.
 */
private object FakeS3 {

    const val LOOPBACK = "127.0.0.1"

    class Bucket(val name: String) {
        val objects = ConcurrentHashMap<String, ByteArray>()
        val deleteObjectsCalls = AtomicInteger(0)
    }

    val starts = AtomicInteger(0)
    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val bucketCount = AtomicInteger(0)

    /** Enough to read a DeleteObjects body without an XML parser in a test fake. */
    private val KEY_ELEMENT = Regex("<Key>(.*?)</Key>")

    val port: Int by lazy {
        starts.incrementAndGet()
        // ⚠⚠ Bind the address the client dials, never the wildcard. On macOS a `0.0.0.0` listener
        // and another process's `127.0.0.1` listener can share a port (SO_REUSEADDR, either order),
        // and loopback connections go to the more specific one. During the gate, ports are handed
        // out sequentially across every JVM, so the fake sometimes landed on the Gradle daemon's
        // loopback port and the SDK's PUT went to the daemon instead: an intermittent 404 or
        // connection reset. Pinned by aForeignLoopbackListener_cannotShareTheFakesPort.
        // Never stopped: the server lives as long as the test JVM, and dies with it.
        val server = embeddedServer(Netty, port = 0, host = LOOPBACK) {
            routing {
                // Path-style addressing: /<bucket>/<key...>, where the key keeps its slashes.
                put("/{bucket}/{key...}") {
                    bucketOf().objects[keyOf()] = call.receiveStream().readBytes()
                    call.respond(HttpStatusCode.OK)
                }
                get("/{bucket}/{key...}") {
                    val bytes = bucketOf().objects[keyOf()]
                    if (bytes == null) call.respondNoSuchKey() else call.respondBytes(bytes)
                }
                head("/{bucket}/{key...}") {
                    // A HEAD 404 carries no body, so there is no error code for the SDK to read —
                    // which is why S3BlobStore.exists also handles a bare 404 status.
                    // Real S3 answers a HEAD with the object's Content-Length (S3BlobStore.sizeOf reads it).
                    val bytes = bucketOf().objects[keyOf()]
                    if (bytes != null) {
                        call.respond(object : io.ktor.http.content.OutgoingContent.NoContent() {
                            override val contentLength: Long = bytes.size.toLong()
                        })
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
                delete("/{bucket}/{key...}") {
                    // Real S3 answers 204 whether or not the key was there; the SPI relies on it.
                    bucketOf().objects.remove(keyOf())
                    call.respond(HttpStatusCode.NoContent)
                }
                // `POST /<bucket>?delete` — the DeleteObjects batch API. Implemented here (rather
                // than left to 404) so the fake can COUNT requests, which is the only way to prove
                // deleteAll batches instead of looping.
                post("/{bucket}") {
                    val target = bucketOf()
                    target.deleteObjectsCalls.incrementAndGet()
                    val body = call.receiveStream().readBytes().decodeToString()
                    KEY_ELEMENT.findAll(body).forEach { target.objects.remove(it.groupValues[1]) }
                    call.respondBytes(
                        """<?xml version="1.0" encoding="UTF-8"?><DeleteResult></DeleteResult>""".toByteArray(),
                        contentType = ContentType.Application.Xml,
                    )
                }
            }
        }.start(wait = false)
        runBlocking { server.engine.resolvedConnectors().first().port }
    }

    fun newBucket(): Bucket = Bucket("contract-${bucketCount.incrementAndGet()}").also { buckets[it.name] = it }

    fun client(): S3Client = S3BlobStore.clientFor(
        endpoint = "http://$LOOPBACK:$port",
        region = "us-east-1",
        accessKey = "test-access",
        secretKey = "test-secret",
    )

    /**
     * A request to a bucket no store was given fails the test loudly (500 at the SDK) rather than
     * reading as an empty bucket, so a mis-routed request cannot pass for isolation.
     */
    private fun RoutingContext.bucketOf(): Bucket =
        buckets.getValue(call.parameters["bucket"].orEmpty())

    private fun RoutingContext.keyOf(): String =
        call.parameters.getAll("key")?.joinToString("/").orEmpty()

    private suspend fun ApplicationCall.respondNoSuchKey() {
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
