package app.tenshu.control.android.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.tenshu.core.common.proxy.BackgroundLiveness
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** See [ForegroundTaskLiveness]'s `idleLingerMs`. */
const val FGS_LINGER_MS: Long = 15_000

/**
 * Phase L2-LIVE (docs/proposals/core-background-processing.md §2.2 / §8): the
 * piece the Layer-2 *runtime* (`TaskHandle`, Phase L2) does not deliver —
 * keeping Control's process alive while a user-started Layer-2 task is in
 * flight after the foreground app (Drive) goes away.
 *
 * This class holds only the registry → liveness *edge logic* and is
 * deliberately free of Android imports so the empty ⇄ in-flight mapping is
 * unit-testable on the JVM. The Android effect (a foreground service +
 * notification) lives behind [TaskLivenessHandle], implemented by
 * `ProxyService`.
 *
 * It is **not** WorkManager (§5.2): a foreground service is process liveness
 * for work happening *now*; WorkManager is Layer-3 deferred/periodic
 * triggering. They are orthogonal.
 */
class ForegroundTaskLiveness(
    private val inFlightCount: StateFlow<Int>,
    private val handle: TaskLivenessHandle,
    /**
     * How long the count must stay at zero before demoting
     * (`control-background-work-off-fgs.md` §5.2).
     *
     * ⚠⚠ Not cosmetic. User-started bulk work is a run of tasks with gaps between them (a folder
     * upload's files, *Back up now*'s files), and once the user has left the app the OS refuses a
     * re-promotion — so demoting in a gap would lose the foreground service for the rest of the run.
     */
    private val idleLingerMs: Long = FGS_LINGER_MS,
) {
    private var active = false

    /**
     * Collects [inFlightCount] for the lifetime of [scope], driving [handle].
     * `StateFlow` already conflates and only emits distinct consecutive values
     * (operator fusion), so no explicit `distinctUntilChanged` is needed.
     */
    fun launchIn(scope: CoroutineScope) {
        scope.launch {
            // collectLatest: a new count cancels a pending linger, so a task starting inside the
            // window keeps the service promoted without an idle→active cycle.
            inFlightCount.collectLatest { count ->
                if (count <= 0 && active) delay(idleLingerMs)
                onCount(count)
            }
        }
    }

    /**
     * Maps one observed in-flight [count] to a liveness transition.
     * Idempotent at the edges: re-entering the same state is a no-op, so a
     * repeated empty count never re-fires [TaskLivenessHandle.onIdle] and a
     * count that stays non-zero only updates the notification text.
     */
    internal fun onCount(count: Int) {
        when {
            count <= 0 && active -> {
                active = false
                handle.onIdle()
            }
            count > 0 && !active -> {
                active = true
                handle.onActive(count)
            }
            count > 0 && active -> handle.onCountChanged(count)
            // count <= 0 && !active -> idle and already idle: no-op.
        }
    }
}

/**
 * The Android-side effect of a Layer-2 liveness transition. Kept as an
 * interface so [ForegroundTaskLiveness]'s edge logic can be exercised without
 * an Android runtime.
 */
interface TaskLivenessHandle {
    /** First in-flight task appeared — promote Control to the foreground. */
    fun onActive(taskCount: Int)

    /** Still in flight, count changed — refresh the ongoing notification. */
    fun onCountChanged(taskCount: Int)

    /** Last in-flight task finished — demote back to a plain bound service. */
    fun onIdle()
}

/**
 * The count `ProxyService` promotes its foreground service on — **user-started work only**
 * (`control-background-work-off-fgs.md` §5.2): the in-flight tasks a client marked user-started,
 * plus the user-started proxy-side sources ([BackgroundLiveness.userStartedActive]).
 *
 * ⚠⚠ Never `ProxyGateway.inFlightCount`, [BackgroundLiveness.active] or the bulk-task count: those
 * include automatic work (a scheduled backup's uploads, the sync push, the maintenance pass), which
 * Play does not allow a `dataSync` foreground service for. That work runs under Control's own jobs.
 */
fun foregroundLivenessCount(
    userInitiatedInFlight: Flow<Int>,
    backgroundLiveness: BackgroundLiveness,
    /** RUNNING bulk tasks a user started (`TaskRegistry.userInitiatedActiveCount`): covers their gaps. */
    userInitiatedTasks: Flow<Int> = kotlinx.coroutines.flow.flowOf(0),
): Flow<Int> = combine(
    userInitiatedInFlight,
    backgroundLiveness.userStartedActive,
    userInitiatedTasks,
) { calls, sources, tasks -> calls + sources + tasks }
