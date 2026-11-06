package app.tenshu.control.ui.screens.models

import app.tenshu.control.inference.ModelActionResult
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelsAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What one of the two list reads answered. ⚠ Three answers; none is another's default. */
internal sealed interface ModelRead {
    data class Got(val models: List<ModelFacts>) : ModelRead

    /** The proxy answered, and its answer was a failure it named. */
    data object Failed : ModelRead

    /** No answer of the operation's at all: the call threw, or failed with nothing it had typed. */
    data object NoAnswer : ModelRead
}

/**
 * A tick of the proxy's progress stream: bytes of the whole checkpoint, and its total.
 *
 * ⚠⚠ [modelId] is the model the tick is **about**, as the proxy stamped it, which is not always
 * the model that was asked for: the client keys a stream by the call site and not by the model
 * (`tech-debt.md`, *Two identical observers in one session share a stream id*), so a second
 * subscription can be handed the first one's stream. A tick is used only for its own model.
 */
internal data class ModelBytes(val modelId: String, val written: Long, val total: Long)

/** The five calls the ledger makes, as plain data, so it runs on the JVM with no client. */
internal interface ModelCalls {
    suspend fun installed(): ModelRead
    suspend fun available(): ModelRead

    /** ⚠ Returns when the download has ended, which is minutes: only the ledger's own scope waits on it. */
    suspend fun download(modelId: String): ModelDownloadOutcome

    /** Ticks while a download of [modelId] runs, and completes when none does (within seconds when none did). */
    fun progress(modelId: String): Flow<ModelBytes>
    suspend fun delete(modelId: String): ModelDeleteOutcome
}

/**
 * What Control knows of its models on Android and desktop, kept for the process and rendered by
 * every Models screen that opens (`ios-on-device-inference.md` §14.18.13).
 *
 * - *Installed* and *offered* are the two reads, as they last answered. A read that failed is
 *   kept as that failure; it is never an empty list. What is installed is local and is published
 *   as soon as it is read; the catalog is a network fetch and follows.
 * - *Fetching* is a `DownloadModel` call of this ledger that has not ended. ⚠⚠ That is a value in
 *   memory establishing a state, and it is allowed for one reason: nothing else on these
 *   platforms starts a download (the operation has this one caller, and the proxy is in this
 *   process), so a call under way is a download under way. It is removed in a `finally`: it
 *   cannot outlive the call. ⚠ The converse does not hold: a call cancelled by somebody else
 *   ends here while the proxy's job may go on (`tech-debt.md`). The bar is the proxy's stream.
 * - *Last* is how such a call ended **after it was answered**, with the model id and the catalog
 *   version it asked for. A call that ended before it was answered told its caller, and is
 *   recorded nowhere.
 *
 * ⚠ Everything a screen asks for runs in [scope], never in the screen's: a screen that is closed
 * mid-removal or mid-read leaves a call that ends and a list that is read again.
 * ⚠ In-process downloads do not survive the process, so neither does any of this.
 */
internal class ModelsLedger(
    private val calls: ModelCalls,
    /** The proxy's standing answer; read at every emission. */
    private val storeAccess: () -> StoreReadOnly?,
    scope: CoroutineScope,
    /** Where the join runs: off whatever thread collects ([flowOn]). */
    private val dispatcher: CoroutineDispatcher,
    private val resubscribeAfter: Duration = 500.milliseconds,
    /**
     * How long one read may take before it is *no answer*. ⚠ A bound, so that a read that never
     * returns does not hold every later one behind it.
     */
    private val readTimeout: Duration = 45.seconds,
    private val removalTimeout: Duration = 60.seconds,
) {

    /** ⚠ The belt: nothing below is meant to throw, and a throw here must not take the app down. */
    private val scope = scope + CoroutineExceptionHandler { _, failure -> println("Models ledger failed: $failure") }

    private sealed interface Installed {
        data object NotRead : Installed
        data object Unreadable : Installed
        data object NoAnswer : Installed
        data class Read(val models: List<ModelFacts>) : Installed
    }

    private sealed interface Catalog {
        data object NotRead : Catalog
        data object Unavailable : Catalog

        /** [stale]: the read after this one failed, and this is still what is shown. */
        data class Read(val models: List<ModelFacts>, val stale: Boolean) : Catalog
    }

    private data class Known(
        val installed: Installed = Installed.NotRead,
        val catalog: Catalog = Catalog.NotRead,
        /** This ledger's `DownloadModel` calls that have not ended, each with its answer. */
        val calling: Map<String, CompletableDeferred<ModelActionResult>> = emptyMap(),
        /** The collector of each running call's progress stream. */
        val watched: Map<String, Job> = emptyMap(),
        val bytes: Map<String, ModelBytes> = emptyMap(),
        val last: Map<String, LastDownload> = emptyMap(),
        /** The removals that have not ended, each with its outcome. ⚠ Here, as [calling] is: a removal outlives the screen that asked for it. */
        val removing: Map<String, CompletableDeferred<ModelDeleteOutcome>> = emptyMap(),
        /** How the last removal of each model ended. Written with the step that ends it. */
        val removalEndings: Map<String, ModelDeleteOutcome> = emptyMap(),
        /** How many callers are waiting for each call's answer: nobody waiting means nobody is told. */
        val waiting: Map<String, Int> = emptyMap(),
        /** A download or a removal was refused by the store. ⚠ Never cleared: true while this process runs. */
        val refused: StoreReadOnly? = null,
    )

    private val known = MutableStateFlow(Known())

    /** ⚠ One read at a time, so an older answer never lands over a newer one. */
    private val reading = Mutex()

    /**
     * ⚠ Its own lock, inside [reading] when both are held: what is installed is local, and the
     * read after a landing or a removal must not wait for a catalog fetch somebody else started.
     */
    private val readingInstalled = Mutex()

    /** Reads asked for, and the last one a finished read had seen: a read that began after a request serves it. */
    private val asked = MutableStateFlow(0L)
    private var served = 0L

    /**
     * ⚠ Emits [ModelsAvailability.Loading] until what is installed has been read once, and never
     * completes. ⚠ It reads nothing by itself: a screen that opens asks ([checkAgain]), every time.
     */
    val state: Flow<ModelsState> = known.map { it.state() }.distinctUntilChanged().flowOn(dispatcher)

    /**
     * Reads what is installed and the catalog again, in [scope], and returns when both have
     * answered or timed out. ⚠ What is shown stays while it runs. A caller that stops waiting
     * stops nothing.
     */
    suspend fun checkAgain() {
        scope.launch { read() }.join()
    }

    private suspend fun read() {
        val mine = asked.updateAndGet { it + 1 }
        reading.withLock {
            // A read that began after this was asked for has already answered it.
            if (served >= mine) return
            val serving = asked.value
            readInstalled()
            val available = answering { calls.available() }
            known.update { was ->
                was.copy(
                    catalog = when (available) {
                        is ModelRead.Got -> Catalog.Read(available.models, stale = false)
                        // ⚠ A refresh that fails keeps the catalog that was read, and says it is stale.
                        ModelRead.Failed, ModelRead.NoAnswer ->
                            (was.catalog as? Catalog.Read)?.copy(stale = true) ?: Catalog.Unavailable
                    },
                )
            }
            served = serving
        }
    }

    /** What is installed is published before anything waits on the network. */
    private suspend fun readInstalled() = readingInstalled.withLock {
        val installed = answering { calls.installed() }
        known.update { was ->
            was.copy(
                installed = when (installed) {
                    is ModelRead.Got -> Installed.Read(installed.models)
                    ModelRead.Failed -> Installed.Unreadable
                    ModelRead.NoAnswer -> Installed.NoAnswer
                },
            )
        }
    }

    /** One read, bounded, and never a throw: a read that threw or did not return gave no answer. */
    /**
     * ⚠ The bound ends the ledger's **wait**: an in-process operation goes on in the proxy. What
     * it answers later is discarded with the cancelled wait and has no way into the state.
     */
    private suspend fun answering(read: suspend () -> ModelRead): ModelRead = try {
        withTimeoutOrNull(readTimeout) { read() } ?: ModelRead.NoAnswer
    } catch (cancelled: CancellationException) {
        // ⚠ The ledger's own cancellation is rethrown; anyone else's is a read that did not answer.
        currentCoroutineContext().ensureActive()
        ModelRead.NoAnswer
    } catch (_: Exception) {
        ModelRead.NoAnswer
    }

    /**
     * Starts a download and answers once it is **under way** (the proxy's stream ticked for it)
     * or has ended before that. ⚠ Not at once: the proxy reads the catalog and replays what is
     * staged before the first tick, which is seconds to tens of seconds; the row is fetching for
     * all of it. ⚠ The call itself goes on in [scope]; what happens after the answer is the
     * row's ([state]), never a second answer.
     */
    suspend fun download(modelId: String): ModelActionResult {
        val answer = CompletableDeferred<ModelActionResult>()
        var running: CompletableDeferred<ModelActionResult>? = null
        var busy = false
        known.update { was ->
            running = was.calling[modelId]
            // ⚠⚠ One operation at a time: not on a model that is being removed (the download
            // would install again what the user removed), and not beside another download (the
            // progress stream cannot tell two apart, see [ModelBytes]).
            busy = running == null && (modelId in was.removing || was.calling.isNotEmpty())
            // ⚠⚠ Whoever presses is counted as waiting **in the step that claims or joins the
            // call**, never after it: the call can end before its caller has got as far as
            // waiting, and an answer with nobody counted is recorded under the row as well.
            val waiting = was.waiting + (modelId to (was.waiting[modelId] ?: 0) + 1)
            when {
                busy -> was
                running != null -> was.copy(waiting = waiting)
                else -> was.copy(calling = was.calling + (modelId to answer), last = was.last - modelId, waiting = waiting)
            }
        }
        if (busy) return ModelActionResult.Refused(ModelActionResult.Why.Busy)
        // ⚠ One call per model: a second press is answered with the first one's answer.
        running?.let { return awaiting(modelId, it) }
        // ⚠ The version asked for is the catalog's as it stood at the press: what `last` is keyed by.
        val version = (known.value.catalog as? Catalog.Read)?.models?.firstOrNull { it.modelId == modelId }?.modelVersion
        scope.launch {
            // ⚠ A watcher of this call's own, started with it and cancelled with it: never one
            // that was already there, which may be collecting a stream that has died.
            val watcher = launch(start = CoroutineStart.LAZY) { watch(modelId, answer) }
            known.update { it.copy(watched = it.watched + (modelId to watcher)) }
            watcher.start()
            var ended = ModelDownloadOutcome.TransportFailed
            var answeredNow = true
            try {
                ended = try {
                    calls.download(modelId)
                } catch (cancelled: CancellationException) {
                    // ⚠ Not the ledger's own: a call cancelled under a live ledger (a session
                    // torn down, a realm locked) is a download that did not finish.
                    ensureActive()
                    ModelDownloadOutcome.TransportFailed
                } catch (_: Exception) {
                    ModelDownloadOutcome.TransportFailed
                }
                // ⚠ Before the answer: whoever is told *the store refused* finds which refusal
                // it was already in the state.
                known.update { it.copy(refused = it.refused ?: ended.asStoreRefusal()) }
                // ⚠ `complete` answers whether THIS is the answer: false means the call was already
                // answered *accepted*, so how it ended is the row's to say.
                answeredNow = answer.complete(ended.asAnswer())
                // ⚠ Before the row stops fetching, so a model that landed is never shown absent.
                if (ended == ModelDownloadOutcome.Landed) readInstalled()
            } finally {
                // ⚠ On every ending, the ledger's own cancellation included: a call that is not
                // running must not be a row that is fetching. ⚠ Cancelled and not joined: the
                // stream's teardown is not the ledger's to wait for, and the watcher writes
                // nothing once it is no longer the one recorded.
                watcher.cancel()
                answer.complete(ModelActionResult.Refused(ModelActionResult.Why.NotConnected))
                // ⚠⚠ Told or recorded, decided in the step that ends the call: an answer somebody
                // is counted as waiting for is theirs and is recorded nowhere; one nobody waits
                // for told nobody and is the row's. Nobody can join after this step, so nobody
                // is handed an answer that was also recorded.
                known.update { was ->
                    val told = answeredNow && (was.waiting[modelId] ?: 0) > 0
                    was.copy(
                        calling = was.calling - modelId,
                        watched = was.watched - modelId,
                        bytes = was.bytes - modelId,
                        waiting = was.waiting - modelId,
                        last = ended.asLast(version).takeUnless { told }?.let { was.last + (modelId to it) } ?: was.last,
                    )
                }
            }
        }
        return awaiting(modelId, answer)
    }

    /**
     * Waits for a call's answer. ⚠ The caller was counted when it claimed or joined the call; a
     * caller that stops waiting **while the call runs** is uncounted here.
     */
    private suspend fun awaiting(modelId: String, answer: CompletableDeferred<ModelActionResult>): ModelActionResult =
        try {
            answer.await()
        } catch (cancelled: CancellationException) {
            known.update { was ->
                // ⚠ Only for the call it waited on: a call that has ended took its count with it.
                if (was.calling[modelId] !== answer) return@update was
                val left = (was.waiting[modelId] ?: 1) - 1
                was.copy(waiting = if (left > 0) was.waiting + (modelId to left) else was.waiting - modelId)
            }
            throw cancelled
        }

    /**
     * Removes a model, in [scope]: the removal and the read after it end whether or not the
     * caller is still there. ⚠ What it answers is what the removal answered. A read that then
     * fails is the list's to say, and never makes a removal that happened a failed one.
     */
    suspend fun delete(modelId: String): ModelDeleteOutcome {
        val mine = CompletableDeferred<ModelDeleteOutcome>()
        var running: CompletableDeferred<ModelDeleteOutcome>? = null
        var busy = false
        known.update { was ->
            running = was.removing[modelId]
            // ⚠ Not under a download of the same model: nothing here can end one first.
            busy = running == null && modelId in was.calling
            if (running != null || busy) was else was.copy(removing = was.removing + (modelId to mine))
        }
        if (busy) return ModelDeleteOutcome.Busy
        running?.let { return waitingForRemoval(it) }
        scope.launch {
            var outcome = ModelDeleteOutcome.Failed
            try {
                outcome = try {
                    // ⚠⚠ Not bounded: an in-process operation is not stopped by its caller giving
                    // up, so a bound here would release the row while the removal still runs.
                    calls.delete(modelId)
                } catch (cancelled: CancellationException) {
                    ensureActive()
                    ModelDeleteOutcome.Failed
                } catch (_: Exception) {
                    ModelDeleteOutcome.Failed
                }
                known.update { it.copy(refused = it.refused ?: outcome.asStoreRefusal()) }
                // ⚠ Whatever it answered: what is installed now is the store's to say, not the
                // outcome's. ⚠ Before the row stops *removing*, so it is never shown installed
                // with a button in between.
                readInstalled()
            } finally {
                // ⚠ One step: the row stops *removing* and how the removal ended is said with it.
                known.update { it.copy(removing = it.removing - modelId, removalEndings = it.removalEndings + (modelId to outcome)) }
                mine.complete(outcome)
            }
        }
        return waitingForRemoval(mine)
    }

    /**
     * ⚠⚠ The bound is on the **wait**, never on the removal: after it the caller is told *still
     * removing*, and the row stays *removing* (and refuses a download) until the call has
     * really ended. Nothing is called failed, or done, before it happened.
     */
    private suspend fun waitingForRemoval(removal: CompletableDeferred<ModelDeleteOutcome>): ModelDeleteOutcome =
        withTimeoutOrNull(removalTimeout) { removal.await() } ?: ModelDeleteOutcome.StillRemoving

    /**
     * Collects the proxy's progress stream for a running call: a tick is the bar, and what says
     * the download is under way. ⚠ Subscribed again while the call runs: the stream ends at once
     * when asked a moment before the proxy has registered the download.
     */
    private suspend fun watch(modelId: String, answer: CompletableDeferred<ModelActionResult>) {
        val me = currentCoroutineContext()[Job]
        while (true) {
            try {
                calls.progress(modelId).collect { tick ->
                    // ⚠⚠ A tick of another model says nothing of this one: not a byte of its
                    // bar, and not that its download is under way.
                    if (tick.modelId != modelId) return@collect
                    // ⚠ Only while this is still the call's watcher: a tick that arrives after
                    // the call has ended writes nothing.
                    known.update { if (it.watched[modelId] === me) it.copy(bytes = it.bytes + (modelId to tick)) else it }
                    answer.complete(ModelActionResult.Accepted)
                }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (_: Exception) {
                // A stream that failed says nothing; the call's own end is what ends the row.
            }
            delay(resubscribeAfter)
        }
    }

    private fun Known.state(): ModelsState {
        val storeReadOnly = storeAccess() ?: refused
        val held = when (installed) {
            // ⚠ A removal's mark and its ending are said whatever the list is: a re-read that
            // failed must not turn a removal that happened into silence, or into another sentence.
            Installed.NotRead -> return ModelsState(ModelsAvailability.Loading, storeReadOnly = storeReadOnly, removing = removing.keys, removalEndings = removalEndings)
            Installed.Unreadable -> return ModelsState(ModelsAvailability.InstalledUnreadable, storeReadOnly = storeReadOnly, removing = removing.keys, removalEndings = removalEndings)
            Installed.NoAnswer -> return ModelsState(ModelsAvailability.NotConnected, storeReadOnly = storeReadOnly, removing = removing.keys, removalEndings = removalEndings)
            is Installed.Read -> installed.models
        }
        val offered = when (catalog) {
            // ⚠ What is installed is shown while the catalog is still being fetched.
            Catalog.NotRead -> null
            Catalog.Unavailable -> null
            is Catalog.Read -> catalog.models
        }
        return ModelsState(
            availability = modelsSnapshot(
                installed = held,
                available = offered,
                stale = (catalog as? Catalog.Read)?.stale == true,
                fetching = calling.keys.associateWith { bytes[it] },
                last = last,
                catalogRead = catalog != Catalog.NotRead,
            ),
            serves = offered.orEmpty().associate { it.modelId to it.capabilityIds },
            storeReadOnly = storeReadOnly,
            removing = removing.keys,
            removalEndings = removalEndings,
        )
    }
}

/** How a download ended after it was answered: the model's catalog version, and what to say. */
internal data class LastDownload(val modelVersion: Int, val said: ModelSetup.DownloadSaid)

/** A call that ended before it was answered, as its answer. */
private fun ModelDownloadOutcome.asAnswer(): ModelActionResult = when (this) {
    // Nothing was left to fetch, or it was fetched before a tick was seen.
    ModelDownloadOutcome.Landed -> ModelActionResult.Accepted
    ModelDownloadOutcome.NoCatalog -> ModelActionResult.Refused(ModelActionResult.Why.NoCatalog)
    ModelDownloadOutcome.UnknownModel -> ModelActionResult.Refused(ModelActionResult.Why.UnknownModel)
    ModelDownloadOutcome.TransportFailed -> refusedSaying(ModelSetup.DownloadSaid.Failed(integrity = false))
    ModelDownloadOutcome.IntegrityFailed -> refusedSaying(ModelSetup.DownloadSaid.Failed(integrity = true))
    ModelDownloadOutcome.StoreNotOwned, ModelDownloadOutcome.StoreLockUnavailable ->
        refusedSaying(ModelSetup.DownloadSaid.StoreRefused)
}

private fun refusedSaying(said: ModelSetup.DownloadSaid) = ModelActionResult.Refused(ModelActionResult.Why.Said(said))

/**
 * A call that ended after it was answered, as what its row says. ⚠ `null` for the store's
 * refusals (they are [ModelsState.storeReadOnly]), and when the catalog did not list the model
 * at the press: with no version there is nothing to hold the sentence to.
 */
private fun ModelDownloadOutcome.asLast(asked: Int?): LastDownload? {
    val said = when (this) {
        ModelDownloadOutcome.Landed, ModelDownloadOutcome.StoreNotOwned, ModelDownloadOutcome.StoreLockUnavailable -> null
        ModelDownloadOutcome.IntegrityFailed -> ModelSetup.DownloadSaid.Failed(integrity = true)
        ModelDownloadOutcome.NoCatalog, ModelDownloadOutcome.UnknownModel, ModelDownloadOutcome.TransportFailed ->
            ModelSetup.DownloadSaid.Failed(integrity = false)
    }
    return if (said == null || asked == null) null else LastDownload(asked, said)
}

private fun ModelDownloadOutcome.asStoreRefusal(): StoreReadOnly? = when (this) {
    ModelDownloadOutcome.StoreNotOwned -> StoreReadOnly.OwnedElsewhere
    ModelDownloadOutcome.StoreLockUnavailable -> StoreReadOnly.LockUnavailable()
    ModelDownloadOutcome.Landed,
    ModelDownloadOutcome.NoCatalog,
    ModelDownloadOutcome.UnknownModel,
    ModelDownloadOutcome.TransportFailed,
    ModelDownloadOutcome.IntegrityFailed -> null
}

private fun ModelDeleteOutcome.asStoreRefusal(): StoreReadOnly? = when (this) {
    ModelDeleteOutcome.StoreNotOwned -> StoreReadOnly.OwnedElsewhere
    ModelDeleteOutcome.StoreLockUnavailable -> StoreReadOnly.LockUnavailable()
    ModelDeleteOutcome.Removed, ModelDeleteOutcome.Failed, ModelDeleteOutcome.Busy, ModelDeleteOutcome.StillRemoving,
    ModelDeleteOutcome.NotConnected -> null
}
