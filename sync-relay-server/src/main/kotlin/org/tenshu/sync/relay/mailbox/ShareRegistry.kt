package org.tenshu.sync.relay.mailbox

/**
 * The relay-side state for **Mode-A link sharing** (`docs/file-sharing.md`
 * §3.1): `shareId → ShareRecord` descriptors + `(shareId, chunkIndex) → ciphertext` chunks,
 * with a revoked/expired/orphan retention GC.
 *
 * Storage SPI (`docs/proposals/sync-relay-persistence.md`, Phase 3b). Two backends:
 *   - [ShareStore] — in-memory; the default + test backend.
 *   - `org.tenshu.sync.relay.storage.SqliteShareStore` — durable; records in SQLite, chunk
 *     bytes in a `BlobStore`.
 *
 * Ciphertext only; `K_share` lives in the URL fragment, never here (A11, House Rule #1).
 * [ShareStore.ShareRecord] stays nested on the in-memory backend so call sites are untouched;
 * both backends use it. `recordCount` is a test/diagnostics hook.
 */
interface ShareRegistry : OwnerBytesStore {

    /** Register a fresh record; idempotent on same-owner re-PUT, null on a different-owner shareId clash. */
    fun create(record: ShareStore.ShareRecord): ShareStore.ShareRecord?

    /** Recipient read: bumps `opensSoFar`; null if absent/revoked/expired/cap-exhausted (→ 410). */
    fun open(shareId: String, nowMs: Long = System.currentTimeMillis()): ShareStore.ShareRecord?

    /** Read without bumping (sharer-side admin / ownership checks). */
    fun peek(shareId: String): ShareStore.ShareRecord?

    /** Soft-revoke (chunks removed later by [gcSweep]); false if not owned by [ownerId]. */
    fun revoke(shareId: String, ownerId: String, nowMs: Long = System.currentTimeMillis()): Boolean

    /** Store one already-AEAD-encrypted chunk; first-write-wins (immutable). */
    fun putChunk(shareId: String, chunkIndex: Int, ciphertext: ByteArray)

    /** Fetch one chunk; null if absent or the share is revoked/expired. */
    fun getChunk(shareId: String, chunkIndex: Int, nowMs: Long = System.currentTimeMillis()): ByteArray?

    /** Drop revoked/expired/orphan records (+ their chunks) past the retention window. Returns count. */
    fun gcSweep(nowMs: Long = System.currentTimeMillis()): Int

    /** Number of records currently held. */
    fun recordCount(): Int

    /**
     * Sum of `chunkCount` over every record held, revoked or not until the GC deletes it — the share
     * side of the storage ceiling. Chunk sizes are never indexed, so the ceiling counts each declared
     * chunk at the per-chunk cap: an upper bound, which is the safe side for a cost brake.
     */
    fun declaredChunkTotal(): Long

    /**
     * Operator takedown (notice-and-action, [TakedownReason]): revoke the share and write the audit
     * row in one step, then delete the share's chunk ciphertext. Idempotent: a repeat records
     * [TakedownOutcome.ALREADY_TAKEN_DOWN] and retries the byte delete. Every call writes one row.
     */
    fun takeDown(
        shareId: String,
        reason: TakedownReason,
        operatorNote: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): TakedownResult

    /** The audit rows for [shareId], oldest first. */
    fun takedowns(shareId: String): List<TakedownRecord>
}
