package org.tenshu.sync.relay.mailbox

import org.tenshu.sync.relay.RelayConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Relay-side state for **Mode-A link sharing**
 * (`docs/file-sharing.md`). In-memory shape mirroring
 * [ContentBlobStore]: a `shareId -> ShareRecord` map for descriptors plus a
 * `(shareId, chunkIndex) -> ByteArray` map for ciphertext chunks; a
 * retention-window GC sweep drops revoked/orphan records along with their
 * chunks.
 *
 * Same zero-trust posture as [ContentBlobStore] (House Rule #1):
 * ciphertext only, no decryption, no inspection. `K_share` is NOT held
 * here — it lives only in the URL fragment (A11, threat-model §5.1).
 *
 * **GC.** Records flip to "collectible" by a wall-clock window
 * ([revokedRetentionWindowMs]) measured from `revokedAtMs` (revoked shares) or
 * `createdAtMs` (orphans without chunks). The default mirrors
 * `RelayConfig.DEFAULT_RETENTION_WINDOW_MS` so the relay's opaque stores share
 * one retention floor. Migration to durable storage is the
 * `sync-backend-relay-plan.md` Phase 4 track.
 */
class ShareStore(
    private val revokedRetentionWindowMs: Long = RelayConfig.DEFAULT_RETENTION_WINDOW_MS,
) : ShareRegistry {

    /**
     * The on-disk descriptor served by `GET /share/{shareId}`. The relay holds
     * only what it enforces or routes by — owner, chunk count (+ the unused
     * `chunkHashes` slot), expiry, open cap, open count, revocation. The file's
     * name, MIME type and exact size live only inside [sealedMeta], AEAD-sealed
     * under `K_share` by the sharer and opaque here (`relay-metadata-minimisation.md`
     * F3; House Rule #1). `K_share` is conspicuously absent.
     *
     * ⚠ What the relay can still infer about size: `chunkCount` × the 2 MiB
     * share chunk stride bounds it, and the last chunk's ciphertext length gives
     * the size mod that stride — so in practice the exact size (F10, accepted).
     * Sealing removes it from the *record*, not from the chunk store.
     *
     * Immutable (the `InviteStore.InviteRecord` shape): state changes build a
     * `copy()` and replace the map entry inside `compute`, so every mutation
     * is republished through the `ConcurrentHashMap` and visibility comes
     * from the map itself — no `@Volatile` discipline on individual fields,
     * and both backends (this store and `SqliteShareStore`, which always
     * materialised fresh records) now share one mutation idiom.
     */
    data class ShareRecord(
        val shareId: String,
        val ownerId: String,
        /** Opaque AEAD ciphertext (name, MIME, size under `K_share`). */
        val sealedMeta: ByteArray,
        val chunkCount: Int,
        val chunkHashes: List<ByteArray>,
        val expiresAtMillis: Long?, // null = no expiry
        val maxOpens: Int?,         // null = no cap
        val createdAtMs: Long,
        val opensSoFar: Int = 0,
        val revokedAtMs: Long? = null,
    ) {
        // The array fields would make the generated equals/hashCode compare by identity.
        override fun equals(other: Any?): Boolean =
            other is ShareRecord &&
                shareId == other.shareId &&
                ownerId == other.ownerId &&
                sealedMeta.contentEquals(other.sealedMeta) &&
                chunkCount == other.chunkCount &&
                chunkHashes.size == other.chunkHashes.size &&
                chunkHashes.indices.all { chunkHashes[it].contentEquals(other.chunkHashes[it]) } &&
                expiresAtMillis == other.expiresAtMillis &&
                maxOpens == other.maxOpens &&
                createdAtMs == other.createdAtMs &&
                opensSoFar == other.opensSoFar &&
                revokedAtMs == other.revokedAtMs

        override fun hashCode(): Int {
            var h = shareId.hashCode()
            h = 31 * h + ownerId.hashCode()
            h = 31 * h + sealedMeta.contentHashCode()
            h = 31 * h + chunkCount
            h = 31 * h + chunkHashes.fold(1) { acc, b -> 31 * acc + b.contentHashCode() }
            h = 31 * h + (expiresAtMillis?.hashCode() ?: 0)
            h = 31 * h + (maxOpens ?: 0)
            h = 31 * h + createdAtMs.hashCode()
            h = 31 * h + opensSoFar
            h = 31 * h + (revokedAtMs?.hashCode() ?: 0)
            return h
        }
    }

    // shareId -> ShareRecord. shareId is a single global namespace (the relay
    // mints it on `create`); ownership is enforced by the recorded ownerId.
    private val records = ConcurrentHashMap<String, ShareRecord>()

    // (shareId, chunkIndex) -> ciphertext. Kept under a single map keyed by
    // shareId so revocation / GC can drop chunks in one sweep.
    private val chunks = ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>()

    /**
     * Register a fresh ShareRecord. Idempotent on same-`ownerId` re-PUT of an
     * identical `shareId` (lets the proxy safely retry mid-create — the descriptor
     * is the same on retry). Returns null if `shareId` is already taken by a
     * *different* owner — the relay refuses (global shareId namespace).
     */
    override fun create(record: ShareRecord): ShareRecord? {
        // ⚠ A taken-down id is retired for good (see `SqliteShareStore.create`).
        if (synchronized(takedownLog) { takedownLog.any { it.shareId == record.shareId } }) return null
        val prior = records.putIfAbsent(record.shareId, record)
        if (prior == null) return record
        // Same owner re-PUT: idempotent (a retried create). Different owner:
        // refuse to overwrite — that would silently hijack an existing share.
        if (prior.ownerId != record.ownerId) return null
        return prior
    }

    /**
     * Read a record for a recipient `GET /share/{shareId}`, atomically bumping
     * `opensSoFar`. Returns null when absent, revoked, expired, or
     * cap-exhausted — the route maps null to 410 Gone.
     */
    override fun open(
        shareId: String,
        nowMs: Long,
    ): ShareRecord? {
        // Atomic increment via compute — multiple concurrent opens at the
        // ceiling can otherwise race past the cap. The bump replaces the
        // entry with a copy under the per-key compute lock; a cap-exhausted
        // record stays untouched and we return null below.
        var opened: ShareRecord? = null
        records.compute(shareId) { _, current ->
            current ?: return@compute null
            if (current.revokedAtMs != null) return@compute current
            current.expiresAtMillis?.let { expiresAt ->
                if (nowMs >= expiresAt) return@compute current
            }
            val capExhausted = current.maxOpens?.let { cap -> current.opensSoFar >= cap } == true
            if (capExhausted) return@compute current
            current.copy(opensSoFar = current.opensSoFar + 1).also { opened = it }
        }
        // `opened` is set only when the record was actually bumped. Otherwise
        // the entry was absent or left unchanged because of revoked / expired
        // / cap-exhausted — all of which map to 410 Gone (null) for the recipient.
        return opened
    }

    /**
     * Read without bumping (sharer-side admin reads, ownership checks in
     * `DELETE`/`PUT` chunk routes).
     */
    override fun peek(shareId: String): ShareRecord? = records[shareId]

    /**
     * Soft-revoke. Real chunk removal happens in [gcSweep] after the retention
     * window. Returns false if the share isn't owned by [ownerId] (the route
     * maps that to 404 to avoid leaking existence — see ShareRouting).
     */
    override fun revoke(
        shareId: String,
        ownerId: String,
        nowMs: Long,
    ): Boolean {
        // Inside compute so the revoke serialises with `open`'s check-then-
        // bump on the same key (the previous in-place field write didn't
        // hold the per-key lock and could interleave mid-open).
        var revoked = false
        records.compute(shareId) { _, current ->
            current ?: return@compute null
            if (current.ownerId != ownerId) return@compute current
            revoked = true
            current.copy(revokedAtMs = nowMs)
        }
        return revoked
    }

    /** Store one already-AEAD-encrypted chunk under `(shareId, chunkIndex)`. */
    override fun putChunk(shareId: String, chunkIndex: Int, ciphertext: ByteArray) {
        // computeIfAbsent for the outer map (same exactly-once-init posture
        // as ContentBlobStore), put for the inner — chunks are immutable.
        val perShare = chunks.computeIfAbsent(shareId) { ConcurrentHashMap() }
        perShare.putIfAbsent(chunkIndex, ciphertext)
        // ⚠ A revoke/takedown that landed while this PUT was in flight: drop the bytes again (see
        // `SqliteShareStore.putChunk`). `takeDown` revokes before it removes the chunk map.
        if (records[shareId]?.revokedAtMs != null) chunks[shareId]?.remove(chunkIndex)
    }

    /** Fetch one chunk; null if absent or the share is revoked/expired. */
    override fun getChunk(
        shareId: String,
        chunkIndex: Int,
        nowMs: Long,
    ): ByteArray? {
        val record = records[shareId] ?: return null
        if (record.revokedAtMs != null) return null
        record.expiresAtMillis?.let { expiresAt ->
            if (nowMs >= expiresAt) return null
        }
        return chunks[shareId]?.get(chunkIndex)
    }

    /**
     * Drop revoked / orphan records older than [revokedRetentionWindowMs]
     * along with their chunks. Same `Long.MAX_VALUE = never GC` sentinel as
     * [ContentBlobStore.gcSweep]. Returns the count dropped.
     */
    override fun gcSweep(nowMs: Long): Int {
        if (revokedRetentionWindowMs == Long.MAX_VALUE) return 0
        var dropped = 0
        val collectibleIds = records.entries
            .filter { (_, record) -> record.isCollectible(nowMs) }
            .map { it.key }
        for (id in collectibleIds) {
            records.compute(id) { _, record ->
                if (record != null && record.isCollectible(nowMs)) {
                    chunks.remove(id)
                    dropped++
                    null // evict
                } else {
                    record // a concurrent create / chunk PUT refreshed it
                }
            }
        }
        return dropped
    }

    /** Test/diagnostics. */
    override fun recordCount(): Int = records.size

    override fun declaredChunkTotal(): Long = records.values.sumOf { it.chunkCount.toLong() }

    /**
     * Account closure, phase 1 (F12): revoke every share of [ownerId] so nothing opens or serves; the
     * records stay as [eraseOwnerBytes]'s ledger, matching `SqliteShareStore`.
     */
    override fun eraseOwner(ownerId: String): Int {
        requireErasableOwner(ownerId)
        val now = System.currentTimeMillis()
        for (id in records.filterValues { it.ownerId == ownerId }.keys) {
            records.computeIfPresent(id) { _, r -> if (r.ownerId == ownerId) r.copy(revokedAtMs = r.revokedAtMs ?: now) else r }
        }
        return 0
    }

    /**
     * Account closure, phase 2 (F12): drop the records and their chunks. `bytesFreed` = Σ stored
     * chunk ciphertext lengths (F3 left the record no declared size), matching `SqliteShareStore`.
     */
    override fun eraseOwnerBytes(ownerId: String): ContentBlobRegistry.ReclaimResult {
        requireErasableOwner(ownerId)
        var chunkCount = 0
        var bytes = 0L
        for ((id, record) in records.filterValues { it.ownerId == ownerId }) {
            if (!records.remove(id, record)) continue
            bytes += chunks.remove(id)?.values?.sumOf { it.size.toLong() } ?: 0L
            chunkCount += record.chunkCount
        }
        return ContentBlobRegistry.ReclaimResult(blobsDropped = chunkCount, bytesFreed = bytes)
    }

    // The in-memory twin of the append-only `share_takedown` table. Guarded by its own monitor, which
    // also serialises takedowns so "already taken down?" and the append cannot interleave.
    private val takedownLog = mutableListOf<TakedownRecord>()

    override fun takeDown(
        shareId: String,
        reason: TakedownReason,
        operatorNote: String?,
        nowMs: Long,
    ): TakedownResult {
        val (row, existed) = synchronized(takedownLog) {
            val prior = takedownLog.firstOrNull { it.shareId == shareId && it.outcome == TakedownOutcome.TAKEN_DOWN }
            var owner: String? = null
            // Revoke under the per-key compute lock, like `revoke`, so it serialises with `open`.
            records.computeIfPresent(shareId) { _, r ->
                owner = r.ownerId
                r.copy(revokedAtMs = r.revokedAtMs ?: nowMs)
            }
            val outcome = when {
                prior != null -> TakedownOutcome.ALREADY_TAKEN_DOWN
                owner == null -> TakedownOutcome.NOT_FOUND
                else -> TakedownOutcome.TAKEN_DOWN
            }
            val record = TakedownRecord(
                id = takedownLog.size + 1L,
                shareId = shareId,
                ownerId = owner ?: prior?.ownerId,
                takenDownAtMs = nowMs,
                reason = reason,
                operatorNote = operatorNote,
                outcome = outcome,
            )
            takedownLog += record
            record to (owner != null)
        }
        val bytes = if (existed) chunks.remove(shareId)?.values?.sumOf { it.size.toLong() } ?: 0L else 0L
        return TakedownResult(row, bytesDeleted = bytes, bytesDeleteCompleted = true)
    }

    override fun takedowns(shareId: String): List<TakedownRecord> =
        synchronized(takedownLog) { takedownLog.filter { it.shareId == shareId } }

    /** Test/maintenance helper. */
    fun clear() {
        records.clear()
        chunks.clear()
    }

    private fun ShareRecord.isCollectible(nowMs: Long): Boolean {
        // A revoked record ages out one retention window past `revokedAtMs`.
        revokedAtMs?.let { revokedAt ->
            return nowMs - revokedAt > revokedRetentionWindowMs
        }
        // An *expired* record (wall-clock past `expiresAtMillis`) ages out one
        // retention window past expiry — so a "burn after 1 open" / dated link
        // doesn't sit in memory forever once it's been spent.
        expiresAtMillis?.let { expiresAt ->
            return nowMs - expiresAt > revokedRetentionWindowMs
        }
        // Orphan (no chunks ever uploaded): age out one window past create.
        if ((chunks[shareId]?.isEmpty() ?: true) &&
            nowMs - createdAtMs > revokedRetentionWindowMs
        ) {
            return true
        }
        return false
    }
}
