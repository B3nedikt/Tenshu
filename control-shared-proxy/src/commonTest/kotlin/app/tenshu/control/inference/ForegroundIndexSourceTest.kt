package app.tenshu.control.inference

import app.tenshu.control.ui.screens.models.IndexStartOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.inference.IndexPassRefused
import org.tenshu.inference.proxy.ContinuationStanding
import org.tenshu.inference.proxy.DeviceIndexingState
import org.tenshu.inference.proxy.ForegroundIndexing
import org.tenshu.inference.proxy.ForegroundIndexingConditions
import org.tenshu.inference.proxy.ForegroundIndexingState
import org.tenshu.inference.proxy.IndexNowAnswer
import org.tenshu.inference.proxy.IndexProgressHolder
import org.tenshu.inference.proxy.IndexingBlock
import org.tenshu.inference.proxy.PassEnding
import org.tenshu.inference.proxy.PassStanding
import org.tenshu.inference.proxy.StartAnswer
import org.tenshu.inference.proxy.ThermalState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The indexing section's source over one graph's foreground pass (`ios-on-device-inference.md`
 * §14.19.6): the **real** driver for the standing and *Index now*, and the store read again when
 * the pass moves.
 */
class ForegroundIndexSourceTest {

    private var phone = DeviceIndexingState(foreground = true, ThermalState.NOMINAL, lowPowerMode = false, batteryLevel = null, charging = null)
    private val runner = MutableStateFlow<PassStanding>(PassStanding.Idle(null))
    private val cleared = MutableStateFlow(false)
    private val coverage = LastIndexCoverage()
    private val readiness = MutableStateFlow(0)
    private var count: () -> Int? = { 0 }
    private var counts = 0

    private fun kotlinx.coroutines.test.TestScope.source(): ForegroundIndexSource {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val driver = ForegroundIndexing(
            scope = scope,
            conditions = ForegroundIndexingConditions { phone },
            changes = emptyFlow(),
            memoryWarnings = emptyFlow(),
            runnerStanding = { runner },
            // As the op does: the manual bar first.
            startPass = {
                if (org.tenshu.inference.proxy.manualIndexingBlock(phone) != null) StartAnswer.Refused(ForegroundIndexing.NOT_READY) else StartAnswer.Started
            },
            cancelPass = {},
            closeSessions = {},
            clock = { 0L },
        ).also { it.start() }
        return ForegroundIndexSource(
            driver = { driver },
            runnerStanding = { runner },
            progress = HolderIndexProgressSource(IndexProgressHolder()),
            coverage = coverage,
            searchable = {
                counts++
                count()
            },
            clearedByOsUpdate = { cleared },
            alsoWhen = { readiness },
            context = UnconfinedTestDispatcher(testScheduler),
        )
    }

    @Test
    fun aPressThePhoneHoldsBackIsAnsweredWaitingAndTheStandingSaysWhy() = runTest {
        val source = source()
        val seen = mutableListOf<ForegroundIndexStanding>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.standing.collect { seen += it } }
        runCurrent()
        phone = phone.copy(lowPowerMode = true)

        assertEquals(IndexStartOutcome.Waiting, source.indexNow())
        runCurrent()

        assertEquals(listOf(ForegroundIndexStanding.NotAsked, ForegroundIndexStanding.Waiting(ForegroundIndexStanding.Waiting.Why.LOW_POWER_MODE)), seen)
        phone = phone.copy(lowPowerMode = false)
        assertEquals(IndexStartOutcome.Started, source.indexNow())
        runCurrent()
        assertEquals(ForegroundIndexStanding.Asked, seen.last())
    }

    /**
     * ⚠⚠ The update's word stands until a pass has finished in this process, and the store is
     * counted again when the pass moves, when readiness moves (the launch's check), and not per
     * photo. A count that throws is *not known*, never zero.
     */
    @Test
    fun theStoreIsCountedWhenThePassOrReadinessMovesAndTheUpdatesWordEndsWithAFinishedPass() = runTest {
        val source = source()
        val seen = mutableListOf<StoredIndex>()
        count = { null }
        cleared.value = true
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.stored.collect { seen += it } }
        runCurrent()
        assertEquals(StoredIndex(null, clearedByOsUpdate = true), seen.last())

        // The launch's check finished: readiness moved, and the store is known now.
        count = { 0 }
        readiness.value = 1
        runCurrent()
        assertEquals(StoredIndex(0, clearedByOsUpdate = true), seen.last())

        val before = counts
        runner.value = PassStanding.Running(1, manual = true)
        runCurrent()
        assertEquals(before + 1, counts, "a pass that starts is one count")
        count = { 120 }
        runner.value = PassStanding.Idle(PassEnding.Finished(1, org.tenshu.inference.proxy.IndexPassResult(embedded = 120, imagesVisited = 120)))
        coverage.record(total = 120, notOnDevice = 0, ran = true)
        runCurrent()
        assertEquals(StoredIndex(120, clearedByOsUpdate = false), seen.last(), "the update's word outlived a finished pass")

        count = { error("the store refused") }
        readiness.value = 2
        runCurrent()
        assertEquals(StoredIndex(null, clearedByOsUpdate = false), seen.last())
    }

    /**
     * ⚠⚠ The running line's input (§15.7 Q2): `true` only while a launched task carries the pass. A
     * request submitted (accepted or not yet answered) is not, and no continuation is never.
     */
    @Test
    fun continuedIsTrueOnlyWhileALaunchedTaskCarriesThePass() = runTest {
        val claim = MutableStateFlow(ContinuationStanding.NONE)
        fun over(continuation: () -> kotlinx.coroutines.flow.Flow<ContinuationStanding>?) = ForegroundIndexSource(
            driver = { null },
            runnerStanding = { MutableStateFlow<PassStanding>(PassStanding.Idle(null)) },
            progress = HolderIndexProgressSource(IndexProgressHolder()),
            coverage = LastIndexCoverage(),
            searchable = { null },
            clearedByOsUpdate = { MutableStateFlow(false) },
            alsoWhen = { MutableStateFlow(0) },
            continuation = continuation,
            context = UnconfinedTestDispatcher(testScheduler),
        )
        val seen = mutableListOf<Boolean>()
        val watch = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        watch.launch { over { claim }.continued.collect { seen += it } }
        claim.value = ContinuationStanding.SUBMITTED
        runCurrent()
        assertEquals(listOf(false), seen, "a submitted request counted as carrying the pass")
        claim.value = ContinuationStanding.LIVE
        runCurrent()
        assertEquals(listOf(false, true), seen, "a launched task not counted")
        claim.value = ContinuationStanding.NONE
        runCurrent()
        assertEquals(listOf(false, true, false), seen, "a released task still counted")
        watch.cancel()
        // No continuation in this graph (below iOS 26), and the default.
        assertEquals(false, over { null }.continued.first())
        assertEquals(false, over { null }.let { it as OnDeviceIndexSource }.continued.first())
    }

    /**
     * Over a retired graph the providers hand out nothing (`PhotosOnDeviceAi`'s):
     * the standing says *not asked*, *Index now* fails, and the count is not known.
     */
    @Test
    fun overARetiredGraphTheStandingIsNotAskedIndexNowFailsAndTheCountIsNotKnown() = runTest {
        val retired = ForegroundIndexSource(
            driver = { null },
            runnerStanding = { MutableStateFlow<PassStanding>(PassStanding.Idle(null)) },
            progress = HolderIndexProgressSource(IndexProgressHolder()),
            coverage = LastIndexCoverage(),
            searchable = { null },
            clearedByOsUpdate = { MutableStateFlow(false) },
            alsoWhen = { MutableStateFlow(0) },
            context = UnconfinedTestDispatcher(testScheduler),
        )
        assertEquals(ForegroundIndexStanding.NotAsked, retired.standing.first())
        assertEquals(IndexStartOutcome.Failed, retired.indexNow())
        assertEquals(StoredIndex(null, clearedByOsUpdate = false), retired.stored.first())
    }

    @Test
    fun everyStateOfTheDriverAndEveryAnswerOfAPressHasItsValue() {
        val states = mapOf(
            ForegroundIndexingState.NotAsked to ForegroundIndexStanding.NotAsked,
            ForegroundIndexingState.Asked(3) to ForegroundIndexStanding.Asked,
            ForegroundIndexingState.Resuming to ForegroundIndexStanding.Asked,
            ForegroundIndexingState.Waiting(IndexingBlock.TOO_WARM, 0) to ForegroundIndexStanding.Waiting(ForegroundIndexStanding.Waiting.Why.WARM),
            ForegroundIndexingState.Waiting(IndexingBlock.LOW_POWER_MODE, 0) to ForegroundIndexStanding.Waiting(ForegroundIndexStanding.Waiting.Why.LOW_POWER_MODE),
            ForegroundIndexingState.Waiting(IndexingBlock.BATTERY_LOW, 0) to ForegroundIndexStanding.Waiting(ForegroundIndexStanding.Waiting.Why.BATTERY_LOW),
            ForegroundIndexingState.Stopped(ForegroundIndexingState.Stopped.Why.LEFT_PHOTOS) to ForegroundIndexStanding.Stopped(ForegroundIndexStanding.Stopped.Why.LEFT_THE_APP),
            ForegroundIndexingState.Stopped(ForegroundIndexingState.Stopped.Why.LOW_MEMORY) to ForegroundIndexStanding.Stopped(ForegroundIndexStanding.Stopped.Why.LOW_MEMORY),
        )
        states.forEach { (state, expected) -> assertEquals(expected, foregroundIndexStandingOf(state), "$state") }

        val answers = mapOf(
            IndexNowAnswer.Started to IndexStartOutcome.Started,
            IndexNowAnswer.Waiting(IndexingBlock.TOO_WARM) to IndexStartOutcome.Waiting,
            IndexNowAnswer.Refused(IndexPassRefused.Reasons.NO_BACKEND) to IndexStartOutcome.NoModel,
            IndexNowAnswer.Refused(IndexPassRefused.Reasons.NO_CONSENT) to IndexStartOutcome.NoConsent,
            IndexNowAnswer.Refused(IndexPassRefused.Reasons.PAUSED) to IndexStartOutcome.Paused,
            IndexNowAnswer.Refused(IndexPassRefused.Reasons.NOT_READY) to IndexStartOutcome.NotReady,
            IndexNowAnswer.Refused("somethingNew") to IndexStartOutcome.Failed,
            IndexNowAnswer.Failed to IndexStartOutcome.Failed,
        )
        answers.forEach { (answer, expected) -> assertEquals(expected, startOutcomeOf(answer), "$answer") }
    }
}
