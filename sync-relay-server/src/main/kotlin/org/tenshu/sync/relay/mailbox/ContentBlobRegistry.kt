package org.tenshu.sync.relay.mailbox

/**
 * The relay-side content plane: `(ownerId, ref) → opaque ciphertext chunk`, with a
 * retention/orphan GC keyed off last-reference time (see [ContentBlobStore] for the
 * House-Rule-#1-safe refcount-proxy rationale).
 *
 * Storage SPI (`docs/proposals/sync-relay-persistence.md`). Two backends:
 *   - [ContentBlobStore] — in-memory; the default + test backend.
 *   - `org.tenshu.sync.relay.storage.SqliteContentBlobStore` — durable; bytes in a
 *     `BlobStore`, last-reference index in SQLite.
 *
 * Bytes are opaque ciphertext — the relay never decrypts (House Rule #1). `nowMs` defaults
 * live here (overrides inherit them); `blobCount` is a test/diagnostics hook.
 */
interface ContentBlobRegistry : OwnerBytesStore {

    /** Outcome of a quota-checked [putWithinQuota]. */
    enum class PutResult {
        /** Stored (or an idempotent re-PUT of an already-present ref). */
        STORED,

        /** Rejected: `bytesUsed(owner) + ciphertext.size` would exceed the entitlement's quota. */
        OVER_QUOTA,
    }

    /**
     * Store [ciphertext] under [ref], **replacing** any blob already there, and refresh its liveness.
     *
     * ⚠⚠ Both backends replace (pinned by `ContentBlobReplacementContractTest`). For a chunk a re-PUT
     * is idempotent anyway, since its ref is `sha256(ciphertext)`; the refs that are not a hash of
     * their body — a manifest, a relation slice re-sealed or withdrawn in place
     * (`sync-device-catch-up.md` §11.4.4) — depend on the replacement.
     */
    fun put(ownerId: String, ref: String, ciphertext: ByteArray, nowMs: Long = System.currentTimeMillis())

    /**
     * Quota-checked variant of [put] (`commerce-billing-plane.md` §8). Stores the chunk
     * only if it fits the owner's entitlement: a *new* ref is rejected with [PutResult.OVER_QUOTA]
     * when `bytesUsed(ownerId) + ciphertext.size > quotaBytes`. A re-PUT of an **already-present**
     * ref no larger than the stored body is always [PutResult.STORED] and replaces the bytes as [put]
     * does — a retried emit must not be billed twice nor refused once stored. ⚠⚠ A re-PUT that
     * **grows** the ref is checked on its growth (`bytesUsed - stored + new`): otherwise many tiny
     * refs overwritten with full-size bodies would grow an owner past any quota.
     *
     * The check-then-store is atomic per owner in the durable backend (one held write lock); the
     * in-memory backend matches the existing per-owner-cap pattern (invites/grants) and tolerates a
     * bounded overshoot under concurrent new-ref PUTs by the same owner (≤ concurrency × the
     * per-blob cap), which the next GC sweep does not need to reconcile.
     */
    fun putWithinQuota(
        ownerId: String,
        ref: String,
        ciphertext: ByteArray,
        quotaBytes: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): PutResult

    /** Fetch the opaque ciphertext under [ref], or null if absent/GC'd; refreshes liveness. */
    fun get(ownerId: String, ref: String, nowMs: Long = System.currentTimeMillis()): ByteArray?

    /** Drop every blob untouched (no put/get) longer than the retention window. Returns the count. */
    fun gcSweep(nowMs: Long = System.currentTimeMillis()): Int

    /** What a [reclaimOwner] call actually freed — measured inside the delete, so it cannot drift. */
    data class ReclaimResult(val blobsDropped: Int, val bytesFreed: Long) {
        companion object {
            val NOTHING = ReclaimResult(blobsDropped = 0, bytesFreed = 0L)
        }
    }

    /**
     * Drop **every** blob belonging to [ownerId] — account-lifecycle reclamation
     * (`retention-quota-model.md` Face A / D4).
     *
     * ⚠⚠ **Irreversible, and for most owners the relay holds the only durable copy.** Content is
     * `OnDemand`: a device's local plaintext is an evictable cache, and second devices / the web
     * client may hold none. Reclaiming an owner who has *not* closed their account destroys their
     * files outright. Treat every call site as data-loss-critical.
     *
     * **Why `ownerId` is the right — and only available — granularity.** The relay is content-blind
     * (House Rule #1): manifests are ciphertext and a delete tombstone exposes only an opaque entity
     * hash, so it can never learn which blobs a *file* orphans. It does not need to: account-lifecycle
     * reclamation operates on a whole owner's keyspace, which is exactly what `(ownerId, ref)` gives.
     *
     * ⚠ **Deliberately independent of the retention window.** [gcSweep] returns 0 when
     * `CONTENT_RETENTION_WINDOW_MS` is `Long.MAX_VALUE` (time-GC off: durable content is never
     * time-reaped, and the production default). This is a *different trigger* — an explicit account event, not elapsed
     * time — so it must still work with the sweep disabled. Sharing that guard would silently make
     * reclamation a no-op in production.
     *
     * ⚠ **Content only.** Tier-1 mailbox blocks have their own correct quorum+retention GC, and
     * device/identity rows are the revocation path's business (ADR 0007); conflating the three is
     * the data-loss bug `retention-quota-model.md` §2 describes. ⚠ Account *closure* is not
     * content-only (F12): it goes through `AccountErasure`, which erases every owner-scoped store
     * via [eraseOwner] / [eraseOwnerBytes] and does not call this.
     *
     * ⚠ A blank [ownerId] throws rather than matching everything — the one input mistake here
     * would be unrecoverable.
     */
    fun reclaimOwner(ownerId: String): ReclaimResult

    /**
     * Drop exactly the blobs [refs] name, for [ownerId] only — **client-driven** reclamation
     * (`drive-upload-stop-and-reclamation.md` ST-3b / §4.4).
     *
     * ⭐ This is the one per-file granularity House Rule #1 permits. The relay cannot compute
     * which blobs a deleted *file* orphaned — manifests are ciphertext and a tombstone exposes
     * only an opaque hash — so it never tries. The **owner's own client** holds the plaintext
     * manifests, works out what nothing references any more, and names it. The relay stays blind
     * and obeys.
     *
     * ⚠⚠ **[ownerId] must come from the caller's verified device JWT, never from the request
     * body.** It is the entire authorisation: with it, a device can delete only its own owner's
     * content; without it, any authenticated device could name another owner's refs and this
     * becomes a cross-tenant delete primitive.
     *
     * ⚠ Irreversible, and for most owners the relay holds the only durable copy — content is
     * `OnDemand` and a device's local plaintext is an evictable cache. A ref still referenced by
     * some other `File` (a copy shares one `contentId`) must never appear here; that judgement is
     * the client's and is the hazard ST-3b's design turns on.
     *
     * Idempotent: refs that are absent, or belong to another owner, are silently skipped rather
     * than erroring — a retried reclamation must not fail because it already worked.
     */
    fun dropRefs(ownerId: String, refs: Collection<String>): ReclaimResult

    /** Number of blobs currently held for [ownerId]. */
    fun blobCount(ownerId: String): Int

    /**
     * Total stored ciphertext bytes for [ownerId] — the figure [putWithinQuota] checks against the
     * entitlement quota. Drops as [dropRefs] (the owner's own reclamation) or [reclaimOwner]
     * free blobs, so a quota freed by deletion is reusable. ⚠ Not via the time-based [gcSweep] —
     * that is off in production (`CONTENT_RETENTION_WINDOW_MS = Long.MAX_VALUE`). Also a test/diagnostics hook, parallel to [blobCount].
     */
    fun bytesUsed(ownerId: String): Long

    /**
     * The stored length of one ref of [ownerId], or null if it holds none. A point read for the
     * write path: the storage ceiling lets a held ref be re-uploaded only at no growth.
     */
    fun storedLength(ownerId: String, ref: String): Long?

    /**
     * Total stored ciphertext bytes over **every** owner — what [org.tenshu.sync.relay.storage.StorageCeiling]
     * measures. ⚠ A full scan: called at most once per ceiling refresh, never per request.
     */
    fun totalBytes(): Long

    /**
     * Every blob [ownerId] holds, as `ref ->` [BlobEntry] — the enumeration side of [blobCount] /
     * [bytesUsed], for the inventory (`content-plane-refcount.md` §11.9).
     *
     * ⚠⚠ **A zero length means a zero-length body was stored.** `byte_len` is recorded on every
     * `PUT` (the column has no default), and a ref is the
     * sha256 of the ciphertext it names under the AEAD layout `nonce(24) || ciphertext || tag(16)`,
     * so no *legitimate* blob is zero-length. The count is reported separately rather than summed
     * into a byte figure, because such a row is a defect to look at, not "almost nothing".
     *
     * ⚠ Such a row holds a 0-byte file whose ref is not the sha256 of the empty string — the bytes
     * are gone. `FileBlobStore.get` returns `readBytes()`
     * for any file that exists, so a `GET` on one answers **200 with an empty body** and the client
     * fails to decrypt rather than seeing a 404.
     *
     * ⚠ Read-only and allocating: the result is materialised, so callers are the diagnostic paths,
     * never the `PUT` hot path.
     */
    fun blobIndex(ownerId: String): Map<String, BlobEntry>

    /**
     * `ref -> lastReferencedAtMs` for **just these** [refs] of [ownerId] — the bounded read
     * [blobIndex] is not (`content-plane-refcount.md` §6e N0).
     *
     * ⚠⚠ **Exists because [blobIndex] is a whole-table scan**, and the enumeration reads need ages
     * for one page's worth of refs, not for the owner's entire store. `ContentEnumerationSweep` hits
     * those routes on every settled tick, so answering "nothing to do" must not cost a full read of
     * `content_blob` while holding the single connection.
     *
     * ⚠ Refs the store does not hold are **absent from the result**, never defaulted to a timestamp.
     * "No such blob here" is a different fact from "touched long ago", and only absence lets a caller
     * tell the two apart. ⚠ It happens not to change the current answer — the age window's `all`-based
     * rule treats an extra ancient entry and a missing one alike — so this is about keeping the map
     * honest for its next reader, not a live defect being guarded against.
     *
     * ⚠ Bytes are not returned. Callers that need them want [blobIndex]; this one is on the
     * enumeration path and returns the single column it is asked about.
     */
    fun lastReferencedAt(ownerId: String, refs: Collection<String>): Map<String, Long>

    /**
     * One blob's index row: what it records, not what the store holds.
     *
     * ⚠⚠ [lastReferencedAtMs] is refreshed by **`get` as well as `put`** (see [ContentBlobStore]'s
     * liveness note), so it is a *touch* time, not an upload time. A blob someone just downloaded
     * looks exactly as young as one just uploaded — which is why the inventory **reports** recently
     * touched blobs rather than excluding them, and why this can never become a freeing predicate.
     */
    data class BlobEntry(val byteLen: Long, val lastReferencedAtMs: Long)
}
