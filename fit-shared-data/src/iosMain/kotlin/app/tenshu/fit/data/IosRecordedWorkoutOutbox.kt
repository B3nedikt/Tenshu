package app.tenshu.fit.data

import app.tenshu.outbox.NsFileOutboxStore
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

/**
 * The iOS [RecordedWorkoutOutbox] (R-iOS-3, fit-app.md §11.3) — the durable recorder journal
 * rooted in the app's Application Support container, the iOS twin of Android's
 * `filesDir/workout_outbox` and desktop's data-dir binding. [EpcFitClient] passes it to
 * [FitProjectionRepository] so a finished recording is journalled *before* the `recordWorkout`
 * op — surviving process death and the iOS Control SIGKILL race (§9.16) that a Finish-time write
 * can otherwise lose. Replay is the shared `drainPendingWorkouts()` (on launch via
 * `HealthImportBootstrap`, and on the reachability recovery when Control comes back, §9.14).
 *
 * Application Support (not Caches) so iOS never reclaims a pending journal under storage
 * pressure; [NsFileOutboxStore] creates the directory chain on first write.
 */
fun iosRecordedWorkoutOutbox(subdir: String = "workout_outbox"): RecordedWorkoutOutbox {
    val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .filterIsInstance<String>()
        .firstOrNull()
        ?: NSTemporaryDirectory()
    return RecordedWorkoutOutbox(NsFileOutboxStore("$base/$subdir"))
}
