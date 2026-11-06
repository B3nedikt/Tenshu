package org.tenshu.file.proxy

import kotlinx.coroutines.CancellationException
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.file.system.OfflineKeeper
import org.tenshu.core.toTString
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleSpec

/** Stable schedule id for the offline keeper; coalesces re-registration on each startup. */
const val OFFLINE_KEEPER_DEDUP_KEY = "tenshu/file/offlineKeeper"

/** Default keeper cadence — reconcile is not urgent, so a slow tick keeps it cheap. */
const val OFFLINE_KEEPER_PERIOD_MS = 6L * 60 * 60 * 1000 // 6 hours

/**
 * Register the [OfflineKeeper] as a **periodic, unmetered-only** `HeadlessSafe`
 * `ScheduleItem` (`drive-offline-online.md` §3.2). The already-running
 * `ScheduleDrainLoop` dispatches it in-process; on Android `WorkManager` wakes a
 * closed Control (`PlatformScheduler`). Idempotent — `dedupKey` coalesces, so
 * calling this on every startup keeps exactly one item.
 *
 * **Downloads on unmetered networks only**, checked by the keeper itself
 * (`selectKeepOfflineDownloads(metered = …)`); the schedule asks for no network, so the
 * evict half runs on cellular too. Call from the proxy init hook
 * on a session scope (`Schedule` is async).
 */
suspend fun ExecutionContext.registerOfflineKeeperSchedule(
    periodMs: Long = OFFLINE_KEEPER_PERIOD_MS,
) {
    try {
        scheduleOfflineKeeper(periodMs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // ⚠⚠ Best-effort BY DECISION, and the guard is load-bearing rather than defensive. Every
        // call site is a bare `sessionScope.launch { registerOfflineKeeperSchedule() }` at proxy
        // init, and that scope carries no CoroutineExceptionHandler — so anything thrown here
        // reaches the thread's uncaught handler, which on Android kills Control. Registering a
        // 6-hourly maintenance tick must not be able to take the proxy down with it.
        //
        // Reachable without exotic conditions: the `Schedule` op resolves through the platform
        // implementation chain, so any DI that does not wire the sync factory throws
        // `No implementation found for operation: …/schedule` right here — a narrowed test harness
        // included, where it would surface as an uncaught exception charged to an unrelated test.
        //
        // Named line rather than silence: the cost of losing it is that kept-offline content stops
        // being reconciled, which is invisible until someone opens a file they expected to be there.
        println("OfflineKeeper -> schedule registration failed, keeper will not run: $e")
    }
}

private suspend fun ExecutionContext.scheduleOfflineKeeper(periodMs: Long) {
    executeAsync<Data>(
        operationId = Schedule.ID,
        caller = null,
        params = mapOf(
            Schedule.Params.OP_ID to OfflineKeeper.ID.toTString(context),
            Schedule.Params.SCHEDULE_SPEC to ScheduleSpec.create(
                context = context,
                kind = ScheduleSpec.KIND_PERIODIC,
                minPeriodMs = periodMs,
            ),
            Schedule.Params.CONSTRAINT_SPEC to ConstraintSpec.create(
                context = context,
                // ⚠⚠ No network constraint (§5.6): the evict half needs none and
                // must run on cellular too, and on Android WorkManager is the only driver of an
                // item with a constraint. The download half checks for a metered network itself.
                network = ConstraintSpec.NETWORK_NONE,
            ),
            Schedule.Params.RETRY_POLICY to RetryPolicy.create(context),
            Schedule.Params.DEDUP_KEY to OFFLINE_KEEPER_DEDUP_KEY.toTString(context),
        ),
    )
}
