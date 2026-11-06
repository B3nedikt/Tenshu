package org.tenshu.sdk.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.core.TContext
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.InFlightTransfers

/**
 * Orchestrates the O3-B background-upload path (ios-drive-parity.md P3b): stages each sealed
 * chunk to disk, records it in the durable [journal], and hands it to the Swift
 * [BackgroundUploadBridge] for a `URLSession(.background)` transfer that survives app
 * suspension/termination. Downloads are unaffected — [BackgroundUrlSessionContentBlobStore]
 * delegates `getChunk` to the [foreground] store.
 *
 * **Why this is a transport swap, not a new trust boundary.** Route-1 already made Drive a
 * key-holding realm device (the 2026-06-28 custody decision; threat-model A4/A5 amended at
 * P1/P5), so it already encrypts content and PUTs blobs from its own process today (P3a,
 * foreground). This changes only *how* the already-sealed bytes reach the relay: staged to a
 * file, then transferred by `nsurlsessiond` out-of-process. The only new at-rest surface is
 * transient opaque ciphertext in the App-Group container — the same posture as the ObjectStore
 * replica already there. No key and no bearer token is persisted (the JWT is re-minted per
 * enqueue).
 *
 * **Fallback.** With no bridge installed — unit tests, or any path before the Swift session is
 * registered — [stageAndEnqueue] uploads foreground through [foreground], preserving the exact
 * pre-P3b behaviour (just not suspension-surviving).
 */
internal class BackgroundUploadManager(
    private val stagingDir: String,
    private val journal: BackgroundUploadJournal,
    private val baseUrl: String,
    private val foreground: ContentBlobStore,
    private val authTokenProvider: suspend () -> String?,
    private val entitlementTokenProvider: suspend () -> String?,
    private val entitlementProofProvider: suspend () -> String?,
    private val bridgeProvider: () -> BackgroundUploadBridge?,
    private val scope: CoroutineScope,
    private val context: TContext,
) {

    /**
     * Transfers with the background session right now: handed off, not yet reported complete
     * (`ios-sync-activity-indicator.md` §6a.1). What the "Uploading…" tail reads.
     *
     * ⚠⚠ Not the journal. A transfer that fails with anything but 401/403 stays journalled with no
     * retry until a later launch, so journal occupancy would read "uploading" with nothing running.
     */
    val inFlight = InFlightTransfers()

    /** Serialises [resumeIncomplete], so the one-time seed lands before any re-enqueue. */
    private val resumeMutex = Mutex()
    private var seeded = false

    /** Every hand-off to the background session goes through here, so every one is counted. */
    private suspend fun handOff(bridge: BackgroundUploadBridge, ref: String) {
        inFlight.handedOff(ref)
        bridge.enqueue(ref, stagedPathOf(ref), urlOf(ref), buildHeaders())
    }

    /**
     * Count what `nsurlsessiond` already holds. It outlives the app, so a launch can find
     * transfers running that this process never handed off. Once per manager, before the first
     * resume adds its own.
     *
     * ⚠ A bridge that never answers must not hold the resume, so the wait is bounded; with no
     * answer nothing is seeded, and the count errs low.
     */
    private suspend fun seedInFlight(bridge: BackgroundUploadBridge) {
        if (seeded) return
        seeded = true
        inFlight.beginSeed()
        val live = withTimeoutOrNull(SEED_TIMEOUT_MS) {
            suspendCancellableCoroutine<List<String>> { continuation ->
                bridge.liveTaskRefs { refs -> if (continuation.isActive) continuation.resume(refs) }
            }
        }
        inFlight.seed(live.orEmpty())
    }

    /**
     * The `ContentBlobStore.putChunk` seam. Stages [ciphertext] and enqueues it on the
     * background session; returns `Success` on stage-and-enqueue (NOT on relay ack — the
     * transfer runs asynchronously). Falls back to a foreground PUT when no bridge is installed.
     *
     * A staging failure returns `Failure`, which `emitFileContent` treats as a failed chunk and
     * aborts the whole manifest — better a retried upload than a manifest referencing a chunk
     * that never got staged.
     */
    suspend fun stageAndEnqueue(ref: String, ciphertext: ByteArray): TResult<Data?> {
        val bridge = bridgeProvider() ?: return foreground.putChunk(ref, ciphertext)

        ensureDirectory(stagingDir)
        val stagedPath = stagedPathOf(ref)
        if (!writeBytesToFile(stagedPath, ciphertext)) {
            return TResult.Failure.create(
                context,
                GenericError.create(context, "upload staging failed for $ref"),
            )
        }
        journal.record(ref)
        handOff(bridge, ref)
        return TResult.Success.create(context, null)
    }

    /** Whether [ref] is staged and journalled, not yet acknowledged by the relay. */
    suspend fun isPending(ref: String): Boolean = ref in journal.pendingRefs()

    /**
     * Swift → Kotlin completion (`URLSessionTaskDelegate.didCompleteWithError`). A 2xx acks the
     * blob: drop the journal entry and delete the spent staging file. A 401/403 means the baked-in
     * JWT expired before `nsurlsessiond` ran the transfer — re-mint fresh headers and re-enqueue
     * (idempotent: content-addressed blob). Any other failure stays journaled for
     * [resumeIncomplete] on the next launch/refresh.
     */
    fun onCompleted(ref: String, statusCode: Int, errorMessage: String?) {
        // ⚠ Before the launch, and whatever the outcome: the transfer is over either way, and a
        // 401/403 re-enqueue below is a new hand-off that counts itself.
        inFlight.completed(ref)
        scope.launch {
            val acked = errorMessage == null && statusCode in 200..299
            if (acked) {
                // ⚠ File first, then the entry. Killed in between, a journal entry with no file is
                // what [resumeIncomplete] drops; the reverse order leaves a file nothing ever deletes.
                deleteFileAt(stagedPathOf(ref))
                journal.markComplete(ref)
                return@launch
            }
            if (statusCode == 401 || statusCode == 403) {
                val bridge = bridgeProvider() ?: return@launch
                if (readBytesFromFile(stagedPathOf(ref)) != null) {
                    handOff(bridge, ref)
                }
            }
            // else: leave journaled — resumeIncomplete() re-drives it later.
        }
    }

    /**
     * Re-enqueue every still-pending staged chunk — the crash-between-stage-and-ack recovery,
     * run on install and on each background refresh. Idempotent (content-addressed). A journal
     * entry whose staging file has vanished is dropped (nothing left to upload).
     */
    fun resumeIncomplete() {
        scope.launch {
            val bridge = bridgeProvider() ?: return@launch
            resumeMutex.withLock {
                seedInFlight(bridge)
                for (ref in journal.pendingRefs()) {
                    if (readBytesFromFile(stagedPathOf(ref)) != null) {
                        handOff(bridge, ref)
                    } else {
                        journal.markComplete(ref)
                    }
                }
            }
        }
    }

    private fun stagedPathOf(ref: String): String = "$stagingDir/$ref"

    private fun urlOf(ref: String): String = "$baseUrl/sync/content/$ref"

    private suspend fun buildHeaders(): Map<String, String> = buildMap {
        put("Content-Type", "application/octet-stream")
        authTokenProvider()?.let { put("Authorization", "Bearer $it") }
        entitlementTokenProvider()?.let { put("X-Entitlement", it) }
        entitlementProofProvider()?.let { put("X-Entitlement-Proof", it) }
    }

    private companion object {
        const val SEED_TIMEOUT_MS = 5_000L
    }
}
