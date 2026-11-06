package app.tenshu.timeline.ui.screens.trip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.timeline.data.RegionBundleResolver
import app.tenshu.timeline.data.RouteRegionStream
import app.tenshu.timeline.data.TripOfflineOffer
import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.model.Trip
import app.tenshu.timeline.ui.screens.home.DEFAULT_PERSONALISATION_STRENGTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.runCatchingCancellable

sealed interface TripDetailUiState {
    data object Loading : TripDetailUiState

    /**
     * The read could not be completed — in practice Control died or was unreachable mid-load.
     * Distinct from [NotFound], which is a *successful* read of a trip that does not exist.
     * [TripDetailViewModel.retry] re-runs the load.
     */
    data object Unavailable : TripDetailUiState
    data class Loaded(
        val trip: Trip,
        val personalisationStrength: Float,
        /** The staged whole-region `.pmtiles` for this trip's route, or null → bundled demo. */
        val regionBundlePath: String? = null,
        /** The D10 streamed source for this trip's route (preferred over [regionBundlePath]). */
        val regionStream: RouteRegionStream? = null,
        /**
         * Every region this trip's route crosses, offered for offline keeping (track 3d). Null = nothing
         * covers the route (no row). The route renders regardless — this buys availability, not a map.
         */
        val offlineOffer: TripOfflineOffer? = null,
        /** A download of the offer's missing regions is in flight (each is hundreds of MB). */
        val offlineDownloading: Boolean = false,
        /**
         * Fraction of the **whole set** landed so far, `0f..1f` — regions already finished plus the bytes
         * of the one in flight, over the total missing. Polled from the proxy's staging file, so it is the
         * real count on disk. Null when nothing is downloading.
         */
        val offlineProgress: Float? = null,
        /**
         * At least one region failed. Surfaced rather than swallowed — a multi-minute fetch that silently
         * does nothing leaves the user staring at an unchanged row. The route keeps streaming meanwhile, so
         * this is a disappointment, not a breakage, and the row stays tappable to retry (only the regions
         * that failed are re-fetched).
         */
        val offlineFailed: Boolean = false,
    ) : TripDetailUiState
    data object NotFound : TripDetailUiState
}

/**
 * Trip detail (Ph3). Recursive: opening a nested trip instantiates this same VM with the
 * nested trip's id — the repo's [TimelineRepository.trip] resolves nested trips uniformly.
 *
 * [regions] resolves the trip route to an offline-basemap region path (render-hop terminus,
 * `maps-region-delivery.md` §7); it is **optional/best-effort** — absent in the standalone graph,
 * a resolution failure never blocks the trip loading, the route just renders on the bundled demo.
 */
class TripDetailViewModel(
    private val repository: TimelineRepository,
    private val regions: RegionBundleResolver?,
    private val tripId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow<TripDetailUiState>(TripDetailUiState.Loading)
    val uiState: StateFlow<TripDetailUiState> get() = _uiState

    init {
        viewModelScope.launch { load() }
    }

    /** Re-run the load after a [TripDetailUiState.Unavailable] verdict (user-driven). */
    fun retry() {
        _uiState.value = TripDetailUiState.Loading
        viewModelScope.launch { load() }
    }

    /**
     * ⚠⚠ Every read here crosses IPC, so it can throw or stall when Control dies. Nothing may
     * escape: this runs in a bare `viewModelScope.launch`, and an uncaught coroutine exception is
     * fatal on Android (observed 2026-08-03 as `android.os.DeadObjectException` killing Timeline
     * whenever an OOMing Control restarted).
     */
    private suspend fun load() {
        val trip = try {
            repository.trip(tripId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.value = TripDetailUiState.Unavailable
            return
        }
        if (trip == null) {
            _uiState.value = TripDetailUiState.NotFound
            return
        }
        // Show the trip immediately on the bundled demo basemap, then upgrade to the trip's
        // downloaded region once the resolver has staged it (the drain can be slow — its
        // dispatching lives in the resolver impl, so this VM stays test-synchronous).
        _uiState.value = TripDetailUiState.Loaded(trip, DEFAULT_PERSONALISATION_STRENGTH)
        val route = trip.route
        val resolver = regions
        if (route.size < 2 || resolver == null) return
        // Prefer the D10 streamed source (fetch only the route's covering tiles); fall back
        // to a fully-downloaded region file only when no stream is available.
        val stream = runCatchingCancellable { resolver.streamHandle(route) }.getOrNull()
        if (stream != null) {
            _uiState.value = TripDetailUiState.Loaded(
                trip, DEFAULT_PERSONALISATION_STRENGTH, regionStream = stream,
            )
        } else {
            val path = runCatchingCancellable { resolver.bundlePath(route) }.getOrNull()
            if (path != null) {
                _uiState.value =
                    TripDetailUiState.Loaded(trip, DEFAULT_PERSONALISATION_STRENGTH, path)
            }
        }

        // The contextual offline offer (track 3d) — resolved last, because it is strictly an
        // extra: the route is already drawn on a streaming basemap by now.
        val offer = runCatchingCancellable { resolver.offlineOffer(route) }.getOrNull()
        updateLoaded { it.copy(offlineOffer = offer) }
    }

    /**
     * "Keep this trip's map areas offline" (track 3d): downloads **every** region the route crosses that
     * is not resident yet, so the whole journey works with no network — not just the half under the
     * centroid.
     *
     * Downloads run one region at a time (each is hundreds of MB; parallel whole-bundle fetches would
     * fight for the same pipe and disk). Partial success is kept: a region that lands is marked resident,
     * one that fails stays in the offer, so a retry only re-fetches what is still missing. A no-op when
     * there is nothing missing or a download is already in flight.
     */
    fun keepOffline() {
        val resolver = regions ?: return
        val state = _uiState.value as? TripDetailUiState.Loaded ?: return
        val missing = state.offlineOffer?.missing.orEmpty()
        if (missing.isEmpty() || state.offlineDownloading) return

        // Progress is over the WHOLE set: a trip crossing two regions should not jump back to 0 % when the
        // second one starts.
        val totalBytes = missing.sumOf { it.byteSize }

        viewModelScope.launch {
            updateLoaded {
                it.copy(offlineDownloading = true, offlineFailed = false, offlineProgress = 0f)
            }
            val landed = mutableSetOf<String>()
            var completedBytes = 0L
            var anyFailed = false

            for (region in missing) {
                // Collect the proxy's pushed byte count for the region currently in flight (§7.1 download
                // UX): a real push per streamed chunk, not a poll. The flow completes on its own when the
                // region finishes; the collector is a child coroutine, so it also dies with the VM.
                val progressJob = launch {
                    resolver.downloadProgress(region.regionId).collect { staged ->
                        if (totalBytes > 0) {
                            val fraction = ((completedBytes + staged).toFloat() / totalBytes).coerceIn(0f, 1f)
                            updateLoaded { it.copy(offlineProgress = fraction) }
                        }
                    }
                }

                val ok = runCatchingCancellable { resolver.download(region.regionId) }.getOrNull() == true
                progressJob.cancel()

                if (ok) {
                    landed += region.regionId
                    completedBytes += region.byteSize
                } else {
                    anyFailed = true
                }
            }

            updateLoaded { loaded ->
                val refreshed = loaded.offlineOffer?.let { offer ->
                    offer.copy(
                        regions = offer.regions.map { region ->
                            if (region.regionId in landed) region.copy(installed = true) else region
                        },
                    )
                }
                loaded.copy(
                    offlineOffer = refreshed,
                    offlineDownloading = false,
                    offlineProgress = null,
                    offlineFailed = anyFailed,
                )
            }
        }
    }

    /** Updates the loaded state in place; a no-op in any other state (NotFound / still loading). */
    private fun updateLoaded(block: (TripDetailUiState.Loaded) -> TripDetailUiState.Loaded) {
        val current = _uiState.value
        if (current is TripDetailUiState.Loaded) _uiState.value = block(current)
    }
}
