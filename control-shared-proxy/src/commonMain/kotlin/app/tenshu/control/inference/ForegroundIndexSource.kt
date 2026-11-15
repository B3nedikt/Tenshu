package app.tenshu.control.inference

import app.tenshu.control.ui.screens.models.IndexStartOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.tenshu.inference.IndexPassRefused
import org.tenshu.inference.proxy.ContinuationStanding
import org.tenshu.inference.proxy.ForegroundIndexing
import org.tenshu.inference.proxy.ForegroundIndexingState
import org.tenshu.inference.proxy.IndexNowAnswer
import org.tenshu.inference.proxy.IndexingBlock
import org.tenshu.inference.proxy.PassStanding
import kotlin.coroutines.CoroutineContext

/**
 * [OnDeviceIndexSource] over one graph's foreground pass (`ios-on-device-inference.md` §14.19.6):
 * [ForegroundIndexing] for *Index now* and for why a pass that was asked for is not running, the
 * runner's holder for the bar, `LastIndexCoverage` for the last pass's reach, and the embedding
 * store for what can be searched.
 *
 * ⚠⚠ **Everything is asked on [context], never on the collector's thread**: the runner's
 * construction opens the stores, and the store's count takes its lock. The view model collects
 * on the main thread (`Main.immediate` on K/N).
 * ⚠ Each input is a provider, asked when the screen first collects: nothing here builds the
 * driver or the runner when the repository is built.
 */
class ForegroundIndexSource(
    /** ⚠ `null` on a retired graph: nothing of it is built for a screen. */
    private val driver: () -> ForegroundIndexing?,
    private val runnerStanding: () -> StateFlow<PassStanding>,
    override val progress: IndexProgressSource,
    override val coverage: IndexCoverageSource,
    /**
     * Photographs with a stored vector, or `null` when that is not known now: the stores are not
     * usable yet (the custody's check has not finished), or the read failed. ⚠ May throw: a throw
     * is `null`.
     */
    private val searchable: suspend () -> Int?,
    /** The custody's own word that the vectors were cleared by an OS update. */
    private val clearedByOsUpdate: () -> StateFlow<Boolean>,
    /**
     * What else moves when the count may have: on iOS readiness's standing, which moves after
     * the launch's check of the stores (before it the count is not known) and when a model
     * comes or goes. ⚠ A provider, like the others.
     */
    private val alsoWhen: () -> Flow<Any?>,
    /**
     * The continued task's standing (`ContinuedIndexing.standings`), or `null` where this graph
     * can make none. ⚠ A provider, like the others; asked when the screen first collects.
     */
    private val continuation: () -> Flow<ContinuationStanding>? = { null },
    private val context: CoroutineContext = Dispatchers.Default,
) : OnDeviceIndexSource {

    /** ⚠⚠ [ContinuationStanding.LIVE] alone: a submitted request may still be refused, and then a leave stops the pass. */
    override val continued: Flow<Boolean> = flow {
        // ⚠ The provider is asked once per collection, so a graph replaced while the screen is up
        // keeps this collector on the old bridge — harmless: the teardown ends its claim, so it
        // reads NONE (*Keep Photos open*), and the next screen collects the new one.
        emitAll(continuation()?.map { it == ContinuationStanding.LIVE } ?: flowOf(false))
    }.distinctUntilChanged().flowOn(context)

    override val standing: Flow<ForegroundIndexStanding> = flow {
        val live: ForegroundIndexing = driver() ?: return@flow emit(ForegroundIndexStanding.NotAsked)
        emitAll(live.standing.map { foregroundIndexStandingOf(it.state) })
    }.distinctUntilChanged().flowOn(context)

    /**
     * ⚠ Read again when a pass starts or ends, when the custody's word moves, and when the
     * coverage does (a finished pass): not per photo, which would take the store's lock beside
     * the pass that writes it. While a pass runs the bar says how far it got.
     */
    override val stored: Flow<StoredIndex> = flow {
        emitAll(
            // ⚠ No `onStart { emit(null) }` in front of the StateFlows: it would say a first
            // *no reach* before the real one, flashing the update's line and counting twice.
            // ⚠ So each input must emit at once, as a StateFlow does: one that stays silent holds
            // the line back.
            combine(runnerStanding(), clearedByOsUpdate(), coverage.coverage, alsoWhen()) { pass, cleared, reach, other ->
                // ⚠ The update's word stands only until a pass has finished in this process.
                Moved(pass is PassStanding.Running, cleared && reach == null, reach, other)
            }
                .distinctUntilChanged()
                .map { moved -> StoredIndex(searchable = countOrNull(), clearedByOsUpdate = moved.cleared) },
        )
    }.distinctUntilChanged().flowOn(context)

    private data class Moved(val running: Boolean, val cleared: Boolean, val reach: IndexCoverage?, val other: Any?)

    private suspend fun countOrNull(): Int? = try {
        searchable()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        println("ForegroundIndexSource -> the index could not be counted: $failure")
        null
    }

    override suspend fun indexNow(): IndexStartOutcome {
        val live: ForegroundIndexing = driver() ?: return IndexStartOutcome.Failed
        return startOutcomeOf(live.indexNow())
    }
}

/** The driver's state in the screen's terms. ⚠ Its own function, as every type test of a value here. */
internal fun foregroundIndexStandingOf(state: ForegroundIndexingState): ForegroundIndexStanding = when (state) {
    ForegroundIndexingState.NotAsked -> ForegroundIndexStanding.NotAsked
    is ForegroundIndexingState.Asked, ForegroundIndexingState.Resuming -> ForegroundIndexStanding.Asked
    is ForegroundIndexingState.Waiting -> when (state.block) {
        IndexingBlock.TOO_WARM -> ForegroundIndexStanding.Waiting(ForegroundIndexStanding.Waiting.Why.WARM)
        IndexingBlock.LOW_POWER_MODE -> ForegroundIndexStanding.Waiting(ForegroundIndexStanding.Waiting.Why.LOW_POWER_MODE)
        IndexingBlock.BATTERY_LOW -> ForegroundIndexStanding.Waiting(ForegroundIndexStanding.Waiting.Why.BATTERY_LOW)
        // ⚠ Never a wait the driver keeps (a press is made in front); said as the leave it is.
        IndexingBlock.NOT_IN_FOREGROUND -> ForegroundIndexStanding.Stopped(ForegroundIndexStanding.Stopped.Why.LEFT_THE_APP)
    }
    is ForegroundIndexingState.Stopped -> when (state.why) {
        ForegroundIndexingState.Stopped.Why.LEFT_PHOTOS -> ForegroundIndexStanding.Stopped(ForegroundIndexStanding.Stopped.Why.LEFT_THE_APP)
        ForegroundIndexingState.Stopped.Why.LOW_MEMORY -> ForegroundIndexStanding.Stopped(ForegroundIndexStanding.Stopped.Why.LOW_MEMORY)
    }
}

/** *Index now*'s typed answer as the screen's outcome: the op's refusals by their reason, as Android's. */
internal fun startOutcomeOf(answer: IndexNowAnswer): IndexStartOutcome = when (answer) {
    IndexNowAnswer.Started -> IndexStartOutcome.Started
    is IndexNowAnswer.Waiting -> IndexStartOutcome.Waiting
    is IndexNowAnswer.Refused -> when (answer.reason) {
        IndexPassRefused.Reasons.NO_BACKEND -> IndexStartOutcome.NoModel
        IndexPassRefused.Reasons.NO_CONSENT -> IndexStartOutcome.NoConsent
        IndexPassRefused.Reasons.PAUSED -> IndexStartOutcome.Paused
        IndexPassRefused.Reasons.NOT_READY -> IndexStartOutcome.NotReady
        else -> IndexStartOutcome.Failed
    }
    IndexNowAnswer.Failed -> IndexStartOutcome.Failed
}
