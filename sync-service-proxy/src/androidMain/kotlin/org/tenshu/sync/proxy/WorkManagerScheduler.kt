package org.tenshu.sync.proxy

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.ScheduleItem
import java.util.concurrent.TimeUnit

/**
 * Android [PlatformScheduler] (bgproc Phase 7). Translates a [ScheduleItem]'s
 * platform-agnostic [ScheduledWorkPlan] into a `WorkManager` request, keyed
 * uniquely on the item's `dedupKey` so re-registering replaces rather than
 * duplicates. The enqueued [TenshuOperationWorker] binds back into Control and
 * dispatches the op by id.
 *
 * The constraint mapping is the §5.6 table; the metered-data ceiling
 * (`maxDataMb`) is intentionally **not** a WorkManager constraint — it is
 * enforced by the Path-A `ConstraintSyncGate` at drain time. Not locally
 * compilable (no Android SDK in the headless build env); the pure mapping is
 * covered by `ScheduledWorkPlanTest`.
 */
class WorkManagerScheduler(
    private val context: Context,
) : PlatformScheduler {

    private val punctualAlarms = PunctualAlarmScheduler(context)

    override fun register(item: ScheduleItem, punctual: Boolean) {
        val plan = item.toWorkPlan { punctual }

        // ⚠⚠ RE-D5: a punctual one-shot goes to AlarmManager, NOT WorkManager.
        // WorkManager batches by design — right for maintenance, wrong for "at
        // sunset". The two coexist; this is the only fork.
        if (plan.punctual) {
            punctualAlarms.schedule(
                dedupKey = plan.dedupKey,
                opId = plan.opId,
                triggerAtEpochMs = System.currentTimeMillis() + (plan.initialDelayMs ?: 0L),
            )
            return
        }
        val constraints = constraintsOf(item.constraintSpec)
        val input = workDataOf(TenshuOperationWorker.KEY_OP_ID to plan.opId)
        val wm = WorkManager.getInstance(context)

        if (plan.periodic) {
            // WorkManager floors the period at 15 min; clamp so a shorter minPeriodMs
            // (the drain loop's finer cadence) doesn't throw at build time.
            val periodMs = (plan.minPeriodMs ?: PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS)
                .coerceAtLeast(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS)
            val request = PeriodicWorkRequestBuilder<TenshuOperationWorker>(periodMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setInputData(input)
                .addTag(TenshuOperationWorker.TAG)
                .apply { plan.initialDelayMs?.let { setInitialDelay(it, TimeUnit.MILLISECONDS) } }
                .apply { plan.backoff?.let { setBackoffCriteria(it.policy(), it.initialMs, TimeUnit.MILLISECONDS) } }
                .build()
            wm.enqueueUniquePeriodicWork(plan.dedupKey, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            val request = OneTimeWorkRequestBuilder<TenshuOperationWorker>()
                .setConstraints(constraints)
                .setInputData(input)
                .addTag(TenshuOperationWorker.TAG)
                .apply { plan.initialDelayMs?.let { setInitialDelay(it, TimeUnit.MILLISECONDS) } }
                .apply { plan.backoff?.let { setBackoffCriteria(it.policy(), it.initialMs, TimeUnit.MILLISECONDS) } }
                .build()
            wm.enqueueUniqueWork(plan.dedupKey, ExistingWorkPolicy.REPLACE, request)
        }
    }

    /**
     * WorkManager enforces every constraint the in-process loop cannot, so it is the only driver of
     * an item that declares one ([ScheduledWorkPlan.platformOwned]): its job holds the process — and
     * Control's network, even under Doze (§3.3) — for as long as the op runs.
     */
    override fun ownsDispatch(item: ScheduleItem, punctual: Boolean): Boolean =
        item.toWorkPlan { punctual }.platformOwned

    /**
     * Run [opId] once **now-ish** under a regular WorkManager job, as the scheduled driver would —
     * the debug `MaintenancePassReceiver`'s path, so a manual pass is measured under the same job a
     * scheduled one gets rather than in a detached scope (§5.6). `CONNECTED`, not expedited: an
     * expedited job's network ends with its 3-minute guarantee, a regular one's with the op.
     */
    fun enqueueOneShotOp(opId: String, uniqueName: String) {
        val request = OneTimeWorkRequestBuilder<TenshuOperationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(TenshuOperationWorker.KEY_OP_ID to opId))
            .addTag(TenshuOperationWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(dedupKey: String) {
        WorkManager.getInstance(context).cancelUniqueWork(dedupKey)
        // Cancel both drivers: a key may have been registered as punctual before
        // and as ordinary work now (or vice versa), and a stale alarm would fire
        // an op the registry no longer wants.
        punctualAlarms.cancel(dedupKey)
    }

    override fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(TenshuOperationWorker.TAG)
    }

    /**
     * Enqueue Control's own sync-push job ([SyncPushWorker], `control-background-work-off-fgs.md`
     * §5.3) — what keeps an automatic push alive now that the foreground service is for
     * user-started work only.
     *
     * - `CONNECTED`, never the Wi-Fi-only constraint: the backlog that raised it is already only
     *   what the current network may carry (metadata always, content only when the metered gate
     *   lets it), and `drainAndPull` re-applies the gate itself if the network changes first.
     * - [ExistingWorkPolicy.KEEP] for a fresh backlog: one queued run drains everything, and a
     *   running one lingers for more. ⚠ [SyncPushRequest.appendToRunning] is the finishing job's own
     *   follow-up, queued behind it; `KEEP` would drop it because the asker is still running.
     * - Expedited only when asked, with [OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST], so a
     *   spent quota delays the push instead of failing it.
     */
    fun enqueueSyncPush(request: SyncPushRequest) {
        val builder = OneTimeWorkRequestBuilder<SyncPushWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(SyncPushWorker.TAG)
        if (request.expedited && request.initialDelayMs == 0L) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        if (request.initialDelayMs > 0) {
            builder.setInitialDelay(request.initialDelayMs, TimeUnit.MILLISECONDS)
            // ⚠ A plane retry is not the stall's retry: it stands down while the push is parked.
            if (!request.planeRetry) builder.setInputData(workDataOf(SyncPushWorker.KEY_STALL_RETRY to true))
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            request.uniqueName,
            if (request.appendToRunning) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            builder.build(),
        )
    }

    /**
     * The push for content the Wi-Fi-only policy is holding: an ordinary job that waits for an
     * **unmetered** network, so the held files upload when Wi-Fi returns. Its own unique name, so it
     * neither replaces nor is kept in place of a connected push.
     */
    fun enqueueDeferredContentPush() {
        val request = OneTimeWorkRequestBuilder<SyncPushWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .addTag(SyncPushWorker.TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(SyncPushScheduler.UNMETERED_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Media pipeline P4: the backoff policy behind a `Result.retry()` — what a
     * `ScheduledRunDeferred` becomes here. Only set when the item's `RetryPolicy` asked for one.
     * ⚠ The wait grows with the attempt count; it is not a fixed interval (see [WorkBackoff]).
     */
    private fun WorkBackoff.policy(): BackoffPolicy =
        if (exponential) BackoffPolicy.EXPONENTIAL else BackoffPolicy.LINEAR

    /**
     * The §5.6 constraint mapping, used by [register]. The network
     * decision is [networkRequirement] — the same pure, JVM-tested function `toWorkPlan` uses — so
     * this side only translates the enum.
     *
     * ⚠⚠ The `ConstraintSpec.create` default (`NETWORK_ANY`) is `CONNECTED`, not `NOT_REQUIRED`: a
     * schedule that needs no network must declare `NETWORK_NONE` (see [networkRequirement]).
     * `maxDataMb` is deliberately not a WorkManager constraint — the metered ceiling is enforced by
     * the Path-A `ConstraintSyncGate` at drain time.
     */
    private fun constraintsOf(spec: ConstraintSpec): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(spec.networkRequirement().toNetworkType())
            .apply {
                if (spec.power.get() == ConstraintSpec.POWER_CHARGING) setRequiresCharging(true)
                if (spec.idle.get() == ConstraintSpec.IDLE_DEVICE_IDLE) setRequiresDeviceIdle(true)
                if (spec.storage.get() == ConstraintSpec.STORAGE_NOT_LOW) setRequiresStorageNotLow(true)
                if (spec.battery.get() == ConstraintSpec.BATTERY_NOT_LOW) setRequiresBatteryNotLow(true)
            }
            .build()

    private fun NetworkRequirement.toNetworkType(): NetworkType = when (this) {
        NetworkRequirement.NONE -> NetworkType.NOT_REQUIRED
        NetworkRequirement.CONNECTED -> NetworkType.CONNECTED
        NetworkRequirement.UNMETERED -> NetworkType.UNMETERED
    }
}
