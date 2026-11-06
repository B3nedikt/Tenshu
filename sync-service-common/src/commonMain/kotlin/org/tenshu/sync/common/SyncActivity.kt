package org.tenshu.sync.common

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What one app is doing with the relay **right now**, as far as a user should be told
 * (`ios-sync-activity-indicator.md` §3). Stringless: the renderer owns the copy.
 *
 * ⚠ There is no problem state. Offline, locked, storage full and the rest have owners already;
 * a problem *hides* the indicator, it is never drawn by it.
 */
sealed interface SyncActivity {

    /** Nothing to say. */
    data object Idle : SyncActivity

    /** A pull is bringing data in. No count: nothing on the device knows one. */
    data object CatchingUp : SyncActivity

    /**
     * This device is sending. [count] is the run's "N of M"; `null` is the tail, where the count
     * is done and chunks are still on their way.
     */
    data class Uploading(val count: Progress?) : SyncActivity

    /** Completed of total, both in files. */
    data class Progress(val completed: Int, val total: Int)
}

/**
 * The pure decision.
 *
 * **Both at once: uploading wins**, in every app. It carries a count, and a pull that matters to
 * the user changes the screen anyway. The state switches only when the winner ends, so an upload
 * that finishes mid-pull hands over to [SyncActivity.CatchingUp] without passing through
 * [SyncActivity.Idle].
 *
 * ⚠ [catchingUp] is the scheduler's settled flag, never its `isSyncing`: that one flips every
 * tick whether or not anything arrived, and an indicator on it blinks for ever on an idle device.
 *
 * @param uploadTotal files in the run; `0` or less means there is no honest count.
 */
fun evaluateSyncActivity(
    catchingUp: Boolean,
    uploading: Boolean = false,
    uploadCompleted: Int = 0,
    uploadTotal: Int = 0,
): SyncActivity = when {
    uploading -> SyncActivity.Uploading(
        count = if (uploadTotal > 0) {
            SyncActivity.Progress(completed = uploadCompleted.coerceIn(0, uploadTotal), total = uploadTotal)
        } else {
            null
        },
    )
    catchingUp -> SyncActivity.CatchingUp
    else -> SyncActivity.Idle
}

/**
 * What this process is sending, for an app that sends (`ios-sync-activity-indicator.md` §6a.1).
 *
 * @property run the backup run's count, or `null` when no run is counting.
 * @property transfersInFlight chunks with the out-of-process uploader ([InFlightTransfers]).
 */
data class UploadInput(val run: SyncActivity.Progress?, val transfersInFlight: Int) {
    companion object {
        /** Nothing being sent: every app but the one that uploads. */
        val None = UploadInput(run = null, transfersInFlight = 0)
    }
}

/**
 * The decision for an app with an upload input. Uploading is a run with a count **or** transfers
 * still in flight; with a count the line carries it, without one it is the tail.
 */
fun evaluateSyncActivity(catchingUp: Boolean, upload: UploadInput): SyncActivity =
    evaluateSyncActivity(
        catchingUp = catchingUp,
        uploading = upload.run != null || upload.transfersInFlight > 0,
        uploadCompleted = upload.run?.completed ?: 0,
        uploadTotal = upload.run?.total ?: 0,
    )

/**
 * The indicator's three delays, in one place. Starting values, not measurements.
 *
 * - [showAfterMs]: activity must last this long, unbroken, before anything shows. A caught-up
 *   device that pulls one small page never shows it.
 * - [minimumShownMs]: once shown it stays at least this long, so it is readable.
 * - [hideAfterIdleMs]: idle must last this long before it goes, so the gap between two ticks of
 *   one catch-up does not blink it.
 */
data class SyncActivityTiming(
    val showAfterMs: Long = 700,
    val minimumShownMs: Long = 1_500,
    val hideAfterIdleMs: Long = 1_000,
) {
    companion object {
        val Default = SyncActivityTiming()
    }
}

/**
 * Smooths a raw [SyncActivity] flow into what should be on screen, by [timing].
 *
 * Emits [SyncActivity.Idle] first, so a collector has a value before any activity. While shown, a
 * switch between two active states passes straight through: only the edges to and from idle are
 * delayed. When the upstream ends it emits `Idle` and completes — the indicator never holds a
 * last value.
 *
 * ⚠ Uses `delay` only, no clock, so it runs on virtual time in tests and needs no time source.
 */
fun Flow<SyncActivity>.debounceSyncActivity(
    timing: SyncActivityTiming = SyncActivityTiming.Default,
): Flow<SyncActivity> = channelFlow {
    val latest = MutableStateFlow<SyncActivity>(SyncActivity.Idle)
    var shown = false
    // Runs out `minimumShownMs` after the indicator appears; the hide waits for it.
    var minimumShown: Job? = null

    send(SyncActivity.Idle)

    val following = launch {
        latest.map { it != SyncActivity.Idle }.distinctUntilChanged().collectLatest { active ->
            if (active) {
                if (!shown) {
                    delay(timing.showAfterMs)
                    shown = true
                    minimumShown = this@channelFlow.launch { delay(timing.minimumShownMs) }
                }
                // Forwards every active state until this block is cancelled by the next edge.
                latest.collect { if (it != SyncActivity.Idle) send(it) }
            } else if (shown) {
                delay(timing.hideAfterIdleMs)
                minimumShown?.join()
                shown = false
                send(SyncActivity.Idle)
            }
        }
    }

    this@debounceSyncActivity.collect { latest.value = it }
    // Upstream ended: nothing can report activity any more.
    following.cancel()
    minimumShown?.cancel()
    send(SyncActivity.Idle)
}.distinctUntilChanged()
