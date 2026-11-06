@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.failureResult
import app.tenshu.sdk.internal.okResult
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.fold
import org.tenshu.sync.client.syncService
import org.tenshu.sync.closeContentStream
import org.tenshu.sync.openContentStream
import org.tenshu.sync.readContentRange
import kotlin.js.Promise

/**
 * Web rendering of the D2b pull streaming ops (`drive-streaming-preview.md` §3.5, S6a) — the thin
 * `@JsExport` *operation* surface (House Rule #5) the Service Worker (S6b) drives to answer HTTP
 * `Range` requests for a `<video src>`. Dispatched through the in-process [JsRuntime] proxy, the
 * same op code natives run: `OnDemandResolution.openContentStream` captures the file's manifest
 * into a `StreamingContentSession` and [readRange] decrypts only the chunk(s) a range touches
 * (Noble AEAD in-browser; GR-7 retained-key trial-decrypt across Kr rotations automatic). This
 * replaces the whole-file blob-URL path (the web D2a OOM trap, §3.5) — no whole file in memory,
 * no MSE.
 *
 * Personal files only for now: [openStream] fills the personal resolve triple
 * `(File.ID, path, File.Data.id)` (SQ8); shared-file web streaming rides Mode-B later (the same op
 * covers it via the `SharedFile` triple).
 */
@JsExport
object ContentStream {

    /**
     * Open a seekable streaming session for the personal file at [path].
     *
     * Resolves `{ ok: true, handle, totalSize }` (the size is for the SW's `Content-Length` /
     * `Content-Range`) or `{ ok: false, error: { kind, message } }`.
     *
     * ⚠⚠ **Not `T?` — and that is the fix, not a style preference.** This used to answer null for
     * both "no stream for this file" and "the op refused", so callers could not tell them apart
     * and every one of them assumed the former. W7's streaming download consequently fell back to
     * a whole-file read on every file, silently, while its unit tests stayed green: the op was
     * answering *"Content stream unavailable — file not synced yet or realm locked"* for ordinary
     * synced files and the reason went in the bin (`tech-debt.md`, 2026-09-01).
     */
    fun openStream(path: String): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.syncService.openContentStream(
                entityType = File.ID,
                entityKey = path,
                propertyId = File.Data.id,
            ).fold(
                onSuccess = { opening ->
                    okResult { r ->
                        r.handle = opening.handle.get()
                        r.totalSize = opening.totalSize.get().toDouble()
                    }
                },
                // The reason now reaches the caller instead of the bin. Still logged: a refusal
                // that a caller chooses to handle silently should still be findable afterwards.
                onFailure = { error ->
                    val message = error.toString()
                    app.tenshu.diagnostics.Diagnostics.w("ContentStream", "openStream refused: $message")
                    failureResult("unavailable", message)
                },
            )
        }

    /**
     * Reads up to [length] plaintext bytes at [offset] from [handle] — only the covering chunk(s)
     * decrypt. [offset] is a JS number (file offsets stay within the 2^53 safe-integer range).
     * Keep [length] ≤ ~256 KiB per the op contract.
     *
     * Resolves `{ ok: true, bytes }` (≤ [length]; **empty** at/after EOF, which is a normal
     * answer) or `{ ok: false, error }` — a closed/unknown handle, or a per-chunk integrity
     * failure.
     *
     * ⚠ `null` here already meant failure alone (EOF is an empty array, not null), so this adds
     * the reason rather than resolving an ambiguity. It is worth it on precisely this method:
     * a range read failing mid-stream is what a truncated download looks like, and the previous
     * bare null is why the streaming path's refusal took a live instrumented run to identify.
     */
    fun readRange(handle: String, offset: Double, length: Int): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.syncService
                .readContentRange(handle, offset.toLong(), length)
                .fold(
                    onSuccess = { bytes -> okResult { it.bytes = bytes.get() } },
                    onFailure = { error -> failureResult("read", error.toString()) },
                )
        }

    /** Close [handle] and release its decrypted-chunk buffers. Idempotent. */
    fun close(handle: String): Promise<Unit> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.syncService.closeContentStream(handle)
            Unit
        }
}

