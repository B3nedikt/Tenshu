package org.tenshu.sync.relay.tools

import org.tenshu.sync.relay.storage.BlobStore
import java.util.UUID

/**
 * Proves a destination store will actually serve the relay's content plane, **before** a migration
 * window commits to it (`docs/how-to/migrate-relay-blobs-to-s3.md`, the first pre-window step).
 *
 * ⚠⚠ **This exists because no test can replace it, and a better test would not help.** The stores
 * that break our client — Hetzner, R2, older MinIO — break by being *less* capable than S3, so the
 * `aws-chunked` framing bug is invisible against real S3, MinIO and S3Mock alike (see
 * `S3BlobStore.clientFor` and `hosting-provider-economics.md` D10 S5). Only the bucket you are
 * about to depend on settles it. This turns that from a paragraph of runbook prose into one
 * command, so it stops depending on someone reading carefully.
 *
 * ⚠⚠ **The payload must be chunk-sized.** A small object round-trips *correctly even through
 * framing corruption*, so a token payload would pass against a store that silently mangles real
 * content. [PROBE_SIZE_BYTES] is the content plane's actual 2 MiB chunk.
 */
class BlobStorePreflight(private val store: BlobStore) {

    /** One check and how it went. [detail] is empty when it passed. */
    data class Check(val name: String, val passed: Boolean, val detail: String = "")

    data class Report(val checks: List<Check>) {
        val ok: Boolean get() = checks.all { it.passed }
    }

    /**
     * Exercises exactly the operations the relay's stores depend on, against a throwaway key.
     * Cleans up whatever it managed to write, including when a check fails — a preflight that
     * leaves an orphan in the bucket has made the thing it was inspecting slightly worse, and the
     * GC works off the SQL index so nothing would ever collect it.
     */
    fun run(): Report {
        val key = "${PROBE_PREFIX}${UUID.randomUUID()}"
        val payload = ByteArray(PROBE_SIZE_BYTES) { (it % 251).toByte() }
        val checks = mutableListOf<Check>()

        try {
            checks += check("write") { store.put(key, payload) }
            if (checks.last().passed) {
                checks += check("read-back-is-byte-exact") {
                    val readBack = store.get(key)
                        ?: error("the store accepted the write and then had no object at that key")
                    if (readBack.size != payload.size) {
                        // The aws-chunked signature: MORE bytes came back than went in, because the
                        // store kept the per-chunk length headers instead of decoding them.
                        error(
                            "wrote ${payload.size} bytes, read back ${readBack.size}" +
                                if (readBack.size > payload.size) {
                                    " — the store did not decode aws-chunked framing. Set " +
                                        "chunkedEncodingEnabled(false) (S3BlobStore.clientFor already does)."
                                } else " — the store truncated the payload."
                        )
                    }
                    if (!readBack.contentEquals(payload)) error("same length, different bytes — the store altered the payload")
                }
                // `exists` is not a convenience: SqliteShareStore's GC reads it as "this share never
                // received chunk 0", so a store that answers it wrongly deletes live share records.
                checks += check("exists-reports-present") {
                    if (!store.exists(key)) error("the object was written and readable, but exists() said it was absent")
                }
                checks += check("delete") { store.delete(key) }
                checks += check("exists-reports-absent-after-delete") {
                    if (store.exists(key)) error("the object was deleted, but exists() still reports it present")
                }
                // The retention GC re-runs over rows whose object is already gone.
                checks += check("delete-is-idempotent") { store.delete(key) }
            }
        } finally {
            runCatching { store.delete(key) }
        }

        return Report(checks)
    }

    private fun check(name: String, body: () -> Unit): Check =
        runCatching { body() }.fold(
            onSuccess = { Check(name, passed = true) },
            onFailure = { Check(name, passed = false, detail = it.message ?: it::class.simpleName.orEmpty()) },
        )

    companion object {
        /**
         * The content plane's real chunk size (`FileContentChunkSizeBytes`). ⚠ Do not shrink it to
         * make the check faster — a small payload survives framing corruption, which is the whole
         * failure this is here to catch.
         */
        const val PROBE_SIZE_BYTES: Int = 2 * 1024 * 1024

        /** Distinct from a real key (`<ownerId>/<ref>`, `share/<id>/<idx>`) so it cannot collide. */
        private const val PROBE_PREFIX = ".preflight-"
    }
}
