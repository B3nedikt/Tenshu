package app.tenshu.drive.data

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.tenshu.ingest.backup.BACKUP_JOB_BUDGET
import app.tenshu.ingest.backup.BackgroundDrainResult
import app.tenshu.ingest.backup.BackupJobFollowUp
import app.tenshu.ingest.backup.BackupJobStart
import app.tenshu.ingest.backup.backupJobStart
import app.tenshu.ingest.backup.BackupPreferences
import app.tenshu.ingest.backup.SyncedFolderController
import app.tenshu.ingest.backup.backupJobConstraints
import app.tenshu.ingest.backup.backupJobFollowUp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import kotlin.time.TimeSource

/**
 * **Automatic** Synced Folders backup on Android (`drive-synced-folders-phase2.md` §4.1 / §4.1a):
 * WorkManager jobs, and ⚠⚠ **no foreground service**. Play's `dataSync` policy names "daily auto
 * backup of all new photos" as a use a foreground service may *not* carry, and Android 15 caps
 * `dataSync` at 6 h a day besides. The foreground service is kept for the user's own *Back up now*
 * only (`SyncedFolderServiceLauncher`); nothing here calls `setForeground`, which would be a
 * foreground service again by another name.
 *
 * The worker runs **in the Drive process** (gallery / SAF enumeration needs Drive's `READ_MEDIA_*` /
 * tree grant — it can't move to Control, §3.3) and drives Drive's own drain
 * ([SyncedFolderController.drainInBackground]), which IPC-uploads through the existing data plane.
 *
 * Three unique jobs, all this class:
 * - **Content-URI trigger** ([CONTENT_WORK]) — a one-shot keyed on the `MediaStore` collections, so
 *   a new photo/video wakes Drive promptly. Content triggers fire once, so every run **re-arms** it.
 * - **Periodic floor** ([PERIODIC_WORK]) — every few hours, the safety net for anything the content
 *   trigger cannot see (SAF document trees post no `MediaStore` event) or missed.
 * - **Continuation** ([CONTINUE_WORK]) — enqueued when a run spends its [BACKUP_JOB_BUDGET] with
 *   files left, or is stopped by the OS. ⚠ Not a `Result.retry()` of the run itself: a retried
 *   content-trigger job keeps its trigger and would wait for the *next* photo before continuing.
 *
 * Policy (Wi-Fi-only / charging) is applied as WorkManager `Constraints` ([backupJobConstraints]) so
 * off-policy states don't even cost a wake; the *authoritative* enforcement is still the drain's own
 * gate (`evaluateBackupGate`), so a relaxed/early wake just pauses.
 *
 * ⚠ Resumability is the drain's, not this class's: the cursor advances only past a file Control
 * accepted, so a run that yields or is stopped re-offers exactly what it did not finish. The budget
 * yields at a file boundary because an OS cancel mid-file is the one case that is not free (§4.1b).
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val controller: SyncedFolderController by inject()
    private val preferences: BackupPreferences by inject()

    override suspend fun doWork(): Result {
        // Backup was turned off while this was queued → stop every job, succeed.
        // ⚠ A one-shot read, never `observeEnabled().first()`: that flow seeds a `false`
        // ahead of the stored row for the benefit of screens, so `first()` would report
        // "disabled" on every wake and this worker would cancel *itself* — leaving background
        // backup dead, re-armed by each Drive process start only for the first run to kill it.
        // ⚠⚠ Tri-state, never `readEnabled()`: that answers "could not read" (Control dead or slow
        // at a cold start, identity unresolved) with the default `false`, which would cancel every
        // backup job. Only a STORED false turns backup off; unknown retries and cancels
        // nothing — this request stays queued, so a content trigger stays armed.
        when (backupJobStart(preferences.readEnabledOrNull())) {
            BackupJobStart.STOP_ALL -> {
                cancel(applicationContext)
                return Result.success()
            }
            BackupJobStart.RETRY -> return Result.retry()
            BackupJobStart.RUN -> Unit
        }
        val deadline = TimeSource.Monotonic.markNow() + BACKUP_JOB_BUDGET
        val result = try {
            controller.drainInBackground(shouldYield = { deadline.hasPassedNow() })
        } catch (stopped: CancellationException) {
            // The OS stopped the job: a constraint went away (Wi-Fi, charger), the 10-minute limit,
            // a quota. WorkManager will run THIS request again, but a content-trigger request
            // re-runs only on the next media change — so the files this run did not reach get a
            // continuation of their own, waiting on nothing but the policy.
            // ⚠ Not when the app cancelled it — backup switched off, or a policy change superseding the
            // request — or turning backup off would queue the very run it was meant to stop.
            if (stopReason != WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
                withContext(NonCancellable) {
                    // Unknown is not "off": queue it, and the continuation asks again.
                    if (preferences.readEnabledOrNull() != false) enqueueContinuation(applicationContext, preferences)
                }
            }
            throw stopped
        } catch (error: Throwable) {
            // A best-effort background job must not crash Drive; ask for a retry with backoff.
            println("BackupWorker: drain failed, will retry: $error")
            BackgroundDrainResult.NOT_READY
        }
        val followUp = backupJobFollowUp(result)
        if (followUp == BackupJobFollowUp.CONTINUE) enqueueContinuation(applicationContext, preferences)
        // ⚠⚠ Never REPLACE from inside a run: when this run IS the content-trigger request, REPLACE
        // cancels the running work it replaces, so every content-triggered run would end
        // "cancelled" (stop reason 1). Appending queues the new trigger behind this run; any other
        // run only makes sure one exists, without touching it.
        // ⚠ "Am I the content request?" is asked of WorkManager by id, not by a tag: a request
        // enqueued by an older build carries no tag, and reading it as "not me" would make it KEEP
        // itself and finish with no trigger left armed.
        val isContentRequest = WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWorkFlow(CONTENT_WORK).first()
            .any { it.id == id }
        reArmContentTrigger(
            applicationContext,
            preferences,
            if (isContentRequest) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
        )
        return if (followUp == BackupJobFollowUp.RETRY) Result.retry() else Result.success()
    }

    companion object {
        const val PERIODIC_WORK = "tenshu.drive.backup.periodic"
        const val CONTENT_WORK = "tenshu.drive.backup.content"
        const val CONTINUE_WORK = "tenshu.drive.backup.continue"

        private const val PERIODIC_HOURS = 6L

        /**
         * (Re)enqueue the periodic floor + arm the content trigger with the current
         * policy constraints. Idempotent — `UPDATE`/`REPLACE` re-apply changed policy.
         */
        suspend fun enqueue(
            context: Context,
            preferences: BackupPreferences,
            /**
             * Replace an existing content-trigger request (its constraints are stale) rather than
             * keep it. ⚠ `false` on the first arm of a process: WorkManager may have started this
             * process to RUN that request, and replacing it cancels the run it started.
             */
            replaceContentTrigger: Boolean,
        ) {
            val periodic = PeriodicWorkRequestBuilder<BackupWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
                .setConstraints(buildConstraints(preferences, withContentTrigger = false))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, periodic,
            )
            // REPLACE here, and only here: a live policy change, where the old request carries stale
            // constraints and must go.
            reArmContentTrigger(
                context,
                preferences,
                if (replaceContentTrigger) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            )
        }

        /** Stop every backup job (backup disabled). */
        fun cancel(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(PERIODIC_WORK)
            workManager.cancelUniqueWork(CONTENT_WORK)
            workManager.cancelUniqueWork(CONTINUE_WORK)
        }

        /**
         * One more run, waiting on the policy only. ⚠ `APPEND_OR_REPLACE`, not `KEEP`/`REPLACE`:
         * when the run asking is itself the continuation, `KEEP` would see it still running and
         * drop the request, and `REPLACE` would cancel it. Appending queues the next one behind it.
         */
        private suspend fun enqueueContinuation(context: Context, preferences: BackupPreferences) {
            val work = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(buildConstraints(preferences, withContentTrigger = false))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                CONTINUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, work,
            )
        }

        private suspend fun reArmContentTrigger(
            context: Context,
            preferences: BackupPreferences,
            policy: ExistingWorkPolicy,
        ) {
            val work = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(buildConstraints(preferences, withContentTrigger = true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(CONTENT_WORK, policy, work)
        }

        private suspend fun buildConstraints(
            preferences: BackupPreferences,
            withContentTrigger: Boolean,
        ): Constraints {
            val policy = backupJobConstraints(
                wifiOnly = preferences.readWifiOnly(),
                chargingOnly = preferences.readChargingOnly(),
            )
            val builder = Constraints.Builder()
                .setRequiredNetworkType(if (policy.unmeteredNetwork) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(policy.charging)
                .setRequiresBatteryNotLow(policy.batteryNotLow)
                .setRequiresStorageNotLow(policy.storageNotLow)
            if (withContentTrigger) {
                // Content triggers are only valid on one-shot work, never periodic.
                builder.addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                if (preferences.readVideos()) {
                    builder.addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                }
            }
            return builder.build()
        }
    }
}
