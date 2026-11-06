package app.tenshu.maps.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.maps.data.PlaceRepository
import app.tenshu.maps.data.OfflineRegionOffer
import app.tenshu.maps.data.RegionRepository
import app.tenshu.maps.data.RegionStreamHandle
import app.tenshu.maps.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * ⚠ [ObjCName] on this type and each of its members: `app.tenshu.timeline.ui.screens.place`'s
 * `PlaceDetailUiState` shares the `TenshuSdk` umbrella, and Kotlin/Native would resolve the clash by
 * appending `_` to whichever fq name sorts later — silently renaming the *shipped* Timeline type.
 * The newcomer takes the qualified name (see `app.tenshu.maps.model.Place`). Kotlin-side untouched.
 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("MapsPlaceDetailUiState")
sealed interface PlaceDetailUiState {
    @OptIn(kotlin.experimental.ExperimentalObjCName::class)
    @kotlin.native.ObjCName("Loading")
    data object Loading : PlaceDetailUiState
    /**
     * The map snippet's tile source (`maps-region-delivery.md` §7 / D10), in preference order:
     * [regionStream] (the D10 streamed default — fetch only the covering tiles), else
     * [regionBundlePath] (a fully-downloaded region file), else null (the bundled demo).
     */
    @OptIn(kotlin.experimental.ExperimentalObjCName::class)
    @kotlin.native.ObjCName("Loaded")
    data class Loaded(
        val place: Place,
        val regionBundlePath: String? = null,
        val regionStream: RegionStreamHandle? = null,
        /**
         * The region covering this place, offered for offline keeping (track 3d). Null = nothing covers
         * it (no row). The map renders regardless — this buys availability without a network.
         */
        val offlineOffer: OfflineRegionOffer? = null,
        /** A download of [offlineOffer] is in flight (it is a whole bundle — hundreds of MB). */
        val offlineDownloading: Boolean = false,
        /**
         * Fraction of [offlineOffer] landed so far, `0f..1f` — polled from the proxy's staging file, so it
         * is the **real** bytes on disk, not an estimate. Null when nothing is downloading.
         */
        val offlineProgress: Float? = null,
        /**
         * The last download failed. Surfaced rather than swallowed: a hundreds-of-MB fetch that silently
         * does nothing is the worst possible outcome — the user is left staring at an unchanged row. The
         * map keeps streaming meanwhile, so this is a disappointment, not a breakage; the row says so and
         * stays tappable to retry.
         */
        val offlineFailed: Boolean = false,
    ) : PlaceDetailUiState
    @OptIn(kotlin.experimental.ExperimentalObjCName::class)
    @kotlin.native.ObjCName("NotFound")
    data object NotFound : PlaceDetailUiState
}

/**
 * Place detail (Ph3, docs/proposals/maps-app.md §6) — the canonical, embeddable detail (D2):
 * single pin + visit history + bookmark state. Collected **reactively** from `places()` (not
 * a one-shot getter) so the header's bookmark toggle reflects [setSaved] live. Timeline &
 * Photos will embed the Ph4 composable over this same VM shape.
 *
 * The map snippet's offline region ([regionRepository]) is resolved once off the main thread and
 * combined into the [Loaded] state; a failure or no-installed-region leaves it null (demo basemap).
 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("MapsPlaceDetailViewModel")
class PlaceDetailViewModel(
    private val repository: PlaceRepository,
    private val regionRepository: RegionRepository,
    private val placeId: String,
    /**
     * Where the three region lookups below run. Injectable **so a test can put them under its own
     * scheduler** — otherwise `init`'s work hops to a real thread pool that `runTest`'s virtual
     * clock cannot reach, and a test is left polling for a result under a wall-clock timeout. That
     * is what `PlaceDetailOfflineOfferTest` did, and it is a named flake in `docs/tech-debt.md`.
     * `docs/testing.md` §10 carries the rule.
     */
    private val regionLookupContext: CoroutineContext = Dispatchers.Default,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PlaceDetailUiState>(PlaceDetailUiState.Loading)
    val uiState: StateFlow<PlaceDetailUiState> get() = _uiState

    private val regionBundlePath = MutableStateFlow<String?>(null)
    private val regionStream = MutableStateFlow<RegionStreamHandle?>(null)
    private val offlineOffer = MutableStateFlow<OfflineRegionOffer?>(null)
    private val offlineDownloading = MutableStateFlow(false)
    private val offlineProgress = MutableStateFlow<Float?>(null)
    private val offlineFailed = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            // Resolve the region **for this place's coordinate** — catalog regions overlap (a city box
            // inside a country box) and the tightest one covering the place is the right basemap (D7),
            // so the coordinate has to be known first.
            //
            // **Wait for the place to appear**; do not sample the first emission. The projected
            // repository emits an empty list before its store has loaded, so a `.first()` would see
            // "no such place", skip resolution, and strand the snippet on the demo basemap forever —
            // even once the place arrived. A place that never appears just leaves this coroutine
            // suspended for the VM's lifetime (the UI is NotFound anyway).
            val place = repository.places()
                .mapNotNull { places -> places.firstOrNull { it.id == placeId } }
                .first()
            // May be null (a place with no coordinate) — the terminus then falls back to the first
            // region, exactly as before this became coverage-aware.
            val location = place.location

            // Prefer the D10 streamed default (a lazy handle — fetch only the covering tiles); fall
            // back to a fully-downloaded region file only when no stream is available.
            val stream = withContext(regionLookupContext) {
                runCatchingCancellable { regionRepository.renderableStream(location) }.getOrNull()
            }
            regionStream.value = stream
            if (stream == null) {
                regionBundlePath.value = withContext(regionLookupContext) {
                    runCatchingCancellable { regionRepository.renderableRegionPath(location) }.getOrNull()
                }
            }

            // The contextual offline offer (track 3d) — resolved after the render source, because it is
            // strictly an extra: the map is already up by now, streaming.
            offlineOffer.value = withContext(regionLookupContext) {
                runCatchingCancellable { regionRepository.offlineOffer(location) }.getOrNull()
            }
        }

        viewModelScope.launch {
            val place = repository.places()
                .map { places -> places.firstOrNull { it.id == placeId } }
            // combine() tops out at five typed flows, so the offline-offer trio is folded into one first.
            val offline = combine(
                offlineOffer, offlineDownloading, offlineProgress, offlineFailed,
            ) { offer, downloading, progress, failed ->
                OfflineState(offer, downloading, progress, failed)
            }
            combine(place, regionBundlePath, regionStream, offline) { found, path, stream, o ->
                found
                    ?.let {
                        PlaceDetailUiState.Loaded(
                            it, path, stream, o.offer, o.downloading, o.progress, o.failed,
                        )
                    }
                    ?: PlaceDetailUiState.NotFound
            }.collect { _uiState.value = it }
        }
    }

    /** Bookmark toggle in the detail header (`variant="saved"`). Re-emits through [places]. */
    fun setSaved(saved: Boolean) {
        viewModelScope.launch { repository.setSaved(placeId, saved) }
    }

    /**
     * "Keep this area offline" (track 3d): downloads the whole covering region into Control's store, so
     * the map works with no network and no per-viewport request reaches the tile host (T-MR-03).
     *
     * A no-op when there is nothing to offer, it is already resident, or a download is in flight — the
     * bundle is hundreds of MB, so a double-tap must not start it twice. A failure just clears the
     * in-flight flag and leaves the offer standing (the map keeps streaming, so nothing is broken).
     */
    fun keepOffline() {
        val offer = offlineOffer.value ?: return
        if (offer.installed || offlineDownloading.value) return
        viewModelScope.launch {
            offlineDownloading.value = true
            offlineFailed.value = false
            offlineProgress.value = 0f

            // Collect the proxy's pushed progress while the download runs (§7.1 download UX): the proxy
            // ticks a device-local flow per streamed chunk over the `downloadProgress` stream op — a real
            // push, not a poll. The flow completes on its own when the download finishes; the collector is
            // a child of this coroutine, so it also dies with the download / the VM.
            val progressJob = launch {
                regionRepository.downloadProgress(offer.regionId).collect { landed ->
                    if (offer.byteSize > 0) {
                        offlineProgress.value = (landed.toFloat() / offer.byteSize).coerceIn(0f, 1f)
                    }
                }
            }

            val landed = runCatchingCancellable { regionRepository.download(offer.regionId) }.getOrNull() == true
            progressJob.cancel()

            offlineDownloading.value = false
            offlineProgress.value = null
            if (landed) {
                offlineOffer.value = offer.copy(installed = true)
            } else {
                offlineFailed.value = true
            }
        }
    }

    /** The four offline-offer flows, folded so the state combine stays within combine()'s arity. */
    private data class OfflineState(
        val offer: OfflineRegionOffer?,
        val downloading: Boolean,
        val progress: Float?,
        val failed: Boolean,
    )
}
