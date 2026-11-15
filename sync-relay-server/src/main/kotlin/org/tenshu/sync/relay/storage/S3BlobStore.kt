package org.tenshu.sync.relay.storage

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI
import java.util.UUID

/**
 * S3-compatible [BlobStore] — the content plane's chunk bodies and share chunks in object
 * storage rather than on the relay's block volume (`sync-relay-persistence.md` §7 + §8 Ph4;
 * `hosting-provider-economics.md` D10).
 *
 * ⚠⚠ **This is primarily a reliability change, not a cost one.** On the volume, running out of
 * space is an *outage* — 2026-08-14, when the relay answered 500 to every push while serving reads
 * and reporting `200` on `GET /` (see [FileBlobStore.probe]). On object storage the same
 * unreclaimed growth is a *bill*. The underlying gap is unchanged: `retention-quota-model.md` P3
 * is built but INERT.
 *
 * ⚠⚠ **It does NOT make the relay multi-node.** The single-writer constraint lives on the SQL
 * side, so decoupling the bytes buys reliability, cost and portability — not a second region.
 *
 * Values are opaque ciphertext; the relay never decrypts (House Rule #1), so nothing here is
 * plaintext-sensitive and at-rest encryption on the bucket is defence-in-depth rather than a
 * confidentiality requirement — the same posture `sync-relay-persistence.md` §2 takes.
 */
class S3BlobStore(
    private val client: S3Client,
    private val bucket: String,
    /**
     * Optional key prefix, so one bucket can host more than one relay (staging next to prod).
     * Empty = keys land at the bucket root. Normalised to end with `/` when non-empty.
     */
    keyPrefix: String = "",
) : BlobStore {

    private val prefix: String = when {
        keyPrefix.isEmpty() -> ""
        keyPrefix.endsWith("/") -> keyPrefix
        else -> "$keyPrefix/"
    }

    /**
     * SPI keys already contain `/` (`<ownerId>/<ref>`, `share/<shareId>/<idx>`), and S3 keys take
     * them verbatim — unlike [FileBlobStore], which base64-encodes into one flat filename. No
     * encoding is applied here on purpose: it keeps objects browsable in a console, and S3 keys are
     * compared as exact strings, so the prefix case the contract pins (`.../1` vs `.../11`) cannot
     * collide.
     */
    private fun objectKey(key: String) = prefix + key

    override fun put(key: String, bytes: ByteArray) {
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(objectKey(key)).build(),
            RequestBody.fromBytes(bytes),
        )
    }

    override fun get(key: String): ByteArray? =
        try {
            client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(objectKey(key)).build()
            ).asByteArray()
        } catch (e: NoSuchKeyException) {
            // Absent, not broken — the SPI's null. Distinct from a zero-length object, which is a
            // successful read of an empty array (the contract pins that difference: conflating them
            // would turn an empty chunk into a 404 at `GET /content/{ref}`).
            null
        }

    override fun exists(key: String): Boolean =
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey(key)).build())
            true
        } catch (e: NoSuchKeyException) {
            false
        } catch (e: S3Exception) {
            // ⚠ HEAD on a missing key answers a bare 404 with no body, and the SDK cannot always
            // map that to NoSuchKeyException (there is no error code to read). Treat exactly 404 as
            // absent and let every other status propagate — swallowing them would report a
            // credentials or bucket failure as "not there", which the share plane's
            // first-write-wins check would read as permission to overwrite.
            if (e.statusCode() == 404) false else throw e
        }

    override fun sizeOf(key: String): Long? =
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey(key)).build()).contentLength()
        } catch (e: NoSuchKeyException) {
            null
        } catch (e: S3Exception) {
            // Same bare-404 mapping as [exists]; every other status propagates.
            if (e.statusCode() == 404) null else throw e
        }

    override fun delete(key: String) {
        // S3 DELETE is already idempotent: deleting an absent key answers 204, not an error.
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey(key)).build())
    }

    /**
     * One `DeleteObjects` call per [BATCH_SIZE] keys instead of one round-trip each — the reason the
     * SPI has this method at all (see [BlobStore.deleteAll]). Reclaiming a 2 TiB owner goes from
     * ~1M requests to ~1,049.
     *
     * ⚠ `quiet(true)` suppresses the per-key success list, which for a 1,000-key batch is the bulk
     * of the response body; errors still come back. And a partial failure is **reported, not
     * thrown**: S3 answers `200` with a per-key error list, so a batch where some keys failed looks
     * like success to a caller that only checks for an exception. Callers are GCs — leaving an
     * orphaned body is survivable, but silently believing it was deleted is what makes the next
     * sweep skip it forever, so the failures are raised.
     */
    override fun deleteAll(keys: Collection<String>) {
        keys.asSequence()
            .map { ObjectIdentifier.builder().key(objectKey(it)).build() }
            .chunked(BATCH_SIZE)
            .forEach { batch ->
                val response = client.deleteObjects(
                    DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(batch).quiet(true).build())
                        .build()
                )
                if (response.hasErrors() && response.errors().isNotEmpty()) {
                    val sample = response.errors().take(ERROR_SAMPLE)
                        .joinToString("; ") { "${it.key()}: ${it.code()} ${it.message()}" }
                    error(
                        "deleteAll: ${response.errors().size} of ${batch.size} keys were not deleted — $sample"
                    )
                }
            }
    }

    override fun deleteByPrefix(prefix: String) {
        val fullPrefix = objectKey(prefix)
        var continuationToken: String? = null
        do {
            val listReq = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(fullPrefix)
                .continuationToken(continuationToken)
                .build()
            val listRes = client.listObjectsV2(listReq)
            val identifiers = listRes.contents().map { ObjectIdentifier.builder().key(it.key()).build() }
            if (identifiers.isNotEmpty()) {
                for (chunk in identifiers.chunked(BATCH_SIZE)) {
                    val delReq = DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(chunk).quiet(true).build())
                        .build()
                    val delRes = client.deleteObjects(delReq)
                    if (delRes.hasErrors() && delRes.errors().isNotEmpty()) {
                        val sample = delRes.errors().take(ERROR_SAMPLE)
                            .joinToString("; ") { "${it.key()}: ${it.code()} ${it.message()}" }
                        error("deleteByPrefix: ${delRes.errors().size} of ${chunk.size} keys were not deleted — $sample")
                    }
                }
            }
            continuationToken = listRes.nextContinuationToken()
        } while (listRes.isTruncated() == true)
    }

    /**
     * ⚠⚠ **The failure this detects is NOT the one [FileBlobStore.probe] detects.** A bucket does
     * not fill up, so this cannot go red for "out of space"; what it proves is that the endpoint is
     * reachable, the credentials are valid and the bucket accepts a write.
     *
     * ⚠⚠ **And it no longer covers the SQL volume.** `HealthRouting` probes the blob store
     * *because* "a full volume breaks the SQLite write too, so probing the disk covers both" — true
     * only while the blobs and the database share a volume. Once the bytes are in a bucket, the
     * volume still holding `relay.db` and every index row is unmonitored by this probe, and the
     * 2026-08-14 failure mode returns invisible. Closing that is its own slice (D10 S3), and until
     * it lands a green `/health` on an S3-backed relay says less than it did.
     *
     * Still a **write**, for the same reason as the file backend: a read would pass against a
     * read-only credential or a bucket policy that forbids `PutObject`.
     */
    override fun probe() {
        val key = objectKey(".health-probe-${UUID.randomUUID()}")
        try {
            client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(HEALTH_PROBE_BYTES),
            )
        } finally {
            // Same lesson as FileBlobStore: a probe that leaks its own object on the condition it
            // exists to detect would leave one orphan per poll — and the GC works off the SQL index,
            // so nothing else would ever collect it.
            runCatching { client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()) }
        }
    }

    companion object {
        /** Content is irrelevant; it only has to be a real write. */
        private val HEALTH_PROBE_BYTES = byteArrayOf(0)

        /**
         * S3's `DeleteObjects` hard limit is 1,000 keys per request; a larger batch is rejected
         * outright rather than truncated, so this is a protocol ceiling, not a tuning knob.
         */
        private const val BATCH_SIZE = 1_000

        /** Enough failed keys to diagnose a pattern, few enough not to log a 1,000-line error. */
        private const val ERROR_SAMPLE = 5

        /**
         * Build a client for any S3-compatible endpoint (AWS, Hetzner, Scaleway, R2, MinIO, B2).
         *
         * ⚠⚠ The two settings that make non-AWS stores work, both deliberate:
         *
         *  - **`forcePathStyle`** — virtual-host addressing (`<bucket>.<endpoint>`) needs wildcard
         *    DNS and TLS the store may not have; path style (`<endpoint>/<bucket>`) is what
         *    S3-compatible providers document.
         *  - **`requestChecksumCalculation(WHEN_REQUIRED)`** — since ~2.30 the SDK sends flexible
         *    checksums (`x-amz-checksum-crc32`) on every PUT by default, and several S3-compatible
         *    stores reject them outright.
         *  - **`chunkedEncodingEnabled(false)`** — ⚠⚠ **and this one is NOT implied by the
         *    checksum setting.** With it left on, `PutObject` frames the body as `aws-chunked`, so
         *    the bytes on the wire are the payload *plus* per-chunk length headers: a 2 MiB blob
         *    arrived as 2,098,678 bytes against a permissive store, and read back corrupt. A store
         *    that does not decode the framing does not fail the PUT — it stores the framing. Caught
         *    by `S3BlobStoreContractTest.chunkSizedBlob_roundTripsByteExact`, which is the reason
         *    that test uses the real 2 MiB chunk size rather than a token payload.
         *
         * ⚠ Which is also the limit of what a green test proves: our suite runs against an
         * in-process fake, so **the target store is verified at deploy time, not at test time.**
         */
        fun clientFor(
            endpoint: String,
            region: String,
            accessKey: String,
            secretKey: String,
            // Production never passes this. It exists so the S3Mock contract test can dial S3Mock's
            // loopback-only HTTPS connector with a client that trusts its self-signed certificate
            // (`S3BlobStoreRealS3ContractTest`); every other setting below stays the shipped one.
            httpClient: SdkHttpClient = UrlConnectionHttpClient.create(),
        ): S3Client =
            S3Client.builder()
                .httpClient(httpClient)
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
                )
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
                .build()
    }
}
