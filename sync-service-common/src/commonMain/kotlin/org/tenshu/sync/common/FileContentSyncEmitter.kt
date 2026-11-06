package org.tenshu.sync.common

import org.tenshu.core.entity.DataId

/**
 * Two-plane file-content emit hook (`docs/adr/0017-file-content-two-plane-channel.md`).
 * File bytes never travel as one whole-file `SyncBlock` payload
 * (`SyncBlockEmitter.emitPropertyBlock(value = wholeFileBytes)`), which would hold the
 * entire file in memory and OOM-kill Control.
 *
 * Instead of materialising the whole file as one block payload, the
 * implementation ([org.tenshu.sync.proxy.SyncObserver]):
 *
 *  1. reads the persisted bytes back from `FileDatabase` **one
 *     `CHUNK`-sized slice at a time** (the bytes are already on disk via
 *     `UploadFileImplementation.writeByteArray` — no second whole-file
 *     copy is kept),
 *  2. AEAD-encrypts each chunk *independently* with
 *     `(fileId, chunkIndex, chunkCount)` bound into the AAD,
 *  3. `PUT`s each ciphertext to the [ContentBlobStore] under a
 *     ref derived from a hash of the **ciphertext**,
 *  4. emits **one small manifest** [org.tenshu.sync.SyncBlock] through
 *     the existing ordered `pendingBlocks` drain.
 *
 * Peak memory is `O(CHUNK)` — independent of file size and backlog.
 *
 * Suspend because steps 2–3 do network IO; `UploadFileImplementation`
 * is already an `AsyncOperationImplementation` so it can `await` this
 * on its evaluation scope. Consumers pull it via
 * [SyncBlockEmitterHolder] for the same Koin-ordering reason
 * [SyncBlockEmitter] is pulled that way.
 */
interface FileContentSyncEmitter {

    /**
     * Chunk, encrypt, PUT, and emit the manifest for the file currently
     * stored at [filePath] in `FileDatabase`, scoped to entity
     * [targetEntityId] / property [propertyId]. A null/absent file or a
     * disabled sync manager is a silent no-op (test harnesses without
     * sync don't propagate bytes).
     *
     * Returns `true` if a manifest was emitted, `false` if it no-opped
     * (sync disabled, no keys, or the file wasn't found) — lets the
     * caller and tests assert behaviour without inspecting internals.
     */
    suspend fun emitFileContent(
        targetEntityId: DataId,
        propertyId: String,
        filePath: String
    ): Boolean

    /**
     * Record the durable intent to upload the content at [filePath] — the journal entry
     * [emitFileContent] writes first — **without** uploading anything.
     *
     * ⚠⚠ For the producer's non-cancellable span: once a row naming new content is saved, the
     * journal entry is the only thing that will ever upload it, so the two must land together even
     * when the op is cancelled between them (`drive-synced-folders-phase2.md` §4.1b). The upload
     * itself stays in [emitFileContent], cancellable, after the span.
     *
     * Idempotent with the entry [emitFileContent] writes (last-wins per content). Default = nothing:
     * an emitter with no journal has no intent to record.
     */
    suspend fun journalFileContent(
        targetEntityId: DataId,
        propertyId: String,
        filePath: String,
    ) {}

    /**
     * [journalFileContent] for content whose id the producer **committed**, not the one the row names
     * when this runs.
     *
     * ⚠⚠ The unbound overload re-reads the content id from the stored row. A producer that does not
     * hold the row against ingest — a peer's row naming other content can land between its commit and
     * this call — would journal its bytes under the peer's id: the manifest then names bytes whose
     * digest is not that content's, and its own content never gets an intent. Binding the id it saved
     * closes that. Default: the unbound path, for an emitter that keys nothing by content.
     */
    suspend fun journalFileContent(
        targetEntityId: DataId,
        propertyId: String,
        filePath: String,
        contentId: String,
        contentHash: String?,
    ) = journalFileContent(targetEntityId, propertyId, filePath)

    /**
     * [emitFileContent] bound to the [contentId] the producer committed — see the bound
     * [journalFileContent]. [contentHash] is the whole-file hash of the bytes at [filePath]
     * (`wholeFileHashOf`): with it a journalled entry is published by its bytes even after its row
     * moves on, which a producer whose rows move and come back needs.
     */
    suspend fun emitFileContent(
        targetEntityId: DataId,
        propertyId: String,
        filePath: String,
        contentId: String,
        contentHash: String?,
    ): Boolean = emitFileContent(targetEntityId, propertyId, filePath)

    /**
     * Run [block] as the sole emitter of this entity's [propertyId] content — the producer's
     * mutations and the emit that publishes them, under one claim.
     *
     * ⚠⚠ Ownership has to start **before** the bytes and the row are written, not at the emit.
     * `UploadFileImplementation` writes the new bytes, saves the new `ContentId` row, and only
     * then emits; `emitFileContentNow` re-resolves the current path and row, so a deferred drain
     * landing in that window emits the **new** version under an older journal entry and clears it.
     * The producer then emits the same version again under a fresh CEK and nonce — a second set of
     * chunks at different `sha256(ciphertext)` refs, with the manifest (whose ref is derived from
     * the contentId, so it is the same address) naming only one set. The other set is unreachable:
     * reclamation resolves a content's chunks through its manifest.
     *
     * ⚠ [emitFileContent] called **inside** [block] for the same entity and property does not
     * re-acquire and does not deadlock — the claim is already this caller's.
     *
     * The default simply runs [block]: an emitter with no journal has no drain to race, which is
     * every test double and the JS runtime.
     */
    suspend fun <T> withContentEmitOwnership(
        targetEntityId: DataId,
        propertyId: String,
        block: suspend () -> T,
    ): T = block()

    /**
     * An unchanged overwrite keeps [contentId] instead of minting new content, which is only safe if
     * the relay still has it. Answers `true` when [contentId] is known resolvable (its manifest and
     * every chunk that manifest names), so nothing is sent. Otherwise (absent, unknown, or no store
     * to ask) the file at [filePath] is handed to [emitFileContent], which re-emits under the
     * entity's existing `contentId` exactly as `ContentRepair` does, and this answers `false`.
     *
     * ⚠ The default always hands off: an emitter that cannot ask the relay must never skip.
     */
    suspend fun ensureContentOnRelay(
        targetEntityId: DataId,
        propertyId: String,
        filePath: String,
        contentId: String,
    ): Boolean {
        emitFileContent(targetEntityId, propertyId, filePath)
        return false
    }

    /**
     * Whether the relay can still serve [contentId] — the tri-state [ensureContentOnRelay] collapses.
     *
     * ⚠⚠ **[ContentResolvability.UNKNOWN] is not "absent", and the difference is load-bearing.** A
     * caller acting on absence deletes or re-mints something; doing that because the relay was
     * unreachable, the realm locked or the manifest undecryptable would destroy on a network blip.
     * The keeper's eviction sentinel is the first such caller
     * (`drive-xxlarge-thumbnail-rung.md` §4.7): it is only cleared on a definite **ABSENT**.
     *
     * ⚠ The default knows nothing, so it answers `UNKNOWN` and every caller holds its position.
     */
    suspend fun contentResolvability(targetEntityId: DataId, contentId: String): ContentResolvability =
        ContentResolvability.UNKNOWN

    /**
     * Whether content can go out **right now**: the metered gate is open and the relay is not
     * refusing on quota.
     *
     * ⚠⚠ For a producer deciding whether to *make* bytes at all, not for one deciding whether to send
     * them — [emitFileContent] already journals what it cannot send. The thumbnail backfill asks
     * because rendering a library's worth of blobs into a closed gate fills the disk with work that
     * can only wait or be evicted (`drive-xxlarge-thumbnail-rung.md` §4.7).
     *
     * ⚠ The default is `true`: an emitter that cannot tell must not stop a producer.
     */
    suspend fun canPublishContentNow(): Boolean = true

    /**
     * The bytes the journalled upload intent for [targetEntityId]'s [propertyId] currently adds to
     * the queued-content figure, or 0 when nothing is journalled for it. An overwrite supersedes that
     * intent (one entry per file, last wins), so the quota gate must not count it alongside the new
     * bytes. The default knows of no journal.
     */
    suspend fun queuedContentBytesFor(targetEntityId: DataId, propertyId: String): Long = 0L

    /**
     * Whether a journalled upload intent **exists** for [targetEntityId]'s [propertyId] — i.e. these
     * are bytes this device still owes the relay.
     *
     * ⚠⚠ **Not `queuedContentBytesFor(...) > 0`, and the difference is load-bearing.** That one is a
     * *quota* figure: it answers the bytes an overwrite would supersede, so it collapses "nothing is
     * journalled" and "an intent is journalled but weighs nothing" — an empty file, or one whose
     * `filePath` no longer resolves — into the same `0`. For the quota gate those two are genuinely
     * the same. For a **byte holder deciding whether deleting a file loses its only copy** they are
     * opposites, and reading the second as the first deletes the last copy of content the relay has
     * never seen. The *entry*, not its size, is what says the bytes have not been sent.
     *
     * ⚠ The default knows of no journal and answers `false`. That is a fail-open, and it is only
     * sound where a wiring with no emitter also has no relay to owe bytes to — a caller that could
     * be wired against a live journal through a null seam must treat null as "cannot tell" itself.
     */
    suspend fun hasQueuedContentFor(targetEntityId: DataId, propertyId: String): Boolean = false

    /**
     * Whether a journalled upload intent reads its bytes from [filePath] — asked by path, for a
     * caller that has a path and no row.
     *
     * ⚠⚠ The case it exists for: a **peer** moves a file whose upload is parked here. Ingest never
     * moves bytes, so they stay at the old path, which no row names — the exact shape
     * `OrphanedBytesSweep` collects — while they are the only copy of content the relay has never
     * seen. [hasQueuedContentFor] cannot answer it: it asks through a row, and there is none.
     *
     * ⚠ The same fail-open default as [hasQueuedContentFor], sound for the same reason.
     */
    suspend fun hasQueuedContentAt(filePath: String): Boolean = false

    /**
     * Retire the parked upload intents an overwrite of [targetEntityId] superseded, as
     * `(contentId, propertyId)` pairs — `File.Data`'s and each derived thumbnail rung's.
     *
     * ⚠ Called by the operation that replaced the bytes, once its own new intent is durable, because
     * it is the only party that still knows the old content ids: the row names the new ones by then.
     * The default knows of no journal, so there is nothing to retire.
     */
    suspend fun discardSupersededContent(targetEntityId: DataId, superseded: List<Pair<String, String>>) = Unit

    /**
     * Mode-B cross-realm content share **without plaintext** (`file-content-addressing.md`
     * §3.4.1). The personal content addressed by [contentId] already lives
     * on the relay as CEK-encrypted chunks + a manifest (put there by whichever device
     * uploaded it — `UploadFile` awaits its content emit). Instead of decrypting +
     * re-encrypting those bytes into the target realm, this:
     *
     *  1. fetches + decrypts the personal manifest (personal identity key),
     *  2. unwraps its per-content key (CEK) under the personal volume key(s),
     *  3. **copies the already-CEK-encrypted chunk blobs by ref** into the target
     *     realm's content store — no decrypt, no re-encrypt,
     *  4. re-wraps the CEK under the **target** realm's volume key, rewrites the
     *     manifest's `propertyId` to [targetPropertyId] (so the joiner's applier
     *     resolves the realm byte-path), re-encrypts the (tiny) manifest under the
     *     target identity key, and PUTs it at the same content-addressed ref in the
     *     target store.
     *
     * Because the chunk ciphertext is realm-independent (the CEK, not the volume key,
     * encrypts it), only the CEK is re-wrapped and only the small manifest is
     * re-encrypted; the large chunk bodies move as opaque ciphertext. Reads the source
     * from the relay-backed personal store, so it works even for an **online-only**
     * file whose plaintext this device never held — and no plaintext ever touches disk.
     *
     * Returns `true` iff the target manifest was written; `false` on any missing
     * source (no contentId manifest on the relay yet, unwrappable CEK, unknown target
     * realm, or a failed copy/PUT) — content is content-addressed + immutable so a
     * retry is idempotent.
     */
    suspend fun shareContentToRealm(
        contentId: String,
        targetRealmOwnerId: String,
        targetPropertyId: String,
    ): Boolean
}

/**
 * What the content plane can say about a piece of content: it is there, it is definitely not, or we
 * could not find out (`FileContentSyncEmitter.contentResolvability`).
 *
 * ⚠⚠ The third case exists because `ContentBlobStore.getChunk` cannot tell a missing blob from a
 * failed request — only `hasChunk` can, and only when it is asked. Collapsing UNKNOWN into ABSENT is
 * how an offline device concludes the relay lost its data.
 */
enum class ContentResolvability {
    /** The manifest and every chunk it names are on the relay. */
    RESOLVABLE,

    /** The relay answered, and the manifest or one of its chunks is not there. */
    ABSENT,

    /** No answer: unreachable, unauthorised, a locked realm, no store, or an unreadable manifest. */
    UNKNOWN,
}
