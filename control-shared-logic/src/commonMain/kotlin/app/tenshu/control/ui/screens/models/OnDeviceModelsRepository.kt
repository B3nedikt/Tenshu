package app.tenshu.control.ui.screens.models

import kotlinx.coroutines.Dispatchers
import app.tenshu.control.inference.ForegroundIndexStanding
import app.tenshu.control.inference.IndexCoverage
import app.tenshu.control.inference.ModelActionResult
import app.tenshu.control.inference.OnDeviceIndexSource
import app.tenshu.control.inference.StoredIndex
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.OnDeviceModelSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.fold
import org.tenshu.core.get
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.ModelUnavailable
import org.tenshu.inference.client.inferenceService
import org.tenshu.inference.deleteModel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where the process that shows the screen is the one that runs the models (Photos on iOS).
 *
 * - `oneDownloadAtATime` is false: the journal keys a download by its entry and the bar by its
 *   model id, so two are told apart.
 * - `indexing` is true (`ios-on-device-inference.md` §14.19.6): the foreground pass is
 *   bound there. ⚠ A repository given no [OnDeviceIndexSource] says false whatever it was given: a
 *   section that can only refuse is not drawn. ⚠ Still behind the build flag: with it off nothing
 *   of this screen is reached.
 */
val OnDeviceModelsCapabilities = ModelsCapabilities(
    cancel = true,
    cellularChoice = true,
    oneDownloadAtATime = false,
    spaceReturns = ModelsCapabilities.SpaceReturns.TOWERS_AT_ONCE,
    // ⚠ Search alone here; a graph says what it bound ([OnDeviceModelsRepository.over]'s
    // `features`): a face switch is offered only beside a face backend, and a switch that changes
    // nothing is not offered.
    features = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
    indexing = true,
    // ⚠ Whether the roster reads is said per read (`null` is *not known*), not here.
    otherDevices = true,
)

/** A device that is offered no model, or has no runtime: no switch would change anything. */
private val NothingToSwitch = OnDeviceModelsCapabilities.copy(features = emptyList(), otherDevices = false, indexing = false)

/** The consent half where there is nothing to consent to: it lists nothing and writes nothing. */
private object NoModelsConsents : ModelsConsents {
    override fun consents(): Flow<List<CapabilityConsent>> = standing(emptyList())
    override suspend fun setRealmConsent(capability: InferenceCapability, allowed: Boolean): Boolean = false
    override suspend fun setDeviceConsent(capability: InferenceCapability, allowed: Boolean): Boolean = false
}

/** One value that stays: for a stream whose contract is that it never completes. */
private fun <T> standing(value: T): Flow<T> = flow {
    emit(value)
    awaitCancellation()
}

/**
 * [ModelsRepository] over the models of **this process** ([OnDeviceModelSource]): a thin adapter
 * (`ios-on-device-inference.md` §14.18.14).
 *
 * ⚠⚠ **Every row, and whether there is a list, is the source's.** This class adds two things
 * and changes nothing else:
 * - [ModelsState.removing], its own removal calls that have not ended. Nothing below publishes
 *   a removal, so the call in flight is all there is to show. It qualifies a row; whether the
 *   model is installed stays the source's.
 * - [ModelsState.serves], from the source's catalog.
 *
 * ⚠⚠ **It refuses nothing on the strength of its own mark.** Which of a download and a removal
 * of one model runs first is the downloader's to order, by when each was asked; a second
 * exclusion here could disagree with it.
 *
 * ⚠ **One collection of the source feeds every screen** ([shared]), so that a removal's mark is
 * let go only after that same collection has said the model is gone.
 *
 * @param source `null`: this graph has none (it was not admitted, or was given no journal).
 * @param notOffered why this build or phone is offered no model, from the static gates. ⚠ The one
 *   producer of [ModelsAvailability.NotOffered]: a source never says it.
 * @param remove the `DeleteModel` operation. ⚠ It ends a download of the model first.
 * @param scope the repository's: a removal and a check end in it whoever is still waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceModelsRepository(
    private val source: OnDeviceModelSource?,
    private val notOffered: ModelsAvailability.NotOffered.Why?,
    capabilities: ModelsCapabilities,
    private val remove: suspend (modelId: String) -> ModelDeleteOutcome,
    private val consents: ModelsConsents,
    scope: CoroutineScope,
    /** ⚠ Each bounds a **wait**: the removal and the check go on. */
    private val removalWait: Duration = 60.seconds,
    private val checkWait: Duration = 45.seconds,
    /** How long a removal that happened waits for the source to say so before its mark goes. */
    private val goneWait: Duration = 10.seconds,
    /**
     * This process's foreground pass (§14.19.6), or `null` where there is none to show: then
     * [capabilities] say no indexing section, whatever they were given.
     */
    private val indexing: OnDeviceIndexSource? = null,
    /** The pause row (`IndexingPaused`, as Android's). ⚠ `null` with no [indexing]: nothing to pause. */
    private val pause: IndexingPause? = null,
) : ModelsRepository, ModelsConsents by consents {

    override val capabilities: ModelsCapabilities = if (indexing == null) capabilities.copy(indexing = false) else capabilities

    /** ⚠ The belt: nothing below is meant to throw, and an uncaught throw aborts a Kotlin/Native process. */
    private val scope = scope + CoroutineExceptionHandler { _, failure -> println("On-device models repository failed: $failure") }

    /** This repository's removal calls that have not ended, and how each model's last one ended. */
    private data class Removals(
        val running: Map<String, CompletableDeferred<ModelDeleteOutcome>> = emptyMap(),
        val endings: Map<String, ModelDeleteOutcome> = emptyMap(),
    )

    private val removals = MutableStateFlow(Removals())

    /** The graph this repository belongs to was torn down ([retire]). */
    private val retired = MutableStateFlow(false)

    /**
     * ⚠ Collected while somebody is subscribed and not otherwise, and nothing is replayed once
     * nobody is: a screen that opens later reads the source afresh, never what an earlier screen
     * last saw.
     */
    private val shared: SharedFlow<ModelsState>? = source?.let { live ->
        combine(live.models.endingNotConnected(), live.serves, removals) { availability, serves, removing ->
            ModelsState(availability = availability, serves = serves, removing = removing.running.keys, removalEndings = removing.endings)
        }
            // ⚠ A throw from any input is *not connected*, said: a shared flow whose upstream
            // failed would otherwise leave every screen at its last value, for good.
            .catch { failure ->
                println("On-device models -> the list failed: $failure")
                emit(ModelsState(ModelsAvailability.NotConnected))
            }
            .distinctUntilChanged()
            // ⚠ Ends after it has said so: the other two inputs never complete by themselves.
            .transformWhile { state ->
                emit(state)
                state.availability != ModelsAvailability.NotConnected
            }
            .shareIn(this.scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)
    }

    // ⚠⚠ No field below this line: [shared] starts work in the scope when it is first collected.

    override fun models(): Flow<ModelsState> = when {
        // ⚠ Stays: a flow that completes is read as *not connected*.
        notOffered != null -> standing(ModelsState(ModelsAvailability.NotOffered(notOffered)))
        shared == null -> flowOf(ModelsState(ModelsAvailability.NotConnected))
        // ⚠ [retired] beside the list: a retired repository's scope is cancelled, so its shared
        // collection may never get to say *not connected* itself.
        else -> flow {
            // ⚠ Asked first: what the shared collection last replayed is a retired graph's list.
            if (retired.value) return@flow emit(ModelsState(ModelsAvailability.NotConnected))
            emitAll(
                merge(shared, retired.filter { it }.map { ModelsState(ModelsAvailability.NotConnected) }).transformWhile { state ->
                    emit(state)
                    state.availability != ModelsAvailability.NotConnected
                },
            )
        }
    }

    /**
     * The graph was torn down: every list ends *not connected* now, whatever the source gets to
     * say, and nothing of this repository goes on. ⚠ A removal in flight is let go of and its
     * caller answered *not connected* ([delete]): whether it still happens is not known here.
     * ⚠ Takes no lock and waits for nothing: a teardown runs on the main thread.
     */
    fun retire() {
        retired.value = true
        scope.cancel()
    }

    override suspend fun download(modelId: String, allowsCellular: Boolean): ModelActionResult =
        answering { source?.download(modelId, allowsCellular) }

    override suspend fun cancel(modelId: String): ModelActionResult = answering { source?.cancel(modelId) }

    /** ⚠ No source is *not connected*; a throw is a refusal nobody named, never an escape. */
    private suspend fun answering(ask: suspend () -> ModelActionResult?): ModelActionResult = try {
        ask() ?: ModelActionResult.Refused(ModelActionResult.Why.NotConnected)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ModelActionResult.Refused(ModelActionResult.Why.Unnamed)
    }

    override suspend fun setRealmConsent(capability: InferenceCapability, allowed: Boolean): Boolean =
        consents.setRealmConsent(capability, allowed).also { written -> if (written && !allowed) withdrawn(capability) }

    override suspend fun setDeviceConsent(capability: InferenceCapability, allowed: Boolean): Boolean =
        consents.setDeviceConsent(capability, allowed).also { written -> if (written && !allowed) withdrawn(capability) }

    /**
     * ⚠⚠ A switch turned off lets go of what was loaded for it **now**: otherwise the text tower
     * warmed for search stays resident until the graph goes. Only after the write went through,
     * and in [scope]: an unload waits for a query that holds the tower.
     */
    private fun withdrawn(capability: InferenceCapability) {
        val live = source ?: return
        scope.launch {
            try {
                live.consentWithdrawn(capability)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                println("On-device models -> what was loaded for $capability was not let go: $failure")
            }
        }
    }

    /** In [scope], as the check: the call goes on whoever asked; the caller's wait is bounded. */
    override suspend fun loadAgain(modelId: String) {
        val live = source ?: return
        val again = scope.launch {
            try {
                live.loadAgain(modelId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                println("On-device models -> trying $modelId once more failed: $failure")
            }
        }
        withTimeoutOrNull(checkWait) { again.join() }
    }

    /**
     * The source's check: the launch's sequence, coalesced there, beside a catalog refresh. ⚠ One
     * path: nothing here starts a second sequence. It runs in [scope]; the caller's wait is
     * bounded.
     */
    override suspend fun checkAgain() {
        val live = source ?: return
        val check = scope.launch {
            try {
                live.checkAgain()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                println("On-device models -> the check failed: $failure")
            }
        }
        withTimeoutOrNull(checkWait) { check.join() }
    }

    override suspend fun delete(modelId: String): ModelDeleteOutcome {
        // ⚠ Nothing was asked of anything: there is no runtime to remove from.
        val live = shared ?: return ModelDeleteOutcome.NotConnected
        val mine = CompletableDeferred<ModelDeleteOutcome>()
        var running: CompletableDeferred<ModelDeleteOutcome>? = null
        // ⚠ Claimed or joined in one step. A second press joins the first call.
        removals.update { was ->
            running = was.running[modelId]
            if (running != null) was else was.copy(running = was.running + (modelId to mine))
        }
        running?.let { return waitingFor(it) }
        // ⚠ Assigned once, when the operation has answered; read by the ending below.
        val answered = CompletableDeferred<ModelDeleteOutcome>()
        scope.launch {
            answered.complete(
                try {
                    // ⚠⚠ Not bounded: the operation is not stopped by its caller giving up, so a
                    // bound here would let go of the row while the removal still runs.
                    remove(modelId)
                } catch (cancelled: CancellationException) {
                    ensureActive()
                    // ⚠⚠ Cut off under a scope that is not this one's (the graph's, cancelled by a
                    // teardown before [retire]): the operation may still complete. Not known.
                    ModelDeleteOutcome.NotConnected
                } catch (_: Exception) {
                    ModelDeleteOutcome.Failed
                },
            )
            // ⚠ Before the row stops *removing*: the collection every screen reads has said the
            // model is gone, so it is never shown installed, with its buttons, in between.
            if (answered.getCompleted() == ModelDeleteOutcome.Removed && withTimeoutOrNull(goneWait) { live.first { !it.holds(modelId) } } == null) {
                // ⚠ The source did not move for it. It is asked to read again and that read is
                // waited for (bounded) before the mark goes.
                checkAgain()
            }
        }.invokeOnCompletion {
            // ⚠⚠ On every ending, a scope cancelled under it or before it ran included ([retire]):
            // the mark goes, the ending is said with it in ONE step, and whoever waits is answered.
            // ⚠⚠ What the operation answered when it did. A removal cut off before that answered
            // nothing: its caller is told *not connected*, and NO ending is recorded, because
            // neither *failed* nor *removed* has happened as far as anyone here knows. The
            // earlier ending of the same model goes too: it is not this removal's.
            val outcome = if (answered.isCompleted) answered.getCompleted() else ModelDeleteOutcome.NotConnected
            removals.update { was ->
                val endings = if (outcome == ModelDeleteOutcome.NotConnected) was.endings - modelId else was.endings + (modelId to outcome)
                was.copy(running = was.running - modelId, endings = endings)
            }
            mine.complete(outcome)
        }
        return waitingFor(mine)
    }

    /** ⚠ The bound is on the wait: after it the caller is told *still removing*, and the row says so. */
    private suspend fun waitingFor(removal: CompletableDeferred<ModelDeleteOutcome>): ModelDeleteOutcome =
        withTimeoutOrNull(removalWait) { removal.await() } ?: ModelDeleteOutcome.StillRemoving

    // ─── Indexing: this process's foreground pass (§14.19.6), where there is one.
    // ⚠ Each stream never completes, as the interface says: with no source it is one value that stays.

    override fun indexProgress(): Flow<IndexProgressState> =
        indexing?.let { indexProgressStates(it.progress) } ?: standing(IndexProgressState())

    override fun indexingPaused(): Flow<Boolean> = pause?.paused() ?: standing(false)

    override suspend fun setIndexingPaused(paused: Boolean) {
        pause?.set(paused)
    }

    override fun lastCoverage(): Flow<IndexCoverage?> = indexing?.coverage?.coverage ?: standing(null)

    override fun foregroundIndex(): Flow<ForegroundIndexStanding?> = indexing?.standing ?: standing(null)

    override fun storedIndex(): Flow<StoredIndex?> = indexing?.stored ?: standing(null)

    override fun indexContinued(): Flow<Boolean> = indexing?.continued ?: standing(false)

    override suspend fun startIndexing(): IndexStartOutcome = indexing?.let { indexNowOrFailed(it) } ?: IndexStartOutcome.Failed

    /** ⚠ A throw is a failure nobody named, never an escape. Its own function: K/N's third face (`tech-debt.md`). */
    private suspend fun indexNowOrFailed(source: OnDeviceIndexSource): IndexStartOutcome {
        try {
            return source.indexNow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            println("On-device models -> Index now failed: $failure")
            return IndexStartOutcome.Failed
        }
    }

    companion object {

        /**
         * Over a graph's source and Photos' own client: the operation for a removal, the rows for
         * consent and for the pause, and the graph's foreground pass ([indexing]) where it has one.
         *
         * @param features the capabilities **the graph bound a backend for**, in the order shown
         *   (`ios-face-grouping.md` F5). ⚠⚠ Derived from the graph, never a constant: a consent row
         *   is offered for each, so a face switch with no face backend would change nothing.
         */
        fun over(
            source: OnDeviceModelSource?,
            client: TenshuClient,
            scope: CoroutineScope,
            features: List<InferenceCapability>,
            indexing: OnDeviceIndexSource? = null,
            /** [ModelsCapabilities.modelFeatures]: which feature each model the graph prepares serves. */
            modelFeatures: Map<String, InferenceCapability> = emptyMap(),
        ): OnDeviceModelsRepository {
            val capabilities: ModelsCapabilities = OnDeviceModelsCapabilities.copy(features = features, modelFeatures = modelFeatures)
            // ⚠⚠ Off the caller's thread: Photos' client is in-process (see [ClientModelsConsents.work]).
            val consents = ClientModelsConsents(client, ClientModelsDevices(client), capabilities.features, work = Dispatchers.Default)
            return OnDeviceModelsRepository(
                source = source,
                notOffered = null,
                capabilities = capabilities,
                remove = { modelId ->
                    client.inferenceService.deleteModel(modelId).fold(
                        onSuccess = { ModelDeleteOutcome.Removed },
                        onFailure = { error -> deleteOutcomeFor((error as? ModelUnavailable)?.reason?.get()) },
                    )
                },
                consents = consents,
                scope = scope,
                indexing = indexing,
                pause = indexing?.let { ClientIndexingPause(client, consents, Dispatchers.Default) },
            )
        }

        /** This build, or this phone, is offered no model: said, with nothing to press. */
        fun notOffered(why: ModelsAvailability.NotOffered.Why, scope: CoroutineScope): OnDeviceModelsRepository =
            OnDeviceModelsRepository(null, why, NothingToSwitch, { ModelDeleteOutcome.NotConnected }, NoModelsConsents, scope)

        /** No runtime is installed. ⚠ A failure, never an empty list. */
        fun notConnected(scope: CoroutineScope): OnDeviceModelsRepository =
            OnDeviceModelsRepository(null, null, NothingToSwitch, { ModelDeleteOutcome.NotConnected }, NoModelsConsents, scope)
    }
}

private fun ModelsState.holds(modelId: String): Boolean =
    (availability as? ModelsAvailability.Listed)?.models?.any { it.modelId == modelId && it.setup is ModelSetup.Held } == true

/**
 * The source's list, ending in [ModelsAvailability.NotConnected] however the source ended: a flow
 * that completed without saying so, or threw, has nothing standing behind its last value.
 */
private fun Flow<ModelsAvailability>.endingNotConnected(): Flow<ModelsAvailability> = flow {
    var last: ModelsAvailability? = null
    // ⚠ `catch`, which sees the source's throw and never a collector's.
    emitAll(this@endingNotConnected.catch { failure -> println("On-device models -> the source failed: $failure") }.onEach { last = it })
    if (last != ModelsAvailability.NotConnected) emit(ModelsAvailability.NotConnected)
}
