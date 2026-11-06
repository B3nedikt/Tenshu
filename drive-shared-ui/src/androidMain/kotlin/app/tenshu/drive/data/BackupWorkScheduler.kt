package app.tenshu.drive.data

import app.tenshu.ingest.backup.BackupPreferences
import app.tenshu.ingest.backup.SyncedFolderController
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import app.tenshu.ingest.backup.BackupJobStart
import app.tenshu.ingest.backup.backupJobStart

/**
 * Phase 2c — keeps the [BackupWorker] WorkManager jobs in sync with backup policy.
 * Observes the master toggle + the Wi-Fi/charging constraints and, on any change,
 * (re)enqueues the worker (so changed constraints re-apply) or cancels it when
 * backup is off. App-scoped, process-lifetime; started once from `DriveApplication`
 * alongside `SyncedFolderController.start()` — so background backup is armed even if
 * the user never opens the Synced Folders screen.
 *
 * This is the *scheduling* half; the *draining* half is the worker → controller.
 * The in-process [SyncedFolderController] drain (app-alive) and these OS-scheduled
 * jobs (app-closed) are complementary — the controller's [SyncedFolderController]
 * mutex serialises any overlap.
 */
class BackupWorkScheduler(
    private val context: Context,
    private val preferences: BackupPreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var started = false

    /** The (wifiOnly, chargingOnly) this process last armed the jobs with; `null` = not armed. */
    private var armedWith: Pair<Boolean, Boolean>? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            // Re-evaluate whenever the toggle OR a constraint changes (constraints are
            // baked into the WorkRequest at enqueue, so a policy change must re-enqueue).
            combine(
                preferences.observeEnabled(),
                preferences.observeWifiOnly(),
                preferences.observeChargingOnly(),
            ) { enabled, wifiOnly, chargingOnly -> Triple(enabled, wifiOnly, chargingOnly) }
                .distinctUntilChanged()
                .collect {
                    // ⚠⚠ The emission is only a "something moved" signal; the values come from the
                    // one-shot reads. `observeEnabled()` seeds `false` ahead of the stored row, and
                    // acting on that seed cancelled every backup job at each process start —
                    // including the job WorkManager had just started this process to run.
                    // ⚠⚠ Tri-state: only a STORED false cancels. `readEnabled()` answers "could not
                    // read" — Control dead or slow at a cold start — with `false`, which cancelled
                    // the periodic, content and continuation jobs together. Unknown changes nothing;
                    // the next emission (identity resolving, the row arriving) asks again.
                    when (backupJobStart(preferences.readEnabledOrNull())) {
                        BackupJobStart.RETRY -> return@collect
                        BackupJobStart.STOP_ALL -> {
                            armedWith = null
                            BackupWorker.cancel(context)
                            return@collect
                        }
                        BackupJobStart.RUN -> Unit
                    }
                    val policy = preferences.readWifiOnly() to preferences.readChargingOnly()
                    // Replace the content trigger only when its constraints are actually stale.
                    // ⚠ Never on the first arm of a process — see `BackupWorker.enqueue`.
                    val replace = armedWith != null && armedWith != policy
                    armedWith = policy
                    BackupWorker.enqueue(context, preferences, replaceContentTrigger = replace)
                }
        }
    }
}
