package app.tenshu.control.indexing

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.tenshu.control.inference.NEW_ARRIVALS_DEBOUNCE_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import androidx.work.WorkInfo
import app.tenshu.control.inference.ArrivalJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tenshu.inference.proxy.ArrivalQueue
import org.tenshu.inference.proxy.IndexPassResult
import org.tenshu.inference.proxy.MediaIndexRunner
import org.tenshu.inference.proxy.NEW_ARRIVALS_BATCH_CAP
import org.tenshu.inference.proxy.runArrivalsBatch
import java.util.concurrent.TimeUnit

/**
 * New arrivals on Android (`inference-service.md` IN-D6a): the **debounced one-time job** that indexes
 * the photographs on the [ArrivalQueue] — and nothing else of the library — about two minutes after
 * the last arrived, Photos need not be open.
 *
 * - ⚠⚠ **A plain [CoroutineWorker], never a foreground one.** A batch reads and indexes at most
 *   [NEW_ARRIVALS_BATCH_CAP] photographs — tens of seconds — so it fits a regular job's 10-minute
 *   execution limit and needs no notification. *Index now*'s [ManualIndexWorker] is the foreground one.
 * - **Constraint:** battery not low, alone. The 12 h idle+charging job keeps the backlog.
 * - **Arming** is `ThrottledArrivalScheduler`'s: `REPLACE` + a two-minute delay, pushed back by later
 *   arrivals for at most ten minutes, and ⚠⚠ never while a batch runs.
 * - ⚠ **"About two minutes" is the best case.** A one-time job is subject to App Standby buckets and
 *   Doze: for an app in the *rare* or *restricted* bucket — Control, with no visible activity —
 *   Android may hold it for hours. The queue keeps the paths meanwhile.
 * - ⚠⚠ **Never chains.** What the cap leaves stays queued for the batch the next arrival arms, or the
 *   12 h pass / *Index now*; this job enqueues nothing.
 * - **Stopped** — the OS, the limit — cancels the batch at its next photograph; the queue is
 *   settled only by a batch that finished, so nothing is dropped.
 */
class NewArrivalsIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val runner: MediaIndexRunner by inject()
    private val queue: ArrivalQueue by inject()
    private val contextHolder: ManualIndexContextHolder by inject()

    override suspend fun doWork(): Result {
        // Cold wake: the proxy session installs the context ~ms after the process starts.
        var waited = 0L
        while (contextHolder.executionContext == null && waited < CONTEXT_WAIT_MS) {
            delay(100)
            waited += 100
        }
        // ⚠⚠ Bounded like a deferral: every retry is a cold start of Control.
        val executionContext = contextHolder.executionContext ?: return retryOrLeave(runAttemptCount)
        return try {
            outcomeOf(runner.runArrivalsBatch(executionContext, queue, NEW_ARRIVALS_BATCH_CAP)?.result, runAttemptCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("NewArrivalsIndexWorker -> batch failed: $e")
            Result.failure()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "new-arrivals-media-index"

        /** How many runs a deferred or context-less batch gets before it is left to the 12 h pass. */
        internal const val MAX_DEFERRED_ATTEMPTS = 2

        private const val CONTEXT_WAIT_MS = 10_000L

        /** The request [enqueue] arms: one-time, battery not low, two minutes out. */
        fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<NewArrivalsIndexWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setInitialDelay(NEW_ARRIVALS_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            .build()

        /**
         * (Re-)arms the job: the delay restarts from now. ⚠⚠ `REPLACE` cancels a RUNNING batch, so
         * the only caller — `ThrottledArrivalScheduler` — asks [jobState] first and never arms over
         * one. ⚠ A job that starts between that read and this write is the one race left: it is
         * cancelled before its first photograph's stamp, and the next arm re-runs it.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request())
        }

        /** Where the unique job stands. ⚠ Blocks on WorkManager's database: off the main thread. */
        suspend fun jobState(context: Context): ArrivalJob = withContext(Dispatchers.IO) {
            jobStateOf(WorkManager.getInstance(context).getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get().map { it.state })
        }

        internal fun jobStateOf(states: List<WorkInfo.State>): ArrivalJob = when {
            WorkInfo.State.RUNNING in states -> ArrivalJob.RUNNING
            states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> ArrivalJob.WAITING
            else -> ArrivalJob.NONE
        }

        /**
         * The batch's report as the job's result. ⚠⚠ Success for a capped batch — never a retry,
         * which would be a chain by another name. ⚠ Success too for no model / no consent / paused
         * (an empty report): not a state that passes, so retrying would only wake the device. Only a
         * deferral — the battery went low between WorkManager's check and the pass's own — is
         * retried, a bounded number of times. `null` is another pass running, which is indexing.
         */
        internal fun outcomeOf(result: IndexPassResult?, runAttemptCount: Int): Result = when {
            result == null -> Result.success()
            result.deferred -> retryOrLeave(runAttemptCount)
            else -> Result.success()
        }

        internal fun retryOrLeave(runAttemptCount: Int): Result =
            if (runAttemptCount + 1 < MAX_DEFERRED_ATTEMPTS) Result.retry() else Result.success()
    }
}
