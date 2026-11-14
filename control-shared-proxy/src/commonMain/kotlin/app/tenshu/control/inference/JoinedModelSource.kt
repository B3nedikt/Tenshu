package app.tenshu.control.inference

import org.tenshu.inference.proxy.catalog.ModelEntryData
import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.InferenceCapability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tenshu.inference.proxy.PreflightKey
import org.tenshu.inference.proxy.PrepareKey
import org.tenshu.inference.proxy.ReadinessStanding
import org.tenshu.inference.proxy.PreflightReport
import org.tenshu.inference.proxy.PrepareRefusal
import org.tenshu.inference.proxy.PrepareReport
import org.tenshu.inference.proxy.catalog.ModelCatalogUnavailableException
import org.tenshu.inference.proxy.catalog.ModelDownloadCancelledException
import org.tenshu.inference.proxy.catalog.ModelDownloadChoiceConflictException
import org.tenshu.inference.proxy.catalog.ModelDownloadJournalUnreadableException
import org.tenshu.inference.proxy.catalog.ModelDownloadJournalView
import org.tenshu.inference.proxy.catalog.ModelDownloadOutcome
import org.tenshu.inference.proxy.catalog.ModelDownloadProgressSnapshot
import org.tenshu.inference.proxy.catalog.ModelFreeSpaceUnknownException
import org.tenshu.inference.proxy.catalog.ModelIndexUnreadableException
import org.tenshu.inference.proxy.catalog.ModelIntegrityException
import org.tenshu.inference.proxy.catalog.ModelStorageInsufficientException
import org.tenshu.inference.proxy.catalog.ModelStoreNotOwnedException
import org.tenshu.inference.proxy.catalog.ModelTransfersUnknownException
import org.tenshu.inference.proxy.catalog.ModelTransportUnavailableException
import org.tenshu.inference.proxy.catalog.UnknownModelException
import kotlin.coroutines.CoroutineContext

/**
 * [OnDeviceModelSource] over the flows of one graph: each emission is [modelsOf] of what they
 * hold at that moment (`ios-on-device-inference.md` §14.18.2).
 *
 * ⚠⚠ **It keeps nothing of its own about the model.** Every input but two is the owner's own
 * flow, read as it is. The two exceptions are reads this class makes because nobody publishes
 * them:
 * - *what is installed* is asked of the store whenever something that could have changed it
 *   moved ([readInstalled]), never remembered here;
 * - *the catalog* is a network fetch, so its last answer is kept ([catalog]), as the answer to
 *   the last fetch and nothing more. It says nothing of what is installed.
 *
 * ⚠⚠ **Joined on [context], never on the collector's thread** ([flowOn]): the store's read
 * takes the store's lock (held for a commit's renames) and the first one decodes the index and
 * runs the store's sweep. Collected by a view model on the main thread it would run there, and
 * on an immediate dispatcher **inside** whoever set one of the flows.
 * ⚠ And a progress tick does not ask the store: the bar is joined after the read.
 * ⚠ When the graph is retired the flow emits [ModelsAvailability.NotConnected] and completes.
 */
class JoinedModelSource(
    private val modelId: String,
    /** Runs a download's call, which outlives whoever asked for it. The graph's. */
    private val scope: CoroutineScope,
    private val retired: Flow<Boolean>,
    readiness: Flow<ReadinessStanding>,
    private val journal: Flow<ModelDownloadJournalView>,
    outcome: Flow<ModelDownloadOutcome?>,
    progress: Flow<Map<String, ModelDownloadProgressSnapshot>>,
    compiling: Flow<PrepareKey?>,
    probing: Flow<PreflightKey?>,
    prepare: Flow<PrepareReport?>,
    prepareRefusal: Flow<PrepareRefusal?>,
    preflight: Flow<PreflightReport?>,
    /** ⚠ May throw: a throw is [InstalledRead.Unreadable], never *nothing installed*. */
    private val readInstalled: suspend () -> InstalledRead,
    /** The catalog as it answers now. ⚠ May suspend for a fetch. */
    private val loadCatalog: suspend () -> CatalogRead,
    /** [admitted] is called once this call has a record in the journal; see [download]. */
    private val startDownload: suspend (modelId: String, allowsCellular: Boolean, admitted: () -> Unit) -> Unit,
    private val cancelDownload: suspend (modelId: String) -> Unit,
    private val recheck: suspend () -> Unit,
    private val isRetired: () -> Boolean,
    private val context: CoroutineContext = Dispatchers.Default,
    /**
     * How long a press waits to be told whether its download was journalled. ⚠⚠ A bound on the
     * **caller's** wait and nothing else: the call goes on in [scope], and its record, when it
     * comes, is the row's to show. The call waits under the step lock behind a prepare or a probe
     * (seconds), then fetches the catalog (unbounded); unbounded, the row would say *Starting…*
     * for ever when the fetch never answers (§14.17.0).
     */
    private val admitWait: Duration = 15.seconds,
    /** Lets go of what is loaded for search: the backend's text tower. */
    private val unloadSearch: suspend () -> Unit = {},
    /**
     * Face grouping was just switched off (`ios-face-grouping.md` F6): what this device holds for it
     * goes now. ⚠⚠ A source's hook only: what is deleted, and that only an **explicit** no deletes,
     * is the operation's to decide (`FaceGroupsImplementation`'s withdrawal-first deletion), never
     * this class's.
     */
    private val facesWithdrawn: suspend () -> Unit = {},
    /**
     * What the verdict's file says of [ModelEntryData] as this device would install it (its key
     * from the entry's digests and this OS build). ⚠ May throw: a throw is *unreadable*.
     */
    private val readOffered: suspend (ModelEntryData) -> AiReadiness.KeyAnswer,
    /**
     * One more attempt after loads Photos did not survive stopped the model (§14.5): of the
     * install when [ModelEntryData] is `null`, else of the entry as it would be installed.
     */
    private val loadAgainOf: suspend (ModelEntryData?) -> Unit,
) : OnDeviceModelSource {

    /**
     * This source's own download calls whose callers stopped waiting (the bound), by model:
     * still starting, or refused after the caller left. ⚠ The call's own, and only while it means
     * something: *Starting* lives as long as the call does, and a refusal until the next press.
     */
    private val pressed = MutableStateFlow<Map<String, ModelSetup.Pressed>>(emptyMap())

    /** The last fetch's answer. See the class. */
    private val catalog = MutableStateFlow<CatalogRead>(CatalogRead.NotReadYet)

    /** Moved by [checkAgain], so that what is installed is asked again with nothing else changed. */
    private val asked = MutableStateFlow(0)

    /** One fetch at a time. */
    private val fetching = Mutex()

    private val disk: Flow<ModelOnDisk> = combine(retired, readiness, journal, catalog, asked) { gone, state, held, offered, _ ->
        // ⚠ Not yet with what is installed: that is read below, once per change of these.
        ModelOnDisk(modelId, gone, state, InstalledRead.NotReadYet, offered, held)
    }

    private val said: Flow<ModelSaid> = combine(
        combine(outcome, compiling, probing) { last, compile, probe -> Triple(last, compile, probe) },
        prepare, prepareRefusal, preflight, pressed,
    ) { (last, compile, probe), prepared, refused, probed, calls ->
        ModelSaid(last, null, compile, probe, prepared, refused, probed, pressed = calls)
    }

    private val joined: Flow<ModelsAvailability> =
        combine(disk, said) { onDisk, remembered ->
            // ⚠ Not asked of a graph that is gone: its store is the next graph's now.
            val read: InstalledRead = if (onDisk.retired) InstalledRead.NotReadYet else installed()
            onDisk.copy(installed = read, offered = offeredFor(onDisk.catalog, read)) to remembered
        }
            // ⚠ The bar AFTER the read: a tick asks the store nothing.
            .combine(progress.map { it[modelId] }.distinctUntilChanged()) { (onDisk, remembered), bar ->
                modelsOf(onDisk, remembered.copy(progress = bar)).also(::forgetRefusalOnceMovedOn)
            }
            .distinctUntilChanged()
            // ⚠ Ends after it has said so: a retired graph's value is not left standing.
            .transformWhile { value ->
                emit(value)
                value != ModelsAvailability.NotConnected
            }
            .flowOn(context)

    /**
     * ⚠ A refusal kept for a caller who left ([ModelSetup.Pressed.Refused]) is about the row it
     * was pressed on: once the row is no longer one that offers a download (the journal names the
     * model, or it is held with no update to offer), it is dropped, or it would come back on the
     * row of a later, unrelated absence. *Starting* stays: its call
     * is still running. ⚠ The check and the drop in one `update`, which has no side effect.
     */
    private fun forgetRefusalOnceMovedOn(listed: ModelsAvailability) {
        val row: ModelRow = (listed as? ModelsAvailability.Listed)?.models?.firstOrNull { it.modelId == modelId } ?: return
        if (offersADownload(row.setup)) return
        pressed.update { held -> if (held[modelId] is ModelSetup.Pressed.Refused) held - modelId else held }
    }

    override val models: Flow<ModelsAvailability> = flow {
        coroutineScope {
            // ⚠ BESIDE the first emission, not before it: a slow catalog shows as *not read yet*.
            val fetch: Job = launch { fetchCatalog(onlyIfNeverRead = true) }
            emitAll(joined)
            fetch.cancel()
        }
    }

    override val serves: Flow<Map<String, List<String>>> = catalog
        .map { read ->
            (read as? CatalogRead.Read)?.entry?.let { entry -> mapOf(entry.modelId to entry.capabilities.map { it.id }) }.orEmpty()
        }
        .distinctUntilChanged()

    /** [readOffered], for a model the store does not hold and the catalog offers; else `null`. */
    private suspend fun offeredFor(catalog: CatalogRead, read: InstalledRead): AiReadiness.KeyAnswer? {
        val entry: ModelEntryData = (catalog as? CatalogRead.Read)?.entry ?: return null
        val held: InstalledRead.Read = read as? InstalledRead.Read ?: return null
        if (held.record != null) return null
        return try {
            readOffered(entry)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            AiReadiness.KeyAnswer.Unreadable
        }
    }

    override suspend fun loadAgain(modelId: String) {
        if (modelId != this.modelId) return
        withContext(context) {
            val read: InstalledRead = installed()
            // ⚠ The install's key when there is one; else the entry's, as it would be installed.
            val entry: ModelEntryData? = if ((read as? InstalledRead.Read)?.record == null) (catalog.value as? CatalogRead.Read)?.entry else null
            loadAgainOf(entry)
        }
        checkAgain()
    }

    private suspend fun installed(): InstalledRead = try {
        readInstalled()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (unreadable: Exception) {
        InstalledRead.Unreadable
    }

    private suspend fun fetchCatalog(onlyIfNeverRead: Boolean) = fetching.withLock {
        // ⚠ Asked UNDER the lock: asked before it, two first collectors make two fetches.
        if (onlyIfNeverRead && catalog.value != CatalogRead.NotReadYet) return@withLock
        val read: CatalogRead = try {
            loadCatalog()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            CatalogRead.Unavailable
        }
        // ⚠⚠ A fetch whose caller has left has answered nobody, and the shared catalog source
        // answers a cancelled fetch with *no catalog* (`tech-debt.md`): recorded, that would
        // stand as "could not be loaded" for every later collector, and nothing would ask again.
        currentCoroutineContext().ensureActive()
        catalog.update { had ->
            // ⚠⚠ A refresh that failed does not erase a catalog that was read: it marks it.
            if (read == CatalogRead.Unavailable && had is CatalogRead.Read) had.copy(stale = true) else read
        }
    }

    /**
     * ⚠⚠ Returns when the download is **journalled** or **refused**, not when it is installed:
     * the call that installs runs on [scope] and is waited for by nobody here.
     *
     * ⚠⚠ **`Accepted` is one fact, told by the downloader: this call has a record in the
     * journal** ([startDownload]'s `admitted`), the one it wrote or the running one it joined.
     * It is never inferred from the journal view. That view also moves for another caller's
     * download, for a record of another key, and for a teardown, which says `Retired` **before**
     * it fails the call: read as "it changed", a download cancelled with its runtime, with no
     * record at all, would be answered *accepted*.
     * ⚠ What the call says after it was admitted (it cannot go on now; it failed; it was
     * installed) is **dropped here**: it is the row's to say (`Download.held`, the store), once.
     * ⚠ On [context], so that the downloader, which admits under its step lock, never resumes
     * a view model inside it.
     */
    override suspend fun download(modelId: String, allowsCellular: Boolean): ModelActionResult = withContext(context) {
        if (isRetired()) return@withContext ModelActionResult.Refused(ModelActionResult.Why.NotConnected)
        val admitted = CompletableDeferred<Unit>()
        val call = CompletableDeferred<Throwable?>()
        scope.launch {
            call.complete(
                try {
                    startDownload(modelId, allowsCellular) { admitted.complete(Unit) }
                    null
                } catch (failure: Throwable) {
                    failure
                },
            )
        }
        // ⚠ A press is a new call: what an earlier one said after its caller left is not this one's.
        pressed.update { it - modelId }
        val accepted: ModelActionResult = withTimeoutOrNull(admitWait) { answer(admitted, call) } ?: run {
            // ⚠⚠ The caller stops waiting; the call does not. The row says *Starting…* for as
            // long as the call lives, and then what it answered.
            pressed.update { it + (modelId to ModelSetup.Pressed.Starting) }
            scope.launch { lateAnswer(modelId, admitted, call) }
            ModelActionResult.StillStarting
        }
        // ⚠ Whatever was true a moment ago: a graph that is gone accepted nothing that will run.
        if (isRetired()) ModelActionResult.Refused(ModelActionResult.Why.NotConnected) else accepted
    }

    /**
     * The answer of a call whose caller left: journalled, or installed already, ends *Starting*
     * (the journal and the store say the rest); a refusal the row cannot say from what it reads is
     * kept as [ModelSetup.Pressed.Refused]. ⚠ A refusal the downloader reported under its key
     * (`Said`) is the row's `last` already, and is not said twice.
     */
    private suspend fun lateAnswer(modelId: String, admitted: CompletableDeferred<Unit>, call: CompletableDeferred<Throwable?>) {
        val result: ModelActionResult = answer(admitted, call)
        val refused: ModelActionResult.Why? = (result as? ModelActionResult.Refused)?.why?.takeUnless { it is ModelActionResult.Why.Said }
        val kept: Map<String, ModelSetup.Pressed> = pressed.updateAndGet { held ->
            if (held[modelId] != ModelSetup.Pressed.Starting) held
            else if (refused == null) held - modelId
            else held + (modelId to ModelSetup.Pressed.Refused(refused))
        }
        if (kept[modelId] is ModelSetup.Pressed.Refused) forgetRefusalOnceJournalled(modelId)
    }

    /**
     * ⚠ A kept refusal is dropped once the journal names the model **whether or not a screen
     * collects [models]** (§14.18.16 item 14): [forgetRefusalOnceMovedOn] runs only inside the
     * join, so a download journalled and ended while no screen was open would leave the old refusal
     * on the next screen's absent row. Watched on [scope] (this call's own coroutine), until the
     * journal names the model or the refusal is gone: a press, or the join's own drop.
     */
    private suspend fun forgetRefusalOnceJournalled(modelId: String) {
        // ⚠⚠ Also ends when the graph is retired: the journal's last word is then `Retired`,
        // which names nothing ever again, and nothing cancels [scope] at a teardown, so a
        // watcher waiting for the journal would outlive its graph. A retired graph's list says
        // *not connected*; its refusal is left as it is, said by nothing.
        val ended: Boolean = combine(journal, pressed, retired) { view, held, gone -> Triple(view, held, gone) }
            .first { (view, held, gone) ->
                gone || view == ModelDownloadJournalView.Retired || held[modelId] !is ModelSetup.Pressed.Refused ||
                    (view as? ModelDownloadJournalView.Read)?.named?.containsKey(modelId) == true
            }
            .let { (view, _, gone) -> gone || view == ModelDownloadJournalView.Retired }
        if (ended) return
        pressed.update { held -> if (held[modelId] is ModelSetup.Pressed.Refused) held - modelId else held }
    }

    private suspend fun answer(admitted: CompletableDeferred<Unit>, call: CompletableDeferred<Throwable?>): ModelActionResult =
        select {
            admitted.onAwait { ModelActionResult.Accepted }
            call.onAwait { failure ->
                when {
                    // Installed already, with nothing to fetch.
                    failure == null -> ModelActionResult.Accepted
                    // ⚠ Admitted and then stopped, with both ready at once and this clause chosen:
                    // the record is there. (`select` takes the first clause when both are ready,
                    // so no case reaches this on one thread; it is for two.)
                    admitted.isCompleted -> ModelActionResult.Accepted
                    else -> ModelActionResult.Refused(why(failure))
                }
            }
        }

    private fun why(failure: Throwable): ModelActionResult.Why = when (failure) {
        is ModelStorageInsufficientException ->
            ModelActionResult.Why.Said(ModelSetup.DownloadSaid.NeedsSpace(failure.neededBytes, failure.freeBytes))
        is ModelFreeSpaceUnknownException -> ModelActionResult.Why.Said(ModelSetup.DownloadSaid.FreeSpaceUnknown)
        is ModelTransportUnavailableException -> ModelActionResult.Why.Said(ModelSetup.DownloadSaid.NoTransport)
        is ModelTransfersUnknownException -> ModelActionResult.Why.Said(ModelSetup.DownloadSaid.TransfersUnknown)
        is ModelDownloadJournalUnreadableException -> ModelActionResult.Why.Said(ModelSetup.DownloadSaid.JournalUnreadable)
        is ModelStoreNotOwnedException, is ModelIndexUnreadableException -> ModelActionResult.Why.Said(ModelSetup.DownloadSaid.StoreRefused)
        is ModelIntegrityException -> ModelActionResult.Why.Said(ModelSetup.DownloadSaid.Failed(integrity = true))
        // ⚠ The request's own refusal: never said of the download that is running.
        is ModelDownloadChoiceConflictException -> ModelActionResult.Why.OtherCellularChoice
        is ModelCatalogUnavailableException -> ModelActionResult.Why.NoCatalog
        is UnknownModelException -> ModelActionResult.Why.UnknownModel
        is ModelDownloadCancelledException ->
            if (isRetired()) ModelActionResult.Why.NotConnected else ModelActionResult.Why.Said(ModelSetup.DownloadSaid.Cancelled)
        else -> if (isRetired()) ModelActionResult.Why.NotConnected else ModelActionResult.Why.Unnamed
    }

    /**
     * ⚠ `Accepted` is the downloader's own return: it read the journal and removed the record
     * in one step, or found none (which is what was asked for). There is no look here first,
     * so nothing can change between a look and the act.
     */
    override suspend fun cancel(modelId: String): ModelActionResult = withContext(context) {
        if (isRetired()) return@withContext ModelActionResult.Refused(ModelActionResult.Why.NotConnected)
        try {
            cancelDownload(modelId)
            if (isRetired()) ModelActionResult.Refused(ModelActionResult.Why.NotConnected) else ModelActionResult.Accepted
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            ModelActionResult.Refused(why(failure))
        }
    }

    /**
     * ⚠ The check runs **beside** the catalog's refresh, not after it, or *Try again* on a
     * paused download would do nothing until a network fetch had come back.
     */
    override suspend fun consentWithdrawn(capability: InferenceCapability) {
        if (capability == InferenceCapability.SEMANTIC_IMAGE_SEARCH) withContext(context) { unloadSearch() }
        if (capability == InferenceCapability.FACE_CLUSTERING) withContext(context) { facesWithdrawn() }
    }

    override suspend fun checkAgain() {
        coroutineScope {
            launch { recheck() }
            fetchCatalog(onlyIfNeverRead = false)
        }
        asked.update { it + 1 }
    }
}

/**
 * Whether [setup] is, or may be, a row a download is pressed on: absent, or held with an update
 * offered. ⚠ An update not known (a catalog or a journal not read) may be one: nothing is
 * concluded from a read that did not happen.
 */
private fun offersADownload(setup: ModelSetup): Boolean = when (setup) {
    is ModelSetup.Absent -> true
    is ModelSetup.Fetching -> false
    is ModelSetup.Held -> when (setup.update) {
        is ModelSetup.Update.Available, ModelSetup.Update.Unknown -> true
        ModelSetup.Update.None, is ModelSetup.Update.Fetching -> false
    }
}
