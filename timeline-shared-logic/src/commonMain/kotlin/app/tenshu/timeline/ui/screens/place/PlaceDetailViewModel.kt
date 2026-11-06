package app.tenshu.timeline.ui.screens.place

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.timeline.data.RegionBundleResolver
import app.tenshu.timeline.data.RouteRegionStream
import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.model.GeoPoint
import app.tenshu.timeline.model.Place
import app.tenshu.timeline.ui.screens.home.DEFAULT_PERSONALISATION_STRENGTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.runCatchingCancellable

sealed interface PlaceDetailUiState {
    data object Loading : PlaceDetailUiState

    /**
     * The read could not be completed — in practice Control died or was unreachable mid-load.
     * Distinct from [NotFound], which is a *successful* read of a place that does not exist:
     * conflating them told the user their place was gone when it was only unreadable, and left
     * no way to try again. [PlaceDetailViewModel.retry] re-runs the load.
     */
    data object Unavailable : PlaceDetailUiState

    data class Loaded(
        val place: Place,
        val personalisationStrength: Float,
        /** The place's own offline-basemap region for the map hero; null → bundled-demo fallback. */
        val regionStream: RouteRegionStream? = null,
        val regionBundlePath: String? = null,
        /**
         * Other places this one could be folded into (§9.4) — everything else the user has, nearest
         * first. Manual merge exists for the pair the automatic pass deliberately will not touch: two
         * places the user has *named differently*.
         */
        val mergeCandidates: List<Place> = emptyList(),
        /**
         * True when *this* row is the result of a merge, so the screen can offer the undo. ⚠ Derived
         * from "is anything folded into me", because the folded row itself is no longer readable —
         * the reader hides it, which is the whole point.
         */
        val isMerged: Boolean = false,
    ) : PlaceDetailUiState
    data object NotFound : PlaceDetailUiState
}

/** Place detail (Ph3) — identity + a map hero + visit-history timeline; rename (place-geocoding.md G4). */
class PlaceDetailViewModel(
    private val repository: TimelineRepository,
    private val placeId: String,
    private val regions: RegionBundleResolver? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PlaceDetailUiState>(PlaceDetailUiState.Loading)
    val uiState: StateFlow<PlaceDetailUiState> get() = _uiState

    init {
        viewModelScope.launch { load() }
    }

    /**
     * Answer the role prompt, or dismiss it (`place-roles.md` PR-3). Both are the *same* write — a
     * dismissal is `PlaceRoleNames.NONE` — and both are recorded as user-authored, which stops the
     * inference pass touching this place again.
     *
     * Mutate-then-reload, matching [rename]: no optimistic state, so what the screen shows is always
     * what the store actually holds.
     */
    fun setRole(role: String) {
        mutate { repository.setPlaceRole(placeId, role) }
    }

    /** User-authored rename — persists via the repository, then reloads. Blank input ignored. */
    fun rename(label: String) {
        if (label.isBlank()) return
        mutate { repository.renamePlace(placeId, label) }
    }

    /**
     * Fold this place into [otherId] (§9.4). The surviving row is chosen deterministically, not by
     * which one the user opened, so [onMerged] receives the id to navigate to — this screen's place
     * may be the one that disappears.
     *
     * ⚠ [onMerged] fires only when the write actually succeeded — navigating to the surviving id
     * after a failed merge would send the user to a place that was never created.
     */
    fun merge(otherId: String, onMerged: (String) -> Unit = {}) {
        mutate(onWritten = onMerged) { repository.mergePlaces(placeId, otherId) }
    }

    /** Undo a merge — the folded rows become their own places again (§9.4). */
    fun unmerge() {
        mutate { repository.unmergePlace(placeId) }
    }

    /**
     * Write-then-reload, with the same no-escape rule as [load]: these all run in a bare
     * `viewModelScope.launch`, so an IPC throw from a dead Control would otherwise be fatal to the
     * app. A failed write leaves the store untouched, and the reload that follows re-reads whatever
     * is really there — so the screen degrades to the truth rather than to a crash.
     */
    private fun <T> mutate(onWritten: (T) -> Unit = {}, write: suspend () -> T) {
        viewModelScope.launch {
            val written = try {
                write()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                load()
                return@launch
            }
            load()
            onWritten(written)
        }
    }

    /** Nearby name suggestions for the picker (§4) — the loaded place's coordinates; empty if unknown. */
    suspend fun suggestions(): List<String> {
        val place = (_uiState.value as? PlaceDetailUiState.Loaded)?.place ?: return emptyList()
        if (place.latE7 == 0L && place.lngE7 == 0L) return emptyList()
        // Proxy-mediated egress over IPC — the picker falls back to free text, never to a crash.
        return try {
            repository.placeSuggestions(place.latE7, place.lngE7)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Re-run the load after an [PlaceDetailUiState.Unavailable] verdict (user-driven). */
    fun retry() {
        _uiState.value = PlaceDetailUiState.Loading
        viewModelScope.launch { load() }
    }

    /**
     * ⚠⚠ Every read here goes over IPC to Control, so **each one can throw or stall** when Control
     * dies — which on Android it does routinely (OEM kills, and an OOMing proxy). Two rules follow,
     * and both were violated before:
     *
     * 1. **Nothing escapes.** This runs inside a bare `viewModelScope.launch`, so an uncaught throw
     *    is an uncaught *coroutine* exception, which Android treats as fatal — a dead Control took
     *    the whole Timeline process down with `android.os.DeadObjectException` (2026-08-03). The
     *    transport now folds that into a typed failure, but a defensive catch is still correct
     *    here: this is the boundary where "the read failed" becomes "the screen says so".
     * 2. **Publish the place before enriching it.** The merge-candidate join reads the *whole*
     *    place list and awaits a `combine` of three streams; it is far more likely to stall than
     *    the single-row read. Gathering it *before* the first `_uiState` write meant one slow
     *    source parked the screen on [PlaceDetailUiState.Loading] forever — the observed "screen
     *    that just shows …". The place is now shown as soon as it is readable, and the candidates
     *    fill in behind it (or never, harmlessly — they only gate an optional action).
     */
    private suspend fun load() {
        val place = try {
            repository.place(placeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.value = PlaceDetailUiState.Unavailable
            return
        }
        if (place == null) {
            _uiState.value = PlaceDetailUiState.NotFound
            return
        }
        val base = PlaceDetailUiState.Loaded(
            place = place,
            personalisationStrength = DEFAULT_PERSONALISATION_STRENGTH,
        )
        _uiState.value = base
        // Structural extras — a failure here must not unshow the place that already rendered.
        _uiState.value = try {
            base.copy(
                mergeCandidates = repository.places().first()
                    .filter { it.id != place.id }
                    .sortedBy { it.name.ifBlank { it.id } },
                isMerged = repository.mergedPlaceCount(place.id) > 0,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            base
        }
        // Show the place immediately on the bundled-demo basemap, then upgrade to the place's own
        // downloaded/streamed region tiles once the resolver has one — mirrors TripDetailViewModel.
        val enriched = _uiState.value as? PlaceDetailUiState.Loaded ?: return
        val resolver = regions
        if (resolver == null || (place.latE7 == 0L && place.lngE7 == 0L)) return
        val point = listOf(GeoPoint(latitude = place.latE7 / 1e7, longitude = place.lngE7 / 1e7))
        val stream = runCatchingCancellable { resolver.streamHandle(point) }.getOrNull()
        // ⚠ Builds on [enriched], not on `base` — the merge-candidate join already landed above and
        // copying from `base` here would silently drop it (and with it the Merge action).
        _uiState.value = when {
            stream != null -> enriched.copy(regionStream = stream)
            else -> runCatchingCancellable { resolver.bundlePath(point) }.getOrNull()
                ?.let { enriched.copy(regionBundlePath = it) } ?: enriched
        }
    }
}
