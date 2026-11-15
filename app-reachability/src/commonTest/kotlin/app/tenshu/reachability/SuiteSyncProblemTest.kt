package app.tenshu.reachability

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.storage.common.RealmStorageState
import org.tenshu.sync.SyncHealthStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SuiteSyncProblemTest {

    private val ok = RealmStorageState.Ok

    /**
     * ⚠⚠ Every value `SyncHealthStatus` can publish, decided — the gap the doc's review found (a
     * value read as "not online" turns Drive's `deferred` into *Offline* in Photos).
     */
    @Test
    fun everyConnectivityValue_isDecided() {
        val expected = mapOf(
            SyncHealthStatus.CONNECTIVITY_ONLINE to SuiteSyncProblem.None,
            SyncHealthStatus.CONNECTIVITY_OFFLINE to SuiteSyncProblem.Offline,
            SyncHealthStatus.CONNECTIVITY_LOCKED to SuiteSyncProblem.Locked,
            SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED to SuiteSyncProblem.DeviceUnrecognized,
            SyncHealthStatus.CONNECTIVITY_NEEDS_ATTENTION to SuiteSyncProblem.NeedsAttention,
            SyncHealthStatus.CONNECTIVITY_STORAGE_FULL to SuiteSyncProblem.StorageFull(null),
            // Drive's file transfers — Drive reports them; no other app has anything to say.
            SyncHealthStatus.CONNECTIVITY_DEFERRED to SuiteSyncProblem.None,
            SyncHealthStatus.CONNECTIVITY_RELAY_RETRYING to SuiteSyncProblem.None,
        )
        for ((value, problem) in expected) {
            assertEquals(problem, evaluateSuiteSyncProblem(null, value, ok), value)
        }
        // A newer value than this build knows, and a value not yet known, are no problem.
        assertEquals(SuiteSyncProblem.None, evaluateSuiteSyncProblem(null, "something_new", ok))
        assertEquals(SuiteSyncProblem.None, evaluateSuiteSyncProblem(null, null, ok))
    }

    /** Drive's order, rank by rank (`SyncBanner.evaluate`). */
    @Test
    fun precedence_followsDrive() {
        val full = RealmStorageState.Full(usedBytes = 10, limitBytes = 10)
        val fenced = UnreachableReason.Fenced(update = null, serviceIds = listOf("s"))
        // Control trouble first: nothing else can be trusted while it is not answering.
        assertEquals(
            SuiteSyncProblem.Control(UnreachableReason.Transport),
            evaluateSuiteSyncProblem(UnreachableReason.Transport, SyncHealthStatus.CONNECTIVITY_LOCKED, full),
        )
        // Locked over device-unrecognised over the fence.
        assertEquals(
            SuiteSyncProblem.Locked,
            evaluateSuiteSyncProblem(fenced, SyncHealthStatus.CONNECTIVITY_LOCKED, full),
        )
        assertEquals(
            SuiteSyncProblem.DeviceUnrecognized,
            evaluateSuiteSyncProblem(fenced, SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED, full),
        )
        // The fence over storage.
        assertEquals(SuiteSyncProblem.Control(fenced), evaluateSuiteSyncProblem(fenced, null, full))
        // No plan over full, full over needs-attention, needs-attention over offline.
        assertEquals(SuiteSyncProblem.NoPlan, evaluateSuiteSyncProblem(null, null, RealmStorageState.NoPlan))
        assertEquals(
            SuiteSyncProblem.StorageFull(full),
            evaluateSuiteSyncProblem(null, SyncHealthStatus.CONNECTIVITY_NEEDS_ATTENTION, full),
        )
        assertEquals(
            SuiteSyncProblem.NoPlan,
            evaluateSuiteSyncProblem(null, SyncHealthStatus.CONNECTIVITY_OFFLINE, RealmStorageState.NoPlan),
        )
    }

    @Test
    fun offline_waitsBeforeItIsSaid_andOtherValuesPassAtOnce() = runTest {
        val source = MutableSharedFlow<String?>()
        val seen = mutableListOf<String?>()
        val job = launch { source.withOfflineDelay(delayMs = 1_000).toList(seen) }
        runCurrent()

        source.emit(SyncHealthStatus.CONNECTIVITY_OFFLINE)
        runCurrent()
        assertEquals(listOf<String?>(null), seen, "not said at once")
        // Recovered before the delay ran out: offline is never said.
        advanceTimeBy(500)
        source.emit(SyncHealthStatus.CONNECTIVITY_ONLINE)
        runCurrent()
        assertEquals(listOf(null, SyncHealthStatus.CONNECTIVITY_ONLINE), seen)

        source.emit(SyncHealthStatus.CONNECTIVITY_OFFLINE)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(SyncHealthStatus.CONNECTIVITY_OFFLINE, seen.last(), "said once it lasted")

        // ⚠⚠ The row is rewritten with the same value on every tick: a repeat must neither restart
        // the wait nor take a shown strip away.
        source.emit(SyncHealthStatus.CONNECTIVITY_ONLINE)
        runCurrent()
        source.emit(SyncHealthStatus.CONNECTIVITY_OFFLINE)
        advanceTimeBy(600)
        source.emit(SyncHealthStatus.CONNECTIVITY_OFFLINE)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(SyncHealthStatus.CONNECTIVITY_OFFLINE, seen.last(), "a repeat did not restart the wait")
        source.emit(SyncHealthStatus.CONNECTIVITY_OFFLINE)
        runCurrent()
        assertEquals(SyncHealthStatus.CONNECTIVITY_OFFLINE, seen.last(), "a repeat did not hide it")

        source.emit(SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED)
        runCurrent()
        assertEquals(SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED, seen.last(), "no delay for the rest")
        job.cancel()
    }
}
