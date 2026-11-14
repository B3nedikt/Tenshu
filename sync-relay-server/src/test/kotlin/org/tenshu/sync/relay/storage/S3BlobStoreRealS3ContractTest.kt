package org.tenshu.sync.relay.storage

import com.adobe.testing.s3mock.S3MockApplication
import software.amazon.awssdk.http.SdkHttpConfigurationOption
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.utils.AttributeMap
import java.net.BindException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [S3BlobStore] against a **real, third-party S3 implementation** rather than a fake we wrote
 * (`hosting-provider-economics.md` D10 S5).
 *
 * ⚠⚠ **What each layer proves, because they are not redundant and none of them is the deploy:**
 *
 *  - [S3BlobStoreContractTest] runs against a hand-written fake that stores whatever bytes arrive.
 *    Being *dumb* is its value: it caught the `aws-chunked` framing bug, because it stored the
 *    framing instead of decoding it.
 *  - This suite runs against an implementation nobody here wrote, so it cannot share a blind spot
 *    with the client code. It proves our **usage** is right: SigV4 is accepted, path-style
 *    addressing resolves, keys survive, `NoSuchKey` maps to the SPI's null.
 *  - ⚠⚠ **Neither de-risks the target store, and a faithful S3 moves *away* from that risk.** The
 *    stores that break — Hetzner, R2, older MinIO — break by being *less* capable than S3, and the
 *    `aws-chunked` bug is the example: real S3, MinIO and this mock all decode the framing
 *    correctly, so against any of them the bug is invisible. Only pointing at the actual bucket
 *    catches it, which is why that is a pre-window step in
 *    `docs/how-to/migrate-relay-blobs-to-s3.md` and not a test.
 *
 * MinIO would be the closer stand-in (`sync-relay-persistence.md` §6) and `deploy/minio/` has the
 * compose file for it — but every container registry's blob CDN is unreachable from this network,
 * so it cannot be pulled here. This runs in-JVM and therefore always runs.
 */
class S3BlobStoreRealS3ContractTest : BlobStoreContractTest() {

    override fun newStore(): BlobStore {
        // `S3_TEST_ENDPOINT` points the SAME contract at MinIO
        // (`docker compose -f docker-compose.minio.yml up -d`), which is the closer stand-in for a
        // deployed bucket. Unset — as in CI, which has no registry access — the JVM's shared
        // in-JVM S3Mock is used instead, so this suite always runs rather than silently skipping.
        val external = EXTERNAL_ENDPOINT
            ?: return S3BlobStore(client = SharedS3Mock.client, bucket = SharedS3Mock.newBucket())

        return S3BlobStore(
            client = S3BlobStore.clientFor(
                endpoint = external,
                region = "us-east-1",
                accessKey = System.getenv("S3_TEST_ACCESS_KEY") ?: "test-access",
                secretKey = System.getenv("S3_TEST_SECRET_KEY") ?: "test-secret",
            ),
            bucket = System.getenv("S3_TEST_BUCKET") ?: "tenshu-relay-test",
            // A prefix per store, so neither a re-run against a persistent MinIO bucket nor an
            // earlier test in this run can let its leftovers satisfy a later assertion.
            keyPrefix = "contract-${System.nanoTime()}",
        )
    }

    @Test
    fun everyStoreInThisJvm_sharesOneS3Mock() {
        // The speed half of the shared fixture, pinned: one Spring Boot start per test JVM, not one
        // per test. Isolation is the other half — BlobStoreContractTest's
        // aFreshStore_sharesNothingWithAnEarlierOne — and it is only meaningful because of this.
        // Against an external endpoint no S3Mock exists, so there is nothing to count.
        if (EXTERNAL_ENDPOINT != null) return
        newStore()
        newStore()
        assertEquals(1, SharedS3Mock.starts.get(), "S3Mock was started more than once in this JVM")
    }

    @Test
    fun theDialledPort_isLoopbackOnly_soNoOtherListenerCanShareIt() {
        // ⚠⚠ The category-F mechanism (`docs/testing-flakes.md`): a WILDCARD listener and another
        // process's 127.0.0.1 listener can share a port on macOS, and loopback connections go to
        // the specific one — so the client's requests land on, say, the Gradle daemon, which
        // answers 403s, read timeouts and invalid HTTP. S3Mock's HTTP connector is built with
        // `setPort` alone and so is always wildcard; this suite dials the HTTPS connector, the one
        // `server.address` reaches. Two probes, because each OS discriminates on a different one.
        // Against an external endpoint nothing dials S3Mock, so it is not started just to probe it.
        if (EXTERNAL_ENDPOINT != null) return
        val port = SharedS3Mock.port

        // 1. The mechanism itself: a decoy on 127.0.0.1:<port>. On macOS it binds beside a wildcard
        //    listener (red against the old HTTP dial) and not beside a loopback one. Linux refuses
        //    it either way, so there this probe is green for both binds and proves nothing.
        assertFalse(canBind(LOOPBACK, port), "a decoy bound $LOOPBACK:$port alongside S3Mock and would receive its requests")

        // 2. The bind itself, on any OS: a wildcard listener also answers on a non-loopback
        //    interface; a loopback one refuses there. The forced pair is in the same run — the HTTP
        //    connector, which this suite used to dial, must answer, or this probe tests nothing.
        //    (No non-loopback IPv4 interface, e.g. an offline laptop: probe 1 is all there is.)
        val elsewhere = nonLoopbackIpv4() ?: return
        assertTrue(accepts(elsewhere, SharedS3Mock.httpPort), "forced arm: S3Mock's wildcard HTTP connector should answer on $elsewhere")
        assertFalse(accepts(elsewhere, port), "S3Mock's dialled port answered on $elsewhere, so it is bound to the wildcard")
    }

    /**
     * ⚠⚠ **Not run at this layer, and the reason is measured rather than assumed.** The contract's
     * 1,500-key test exists to prove a backend splits at S3's 1,000-key `DeleteObjects` limit. This
     * layer can prove neither half of that:
     *
     *  - It cannot **observe** the split — only [S3BlobStoreContractTest] counts requests, and its
     *    `deleteAll_issuesOneRequestPerThousandKeys_ratherThanOnePerKey` asserts the ratio (2, not
     *    1,500) directly. That is the assertion this one would have to be a weaker copy of.
     *  - It cannot **enforce** the limit either. S3Mock 3.9.1 accepts an oversized request: sending
     *    the 1,500 keys `deleteAll` builds, minus its `chunked(1_000)` split, answers `200` with
     *    `deleted=1500` and no error (probed 2026-09-04). So if [S3BlobStore.deleteAll] lost its
     *    batching tomorrow, this test would stay green here. Real S3 answers `MalformedXML`.
     *
     * What it did cost was **7.3 s of this class's 13.3 s** — 1,500 sequential PUTs through the SDK
     * — which made it the gate's largest timeout surface (`tech-debt.md`, the cold-worktree row).
     *
     * ⚠ `deleteAll`'s SDK path is still covered here three times over, cheaply, by the contract's
     * `deleteAll_removesEveryListedKey_andSparesTheRest`, `_toleratesAbsentKeys` and
     * `_emptyCollection_isANoOp`. What is dropped is the key *count*, not the operation.
     */
    @Test
    @Ignore
    override fun deleteAll_spansMoreThanOneBatch() = Unit

    @Test
    fun theClientWeShipIsAcceptedByAnS3WeDidNotWrite() {
        // The point of this suite in one assertion: a full round-trip through SigV4 signing,
        // path-style addressing and a real bucket, at the size the content plane actually uses.
        val store = newStore()
        val chunk = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        store.put("alice/blob-real", chunk)

        assertEquals(2 * 1024 * 1024, store.get("alice/blob-real")?.size)
        assertContentEquals(chunk, store.get("alice/blob-real"))
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"

        /** Set, the contract runs against that S3 (e.g. MinIO) and the in-JVM S3Mock is never started. */
        val EXTERNAL_ENDPOINT: String? = System.getenv("S3_TEST_ENDPOINT")?.takeIf { it.isNotBlank() }

        fun canBind(host: String, port: Int): Boolean = ServerSocket().use { decoy ->
            decoy.reuseAddress = true
            try {
                decoy.bind(InetSocketAddress(host, port))
                true
            } catch (_: BindException) {
                false
            }
        }

        fun accepts(address: InetAddress, port: Int): Boolean = Socket().use { socket ->
            try {
                // wall-clock: a connect to one of this host's own addresses is refused or accepted
                // at once; the bound only stops a filtered interface from hanging the test.
                socket.connect(InetSocketAddress(address, port), 5_000)
                true
            } catch (_: ConnectException) {
                false
            }
        }

        fun nonLoopbackIpv4(): InetAddress? = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && !it.isLinkLocalAddress }
    }
}

/**
 * One S3Mock per test JVM, started on first use, with a fresh bucket per store.
 *
 * ⚠⚠ **HTTPS on 127.0.0.1, never S3Mock's HTTP port.** S3Mock adds its HTTP connector with
 * `Connector.setPort` alone, so it binds the wildcard whatever `server.address` says; only the
 * HTTPS connector (Spring Boot's own, `server.port`) honours `server.address`. A wildcard listener
 * can share its port with a foreign loopback listener on macOS — the `S3BlobStoreContractTest`
 * mechanism — so the client dials HTTPS with a trust-all client for S3Mock's self-signed
 * certificate. `server.port = 0` also retires S3Mock's default fixed 9191, which two concurrent
 * gates on one machine would have contended for.
 *
 * Never stopped explicitly: Spring Boot registers a JVM shutdown hook, which closes the context
 * and with it S3Mock's temporary store root.
 */
private object SharedS3Mock {

    val starts = AtomicInteger(0)
    private val buckets = AtomicInteger(0)

    private val app: S3MockApplication by lazy {
        starts.incrementAndGet()
        S3MockApplication.start(
            mapOf(
                S3MockApplication.PROP_HTTPS_PORT to 0,
                "server.address" to "127.0.0.1",
                // The wildcard HTTP connector cannot be switched off; port 0 at least keeps it off
                // any fixed port. Nothing dials it except the forced arm of the bind probe.
                S3MockApplication.PROP_HTTP_PORT to 0,
                S3MockApplication.PROP_SILENT to true,
            )
        )
    }

    val port: Int get() = app.port
    val httpPort: Int get() = app.httpPort

    val client: S3Client by lazy {
        S3BlobStore.clientFor(
            endpoint = "https://127.0.0.1:$port",
            region = "us-east-1",
            accessKey = "test-access",
            secretKey = "test-secret",
            httpClient = UrlConnectionHttpClient.builder().buildWithDefaults(
                AttributeMap.builder().put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, true).build()
            ),
        )
    }

    /** A bucket no earlier store in this JVM has touched: the per-test isolation boundary. */
    fun newBucket(): String = "contract-${buckets.incrementAndGet()}".also { bucket ->
        client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
    }
}
