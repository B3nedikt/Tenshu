package app.tenshu.control.inference

import app.tenshu.control.ui.screens.models.IndexStartOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Where the pass a person asked for stands on a phone that indexes **only while the app is in
 * front** (`ios-on-device-inference.md` §14.19, §14.19.6): the proxy's `ForegroundIndexing`, in
 * this module's terms. ⚠ Android and desktop have none: their repository says `null`.
 *
 * ⚠ Whether a pass is **running**, and how far it got, is the bar's ([IndexProgressSource]), not
 * this: [Asked] is *asked, and starting or running*.
 */
sealed interface ForegroundIndexStanding {
    /** Nothing is asked, or what was asked ran to its end. */
    data object NotAsked : ForegroundIndexStanding

    /** Asked, and starting or running. */
    data object Asked : ForegroundIndexStanding

    /** Asked, and stopped by the phone: it goes on by itself once [why] clears, while the app stays in front. */
    data class Waiting(val why: Why) : ForegroundIndexStanding {
        enum class Why { WARM, LOW_POWER_MODE, BATTERY_LOW }
    }

    /** Asked, and stopped by [why]. Nothing resumes it but a press. */
    data class Stopped(val why: Why) : ForegroundIndexStanding {
        enum class Why { LEFT_THE_APP, LOW_MEMORY }
    }
}

/**
 * What this device's index holds, read from the store itself (§14.19.6).
 *
 * @param searchable photographs whose vector is stored: the ones search by description can find.
 *   ⚠ *Can be searched*, never *indexed*. `null`: not known — the stores have not
 *   been checked in this process yet, or the read failed.
 * @param clearedByOsUpdate the vectors were cleared because the OS build changed (the fingerprint
 *   holds it, §14.18.16 item 3), and no pass has finished since in this process. ⚠ What turns a
 *   bare *0 of N* into a sentence that says why.
 */
data class StoredIndex(val searchable: Int?, val clearedByOsUpdate: Boolean = false)

/**
 * The indexing half of a Models screen over **this process's** pass (iOS, §14.19.6). ⚠ An
 * interface for [IndexProgressSource]'s reason: the driver lives in `:inference-service-proxy`,
 * which this module must not see; `:control-shared-proxy` adapts it.
 */
interface OnDeviceIndexSource {
    /** The running pass. ⚠ Never completes. */
    val progress: IndexProgressSource

    /** The last finished pass's reach, process-local. ⚠ Never completes. */
    val coverage: IndexCoverageSource

    /** ⚠ Never completes. */
    val standing: Flow<ForegroundIndexStanding>

    /** ⚠ Never completes. */
    val stored: Flow<StoredIndex>

    /**
     * Whether a continued task **carries** the running pass — on iOS 26+, a launched
     * `BGContinuedProcessingTask` (`ios-on-device-inference.md` §15) — so that leaving the app does
     * not stop it. ⚠⚠ Only a launched task: a request submitted and not yet answered, or refused,
     * leaves the pass one a leave stops (§15.2). ⚠ Defaulted to *never*, which is every platform
     * with no continuation (below iOS 26, Android, desktop). Never completes.
     */
    val continued: Flow<Boolean> get() = flowOf(false)

    /**
     * *Index now*, through the start operation. ⚠ Returns when the pass has started, been
     * refused, or waits for the phone ([IndexStartOutcome.Waiting]); never when it ends.
     */
    suspend fun indexNow(): IndexStartOutcome
}
