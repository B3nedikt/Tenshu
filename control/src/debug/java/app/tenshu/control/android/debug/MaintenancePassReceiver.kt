package app.tenshu.control.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.tenshu.sync.BackfillContentDeclarations
import org.tenshu.sync.proxy.WorkManagerScheduler

/**
 * ⚠⚠ **Debug builds only — it lives in `src/debug/` and therefore cannot exist in a release
 * APK at all.** That is deliberately stronger than a `BuildConfig.DEBUG` branch: there is no
 * release code path to reason about, and no receiver in the merged release manifest.
 *
 * ## Why this exists
 *
 * `BackfillContentDeclarations` is a periodic **idle + charging + unmetered** op, which on Android
 * is driven by WorkManager. That combination is deliberately unsummonable — and it makes the pass
 * effectively untestable on a device:
 *
 * - ⚠⚠ Keeping the screen on to stop the process being reaped **guarantees the device is never
 *   idle**, so the constraint can never be met.
 * - `cmd jobscheduler run -f` only runs work WorkManager already considers due, so it cannot
 *   summon a once-a-day item.
 * - The in-process `ScheduleDrainLoop` is a per-process tick accumulator with no wall clock, so a
 *   24 h period needs 24 h of *continuous uptime* (`SliceSnapshotSchedule`'s KDoc says as much).
 *
 * The pass's numbers are the evidence `content-plane-refcount.md` §9.10 asks for, so "run it and
 * read the numbers" has to be a thing a person can actually do.
 *
 * ## What it does and does not do
 *
 * ⚠ It enqueues the **same op the schedule names**, as a one-shot WorkManager job
 * (`WorkManagerScheduler.enqueueOneShotOp`), so there is exactly one code path and this cannot drift
 * from what production runs. It needs only a connection, not idle + charging, and leaves the
 * periodic schedule alone. The broadcast itself starts Control if it is not running; the job then
 * holds the process and the network for the pass.
 *
 * ⚠ On a realm this build created, recovered or joined the pass reports *"skipped: already
 * backfilled"*: such a realm is stamped described-from-genesis, so only a realm from before the
 * declaration seam does any work here.
 *
 * ⚠⚠ It takes **no parameters** — deliberately. A generic "dispatch this op id" receiver would be a
 * far wider surface for one pass's sake, and would need its own `HeadlessSafe` gate to be safe.
 * This can start one maintenance pass and nothing else.
 *
 * ⚠ The pass it starts **deletes nothing**: the relay's own sweep is the only thing that frees
 * relay bytes (`content-plane-cr2-b6-spec.md` §2). It still fetches one manifest per live content
 * object, which is why this is not a release affordance.
 *
 * ```
 * adb shell am broadcast -a app.tenshu.control.debug.RUN_CONTENT_MAINTENANCE \
 *     -n app.tenshu.control.android/app.tenshu.control.android.debug.MaintenancePassReceiver
 * adb logcat | grep ContentDeclarationBackfill
 * ```
 */
class MaintenancePassReceiver : BroadcastReceiver(), KoinComponent {

    private val workManager: WorkManagerScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        // ⚠⚠ Under a WorkManager job, never in a detached scope
        // (`control-background-work-off-fgs.md` §5.6). A detached pass has a network only while
        // something else keeps Control in the foreground, so under Doze it loses the network
        // partway through (§3.3). A regular job is held for as long as the op runs, and
        // `TenshuOperationWorker` dispatches the op the schedule names — the same path, start to end,
        // as a scheduled pass.
        println("MaintenancePassReceiver -> enqueued the content-maintenance pass as a one-shot job")
        workManager.enqueueOneShotOp(BackfillContentDeclarations.ID, uniqueName = DEBUG_WORK_NAME)
    }

    private companion object {
        /** `KEEP`: a second broadcast while one is queued or running is the same request. */
        const val DEBUG_WORK_NAME = "debug-content-maintenance"
    }
}
