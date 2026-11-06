package app.tenshu.timeline.ui.screens.moment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.timeline.data.RegionBundleResolver
import app.tenshu.timeline.data.RouteRegionStream
import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.model.GeoPoint
import app.tenshu.timeline.model.Moment
import app.tenshu.timeline.model.MomentKind
import app.tenshu.timeline.model.Photo
import app.tenshu.timeline.ui.screens.home.DEFAULT_PERSONALISATION_STRENGTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.runCatchingCancellable

sealed interface MomentDetailUiState {
    data object Loading : MomentDetailUiState

    /**
     * The read could not be completed — in practice Control died or was unreachable mid-load.
     * Distinct from [NotFound], which is a *successful* read of a moment that does not exist.
     * [MomentDetailViewModel.retry] re-runs the load.
     */
    data object Unavailable : MomentDetailUiState
    /** The moment's [Moment.kind] drives which of the four anatomies the screen renders (§1.3). */
    data class Loaded(
        val moment: Moment,
        val personalisationStrength: Float,
        /**
         * A [MomentKind.Movement] moment's decoded path (M5, `timeline-movement-capture.md` §7) —
         * the route the detail hero draws over the offline basemap. Empty for every other kind, and
         * for a movement whose trace is unknown or whose polyline was dropped (stats-only then).
         */
        val route: List<GeoPoint> = emptyList(),
        /** The route's own offline-basemap region for the map hero; null → bundled-demo fallback. */
        val regionStream: RouteRegionStream? = null,
        val regionBundlePath: String? = null,
        /**
         * A photo the user took while this moment was happening, for the hero
         * (`timeline-photos.md` T-P1), with its encoded preview [coverPhotoBytes]. Both are `null`
         * together and stay that way unless there really is an image to draw: no photo, a preview
         * this device has not fetched, and an unreachable Control are all the same outcome, so a
         * moment never grows an empty hero it did not have before.
         *
         * The bytes stay **raw** — the screen decodes them, which is what keeps this class
         * Compose-free and compiling on iOS.
         */
        val coverPhoto: Photo? = null,
        val coverPhotoBytes: ByteArray? = null,
    ) : MomentDetailUiState
    data object NotFound : MomentDetailUiState
}

/** Moment detail (Ph3). The moment can live in a home day or any (nested) trip. */
class MomentDetailViewModel(
    private val repository: TimelineRepository,
    private val momentId: String,
    /**
     * Resolves a movement's route to its offline-basemap region — **optional/best-effort** exactly
     * like the trip/place details: absent (standalone graph), or on any resolution failure, the
     * route still renders on the bundled demo basemap.
     */
    private val regions: RegionBundleResolver? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow<MomentDetailUiState>(MomentDetailUiState.Loading)
    val uiState: StateFlow<MomentDetailUiState> get() = _uiState

    init {
        // Entry: the feed just projected this moment, so reuse it rather than re-deriving.
        viewModelScope.launch { load(fresh = false) }
    }

    /** Re-run the load after an [MomentDetailUiState.Unavailable] verdict (user-driven). */
    fun retry() {
        _uiState.value = MomentDetailUiState.Loading
        // A user-driven retry after a failure must not be answered from a cached projection.
        viewModelScope.launch { load(fresh = true) }
    }

    /**
     * Rename the visit's place from the detail (§4) — the same user-authored rename the place detail
     * offers, so a mis-named stay can be fixed where it's noticed. Reloads so the subtitle updates.
     */
    fun rename(label: String) {
        val placeId = loadedMoment()?.placeId ?: return
        if (label.isBlank()) return
        viewModelScope.launch {
            // Same no-escape rule as [load]: a bare launch makes an IPC throw fatal to the app.
            try {
                repository.renamePlace(placeId, label)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The write never landed; the reload below shows what the store really holds.
            }
            // ⚠ fresh: the feed's projection predates this write, so reusing it would show the
            // rename as having silently failed.
            load(fresh = true)
        }
    }

    /** Nearby name suggestions for the picker (§4) — the visit's place coordinates; empty if unknown. */
    suspend fun suggestions(): List<String> {
        val m = loadedMoment() ?: return emptyList()
        if (m.latE7 == 0L && m.lngE7 == 0L) return emptyList()
        // Proxy-mediated egress over IPC — the picker falls back to free text, never to a crash.
        return try {
            repository.placeSuggestions(m.latE7, m.lngE7)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadedMoment(): Moment? = (_uiState.value as? MomentDetailUiState.Loaded)?.moment

    /**
     * ⚠⚠ Every read here crosses IPC, so it can throw or stall when Control dies — which on Android
     * it does routinely. Nothing may escape: this runs in a bare `viewModelScope.launch`, and an
     * uncaught coroutine exception is fatal on Android (observed 2026-08-03 as
     * `android.os.DeadObjectException` killing Timeline whenever an OOMing Control restarted).
     */
    private suspend fun load(fresh: Boolean) {
        val moment = try {
            repository.moment(momentId, fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.value = MomentDetailUiState.Unavailable
            return
        }
        if (moment == null) {
            _uiState.value = MomentDetailUiState.NotFound
            return
        }
        // Show the moment immediately; a movement then upgrades in place — first the decoded route
        // (its own read: the polyline blob is deliberately absent from the feed projection), then
        // the route's streamed/staged region tiles — mirroring PlaceDetailViewModel's hero.
        val base = MomentDetailUiState.Loaded(moment, DEFAULT_PERSONALISATION_STRENGTH)
        _uiState.value = base
        // A movement's hero is its route; everything else's is a photo. Only one of the two ever
        // renders, so only one of the two is ever read.
        if (!loadRouteHero(base, moment)) loadCoverPhoto(moment)
    }

    /**
     * The movement hero (M5). Returns whether the map is what the screen ended up showing — false
     * for every other kind, and for a movement whose trace is missing or too short to draw, which
     * falls back to the photo hero like any other moment.
     */
    private suspend fun loadRouteHero(base: MomentDetailUiState.Loaded, moment: Moment): Boolean {
        // ⚠ traceIds, not traceId: a collapsed transit ride has several legs behind one row, and the
        // hero must draw the whole journey or it contradicts the card's combined distance.
        val traceIds = moment.traceIds.ifEmpty { listOfNotNull(moment.traceId) }
        if (moment.kind != MomentKind.Movement || traceIds.isEmpty()) return false
        // Best-effort: the moment already rendered, so a failed route read must not unshow it.
        val route = try {
            repository.movementRoute(traceIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        if (route.size < 2) return false
        _uiState.value = base.copy(route = route)
        val resolver = regions ?: return true
        val stream = runCatchingCancellable { resolver.streamHandle(route) }.getOrNull()
        _uiState.value = when {
            stream != null -> base.copy(route = route, regionStream = stream)
            else -> runCatchingCancellable { resolver.bundlePath(route) }.getOrNull()
                ?.let { base.copy(route = route, regionBundlePath = it) }
                ?: base.copy(route = route)
        }
        return true
    }

    /**
     * The photo hero (`timeline-photos.md` T-P1…T-P3) — bytes for the first photo the *projection*
     * assigned to this moment, upgraded in place exactly like the route: the moment is already on
     * screen, so a moment with no photo, a device that has not fetched its preview, and an
     * unreachable Control are all the same outcome here (the gradient placeholder stays).
     *
     * ⚠ Since T-P3 the moment arrives carrying its photos, so this no longer asks *which* — it only
     * fetches pixels for the one it is about to draw. That is what makes the hero and the feed row
     * show the same photograph.
     */
    private suspend fun loadCoverPhoto(moment: Moment) {
        val photo = moment.photos.firstOrNull() ?: return
        val uri = photo.uri ?: return
        val bytes = try {
            repository.photoBytes(
                uri,
                width = COVER_EDGE,
                height = COVER_EDGE,
                heroFidelity = true,
            ) ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        // Copy onto whatever is on screen now, not onto a stale snapshot — a rename may have
        // reloaded the moment while the bytes were in flight.
        val current = _uiState.value as? MomentDetailUiState.Loaded ?: return
        _uiState.value = current.copy(coverPhoto = photo, coverPhotoBytes = bytes)
    }

    private companion object {
        /**
         * The hero's requested preview size — **square**, and sized to the widest hero this app
         * draws anywhere.
         *
         * ⚠ Square rather than the band's own 220.dp-tall shape, because the hero draws with
         * `ContentScale.Crop` while `GetPreview` *fits*: asking for the band's aspect returns a
         * photo only as wide as the band is tall, which the crop then blows straight back up. This
         * is the same rule `previewRequestPx` applies UI-side; see its KDoc for the worked example.
         *
         * ⚠ 1600 covers a retina desktop hero (840 pt content cap × 2) and a phone (≈400 pt × 3)
         * with a little margin. The earlier 1080 × 720 was chosen when "generous enough that the
         * `LARGE` stored class answers" was the goal.
         *
         * ⚠ It must stay above `GetStoredThumbnail.LARGE_PX`, or `heroFidelity` does not ask for a
         * hero rung by name at all (`asksForHeroByName`).
         *
         * ⚠ Since X-6 it is **`XXLARGE` that answers this**, not `XLARGE`: 1600 is above
         * `XLARGE_PX`, so the named read asks for the 2048 px class first and falls back to the
         * 1024 px one only where no device minted the larger. The old note about staying inside
         * `GetPreview`'s 2x cover tolerance described the fallback arm — `XLARGE` stretched 1.56x —
         * which is now what happens when `XXLARGE` does not exist, rather than the normal case.
         */
        const val COVER_EDGE = 1600
    }
}
