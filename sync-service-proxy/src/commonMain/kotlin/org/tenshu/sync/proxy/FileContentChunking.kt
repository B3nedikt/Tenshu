package org.tenshu.sync.proxy

// The whole-file hash-chain (IncrementalSha256), the 2 MiB chunk stride
// (FileContentChunkSizeBytes) and toHexLower now live in :core-common
// (org.tenshu.core.common.util.WholeFileHash) so the upload/emit/read planes
// share one construction — re-exported here as type aliases / consts so the
// existing internal call sites (SyncObserver, FileContentManifestApplier,
// FileChunkDecryptor, StreamingContentSession) keep compiling unchanged.
import org.tenshu.core.common.util.FileContentChunkSizeBytes as SharedFileContentChunkSizeBytes
import org.tenshu.core.common.util.sha256
import org.tenshu.sync.common.manifestRefOf
import org.tenshu.core.common.util.toHexLower as sharedToHexLower

/**
 * Shared chunk-plane helpers for the two-plane file-content design
 * (`docs/adr/0017-file-content-two-plane-channel.md`). The whole-file anchor + chunk
 * stride are the `:core-common` shared symbols (above); the AAD + the manifest
 * content-ref helper below are chunk-plane specifics that stay here, next to the
 * only two consumers (emit + read).
 */

internal typealias IncrementalSha256 = org.tenshu.core.common.util.IncrementalSha256

internal const val FileContentChunkSizeBytes: Int = SharedFileContentChunkSizeBytes

internal fun ByteArray.toHexLower(): String = sharedToHexLower()

/**
 * The per-chunk AEAD associated data. Binds `(fileId, chunkIndex,
 * chunkCount)` so a chunk ciphertext cannot be silently reordered,
 * truncated, replayed across files, or spliced — any tamper of the
 * bound context fails [org.tenshu.core.security.SyncCryptoManager.decryptWithAad]
 * (threat-model T-R-09 / `docs/adr/0017-file-content-two-plane-channel.md`). The reader
 * MUST reconstruct this identically; that is the whole point of
 * sharing it here rather than duplicating the format string.
 *
 * `fileId` is the file's stable random `contentId` (content-addressing Phase 1):
 * binding the AAD to it — not to the mutable path-hash — is what lets a moved
 * file's chunks stay valid without re-encryption.
 */
internal fun fileChunkAad(fileId: String, chunkIndex: Int, chunkCount: Int): ByteArray =
    "tenshu/file-chunk/v1|$fileId|$chunkIndex|$chunkCount".encodeToByteArray()

/**
 * AAD binding the CEK-wrap (content-addressing §3.4) to its content ([fileId] = the file's
 * `contentId` / DataId hash), domain-separated from chunk encryption ([fileChunkAad]) so a
 * wrapped CEK can't be lifted onto another file's manifest.
 */
internal fun cekWrapAad(fileId: String): ByteArray =
    "tenshu/cek-wrap/v1|$fileId".encodeToByteArray()

/**
 * The content-plane ref a file's **manifest** is stored/fetched under, in the same
 * [org.tenshu.sync.common.ContentBlobStore] as the chunks' `blob-<sha256(ciphertext)>`.
 *
 * Shaped `blob-<sha256(...)>` so it satisfies the relay's
 * `ContentRouting.REF_PATTERN` (`^blob-[0-9a-f]{64}$`, enforced on **both** PUT and GET). It is
 * derived from the file's `contentId` — **not** the manifest ciphertext — because the reader
 * knows the `contentId` from the `File` entity but not the manifest bytes it is trying to fetch;
 * a distinct `"manifest:"` domain tag keeps it from ever colliding with a chunk ref. The
 * `contentId` is a random id (not a content hash), so the ref is not a confirmation-of-file
 * oracle. The relay is content-blind and does not special-case manifests (GC is per-blob PUT/GET
 * recency — `ContentBlobStore`), so sharing the `blob-` key space is safe.
 *
 * ⚠ Was `manifest-<contentId>` (content-addressing Phase 1 §8.3) — but that shape fails the
 * relay's `REF_PATTERN`, so cross-device manifest PUT/GET 400'd and OnDemand byte delivery broke
 * on every real relay (unit tests use in-process mailboxes that don't enforce the ref shape; the
 * `:system-test-utils` file-bytes tests caught it). Pre-release Phase-0 reset ⇒ no stored
 * manifests to migrate.
 */
internal fun manifestContentRef(contentId: String): String = manifestRefOf(contentId)

/**
 * Raised by a chunk transfer that failed, to abort the whole batch.
 *
 * Chunk transfers run concurrently (`mapChunksBounded`), and a `TResult.Failure` return can only
 * end the *one* transfer that saw it — the siblings would keep pushing bytes for a manifest that
 * is already doomed. Throwing instead cancels them through the enclosing `coroutineScope`, which
 * is how the concurrent path keeps the sequential loop's "first failure ends the upload"
 * behaviour.
 *
 * ⚠ Deliberately a plain [Exception] and not a [kotlinx.coroutines.CancellationException]: the
 * caller catches exactly this type and converts it back to the `false` its contract promises, and
 * a cancellation subtype would be swallowed as ordinary cooperative cancellation instead.
 *
 * ⚠⚠ **[reason] is the transport's own failure text, and omitting it was a real bug.** The message
 * this is constructed with names the *file and chunk*; only [reason] names **why**. Without it the
 * rejection markers ([STORAGE_REJECTION_MARKER], [ENTITLEMENT_REJECTION_MARKER]) were stripped at
 * the throw site, so a doomed write was indistinguishable from a transient one by the time it
 * reached the scheduler — and the log line printed 176,000 times on a real phone (2026-09-06) said
 * "chunk PUT failed" without ever once saying it was a `507`. Callers classify on this exception,
 * so the reason must travel *on* it, not merely be logged.
 */
internal class ChunkPutFailed(
    message: String,
    /** The failing transport's own error text — carries the rejection marker, when there is one. */
    val reason: String? = null,
) : Exception(if (reason == null) message else "$message — $reason")
