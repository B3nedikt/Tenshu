package org.tenshu.file.impl.share

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CancellationException
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.InvalidCallerError
import org.tenshu.core.NotFoundError
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.common.proxy.onDemandResolution
import org.tenshu.core.common.util.mapChunksBounded
import org.tenshu.core.common.util.nextSecureRandomBytes
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.share.Share
import org.tenshu.core.file.share.ShareLink
import org.tenshu.core.file.share.ShareLinkResult
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.time.clock
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.storage.proxy.ensureCapacity
import org.tenshu.sync.common.ShareMeta
import org.tenshu.sync.common.ShareRelayClient
import org.tenshu.sync.common.sealShareMeta
import org.tenshu.sync.common.shareChunkAad

/**
 * Mode-A `ShareLink` op (`docs/file-sharing.md`).
 *
 * **End-to-end (P1b):**
 *  1. Generate a 32-byte `K_share` via the platform CSPRNG (A11) — never
 *     persisted on the wire, returned only in [ShareLinkResult.urlFragment]
 *     so the UI assembles `https://tenshu.app/s/{shareId}#k={K_share}` (the
 *     apex serves the viewer until Phase 8's `drive.tenshu.app`; host lives in
 *     `ShareLinkUrl.DEFAULT_HOST`).
 *  2. Generate a 16-byte opaque `shareId` (base64url).
 *  3. **Re-encrypt the file under `K_share`** (§3.3): materialise the file's
 *     bytes on this device (`File.Data` is `Syncable(OnDemand)` — see
 *     [materialiseBytes]), read them back from [FileDatabase] in bounded
 *     `CHUNK`-sized windows, AEAD-encrypt each
 *     chunk independently with AAD = `(shareId, chunkIndex, chunkCount)` (via
 *     [shareChunkAad]), and stream the ciphertext to the relay. Per-share
 *     independent chunks (rather than wrapping the file's volume-key chunks)
 *     are what make revocation strictly per-share — see §3.3 "Why re-encrypt".
 *  4. `POST /share` the descriptor (`sealedMeta`, `chunkCount`, caps) **before**
 *     the chunk PUTs — the relay's chunk route binds chunks to an existing
 *     owner-scoped record. The file's name, MIME type and exact size travel only
 *     inside `sealedMeta`, AEAD-sealed under `K_share` ([sealShareMeta]); the
 *     relay never holds them in the clear (`relay-metadata-minimisation.md` F3).
 *  5. Save the local [Share] entity (`Syncable(Eager)`) so the *My shares*
 *     list across realm devices reflects the new link immediately.
 *  6. Return [ShareLinkResult] carrying `shareId` + base64url(`K_share`).
 *
 * **Integrity model (v1).** The descriptor's per-chunk `chunkHashes` are left
 * empty: the integrity/authenticity boundary is the **per-chunk AEAD under
 * `K_share`** (proposal §5.5 — a tampering relay produces AEAD failures the
 * viewer surfaces as "this link is no longer available"). The AAD binds
 * `(shareId, chunkIndex, chunkCount)`, so reorder / cross-share splice fail
 * decryption and a missing trailing chunk is caught by `chunkCount` + the
 * relay's 410 on an absent chunk. A *sharer-authored* per-chunk hash anchor
 * would need a chunks-first relay (or a finalize endpoint) since the hash of a
 * randomly-nonced ciphertext can't be known before the chunk is produced; the
 * `chunkHashes` field stays in the schema for that future slice (§3.7 K3).
 *
 * **Degraded (local-only) mode.** When [shareRelayClient] or [cryptoManager] is
 * unset (test harnesses / pre-sync proxies), steps 3–4 are skipped: only the
 * local [Share] is saved and the minted URL resolves to a relay 410 until a
 * real client is wired. This mirrors how the content plane degrades without a
 * `ContentBlobStore`, and keeps the *My shares* projection + Eager multi-device
 * convergence exercisable with no relay running.
 */
internal class ShareLinkImplementation(
    private val fileDatabase: FileDatabase,
    private val deviceId: String? = null,
    private val shareRelayClient: ShareRelayClient? = null,
    private val cryptoManager: SyncCryptoManager? = null,
    private val storageCapacityHolder: StorageCapacityHolder? = null,
) : AsyncOperationImplementation {

    @OptIn(ExperimentalEncodingApi::class)
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val path: TString = params.read(ShareLink.Params.PATH)
        val expiresAtMillis: TInteger = params.read(ShareLink.Params.EXPIRES_AT_MILLIS)
        val maxOpens: TInteger = params.read(ShareLink.Params.MAX_OPENS)
        val filePath = path.get()

        // ID + key generation. K_share never leaves the device except via the
        // URL fragment in the return value; the relay never sees it (A11).
        val kShare = nextSecureRandomBytes(K_SHARE_SIZE)
        val shareIdBytes = nextSecureRandomBytes(SHARE_ID_SIZE)
        val shareId = Base64.UrlSafe.encode(shareIdBytes).trimEnd('=')
        val urlFragment = Base64.UrlSafe.encode(kShare).trimEnd('=')

        // Re-encrypt + upload to the relay (skipped in local-only mode).
        val relay = shareRelayClient
        val crypto = cryptoManager
        if (relay != null && crypto != null) {
            uploadShare(
                executionContext = executionContext,
                relay = relay,
                crypto = crypto,
                shareId = shareId,
                kShare = kShare,
                filePath = filePath,
                expiresAtMillis = expiresAtMillis.get(),
                maxOpens = maxOpens.get(),
            )
        }

        val createdAtMillis = executionContext.context.clock().nowEpochMillis()

        // Persist the local Share entity. `ShareRelationMaintainer` watches for
        // these saves and adds them to `FileService.SHARES`. `Syncable(Eager)`
        // replicates the entity to every other realm device on the next drain.
        val shareEntity = Entity.create<Share>(
            context = executionContext.context,
            composite = Share.create(
                context = executionContext.context,
                shareId = shareId,
                fileId = filePath,
                createdAtMillis = createdAtMillis,
                createdOnDeviceId = deviceId ?: UNKNOWN_DEVICE,
                expiresAtMillis = expiresAtMillis.get(),
                maxOpens = maxOpens.get(),
                revoked = false,
            )
        )
        executionContext.saveData(shareEntity)

        return ShareLinkResult.create(
            context = executionContext.context,
            shareId = shareId,
            urlFragment = urlFragment,
        ) as T
    }

    /**
     * `POST /share` the descriptor, then stream the AEAD-encrypted chunks. Any
     * failure aborts before the local [Share] is saved, so a relay outage never
     * leaves a *My shares* row pointing at a record the recipient can't open.
     *
     * Phase 4 of `docs/proposals/core-error-channel.md`: relay-side failures
     * become a typed throw (`TenshuException(relay.error)`) instead of a
     * returned `TResult.Failure`, so the wire-side catch at `LocalTaskExecutor`
     * surfaces them on the reply.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun uploadShare(
        executionContext: ExecutionContext,
        relay: ShareRelayClient,
        crypto: SyncCryptoManager,
        shareId: String,
        kShare: ByteArray,
        filePath: String,
        expiresAtMillis: Long,
        maxOpens: Long,
    ) {
        val totalSize = materialiseBytes(executionContext, filePath)

        // Tier-2 storage gate (storage-capacity-gating.md §2): a share re-encrypts
        // + uploads a copy to the relay, so it consumes the owner's storage. The
        // authoritative check, on the proxy with the size in hand. No-op when
        // storage isn't wired/known.
        storageCapacityHolder.ensureCapacity(executionContext, totalSize)
        val chunkCount =
            if (totalSize == 0L) 0
            else ((totalSize + SHARE_CHUNK_SIZE - 1) / SHARE_CHUNK_SIZE).toInt()

        // Descriptor first: the relay's chunk route binds each PUT to an
        // existing owner-scoped record (existence-leak-proof 404 otherwise).
        val sealedMeta = sealShareMeta(
            crypto = crypto,
            kShare = kShare,
            shareId = shareId,
            chunkCount = chunkCount,
            meta = ShareMeta(
                fileName = filePath.substringAfterLast('/').ifEmpty { filePath },
                mimeType = mimeTypeForFileName(filePath),
                size = totalSize,
            ),
        )
        val descriptor = ShareRelayClient.ShareDescriptor(
            shareId = shareId,
            sealedMetaBase64Url = Base64.UrlSafe.encode(sealedMeta).trimEnd('='),
            chunkCount = chunkCount,
            // v1: AEAD is the integrity boundary; no sharer-authored hash anchor.
            chunkHashesBase64Url = emptyList(),
            // Normalise the UI's 0L "no cap" sentinel to null for the wire.
            expiresAtMillis = expiresAtMillis.takeIf { it > 0L },
            maxOpens = maxOpens.takeIf { it > 0L }?.toInt(),
        )
        val created = relay.createShare(descriptor)
        if (created is TResult.Failure) throw TenshuException(created.error)

        // Chunk PUTs overlap, bounded by [DefaultChunkTransferConcurrency]. A share upload is one
        // round trip per chunk against a single-region relay, so serially it is round-trip-bound
        // rather than bandwidth-bound — the further the sharer is from the relay, the more of the
        // wall clock is spent idle. Reading stays sequential inside the producer because `offset`
        // advances by the bytes actually read; only the encrypt + PUT fan out, and the permit is
        // taken before the read so at most N chunk buffers are ever resident.
        var readOffset = 0L
        var sizeChanged = false
        mapChunksBounded(
            produceNext = { index ->
                if (index >= chunkCount || sizeChanged) {
                    null
                } else {
                    val expected = minOf(SHARE_CHUNK_SIZE.toLong(), totalSize - readOffset).toInt()
                    val plainChunk = fileDatabase.readBytesRange(filePath, readOffset, SHARE_CHUNK_SIZE)
                        ?: ByteArray(0)
                    readOffset += plainChunk.size
                    // ⚠ Every chunk must be exactly the length `totalSize` promised. The sealed
                    // meta already committed `chunkCount` + `size`, so a file that shrank (or grew)
                    // since it was measured would reach the recipient as a missing or mis-sized
                    // chunk — indistinguishable from tampering ("link corrupted"). End the stream
                    // and fail the share instead.
                    if (plainChunk.size != expected) {
                        sizeChanged = true
                        null
                    } else {
                        plainChunk
                    }
                }
            },
        ) { index, plainChunk ->
            // `index` is the production index, so the AAD's (shareId, index, chunkCount) binding
            // is unaffected by which transfer completes first.
            val aad = shareChunkAad(shareId, index, chunkCount)
            val cipher = crypto.encryptWithAad(plainChunk, kShare, aad)
            val put = relay.putChunk(shareId, index, cipher)
            // Throwing aborts the batch: `mapChunksBounded` cancels the sibling PUTs rather than
            // finishing uploads for a share this failure has already sunk. Same exception the
            // sequential loop raised, so `createShareLink`'s error surfacing is unchanged.
            if (put is TResult.Failure) throw TenshuException(put.error)
        }
        if (sizeChanged) {
            // Best effort: the shareId is never returned, so nobody can open this record, but
            // revoking it lets the relay's revoke-GC reclaim the chunks already sent.
            try {
                relay.deleteShare(shareId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            throw TenshuException(
                GenericError.create(
                    context = executionContext.context,
                    message = "Cannot share '$filePath': the file changed while it was being shared",
                )
            )
        }
    }

    /**
     * Size of the file's plaintext at [filePath], fetching it first when it
     * isn't on this device's disk.
     *
     * `File.Data` is `Syncable(OnDemand)` — the bytes of a file are NOT
     * guaranteed to be local just because its `File` entity is. They are absent
     * whenever the file was uploaded on another device and never opened here,
     * was evicted (un-pinned, or a folder flipped to `SyncPolicy.ONLINE_ONLY`),
     * or its effective policy simply never pulled it down. Sharing re-encrypts
     * the plaintext under `K_share`, so it needs the bytes — it must drive the
     * OnDemand fetch exactly as `ReadSharedFileImplementation` and
     * `OfflineKeeperImplementation` do (a no-op when the bytes are already
     * present), instead of reading [FileDatabase] blind.
     *
     * Distinguishes the three failures the old blind read collapsed into one
     * "file not found": no such `File` entity; a **folder**, which has no bytes
     * to share and never will (Mode-A links are per-file — a folder is shared
     * into a realm via Mode-B *Share* / *Manage access*); and an entity whose
     * content couldn't be fetched (offline / not yet synced).
     */
    private suspend fun materialiseBytes(
        executionContext: ExecutionContext,
        filePath: String,
    ): Long {
        fileDatabase.fileSize(filePath)?.let { return it }

        val fileId = DataId.StringDataId.create(
            context = executionContext.context,
            type = File.ID,
            keyValue = filePath,
        )
        val entity = executionContext.database.getData(fileId, asEntity = true)
        if (entity == null) {
            throw TenshuException(
                NotFoundError.create(
                    context = executionContext.context,
                    message = "Cannot share '$filePath': file not found"
                )
            )
        }

        // A folder is a `File` subtype carrying children rather than bytes, so it
        // has no plaintext to re-encrypt — the blind `fileSize` read reported that
        // as "file not found", which is what sent this bug hunting in the content
        // plane. Reject it here (the Drive sheet also hides *Link* on folders; the
        // op is reachable from multi-select, the SDK and web too).
        val isFolder = entity is Entity<*> && executionContext.schemaProvider
            .getSchema(entity.type.get())
            ?.isSubtypeOf(Folder.ID) == true
        if (isFolder) {
            throw TenshuException(
                InvalidCallerError.create(
                    context = executionContext.context,
                    message = "Cannot create a link for '$filePath': it's a folder. " +
                        "Share a folder with Share / Manage access instead."
                )
            )
        }

        // No resolver (test / pre-sync wirings) falls through to the same
        // "contents aren't here" error as a fetch that came back empty.
        val resolver = executionContext.context.onDemandResolution()
        if (resolver != null) {
            try {
                resolver.resolve(
                    executionContext = executionContext,
                    entityId = fileId,
                    propertyId = File.Data.id,
                    params = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Best-effort, as in `OfflineKeeperImplementation`: a failed fetch
                // (offline, decode error) falls through to the error below, whose
                // message is what the UI shows — better than surfacing a raw
                // transport throw.
            }
        }

        val fetched = fileDatabase.fileSize(filePath)
        if (fetched == null) {
            throw TenshuException(
                NotFoundError.create(
                    context = executionContext.context,
                    message = "Cannot share '$filePath': this file's contents aren't on " +
                        "this device and couldn't be downloaded. Open the file once " +
                        "while online, then share it."
                )
            )
        }
        return fetched
    }

    companion object {
        /** 32 random bytes, per `AeadBackend.AEAD_KEY_SIZE`. */
        private const val K_SHARE_SIZE = 32

        /** 16 random bytes → ~22-char base64url shareId. */
        private const val SHARE_ID_SIZE = 16

        /**
         * Same `CHUNK` window as the content-plane chunking (§3.3) so peak heap
         * is `O(CHUNK)` regardless of file size; comfortably under the relay's
         * 8 MiB per-chunk cap.
         */
        private const val SHARE_CHUNK_SIZE = 2 * 1024 * 1024

        /** Stamped onto [Share.createdOnDeviceId] when no [deviceId] was wired. */
        private const val UNKNOWN_DEVICE = "unknown-device"

        /**
         * Best-effort MIME from the file extension — the recipient uses it only
         * to pick the viewer chrome. It travels sealed inside `sealedMeta`, so the
         * relay never sees it (F3). Unknown extensions fall back to
         * `application/octet-stream` (download).
         */
        private fun mimeTypeForFileName(fileName: String): String =
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                "pdf" -> "application/pdf"
                "txt", "md", "log" -> "text/plain"
                "json" -> "application/json"
                "csv" -> "text/csv"
                "html", "htm" -> "text/html"
                "mp4" -> "video/mp4"
                "mp3" -> "audio/mpeg"
                "zip" -> "application/zip"
                else -> "application/octet-stream"
            }
    }
}
