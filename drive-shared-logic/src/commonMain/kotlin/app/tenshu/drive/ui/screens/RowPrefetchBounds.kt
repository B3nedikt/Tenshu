package app.tenshu.drive.ui.screens

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * How many per-row loads a list screen keeps in flight while the user scrolls.
 *
 * ⚠⚠ **This is a prefetch-depth knob, not an IPC safety invariant** — and the KDoc it
 * replaces claimed the latter. The old wording ("the kernel buffer is per-process and
 * finite; explosive concurrency on scroll can degrade the foreground IPC pipeline") named
 * a **per-process** resource, which a bound held by one ViewModel structurally cannot
 * protect: Files and Home each held their own copy of these two permits, so the real
 * in-flight ceiling was already double what either file claimed, and nothing anywhere
 * bounded the total. Nothing at the transport layer bounds concurrency either — it bounds
 * *size* (the ~1 MB parcel, via tokenize/OOB).
 *
 * What the bound genuinely does, and does well, is shape work against the **viewport**:
 * only the client knows which rows are visible, and a permit released by a cancelled
 * `produceState` is the cheapest possible "stop fetching what the user scrolled past".
 * That is the consumer-side half of `core-deferred-properties.md` §2, and it belongs
 * here. Read the numbers as "how far ahead do we fetch", which is a UX trade a surface is
 * free to tune, rather than as a limit something breaks without.
 *
 * ⚠ A real transport-level bound would be a different mechanism and is deliberately not
 * built: a single global queue would let a burst of background prefetches sit in front of
 * a user-initiated rename or folder-open, so doing it properly needs interactive-vs-prefetch
 * lanes — a scheduler, and no incident yet justifies one.
 *
 * ⚠ [PREVIEW] is the tighter bound because a preview fetch carries a payload and, since C7
 * (`sync-device-catch-up.md` §11.3), may additionally miss locally and wait on the content
 * plane — so a permit is now held for a round trip rather than a local read. The number was
 * tuned when it could only ever be a local read; it is worth re-checking on device.
 */
object RowPrefetchBounds {

    /** Per-row preview/thumbnail fetches — carries a payload, may hit the content plane. */
    const val PREVIEW = 4

    /** Per-row byte-presence probes — an O(1) `fileSize` stat, so a looser bound. */
    const val LOCAL_DATA = 8
}

/**
 * A viewport-scoped prefetch bound shared by the list screens (see [RowPrefetchBounds]).
 *
 * Exists so the two ViewModels stop each declaring their own byte-identical pair of
 * semaphores — the third copy is where the constants start to drift.
 */
class RowPrefetchLimiter(permits: Int) {

    private val semaphore = Semaphore(permits = permits)

    /**
     * Run [block] holding one permit. Cancellation (the row leaving composition) releases
     * the permit immediately, which is the whole point of bounding here rather than deeper.
     */
    suspend fun <T> withSlot(block: suspend () -> T): T = semaphore.withPermit { block() }
}
