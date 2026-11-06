package org.tenshu.sync.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The evaluator and the debounce behind the sync-activity indicator
 * (`ios-sync-activity-indicator.md` §3), on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncActivityTest {

    private val idle = SyncActivity.Idle
    private val catchingUp = SyncActivity.CatchingUp

    @Test
    fun nothingHappening_isIdle() {
        assertEquals(idle, evaluateSyncActivity(catchingUp = false))
    }

    @Test
    fun aPull_isCatchingUp() {
        assertEquals(catchingUp, evaluateSyncActivity(catchingUp = true))
    }

    @Test
    fun bothAtOnce_uploadingWins_andCarriesItsCount() {
        assertEquals(
            SyncActivity.Uploading(SyncActivity.Progress(completed = 3, total = 12)),
            evaluateSyncActivity(catchingUp = true, uploading = true, uploadCompleted = 3, uploadTotal = 12),
        )
    }

    @Test
    fun uploadingWithNoTotal_isTheTail_notZeroOfZero() {
        assertEquals(
            SyncActivity.Uploading(count = null),
            evaluateSyncActivity(catchingUp = false, uploading = true),
        )
    }

    @Test
    fun anUploadEndingMidPull_handsOverToCatchingUp_withNoIdleBetween() = runTest {
        // The evaluator has no memory, so "no Idle between" is a property of what it returns for
        // the two inputs either side of the edge, carried through the debounce unchanged.
        val uploading = evaluateSyncActivity(catchingUp = true, uploading = true, uploadCompleted = 1, uploadTotal = 2)
        val after = evaluateSyncActivity(catchingUp = true, uploading = false)
        val input = MutableStateFlow(uploading)
        val seen = collect(input)

        advanceTimeBy(5_000)
        input.value = after
        advanceTimeBy(5_000)

        assertEquals(listOf(idle, uploading, catchingUp), seen)
    }

    // ---- The upload input (§6a.1) ---------------------------------------------------------------

    private fun run(completed: Int, total: Int) = SyncActivity.Progress(completed, total)

    @Test
    fun noUploadInput_isWhatThePullSays() {
        assertEquals(idle, evaluateSyncActivity(catchingUp = false, upload = UploadInput.None))
        assertEquals(catchingUp, evaluateSyncActivity(catchingUp = true, upload = UploadInput.None))
    }

    @Test
    fun aCountingRun_isUploadingWithItsCount_whateverIsInFlight() {
        val expected = SyncActivity.Uploading(run(3, 12))
        assertEquals(expected, evaluateSyncActivity(false, UploadInput(run(3, 12), transfersInFlight = 0)))
        assertEquals(expected, evaluateSyncActivity(false, UploadInput(run(3, 12), transfersInFlight = 7)))
    }

    @Test
    fun theRunIsDone_andTransfersAreStillInFlight_isTheTail_withNoNumber() {
        assertEquals(
            SyncActivity.Uploading(count = null),
            evaluateSyncActivity(catchingUp = false, upload = UploadInput(run = null, transfersInFlight = 2)),
        )
    }

    @Test
    fun uploadingWinsOverAPull_withACount_andInTheTail() {
        assertEquals(
            SyncActivity.Uploading(run(1, 2)),
            evaluateSyncActivity(catchingUp = true, upload = UploadInput(run(1, 2), transfersInFlight = 0)),
        )
        assertEquals(
            SyncActivity.Uploading(count = null),
            evaluateSyncActivity(catchingUp = true, upload = UploadInput(run = null, transfersInFlight = 1)),
        )
    }

    @Test
    fun aPullAlone_isNotUploading_onceTheLastTransferIsDone() {
        assertEquals(catchingUp, evaluateSyncActivity(catchingUp = true, upload = UploadInput(null, 0)))
    }

    @Test
    fun aRun_itsTail_thenAPullStillRunning_isOneAppearance_withNoIdleAndNoAlternation() = runTest {
        val input = MutableStateFlow(evaluateSyncActivity(true, UploadInput(run(1, 2), 1)))
        val seen = collect(input)
        advanceTimeBy(5_000)

        // The pull flickers under the run: the winner does not change, so nothing is emitted.
        input.value = evaluateSyncActivity(false, UploadInput(run(1, 2), 1))
        advanceTimeBy(300)
        input.value = evaluateSyncActivity(true, UploadInput(run(2, 2), 2))
        advanceTimeBy(5_000)
        input.value = evaluateSyncActivity(true, UploadInput(null, 2)) // the run left BackingUp
        advanceTimeBy(5_000)
        input.value = evaluateSyncActivity(true, UploadInput(null, 0)) // the last chunk landed
        advanceTimeBy(5_000)
        input.value = evaluateSyncActivity(false, UploadInput(null, 0))
        advanceTimeBy(5_000)

        assertEquals(
            listOf(
                idle,
                SyncActivity.Uploading(run(1, 2)),
                SyncActivity.Uploading(run(2, 2)),
                SyncActivity.Uploading(count = null),
                catchingUp,
                idle,
            ),
            seen,
        )
    }

    @Test
    fun aPullEndingMidUpload_changesNothingOnScreen() = runTest {
        val input = MutableStateFlow(evaluateSyncActivity(true, UploadInput(run(4, 9), 0)))
        val seen = collect(input)
        advanceTimeBy(5_000)
        input.value = evaluateSyncActivity(false, UploadInput(run(4, 9), 0))
        advanceTimeBy(5_000)

        assertEquals(listOf(idle, SyncActivity.Uploading(run(4, 9))), seen)
    }

    @Test
    fun theGapBetweenTheRunsLastFileAndItsFirstTransfer_doesNotBlink() = runTest {
        // A run can leave BackingUp a moment before the count of transfers is read as non-zero.
        val input = MutableStateFlow(evaluateSyncActivity(false, UploadInput(run(2, 2), 0)))
        val seen = collect(input)
        advanceTimeBy(5_000)
        input.value = evaluateSyncActivity(false, UploadInput(null, 0))
        advanceTimeBy(200)
        input.value = evaluateSyncActivity(false, UploadInput(null, 3))
        advanceTimeBy(5_000)

        assertEquals(
            listOf(idle, SyncActivity.Uploading(run(2, 2)), SyncActivity.Uploading(count = null)),
            seen,
        )
    }

    @Test
    fun twoHundredMillisecondsOfActivity_showsNothing() = runTest {
        val input = MutableStateFlow<SyncActivity>(idle)
        val seen = collect(input)

        input.value = catchingUp
        advanceTimeBy(200)
        input.value = idle
        advanceTimeBy(10_000)

        assertEquals(listOf<SyncActivity>(idle), seen)
    }

    @Test
    fun activityShows_onlyAfterItHasLastedTheShowDelay() = runTest {
        val input = MutableStateFlow<SyncActivity>(idle)
        val seen = collect(input)

        input.value = catchingUp
        advanceTimeBy(699)
        runCurrent()
        assertEquals(listOf<SyncActivity>(idle), seen, "not yet")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(idle, catchingUp), seen)
    }

    @Test
    fun onceShown_itStaysForTheMinimum_evenIfTheWorkEndsAtOnce() = runTest {
        val input = MutableStateFlow<SyncActivity>(idle)
        val seen = collect(input)

        input.value = catchingUp
        advanceTimeBy(701) // shown at 700
        input.value = idle
        advanceTimeBy(1_400) // 2 101: idle for 1.4 s, but shown for only 1.401 s
        runCurrent()
        assertEquals(listOf(idle, catchingUp), seen, "held for the minimum")

        advanceTimeBy(200) // 2 301: past shownAt + 1.5 s
        runCurrent()
        assertEquals(listOf(idle, catchingUp, idle), seen)
    }

    @Test
    fun aLongShownIndicator_hidesOneSecondAfterTheWorkEnds() = runTest {
        val input = MutableStateFlow<SyncActivity>(idle)
        val seen = collect(input)

        input.value = catchingUp
        advanceTimeBy(10_000)
        input.value = idle
        advanceTimeBy(999)
        runCurrent()
        assertEquals(listOf(idle, catchingUp), seen, "still up inside the idle delay")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(idle, catchingUp, idle), seen)
    }

    @Test
    fun theGapBetweenTwoTicksOfOneCatchUp_doesNotBlink() = runTest {
        val input = MutableStateFlow<SyncActivity>(idle)
        val seen = collect(input)

        input.value = catchingUp
        advanceTimeBy(5_000)
        input.value = idle
        advanceTimeBy(400)
        input.value = catchingUp
        advanceTimeBy(5_000)

        assertEquals(listOf(idle, catchingUp), seen, "one appearance, no hide in the gap")
    }

    @Test
    fun whenTheSourceEnds_itSaysIdleAndCompletes_holdingNoLastValue() = runTest {
        val out = flow {
            emit(catchingUp)
            kotlinx.coroutines.delay(5_000)
        }.debounceSyncActivity().toList()

        assertEquals(listOf(idle, catchingUp, idle), out)
    }

    private fun TestScope.collect(input: MutableStateFlow<out SyncActivity>): List<SyncActivity> {
        val seen = mutableListOf<SyncActivity>()
        backgroundScope.launch { input.debounceSyncActivity().collect { seen += it } }
        runCurrent()
        return seen
    }
}
