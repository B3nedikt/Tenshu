package app.tenshu.timeline.android

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import app.tenshu.timeline.capture.TimelineCaptureControl
import java.util.concurrent.TimeUnit

/**
 * Keeps the [CaptureWatchdogWorker] enqueued while capture is opted in, cancelled when it is off —
 * mirroring Drive's `BackupWorkScheduler`. Started once from `TimelineApplication`, so the watchdog
 * is armed even if the user never returns to the Settings screen.
 *
 * The worker itself is a no-op when the service is healthy, so an always-on periodic job while
 * capturing is cheap; gating enqueue on the opt-in just avoids scheduling work for users who never
 * turned capture on.
 */
class CaptureWatchdogScheduler(
    private val context: Context,
    private val control: TimelineCaptureControl,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            control.enabled.collect { enabled ->
                if (enabled) enqueue() else WorkManager.getInstance(context).cancelUniqueWork(WORK)
            }
        }
    }

    private fun enqueue() {
        val request = PeriodicWorkRequestBuilder<CaptureWatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val WORK = "tenshu.timeline.capture.watchdog"
    }
}
