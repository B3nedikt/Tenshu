package org.tenshu.sync.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.externalPropertyWriter
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.FileContentManifestPayload
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncMetadata

/**
 * Joiner-side counterpart to [SyncObserver.emitFileContent] — the
 * **read path** of the two-plane file-content design
 * (`docs/adr/0017-file-content-two-plane-channel.md`, Phase P2). It is
 * the exact mirror of the P1 emit:
 *
 *  1. The small [FileContentManifestPayload] already arrived on the
 *     ordered metadata drain (P1) — it lists, *inside the ciphertext*,
 *     this file's `(fileId, propertyId, chunkCount, totalSize,
 *     wholeFileHash, ordered chunk refs + per-chunk cipher hashes)`.
 *  2. [apply] fetches **only this file's** chunks by opaque ref from
 *     the content plane via [ContentBlobStore.getChunk], **one at a
 *     time** (selective fetch: opening file A never pulls file B's
 *     blobs; a multi-TB realm syncs only manifests and pulls bytes
 *     lazily per opened file).
 *  3. Each ciphertext is integrity-checked **before** it is trusted:
 *     its `sha256` must equal the manifest's `cipherHash` (catches a
 *     relay serving a wrong/old blob under a ref before spending an
 *     AEAD decrypt), then [SyncCryptoManager.decryptWithAad] re-binds
 *     the SAME AAD the emitter used —
 *     `tenshu/file-chunk/v1|fileId|chunkIndex|chunkCount` under the
 *     shared *volume* key (see [fileChunkAad]) — so a reordered /
 *     cross-file-spliced / truncated chunk fails authenticated
 *     decryption.
 *  4. The whole-file hash-chain anchor is folded with the SAME shared
 *     [IncrementalSha256] the emitter used and must match after every
 *     chunk is consumed.
 *
 * **Bounded memory (`O(CHUNK)`), integrity-gated, no partial write.**
 * Decrypted plaintext is streamed one chunk at a time into a *temp*
 * path via [FileDatabase.writeBytesRange] (never the whole file in
 * heap). The file only surfaces — the temp is atomically promoted onto
 * the real path via [FileDatabase.moveFile] — when **all** of:
 *  - every `chunkCount` chunk was fetched and present,
 *  - every chunk's `cipherHash` + AEAD + AAD verified,
 *  - the total bytes written equal `totalSize`,
 *  - the folded whole-file hash equals `wholeFileHash`,
 *
 * hold. On *any* failure (missing chunk, tampered ciphertext, AAD
 * mismatch, wrong whole-file hash) the temp is deleted and the real
 * path is left untouched: a partial/tampered set never surfaces as a
 * valid file. A transient absence (relay hasn't got the blob yet, or
 * the parent `File` entity hasn't synced yet — the "entity-before-
 * bytes" ordering note from [org.tenshu.file.proxy.FileBytesPropertyWriter])
 * is a clean no-op; the next OnDemand resolve retries (chunks are
 * immutable + content-addressed ⇒ idempotent). A *tamper* fails the
 * same way structurally — it just won't ever succeed on retry, which
 * is the correct outcome.
 *
 * Selective-fetch note: [apply] is only ever invoked from the
 * **suspend** OnDemand resolve path ([SyncProcessor.processSyncBlockAsync]
 * ← `OnDemandResolutionImpl.resolve` ← `Maybe.get()`/`.flow()` on the
 * `File.Relation.Data` OnDemand property). The eager, non-suspend
 * scheduler drain ([SyncProcessor.processSyncBlock]) deliberately does
 * NOT apply manifests — it would defeat the whole point of the split by
 * draining every chunk of every file. The manifest itself still rides
 * the eager drain (small + bounded); only the *bytes* are lazy.
 */
class FileContentManifestApplier(
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    private val fileDatabase: FileDatabase,
    private val contentBlobStore: ContentBlobStore,
    /**
     * Fixed realm keys to decrypt under, for a **shared-realm** applier
     * (`drive-shared-realm-file-model.md` §6 Phase 3b.2). A shared file's chunks
     * are AEAD'd under the *realm's* `sharedVolumeKey` and stored in the realm's
     * own [ContentBlobStore], so the per-realm `SyncProcessor` wires an applier
     * with that realm's keys + store — mirroring `SyncProcessor.realmKeys`. `null`
     * (the default) = the personal applier, which uses
     * `syncManager.getDerivedKeys()` exactly as before (byte-identical, no
     * behaviour change for the single-realm path).
     */
    private val realmKeys: SyncCryptoManager.DerivedKeys? = null,
    /**
     * GR-7 retained keyring (`shared-realm-membership-grant.md` §13.4): the retired
     * realm keys to fall back to when a chunk fails to decrypt under the current
     * [realmKeys] volume key — a file written before a member-removal rotation stays
     * encrypted under the old `Kr` (ADR-0017, no bulk re-encryption). Empty for the
     * personal applier (never rotates) and a freshly-registered shared realm; the
     * per-realm processor factory supplies `RealmContext.historicalDerivedKeys`.
     */
    private val realmHistoricalKeys: List<SyncCryptoManager.DerivedKeys> = emptyList(),
) {

    /**
     * Materialise the file described by [manifest]. [targetEntityHash]
     * is `block.targetEntityIdHash` (used to resolve the local `File`
     * entity → its on-disk path, exactly as `FileBytesPropertyWriter`
     * does); [tombstoneRegistry] is the same hash→DataId reverse index
     * `SyncProcessor.attachPropertyAndSave` uses.
     *
     * Returns `true` iff the file fully verified and was promoted onto
     * its real path (subsequent OnDemand resolves then read real bytes
     * and short-circuit on the bumped [SyncMetadata]); `false` for a
     * clean no-op / failed gate (no partial write performed).
     */
    suspend fun apply(
        executionContext: ExecutionContext,
        manifest: FileContentManifestPayload,
        targetEntityHash: String,
        tombstoneRegistry: TombstoneRegistry
    ): Boolean {
        // Shared-realm applier decrypts under its fixed realm keys; the personal
        // applier (realmKeys == null) uses the device's current derived keys.
        val keys = realmKeys ?: syncManager.getDerivedKeys() ?: return false

        // Resolve the entity → its external FileDatabase path through
        // the `:core-common` ExternalPropertyWriter port. This applier
        // lives in `:sync-service-proxy`, which deliberately cannot see
        // the `:file-service` `File` type (commonMain layering — same
        // reason `SyncProcessor.attachPropertyAndSave` routes via this
        // port). If the parent entity hasn't synced yet the path is
        // null — documented "entity-before-bytes" ordering tolerance:
        // clean no-op, the next resolve retries once it lands.
        val dataId = tombstoneRegistry.resolveDataId(targetEntityHash) ?: return false
        val propertyId = manifest.propertyId.get()
        val writer = executionContext.context.externalPropertyWriter() ?: return false
        if (!writer.supports(propertyId)) return false
        val path = writer.resolveExternalPath(
            executionContext = executionContext,
            targetEntityId = dataId,
            propertyId = propertyId
        ) ?: return false

        val chunkCount = manifest.chunkCount.get().toInt()
        val totalSize = manifest.totalSize.get()
        val expectedWholeFileHash = manifest.wholeFileHash.get()

        // The per-chunk fetch + cipherHash-gate + AEAD-decrypt is shared with the
        // streaming read path (D2b S1, drive-streaming-preview.md §3.5); this path
        // layers the whole-file hash/size gate on top.
        val decryptor = buildDecryptor(manifest, keys)
        if (!decryptor.isWellFormed) return false

        // Reassemble into a temp sibling path so a failed/tampered set
        // never even momentarily exists at the real path.
        val tempPath = "$path.tenshu-partial"
        fileDatabase.deleteFile(tempPath)

        val wholeFileHasher = IncrementalSha256()
        var offset = 0L
        var ok = true

        for (index in 0 until chunkCount) {
            // A null is a clean gate fail (missing blob, cipherHash mismatch, or no
            // candidate key authenticates it) — drop the partial set and retry later.
            val plain = decryptor.decryptChunk(index)
            if (plain == null) {
                ok = false
                break
            }

            wholeFileHasher.update(plain)
            fileDatabase.writeBytesRange(tempPath, offset, plain)
            offset += plain.size
        }

        if (!ok ||
            offset != totalSize ||
            wholeFileHasher.digest().toHexLower() != expectedWholeFileHash
        ) {
            // Integrity gate failed: drop the partial reassembly, leave
            // the real path untouched. The file does not surface; a
            // later resolve retries (idempotent — chunks immutable).
            fileDatabase.deleteFile(tempPath)
            return false
        }

        // Gate passed → atomically promote onto the real path. Only now
        // does the file become readable; OnDemand re-resolve reads real
        // bytes from FileDatabase.
        fileDatabase.moveFile(tempPath, path)

        // Bump the per-property sync metadata so subsequent resolves
        // can short-circuit on HLC — keyed "entityHash/propertyId",
        // mirroring SyncProcessor.attachPropertyAndSave.
        executionContext.saveData(
            SyncMetadata.create(
                context = executionContext.context,
                hlc = syncManager.hlcManager.tick(),
                targetDataIdHash = "$targetEntityHash/$propertyId",
                isConflicted = false
            ),
            origin = WriteOrigin.SYNC_INGEST
        )
        return true
    }

    /**
     * Open a [StreamingContentSession] for [manifest] (D2b S2,
     * `drive-streaming-preview.md` §3.5) — the pull read path's counterpart to [apply]'s
     * reassemble. Reuses this applier's exact key wiring (the realm/personal volume key + GR-7
     * historical keys) and content store via the same [FileChunkDecryptor], but reads ranges
     * lazily ([StreamingContentSession.readRange]) instead of reassembling the whole file to
     * disk + applying the whole-file gate (§4). Returns `null` if no realm keys are available
     * (locked / pre-realm) — the caller surfaces that as "not openable yet".
     */
    internal fun openStreamingSession(manifest: FileContentManifestPayload): StreamingContentSession? {
        val keys = realmKeys ?: syncManager.getDerivedKeys() ?: return null
        return StreamingContentSession(
            decryptor = buildDecryptor(manifest, keys),
            totalSize = manifest.totalSize.get()
        )
    }

    /**
     * Fetch the raw (still identity-key-encrypted) **manifest** bytes for [contentId]
     * from the content plane — at `manifestContentRef(contentId)` (a `blob-<sha256(...)>` ref),
     * the sibling blob of this file's chunks (content-addressing Phase 1 §8.3). Returns `null`
     * on a missing blob /
     * transport error — the reader treats it as "not synced yet" and a later resolve
     * retries (immutable + content-addressed ⇒ idempotent), exactly like a missing
     * chunk. The caller ([SyncProcessor.resolveContentManifest]) owns the identity-key
     * decrypt + parse; this only performs the opaque GET, keeping the store content-blind.
     */
    internal suspend fun fetchManifestCiphertext(contentId: String): ByteArray? =
        (contentBlobStore.getChunk(manifestContentRef(contentId)) as? org.tenshu.core.TResult.Success)
            ?.value?.get()

    /**
     * Build the shared [FileChunkDecryptor] for [manifest] under [keys] (+ any GR-7 retired
     * keys). Trial-decrypt candidates: the current volume key first, then each retired key. A
     * whole file is written under a single Kr, so chunk 0 settles on a working key and the rest
     * reuse it (memoised inside the decryptor); the empty-keyring case is a single candidate =
     * byte-identical to the pre-GR-7 path. Shared by [apply] (reassemble) and
     * [openStreamingSession] (stream).
     */
    private fun buildDecryptor(
        manifest: FileContentManifestPayload,
        keys: SyncCryptoManager.DerivedKeys
    ): FileChunkDecryptor {
        val candidateVolumeKeys = if (realmHistoricalKeys.isEmpty()) {
            listOf(keys.sharedVolumeKey)
        } else {
            listOf(keys.sharedVolumeKey) + realmHistoricalKeys.map { it.sharedVolumeKey }
        }
        // Content-addressing §3.4: chunks are encrypted under a per-content key (CEK) carried on
        // the manifest, wrapped under the realm volume key. Unwrap it here — trying the current
        // volume key then each retired one (GR-7 moves from per-chunk trial to this one-shot
        // unwrap) — and hand the chunk decryptor the single CEK.
        // ⚠ No CEK (absent, or no key this device holds opens it) ⇒ the content is undecryptable
        // here: the decryptor gets no key and every chunk read is a clean null ("not available"),
        // never a throw. Chunks are never decrypted under the volume key directly.
        val wrappedCek = manifest.wrappedCek.get()
        val cek = if (wrappedCek.isEmpty()) {
            null
        } else {
            unwrapCek(wrappedCek, manifest.fileId.get(), candidateVolumeKeys)
        }
        return FileChunkDecryptor(
            cryptoManager = cryptoManager,
            contentBlobStore = contentBlobStore,
            manifest = manifest,
            candidateChunkKeys = listOfNotNull(cek)
        )
    }

    /** Unwrap the CEK from [wrappedCek], trying each candidate volume key (GR-7). */
    private fun unwrapCek(
        wrappedCek: ByteArray,
        fileId: String,
        candidateVolumeKeys: List<ByteArray>,
    ): ByteArray? {
        val aad = cekWrapAad(fileId)
        for (volumeKey in candidateVolumeKeys) {
            try {
                return cryptoManager.decryptWithAad(wrappedCek, volumeKey, aad)
            } catch (_: Exception) {
                // wrong key ⇒ AEAD tag fails; try the next retired key.
            }
        }
        return null
    }
}
