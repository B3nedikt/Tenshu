package org.tenshu.sync.relay.mailbox

import org.tenshu.sync.relay.RelayConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-owner storage for the **content plane** of the two-plane file-content
 * design (`docs/adr/0017-file-content-two-plane-channel.md`, Phase P3).
 *
 * Chunk bodies are immutable, content-addressed, **ciphertext-opaque** blobs.
 * The relay stores them keyed by `(ownerId, ref)` and serves them by ref. It
 * **never decrypts, never inspects, never derives anything from blob contents**
 * (House Rule #1 — the blob is opaque AEAD output and the keying material never
 * reaches the relay). The `ref` is the client's `blob-<sha256(ciphertext)>` opaque key; the
 * relay treats it as an opaque path segment and learns nothing about plaintext.
 *
 * This implementation is in-memory — the dev/test backend. The durable shape shipped as
 * `SqliteContentBlobStore` + `S3BlobStore`/`FileBlobStore`, selected in `Application.kt`
 * (SQLite + S3-compatible object storage, not the Postgres the old Phase-4 plan named).
 *
 * ## Durable content — no time-based GC (`file-content-addressing.md` §4.2, T-R-09)
 *
 * The content plane holds **durable user data**, not an ephemeral delivery log,
 * so — unlike the ordered-log tombstone GC (`SyncMailbox.gcSweep`, quorum +
 * retention) — it is **not** time-reaped. A file nobody opens for months is
 * still live: its `contentId` is still referenced by a live `File`/`SharedFile`,
 * and the relay may be its only durable copy (content is `OnDemand` — the local
 * plaintext can be evicted, and second devices / the web client hold none). A
 * *time-since-last-touch* sweep would reap that live data (the "opened after two
 * years → gone" bug), because content blobs are only `GET`-ed on open and
 * ordinary metadata sync never touches them. So [contentRetentionWindowMs]
 * defaults to `Long.MAX_VALUE` — GC **off** ([RelayConfig.CONTENT_RETENTION_WINDOW_MS]).
 *
 * Why not a manifest refcount: chunk blobs are referenced by **manifests**,
 * which travel as ciphertext the relay cannot read (House Rule #1); a file-delete
 * `DeleteBlock` exposes only an opaque `targetEntityIdHash` + entity type, never
 * the chunk refs. So the relay can never learn *which* blobs a delete orphans
 * without reading content — a literal refcount is HR#1-impossible.
 *
 * Reclamation is therefore never keyed on blob contents or access time. Two mechanisms
 * currently free bytes, and both are `ownerId`- or ref-scoped:
 *  - **account-lifecycle** — [reclaimOwner], driven by an entitlement/commerce-plane signal
 *    after a grace period. Flag-gated (`accountClosureReclaim`): off by default, ON in production
 *    since 2026-10-24.
 *  - **the relay's content sweep** (CR-2 B5, `ContentSweepJob`), deployed `armed`: content
 *    unreferenced for the grace period T is freed on the relay's own clock. ⚠ Since CR-2 B6 this is
 *    the ONLY per-content freeing path — the client-driven `POST /sync/content/delete` (ST-3b) and
 *    `/sync/content/reclaim` (CR-5) are gone, and no device names bytes to free any more.
 *
 * A third is built but frees nothing yet: the client-**declared** content edge set
 * ([ContentEdgeRegistry], `POST /sync/content/declare`) — a holder says what it references and its
 * tombstone says it stopped. ⚠ Nothing reads those edges to reclaim, so it is not yet a mechanism,
 * only a seam (`content-plane-refcount.md` §9). A relay-*derived* refcount stays HR#1-impossible.
 *
 * An orphan `PUT` whose manifest never lands (an aborted upload) still lingers against quota,
 * and quota remains the brake for anything none of these names.
 *
 * The `gcSweep` *mechanism* below is retained — as a test hook and the seam an
 * account-lapse reclaim or admin tool drives with an explicit per-owner window —
 * but the production nightly sweep is a no-op at the `Long.MAX_VALUE` default.
 * A blob is collectible only once untouched for [contentRetentionWindowMs]; with
 * the default that never fires. Tracked in `docs/sync/threat-model.md` T-R-09.
 */
class ContentBlobStore(
    /**
     * Untouched-window before a blob is collectible. Defaults to the
     * tombstone-body retention window so content GC provably outlives the
     * ordered-log tombstone GC (see class KDoc). Test/admin hook — production
     * uses the default; a future per-volume override plugs in here exactly as
     * `SyncMailbox.setRetentionWindowMs` does for tombstone bodies.
     */
    private val contentRetentionWindowMs: Long = RelayConfig.CONTENT_RETENTION_WINDOW_MS
) : ContentBlobRegistry {

    private class Blob(
        val ciphertext: ByteArray,
        @Volatile var lastReferencedAtMs: Long
    )

    // ownerId -> (ref -> blob). Outer + inner are ConcurrentHashMap; per-owner
    // map is created via computeIfAbsent (NOT getOrPut) for the same
    // exactly-once-init reason documented in SyncMailbox's header.
    private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Blob>>()

    /**
     * Store one already-encrypted chunk [ciphertext] under [ref] for [ownerId].
     *
     * A re-PUT of an already-present ref **replaces** the ciphertext and refreshes
     * `lastReferencedAtMs` — the PUT is itself a liveness signal that the emitting
     * device still intends this blob.
     *
     * ⚠⚠ Replace, not keep-first, to match the production backend: `SqliteContentBlobStore`
     * overwrites the bytes and upserts `byte_len`. For a chunk the two are the same thing, since
     * its ref is `sha256(ciphertext)` and a retry carries identical bytes. They differ for the refs
     * that are **not** a hash of their body, which the relay cannot tell apart (see
     * `MIN_CONTENT_BLOB_BYTES`): a manifest re-sealed under a fresh nonce, and a relation slice,
     * whose address is a latest-state pointer re-sealed in place and withdrawn with a marker
     * (`sync-device-catch-up.md` §11.4.4). Keep-first here froze those at their first upload on any
     * relay without `DATABASE_URL`.
     */
    override fun put(
        ownerId: String,
        ref: String,
        ciphertext: ByteArray,
        nowMs: Long
    ) {
        val ownerBlobs = store.computeIfAbsent(ownerId) { ConcurrentHashMap() }
        ownerBlobs[ref] = Blob(ciphertext, nowMs)
    }

    /**
     * Quota-checked [put] (§8 Phase 2b). A *new* ref is refused with [ContentBlobRegistry.PutResult.OVER_QUOTA]
     * once `bytesUsed(owner) + ciphertext.size` would exceed [quotaBytes]; an already-present ref is
     * stored without a check when the body does not grow it, and on its growth otherwise — exactly
     * what `SqliteContentBlobStore.putWithinQuota` does.
     *
     * The owner's per-ref bin is the only thing locked (via `compute`), so two concurrent *new*-ref
     * PUTs by one owner can both read the same `bytesUsed` and slip past — the same bounded race the
     * invite/grant per-owner caps accept; overshoot is ≤ concurrency × the per-blob cap and never
     * corrupts accounting. The durable backend, under its single write lock, has no such race.
     */
    override fun putWithinQuota(
        ownerId: String,
        ref: String,
        ciphertext: ByteArray,
        quotaBytes: Long,
        nowMs: Long,
    ): ContentBlobRegistry.PutResult {
        val ownerBlobs = store.computeIfAbsent(ownerId) { ConcurrentHashMap() }
        var result = ContentBlobRegistry.PutResult.STORED
        ownerBlobs.compute(ref) { _, existing ->
            if (existing != null && ciphertext.size <= existing.ciphertext.size) {
                // Re-PUT at no growth: always accepted, bytes replaced (see [put]), not re-checked.
                Blob(ciphertext, nowMs)
            } else {
                // A new ref, or a re-PUT that grows one: only the growth is charged.
                val used = ownerBlobs.values.sumOf { it.ciphertext.size.toLong() } - (existing?.ciphertext?.size ?: 0)
                if (used + ciphertext.size > quotaBytes) {
                    result = ContentBlobRegistry.PutResult.OVER_QUOTA
                    existing // not stored: a refused growth keeps the old body
                } else {
                    Blob(ciphertext, nowMs)
                }
            }
        }
        return result
    }

    /** Total stored ciphertext bytes for [ownerId] (sum over its live blobs). */
    override fun bytesUsed(ownerId: String): Long =
        store[ownerId]?.values?.sumOf { it.ciphertext.size.toLong() } ?: 0L

    override fun storedLength(ownerId: String, ref: String): Long? =
        store[ownerId]?.get(ref)?.ciphertext?.size?.toLong()

    override fun totalBytes(): Long =
        store.values.sumOf { owner -> owner.values.sumOf { it.ciphertext.size.toLong() } }

    /**
     * Fetch the opaque ciphertext under [ref] for [ownerId], or null if absent
     * (never stored, or GC'd). A successful read refreshes the blob's
     * `lastReferencedAtMs` — the GET is the relay-visible proof a live manifest
     * still points here, which is what keeps a shared / still-referenced blob
     * from being collected (see class KDoc).
     */
    override fun get(
        ownerId: String,
        ref: String,
        nowMs: Long
    ): ByteArray? {
        val blob = store[ownerId]?.get(ref) ?: return null
        blob.lastReferencedAtMs = nowMs
        return blob.ciphertext
    }

    /**
     * Unreferenced-blob GC sweep (`docs/adr/0017-file-content-two-plane-channel.md` content-plane
     * GC). Drops every blob untouched (no PUT, no GET) for at least
     * [contentRetentionWindowMs]. Distinct from `SyncMailbox.gcSweep` (the
     * ordered-log full-quorum HLC-tip body GC) — this is a simpler immutable-
     * object retention sweep on the content plane; the two compose (see class
     * KDoc), they do not duplicate.
     *
     * `Long.MAX_VALUE` retention disables content GC entirely (parity with the
     * tombstone-body GC's `Long.MAX_VALUE` "never GC" sentinel) and is guarded
     * against `now + window` overflow.
     *
     * Returns the number of blobs dropped — primarily a test hook so callers
     * can assert "the sweep did / didn't fire."
     */
    override fun gcSweep(nowMs: Long): Int {
        if (contentRetentionWindowMs == Long.MAX_VALUE) return 0
        var dropped = 0
        for ((ownerId, ownerBlobs) in store) {
            val refsToDrop = ownerBlobs.entries
                .filter { (_, blob) ->
                    nowMs - blob.lastReferencedAtMs > contentRetentionWindowMs
                }
                .map { it.key }
            for (ref in refsToDrop) {
                // remove(key, value) would be ideal but the value identity can
                // race a concurrent PUT refreshing the same ref; re-check the
                // staleness under the per-ref compute lock so a blob touched
                // between filter and removal survives.
                ownerBlobs.compute(ref) { _, blob ->
                    if (blob != null &&
                        nowMs - blob.lastReferencedAtMs > contentRetentionWindowMs
                    ) {
                        dropped++
                        null // evict
                    } else {
                        blob // a concurrent PUT/GET refreshed it — keep
                    }
                }
            }
            if (ownerBlobs.isEmpty()) {
                // Best-effort prune of the now-empty per-owner map. Safe to
                // skip if a concurrent PUT just re-populated it.
                store.remove(ownerId, ownerBlobs)
            }
        }
        return dropped
    }

    /**
     * Account-lifecycle reclamation (Face A / D4) — see the SPI KDoc for why this is
     * `ownerId`-granular and why it must ignore the retention window.
     *
     * One `remove` on the outer map takes the owner's whole bin atomically, so there is no
     * window in which half the keyspace is gone; a PUT racing the reclaim either lands in the
     * detached bin (and is dropped with it) or re-creates a fresh empty bin afterwards. The
     * latter is correct: a device still writing has not observed the closure yet, and its next
     * write is refused by the entitlement gate rather than by this.
     */
    override fun reclaimOwner(ownerId: String): ContentBlobRegistry.ReclaimResult {
        require(ownerId.isNotBlank()) { "reclaimOwner requires a non-blank ownerId." }
        val ownerBlobs = store.remove(ownerId) ?: return ContentBlobRegistry.ReclaimResult.NOTHING
        return ContentBlobRegistry.ReclaimResult(
            blobsDropped = ownerBlobs.size,
            bytesFreed = ownerBlobs.values.sumOf { it.ciphertext.size.toLong() },
        )
    }

    /**
     * Account closure, phase 1 (F12) — a no-op here. In memory the index and the bytes are the same
     * object, so there is no row to take off a read path ahead of the bytes; [eraseOwnerBytes] drops
     * both at once. (The SQLite twin moves its index rows into the deletion queue here.)
     */
    override fun eraseOwner(ownerId: String): Int {
        requireErasableOwner(ownerId)
        return 0
    }

    /** Account closure, phase 2 (F12): exactly [reclaimOwner]. */
    override fun eraseOwnerBytes(ownerId: String): ContentBlobRegistry.ReclaimResult = reclaimOwner(ownerId)

    override fun dropRefs(
        ownerId: String,
        refs: Collection<String>,
    ): ContentBlobRegistry.ReclaimResult {
        require(ownerId.isNotBlank()) { "dropRefs requires a non-blank ownerId." }
        val owned = store[ownerId] ?: return ContentBlobRegistry.ReclaimResult.NOTHING
        var dropped = 0
        var freed = 0L
        for (ref in refs.toSet()) {
            val blob = owned.remove(ref) ?: continue
            dropped++
            freed += blob.ciphertext.size.toLong()
        }
        return ContentBlobRegistry.ReclaimResult(blobsDropped = dropped, bytesFreed = freed)
    }

    /** Test/diagnostics: number of blobs currently held for [ownerId]. */
    override fun blobCount(ownerId: String): Int = store[ownerId]?.size ?: 0

    // ⚠ Unlike the SQLite twin this always reports a real length: an in-memory blob carries its
    // ciphertext, so there is no `byte_len DEFAULT 0` legacy population to stand in for. A test
    // that wants to exercise the inventory's zero-length branch therefore needs the SQLite store.
    override fun blobIndex(ownerId: String): Map<String, ContentBlobRegistry.BlobEntry> =
        store[ownerId]?.entries?.associate { (ref, blob) ->
            ref to ContentBlobRegistry.BlobEntry(blob.ciphertext.size.toLong(), blob.lastReferencedAtMs)
        } ?: emptyMap()

    override fun lastReferencedAt(ownerId: String, refs: Collection<String>): Map<String, Long> {
        if (refs.isEmpty()) return emptyMap()
        val owned = store[ownerId] ?: return emptyMap()
        // ⚠ Driven by the asked-for refs rather than by the owner's map: the point of this method is
        // that its cost follows the request, not the store.
        return refs.distinct().mapNotNull { ref -> owned[ref]?.let { ref to it.lastReferencedAtMs } }.toMap()
    }

    /** Test/maintenance helper. */
    fun clear() {
        store.clear()
    }
}
