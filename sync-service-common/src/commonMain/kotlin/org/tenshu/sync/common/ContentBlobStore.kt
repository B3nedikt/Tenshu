package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult

/**
 * The **content plane** client port of the two-plane file-content
 * design (`docs/adr/0017-file-content-two-plane-channel.md`, Phase P1).
 *
 * Chunk bodies are immutable, independently-AEAD-encrypted,
 * ciphertext-opaque blobs addressed by an **opaque ref**. They are
 * `PUT` to a content-addressed store on the relay and are **never**
 * added to the ordered `pendingBlocks` drain — that is the whole point
 * of the split: a fresh device can fetch one file's chunks on demand
 * without draining every chunk of every file, and the emit/queue/push
 * peak memory is bounded to one chunk (`O(CHUNK)`), independent of file
 * size and backlog depth.
 *
 * House Rule #1 unchanged: the caller AEAD-encrypts each chunk on
 * Control *before* calling [putChunk]; this port only ever sees opaque
 * ciphertext. The relay stores and serves it without decrypting.
 *
 * [putChunk] is the **write path** (P1). [getChunk] is the symmetric
 * **read path** (P2 — `docs/adr/0017-file-content-two-plane-channel.md`): the OnDemand
 * resolver fetches *only the opened file's* chunks by ref, one at a
 * time, decrypts + integrity-gates them client-side, and reassembles
 * the file. The relay-side content store shipped; the "ref-counted GC" once paired with it
 * is House-Rule-#1-impossible and was superseded by owner-/ref-scoped reclamation
 * (`retention-quota-model.md`). This is a separate side channel from
 * [SyncRelay] by design (`docs/agent.md` §4 "Side Channels") so a
 * multi-MB chunk body never travels the block-shaped `pushBlock`
 * `BinarySerializer` path that caused the OOM.
 */
interface ContentBlobStore {

    /**
     * Stream one already-encrypted chunk [ciphertext] to the content
     * store under [ref]. Implementations MUST stream the body (e.g. a
     * Ktor `OutgoingContent.WriteChannelContent`) rather than buffering
     * a second whole-block copy — re-buffering here would re-introduce
     * the very heap amplification this design removes.
     *
     * Content blobs are immutable and content-addressed: a `PUT` of an
     * already-present [ref] is idempotent (same ciphertext ⇒ same ref),
     * so a retried emit is safe.
     */
    suspend fun putChunk(ref: String, ciphertext: ByteArray): TResult<Data?>

    /**
     * Fetch the opaque ciphertext blob stored under [ref] from the
     * content plane (P2 read path — `docs/adr/0017-file-content-two-plane-channel.md`).
     *
     * - **By opaque ref only.** [ref] is the `blob-<sha256(ciphertext)>`
     *   key from a [org.tenshu.sync.FileChunkRef]; the caller never
     *   sends a plaintext-derived value (House Rule #1 — the store
     *   serves opaque ciphertext, all decrypt + integrity is
     *   client-side).
     * - **One chunk at a time.** Implementations MUST stream the
     *   response body and return exactly one chunk's ciphertext — they
     *   MUST NOT buffer or prefetch beyond a single chunk, and a fetch
     *   for one file's ref MUST NOT pull any other file's blobs.
     *   Bounded read peak is `O(CHUNK)`, mirroring the emit side.
     * - **Returns** [TResult.Success] wrapping the ciphertext as a
     *   [TByteArray] (the `Data`-typed byte carrier — [TResult]'s type
     *   parameter is bound to `Data?`; callers `.get()` the raw bytes)
     *   when the blob is present; [TResult.Failure] for a missing blob
     *   (relay dropped / never stored it) or a transport error. A
     *   missing chunk is a clean failure the reader gates on — the file
     *   does not surface, and a later resolve retries (immutable +
     *   content-addressed ⇒ idempotent).
     */
    suspend fun getChunk(ref: String): TResult<TByteArray>

    /**
     * Whether the content plane holds a blob under [ref]: `true` present, `false` the relay answered
     * that it is absent, `null` unknown (a transport error, an auth refusal, or an implementation that
     * cannot tell). Meant for small blobs such as a manifest.
     *
     * ⚠⚠ `null` is neither answer. A caller deciding whether to skip an upload must treat it as "not
     * known present", or an unreachable relay reads as a healthy one. [getChunk] cannot stand in for
     * this: it reports a missing blob and a failed request as the same `Failure`.
     */
    suspend fun hasChunk(ref: String): Boolean? = null

    /**
     * `POST /sync/content/declare`, the **describe** form — the only declare left after CR-2 B3
     * (`content-plane-cr2-b3-wire-spec.md` §3.4): each [ContentDescription] says which blob refs one
     * content object is made of, so the relay can free them once nothing references it (§10.12 Q3).
     *
     * ⚠⚠ **No edges.** Which holder references which content reaches the relay only with the holder's
     * own push (`SyncRelay.pushBlock`'s edges) and leaves only with its tombstone, cascade or reap. The
     * edge form of this route and `/sync/content/undeclare` are deleted in B3, so a client that sent
     * either would meet a `404`.
     *
     * ⚠ A describe carries no edge, so it never starts G2's clock, and it may arrive before or after
     * the row's push. Sent by the uploader once its chunks and manifest have landed.
     *
     * @return true when the relay accepted every batch. False is "not described", never "probably
     *   fine"; it is not fatal to the emit, and the backfill re-describes.
     */
    suspend fun describeContent(contents: List<ContentDescription>): Boolean = false

    /**
     * [describeContent] with B4's typed refusal (`content-plane-cr2-b4-spec.md` §2.1, §2.2 step 4): a
     * describe naming FREED content is refused (`409 content_freed`), and that is final — a describe is
     * never the way out of FREED, only a revive on the holder's push is.
     *
     * The default (a store that predates B4) cannot tell a refusal from a failure.
     */
    suspend fun describe(contents: List<ContentDescription>): DescribeOutcome =
        if (describeContent(contents)) DescribeOutcome.Described else DescribeOutcome.Failed

    /**
     * `POST /sync/content/reap` — this device's reaps (CR-2 B3, wire spec §3.3): each holder in
     * [removals] was removed with a deleted folder at that folder tombstone's HLC, so the relay
     * removes its edges at or below that point.
     *
     * ⚠ Idempotent: every device that reaps sends the same `(holder, T)`. At most
     * [MAX_REAP_BATCH] entries per request; an implementation chunks a longer list.
     *
     * @return true only when the relay accepted every batch (a 2xx). The caller keeps its durable
     *   outbox entries otherwise, and retries them.
     */
    suspend fun reapRemovals(removals: List<ReapRemoval>): Boolean = false

    companion object {
        const val ID = "tenshu/sync/contentBlobStore"

        /**
         * Batch cap for [describeContent], matching the relay's own `MAX_DECLARE_EDGES` (which
         * bounds the describe form too). A larger batch is a 400, so callers chunk to this.
         */
        const val MAX_DESCRIBE_BATCH = 512

        /** Batch cap for [reapRemovals] (wire spec §3.3). */
        const val MAX_REAP_BATCH = 1_000
    }
}

/**
 * One **holder-less description**: this content object is made of these [refs]
 * (`content-plane-cr2-quorumless.md` §10.12 Q3).
 *
 * ⚠⚠ No holder and no propertyId, which is the point: the relay learns nothing about which entity or
 * slot holds the content. The edges that say a content is referenced travel with each holder's push.
 *
 * ⚠ CR-2 B6 retired the §6e N1 reference handle (`referenceId`) this used to carry, and the `hlc` that
 * stamped it: the relay holds no handles any more (`content_reference` dropped), so a describe is the
 * content and its refs alone.
 */
data class ContentDescription(
    val contentId: String,
    val refs: List<String>,
)

/**
 * One reap removal (CR-2 B3, wire spec §3.3): [holder] — a wire name — was removed with a deleted
 * folder, and the relay removes its edges at or below [hlc], the **folder tombstone's** HLC as an
 * `HlcStamp` string (§10.12 Q4, revised). The same `(holder, T)` on every device that reaps.
 */
data class ReapRemoval(
    val holder: String,
    val hlc: String,
)
