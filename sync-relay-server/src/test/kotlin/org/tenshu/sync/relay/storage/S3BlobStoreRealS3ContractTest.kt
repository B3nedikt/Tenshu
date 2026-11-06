package org.tenshu.sync.relay.storage

import com.adobe.testing.s3mock.S3MockApplication
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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

    private var s3mock: S3MockApplication? = null

    override fun newStore(): BlobStore {
        stop()
        // `S3_TEST_ENDPOINT` points the SAME contract at MinIO
        // (`docker compose -f docker-compose.minio.yml up -d`), which is the closer stand-in for a
        // deployed bucket. Unset — as in CI, which has no registry access — an in-JVM S3Mock is
        // started instead, so this suite always runs rather than silently skipping.
        val external = System.getenv("S3_TEST_ENDPOINT")?.takeIf { it.isNotBlank() }
        val endpoint = external ?: S3MockApplication.start(
            mapOf(
                S3MockApplication.PROP_HTTP_PORT to 0,
                S3MockApplication.PROP_SILENT to true,
                S3MockApplication.PROP_INITIAL_BUCKETS to BUCKET,
            )
        ).also { s3mock = it }.let { "http://127.0.0.1:${it.httpPort}" }

        return S3BlobStore(
            client = S3BlobStore.clientFor(
                endpoint = endpoint,
                region = "us-east-1",
                accessKey = System.getenv("S3_TEST_ACCESS_KEY") ?: "test-access",
                secretKey = System.getenv("S3_TEST_SECRET_KEY") ?: "test-secret",
            ),
            bucket = System.getenv("S3_TEST_BUCKET") ?: BUCKET,
            // A per-run prefix, so re-running against a persistent MinIO bucket cannot let one
            // run's leftovers satisfy the next run's assertions.
            keyPrefix = if (external != null) "contract-${System.nanoTime()}" else "",
        )
    }

    @AfterTest
    fun stop() {
        s3mock?.stop()
        s3mock = null
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
        const val BUCKET = "tenshu-relay-test"
    }
}
