package app.tenshu.control.indexing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.tenshu.control.inference.ArrivalJob
import app.tenshu.control.inference.NEW_ARRIVALS_DEBOUNCE_MS
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tenshu.inference.proxy.IndexPassResult
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * IN-D6a's Android job: one debounced one-time WorkManager job, battery-not-low alone, no
 * foreground service, and never a chain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NewArrivalsIndexWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    private fun pending(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(NewArrivalsIndexWorker.UNIQUE_WORK_NAME).get()
            .filter { !it.state.isFinished }

    @Test
    fun anArrivalEnqueuesOneDelayedJob_andABurstIsStillOne() {
        NewArrivalsIndexWorker.enqueue(context)
        assertEquals(1, pending().size)
        assertEquals(WorkInfo.State.ENQUEUED, pending().single().state)

        repeat(20) { NewArrivalsIndexWorker.enqueue(context) }
        assertEquals(1, pending().size, "REPLACE: a burst of re-arms is one job")
    }

    @Test
    fun theRequestIsBatteryNotLowAloneTwoMinutesOut_andNotExpedited() {
        val request = NewArrivalsIndexWorker.request()
        val constraints = request.workSpec.constraints
        assertTrue(constraints.requiresBatteryNotLow())
        assertFalse(constraints.requiresCharging(), "the 12 h job keeps the charger, not this one")
        assertFalse(constraints.requiresDeviceIdle(), "nor the idle")
        assertEquals(NetworkType.NOT_REQUIRED, constraints.requiredNetworkType)
        assertEquals(NEW_ARRIVALS_DEBOUNCE_MS, request.workSpec.initialDelay)
        assertFalse(request.workSpec.expedited)
        assertFalse(request.workSpec.isPeriodic)
    }

    @Test
    fun aCappedBatchSucceeds_neverRetries() {
        assertIs<ListenableWorker.Result.Success>(
            NewArrivalsIndexWorker.outcomeOf(IndexPassResult(embedded = 50, batchCapReached = true), runAttemptCount = 0),
        )
        assertIs<ListenableWorker.Result.Success>(NewArrivalsIndexWorker.outcomeOf(null, 0), "another pass is indexing")
    }

    @Test
    fun aDeferredBatchIsRetriedALittleThenLeft() {
        assertIs<ListenableWorker.Result.Retry>(NewArrivalsIndexWorker.outcomeOf(IndexPassResult(deferred = true), 0))
        assertIs<ListenableWorker.Result.Success>(
            NewArrivalsIndexWorker.outcomeOf(IndexPassResult(deferred = true), NewArrivalsIndexWorker.MAX_DEFERRED_ATTEMPTS - 1),
        )
        assertIs<ListenableWorker.Result.Success>(
            NewArrivalsIndexWorker.outcomeOf(IndexPassResult(), 0),
            "no model / no consent is not retried: it does not pass on its own",
        )
    }

    /** ⚠⚠ A cold wake that finds no proxy session retries a bounded number of times — each is a cold start. */
    @Test
    fun aColdWakeWithoutAContextIsRetriedBoundedly() {
        assertIs<ListenableWorker.Result.Retry>(NewArrivalsIndexWorker.retryOrLeave(0))
        assertIs<ListenableWorker.Result.Success>(NewArrivalsIndexWorker.retryOrLeave(NewArrivalsIndexWorker.MAX_DEFERRED_ATTEMPTS - 1))
        assertIs<ListenableWorker.Result.Success>(NewArrivalsIndexWorker.retryOrLeave(50))
    }

    @Test
    fun aRunningJobReadsAsRunning_soItIsNeverReplaced() {
        assertEquals(ArrivalJob.RUNNING, NewArrivalsIndexWorker.jobStateOf(listOf(WorkInfo.State.RUNNING)))
        assertEquals(ArrivalJob.RUNNING, NewArrivalsIndexWorker.jobStateOf(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.RUNNING)))
        assertEquals(ArrivalJob.WAITING, NewArrivalsIndexWorker.jobStateOf(listOf(WorkInfo.State.ENQUEUED)))
        assertEquals(ArrivalJob.NONE, NewArrivalsIndexWorker.jobStateOf(listOf(WorkInfo.State.SUCCEEDED)))
        assertEquals(ArrivalJob.NONE, NewArrivalsIndexWorker.jobStateOf(emptyList()))
    }

    @Test
    fun anEnqueuedJobReadsAsWaiting() = kotlinx.coroutines.test.runTest {
        NewArrivalsIndexWorker.enqueue(context)
        assertEquals(ArrivalJob.WAITING, NewArrivalsIndexWorker.jobState(context))
    }

    /** ⚠⚠ A plain worker: no foreground info, unlike *Index now*'s. */
    @Test
    fun itIsNotAForegroundWorker() {
        val method = NewArrivalsIndexWorker::class.java.declaredMethods.map { it.name }
        assertFalse(method.any { it.startsWith("getForegroundInfo") }, "a short batch needs no foreground notification")
    }
}
