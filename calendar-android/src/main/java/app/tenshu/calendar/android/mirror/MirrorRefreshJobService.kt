package app.tenshu.calendar.android.mirror

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import app.tenshu.calendar.data.external.MirrorBackgroundScheduler
import app.tenshu.calendar.data.external.MirrorRefresher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Refreshes this device's *Keep in sync* calendars in the background
 * (`calendar-external-sources.md` CX-1b, §4.4).
 *
 * ⚠⚠ **Calendar's own job, not Control's.** The reader needs `READ_CALENDAR`, which Calendar
 * holds and Control does not (§4.4), and Control's `ScheduleSpec` knows only one-shot and
 * periodic — it cannot wake on a content-provider change, which is the trigger worth having. This
 * makes Calendar the first app to schedule its own work; its refreshes do not appear in Control's
 * schedule registry.
 */
class MirrorRefreshJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartJob(params: JobParameters): Boolean {
        // ⚠ `getOrNull`, the widget's rule: the job can be started before anything else in the
        // process, and an absent graph or refresher means there is nothing to do yet.
        val refresher = GlobalContext.getOrNull()?.getOrNull<MirrorRefresher>()
        if (refresher == null) {
            MirrorJobs.scheduleOnChange(applicationContext)
            return false
        }
        scope.launch {
            try {
                refresher.refreshAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // ⚠ Named, not silent: a mirror that quietly stops refreshing looks exactly like a
                // source that stopped changing.
                Log.w(TAG, "Mirror refresh failed; the next change or the daily run retries", e)
            } finally {
                // ⚠⚠ A content-trigger job is one-shot: it must re-arm itself on every run, or
                // the mirror refreshes once and then never again.
                MirrorJobs.scheduleOnChange(applicationContext)
                jobFinished(params, false)
            }
        }
        return true
    }

    /** ⚠ `true`: the OS stopped us mid-refresh, and the work is still owed. */
    override fun onStopJob(params: JobParameters): Boolean = true

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "CalendarMirrors"
    }
}

/**
 * The two jobs that run [MirrorRefreshJobService].
 *
 * - **On change** — a content-URI trigger on the calendar provider: the OS sync adapter writes
 *   the provider when the server changes, so a mirror refreshes when there is something to
 *   refresh, not on the hour. Batched (30 s after the last change, at most 10 min late), and
 *   one-shot by the platform's design — re-armed on every run.
 * - **Daily** — persisted, so it survives a reboot (content-trigger jobs cannot be persisted), and
 *   it re-arms the change job too.
 *
 * ⚠ Both cost nothing without mirrors: the refresher finds no bound mirror and returns before
 * reading anything.
 */
object MirrorJobs : MirrorBackgroundScheduler {

    const val ON_CHANGE_JOB_ID = 0x7CA10001
    const val DAILY_JOB_ID = 0x7CA10002

    private lateinit var appContext: Context

    /** Arms both jobs; a no-op without `READ_CALENDAR`, which the refresh could not use anyway. */
    fun schedule(context: Context) {
        appContext = context.applicationContext
        if (!canRead(context)) return
        // ⚠⚠ Only when none is armed: `JobScheduler.schedule` with the id of a job that is RUNNING
        // stops it — and on a cold start it is exactly that job that started the process, so
        // re-arming from `Application.onCreate` would drop the change that woke us. The job re-arms
        // itself at the end of each run.
        if (scheduler(context).getPendingJob(ON_CHANGE_JOB_ID) == null) scheduleOnChange(context)
        scheduleDaily(context)
    }

    override fun ensureScheduled() {
        if (::appContext.isInitialized) schedule(appContext)
    }

    fun scheduleOnChange(context: Context) {
        if (!canRead(context)) return
        val job = JobInfo.Builder(ON_CHANGE_JOB_ID, component(context))
            .addTriggerContentUri(descendants(CalendarContract.Events.CONTENT_URI))
            .addTriggerContentUri(descendants(CalendarContract.Reminders.CONTENT_URI))
            .addTriggerContentUri(descendants(CalendarContract.Calendars.CONTENT_URI))
            .setTriggerContentUpdateDelay(30_000L)
            .setTriggerContentMaxDelay(10 * 60_000L)
            .build()
        scheduler(context).schedule(job)
    }

    private fun scheduleDaily(context: Context) {
        // ⚠ Not re-scheduled when already pending: rescheduling a periodic job restarts its
        // period, and a launch every few hours would push the daily run out forever.
        if (scheduler(context).getPendingJob(DAILY_JOB_ID) != null) return
        val job = JobInfo.Builder(DAILY_JOB_ID, component(context))
            .setPeriodic(24 * 60 * 60_000L)
            .setPersisted(true)
            .build()
        scheduler(context).schedule(job)
    }

    private fun descendants(uri: android.net.Uri) =
        JobInfo.TriggerContentUri(uri, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS)

    private fun canRead(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun component(context: Context) = ComponentName(context, MirrorRefreshJobService::class.java)

    private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)
}
