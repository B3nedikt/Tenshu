package app.tenshu.photos.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoHit
import app.tenshu.photos.model.PhotoHitSource
import app.tenshu.photos.model.SemanticSearchStatus
import app.tenshu.photos.ui.screens.common.boundedCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Search over the Home tab (`photos-app.md` S2): one bar, three sources, one list.
 *
 * ⚠⚠ **Source 1 carries the screen.** Name/path search needs nothing but the chain DSL and works on
 * every device. Labels and semantic search both need a model, and the production backend registry
 * is empty (`inference-service.md` §17) — so on every real device today sources 2 and 3 contribute
 * nothing, and the screen must read as *complete* rather than broken. That is the load-bearing
 * requirement here, not the merge.
 *
 * ⚠⚠ Three answers this state keeps apart, because each looks like the others if you let it
 * (`photos-ios.md` §8.19):
 * - **nothing matched** — every source asked answered, and none had a hit;
 * - **off** — a source cannot run here ([semanticStatus]: a refusal the proxy named, or
 *   [SemanticSearchStatus.NotBuiltHere] on a platform with no index). `photosLike` throws when the
 *   capability is off or no model is installed precisely so a caller cannot collapse "this cannot
 *   run here" into "there is nothing" — the mistake `mia-query-layer.md` §14.1 names;
 * - **could not look** — a source was asked and did not answer ([couldNotLook]): its read failed,
 *   threw, or outlasted its bound. Neither empty nor off, and the only one with a retry.
 */
data class PhotoSearchUiState(
    val query: String = "",
    /** ⚠ The hits of the last query that was ANSWERED — kept on screen under a newer one still running. */
    val hits: List<PhotoHit> = emptyList(),
    val searching: Boolean = false,
    /** Whether a search has ever completed for the current query — so "no results" can wait. */
    val settled: Boolean = false,
    /**
     * What source 3 had to say. ⚠ [SemanticSearchStatus.Unavailable] is **not** an error state for
     * the screen: name search may well have found plenty. It is a note explaining a source that
     * did not contribute.
     */
    val semanticStatus: SemanticSearchStatus = SemanticSearchStatus.Available,
    /** True when the result list was cut at the repository's cap rather than exhausted. */
    val capped: Boolean = false,
    /**
     * The sources that were asked and did not answer. ⚠⚠ Never folded into an empty list: a walk
     * that failed found nothing because it looked at nothing.
     */
    val couldNotLook: Set<PhotoHitSource> = emptySet(),
    /** Whose Settings hold the switch a `noConsent` refusal points at: the view model's, fixed. */
    val modelsSwitch: ModelsSwitchHome = ModelsSwitchHome.CONTROL,
) {
    /** ⚠ Empty only once a search has actually settled — never while one is in flight. */
    val isEmpty: Boolean get() = hits.isEmpty() && settled && query.isNotBlank()

    val isIdle: Boolean get() = query.isBlank()

    /**
     * What a tapped result opens the viewer over: the hits **in the order the list shows them**, so
     * swiping walks the results the user is looking at rather than the library behind them.
     */
    val photos: List<Photo> get() = hits.map { it.photo }

    /** Everything both shells draw — see [PhotoSearchLayout]. */
    val layout: PhotoSearchLayout get() = PhotoSearchLayout.of(this)
}

/**
 * Whether search by description can run on this platform at all — the SHELL's answer, given once
 * (`photos-ios.md` §8.19).
 */
sealed interface DescriptionSearch {
    /** The proxy on this device answers `photosLike` — with hits, or with its reason. Android, desktop. */
    data object AskThisDevice : DescriptionSearch

    /**
     * No index exists on this platform and nothing here can build one — iOS.
     *
     * ⚠⚠ The source is then **never asked**. The proxy there would answer `noBackend` today, but its
     * `noConsent` names a switch in Control's Settings → Models, which Control-on-iOS does not have:
     * a screen that relied on the order of the proxy's checks to stay honest would start offering a
     * switch nobody can flip the day that order changed. [device] is the shell's noun for itself.
     */
    data class NotBuilt(val device: String) : DescriptionSearch
}

/**
 * Where *Settings → Models* is for the proxy that answers this app's search: the screen a
 * `noConsent` refusal sends the user to. ⚠ A sentence that names a screen which is not there is
 * worse than none.
 */
enum class ModelsSwitchHome {
    /** Control owns the models and their switches (Android, desktop). */
    CONTROL,

    /** This app runs its own proxy and has its own Models screen (Photos on iOS). */
    THIS_APP,
}

class PhotoSearchViewModel(
    private val repository: PhotosRepository,
    private val semanticLimit: Int = SEMANTIC_LIMIT,
    private val descriptionSearch: DescriptionSearch = DescriptionSearch.AskThisDevice,
    private val timeoutMs: Long = SEARCH_TIMEOUT_MS,
    /**
     * What a `noBackend` refusal means on this device, or `null` for the proxy's own line
     * (`ios-on-device-inference.md` §3.2, *What search shows*). iOS answers
     * [SemanticSearchStatus.NotBuiltHere] once this phone was found unsuitable: "there isn't a model
     * yet" would promise one that will never run here.
     *
     * ⚠ Asked when that refusal arrives, never at construction: the answer changes while the screen
     * is up. ⚠ No other refusal passes through it.
     */
    private val onNoBackend: () -> SemanticSearchStatus? = { null },
    private val modelsSwitch: ModelsSwitchHome = ModelsSwitchHome.CONTROL,
) : ViewModel() {

    // ⚠⚠ Every field is declared before anything can run (`photos-ios.md` §8.16 note 2): on K/N
    // `viewModelScope` is `Main.immediate`, so a launch started from a constructor or from the first
    // call runs at once, and a field below it is read before it exists — a SIGSEGV with the JVM green.

    /** The bar with nothing in it. ⚠ It already says what this platform cannot do. */
    private val idle = PhotoSearchUiState(
        semanticStatus = when (descriptionSearch) {
            DescriptionSearch.AskThisDevice -> SemanticSearchStatus.Available
            is DescriptionSearch.NotBuilt -> SemanticSearchStatus.NotBuiltHere(descriptionSearch.device)
        },
        modelsSwitch = modelsSwitch,
    )

    private val _state = MutableStateFlow(idle)
    val state: StateFlow<PhotoSearchUiState> = _state.asStateFlow()

    /** The search in flight (its debounce included), or `null`. */
    private var job: Job? = null

    /** The trimmed query [job] is for. */
    private var running: String? = null

    /** The trimmed query the hits on screen answer. */
    private var answered: String? = null

    /**
     * Counts every search started. ⚠⚠ A search publishes only while it is still the newest one — the
     * second guard behind cancelling [job], because a repository call does not honour cancellation
     * and its answer can arrive after a newer query was typed.
     */
    private var generation = 0

    fun onQueryChanged(text: String) {
        val wanted = text.trim()
        if (wanted.isEmpty()) {
            abandon()
            answered = null
            // ⚠ The typed text is kept (a bar holding only spaces still shows them); the rest is idle.
            _state.value = idle.copy(query = text)
            return
        }
        // ⚠⚠ The question already answered — a trailing space, or a letter typed and taken back. The
        // answer is on screen and stays there. Un-settling the screen here would run NOTHING (the
        // pipeline drops a query equal to the last one it ran) and leave the spinner up for good:
        // typing "dog" and then a space is enough.
        if (wanted == answered) {
            // ⚠ A quiet re-read of this same answer ([refresh]) is left to finish.
            if (running != wanted) abandon()
            _state.update { it.copy(query = text, searching = false, settled = true) }
            return
        }
        // The question already being asked: let it finish rather than start it again.
        if (wanted == running && job?.isActive == true) {
            _state.update { it.copy(query = text) }
            return
        }
        _state.update {
            it.copy(
                query = text,
                // ⚠ Clearing `settled` on every keystroke is what stops "No photos match" flashing
                // between two letters of a query that will match plenty.
                settled = false,
                searching = true,
            )
        }
        start(wanted, debounce = true)
    }

    fun clear() = onQueryChanged("")

    /**
     * Ask the same question again, now — what "could not look" offers. ⚠ Not debounced (it is a
     * button, not a keystroke), and not a second search beside one already running for this query.
     */
    fun retry() {
        val wanted = _state.value.query.trim()
        if (wanted.isEmpty()) return
        if (wanted == running && job?.isActive == true) return
        _state.update { it.copy(searching = true, settled = false) }
        start(wanted, debounce = false)
    }

    /**
     * Re-read the answer on screen, quietly — the shells call it when the screen comes back from the
     * viewer, where a photograph may have been trashed: a result must not go on citing a photograph
     * that is gone. ⚠ No spinner: the results stay up until the newer answer replaces them. ⚠ Nothing
     * while a search is typed or running — that one is already newer than this would be.
     */
    fun refresh() {
        val wanted = answered ?: return
        if (job?.isActive == true) return
        start(wanted, debounce = false)
    }

    private fun abandon() {
        generation++
        job?.cancel()
        job = null
        running = null
    }

    private fun start(wanted: String, debounce: Boolean) {
        abandon()
        val mine = generation
        running = wanted
        // ⚠⚠ Locals only below (`photos-ios.md` §8.16 note 15): a repository call that never returns
        // keeps its own closure alive for good, and one that captured `this` would keep the view model.
        val repository = repository
        val limit = semanticLimit
        val askSemantic = descriptionSearch == DescriptionSearch.AskThisDevice
        val bound = timeoutMs
        val onNoBackend = onNoBackend
        job = viewModelScope.launch {
            // ⚠⚠ Debounced because source 3 is a **scan**: `photosLike` computes one dot product per
            // indexed photo, per call, by its own KDoc's admission ("not fine to re-read per
            // keystroke"). Source 1 is a full-tree walk. Neither is a per-keystroke cost.
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val answer = lookUp(repository, wanted, limit, askSemantic, bound, onNoBackend)
            // ⚠⚠ Only the newest search publishes: a slow scan for "do" must not overwrite the
            // results for "dog" that the user has since typed.
            if (mine != generation) return@launch
            answered = wanted
            running = null
            // ⚠ Its OWN fields, into the latest state (`photos-ios.md` §8.16 note 5) — never a copy of
            // the state as it was when the search began: the bar's text is not this read's to put back.
            _state.update {
                it.copy(
                    hits = answer.hits,
                    searching = false,
                    settled = true,
                    semanticStatus = answer.semanticStatus ?: it.semanticStatus,
                    capped = answer.capped,
                    couldNotLook = answer.couldNotLook,
                )
            }
        }
    }

    companion object {
        /** Matches Drive's search bar — long enough to swallow a burst, short enough to feel live. */
        const val SEARCH_DEBOUNCE_MS = 250L

        /** ⚠ Kept small: the op scores every indexed vector and the list is a first look. */
        const val SEMANTIC_LIMIT = 30

        /**
         * The repository's own cap on source 1. ⚠ Duplicated as a constant rather than exposed,
         * because the only thing the view model does with it is decide whether to *say* the list
         * was cut — and a screen that quietly shows 60 of 900 is the dishonest version.
         */
        const val NAME_CAP = 60

        /**
         * How long one source may take before it counts as "could not look". ⚠ Generous: source 1
         * walks the whole tree, and a slow answer is still an answer. It bounds a reply that was
         * LOST, which never comes at all.
         */
        const val SEARCH_TIMEOUT_MS = 60_000L
    }
}

/** The proxy's "nothing here can run this": `SemanticSearchUnavailable.Reasons.NO_BACKEND`, as the repository carries it. */
private val NO_BACKEND = SemanticSearchStatus.Unavailable("noBackend")

/** What one search came back with. ⚠ [semanticStatus] `null` = source 3 was never asked on this platform. */
private class SearchAnswer(
    val hits: List<PhotoHit>,
    val semanticStatus: SemanticSearchStatus?,
    val capped: Boolean,
    val couldNotLook: Set<PhotoHitSource>,
)

/** A source's reply. ⚠ A wrapper, because `null` inside it means "the read failed" and no reply means "timed out". */
private class Reply<T>(val value: T?)

/**
 * Asks the three sources, in order, each bounded.
 *
 * ⚠ Sequential, not concurrent. Each source is a scan of the whole library, and running three at
 * once on a phone competes for the one proxy that has to serve the feed too. The ordering also
 * matches how they are merged: the cheapest and most certain first.
 *
 * ⚠⚠ A top-level function over its parameters, never a method: see the closure note in `start`.
 */
private suspend fun lookUp(
    repository: PhotosRepository,
    query: String,
    semanticLimit: Int,
    askSemantic: Boolean,
    timeoutMs: Long,
    onNoBackend: () -> SemanticSearchStatus?,
): SearchAnswer {
    val couldNotLook = LinkedHashSet<PhotoHitSource>()
    // ⚠ After the first source that did not ANSWER in time, the rest are not asked: a lost reply
    // means the proxy is not serving, and each further source would hold the screen one more bound
    // and leave one more scan that may still run (the Trash's rule, `photos-ios.md` §8.17 note 13).
    var unanswered = false

    suspend fun <T> ask(source: PhotoHitSource, call: suspend () -> T?): T? {
        if (unanswered) {
            couldNotLook += source
            return null
        }
        val reply = try {
            boundedCall(timeoutMs) { Reply(call()) }
        } catch (e: CancellationException) {
            // ⚠⚠ A superseded search stops here (`runCatchingCancellable`'s rule) — but a
            // cancellation thrown by the CALL, with this search still wanted, is a failed read: let
            // through, it would end the search without publishing and leave the spinner up for good.
            currentCoroutineContext().ensureActive()
            Reply<T>(null)
        } catch (t: Throwable) {
            // ⚠ A dropped connection reaches a client as a THROW (`photos-ios.md` §8.18 note 4).
            println("PhotoSearchViewModel -> $source could not look: $t")
            Reply<T>(null)
        }
        if (reply == null) {
            println("PhotoSearchViewModel -> $source did not answer in ${timeoutMs}ms")
            unanswered = true
        }
        val value = reply?.value
        if (value == null) couldNotLook += source
        return value
    }

    val byName = ask(PhotoHitSource.NAME) { repository.searchByNameRead(query) }
    val byLabel = ask(PhotoHitSource.LABEL) { repository.searchByLabelRead(query) }
    val semantic = if (askSemantic) ask(PhotoHitSource.SEMANTIC) { repository.searchSemantic(query, semanticLimit) } else null
    val semanticStatus = when {
        !askSemantic -> null
        // ⚠ Asked and not answered is `Failed`, never `Unavailable`: that arm names a cause.
        semantic == null -> SemanticSearchStatus.Failed
        semantic.second == NO_BACKEND -> onNoBackend() ?: semantic.second
        else -> semantic.second.also { if (it == SemanticSearchStatus.Failed) couldNotLook += PhotoHitSource.SEMANTIC }
    }

    // ⚠⚠ One row per photo, carrying every source that found it. Three sources over one library
    // agree often, and a list that repeats a photograph once per source reads as a broken search
    // rather than a thorough one.
    val merged = LinkedHashMap<String, PhotoHit>()
    fun add(path: String, hit: PhotoHit) {
        val existing = merged[path]
        merged[path] = if (existing == null) hit else existing.copy(
            sources = existing.sources + hit.sources,
            matchedLabel = existing.matchedLabel ?: hit.matchedLabel,
        )
    }

    // Insertion order is the result order: name matches lead (checkable at a glance), then labels
    // (they name the word a model chose), then the ranked "looks like" hits.
    byName.orEmpty().forEach { add(it.path, PhotoHit(it, setOf(PhotoHitSource.NAME))) }
    byLabel.orEmpty().forEach { (photo, label) ->
        add(photo.path, PhotoHit(photo, setOf(PhotoHitSource.LABEL), matchedLabel = label))
    }
    semantic?.first.orEmpty().forEach { add(it.path, PhotoHit(it, setOf(PhotoHitSource.SEMANTIC))) }

    return SearchAnswer(
        hits = merged.values.toList(),
        semanticStatus = semanticStatus,
        capped = byName.orEmpty().size >= PhotoSearchViewModel.NAME_CAP,
        couldNotLook = couldNotLook,
    )
}
