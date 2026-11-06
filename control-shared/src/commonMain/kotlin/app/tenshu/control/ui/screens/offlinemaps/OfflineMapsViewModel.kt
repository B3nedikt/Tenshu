package app.tenshu.control.ui.screens.offlinemaps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.runCatchingCancellable

/** Transient user-facing signal, mapped to text by the UI (ADR 0016). */
enum class OfflineMapsMessage { DownloadFailed }

data class OfflineMapsUiState(
    val loading: Boolean = true,
    val regions: List<OfflineMapRegion> = emptyList(),
    /** A region whose download/delete is in flight (its row shows progress + disables actions). */
    val busyRegionId: String? = null,
    /**
     * Bytes of [busyRegionId]'s bundle written so far — non-null **exactly while a download is in
     * flight**, which is also what tells a busy row apart from a deleting one. Starts at 0 (the
     * download really has written nothing while the proxy resolves the catalog entry and opens the
     * staging file) and is then pushed per streamed chunk.
     */
    val downloadedBytes: Long? = null,
    /** @see OfflineMapsSnapshot.catalogUnavailable */
    val catalogUnavailable: Boolean = false,
    /**
     * The list filter. Held here rather than in the composable so it survives configuration change and
     * a re-`refresh()`, and so a test can drive it — but it is only ever *applied* by the pure
     * [groupRegions], never by narrowing [regions], which stays the full set the count is measured against.
     */
    val query: String = "",
    /**
     * A region whose download stopped because the device is on mobile data — the screen asks before
     * spending it (D5 / §7.1). Null means nothing is pending.
     */
    val meteredConsentRegion: OfflineMapRegion? = null,
    val message: OfflineMapsMessage? = null,
)

/**
 * Control's "Offline maps" management view model (`maps-region-delivery.md` §7 / D5).
 * Lists the catalog with install state + sizes; "make available offline" downloads a whole bundle,
 * delete evicts it. Snapshot + re-fetch after a mutation (the install set is small and changes only
 * on explicit user action) — mirrors Maps' `RegionPickerViewModel`, but Control is the canonical
 * owner of this surface.
 */
class OfflineMapsViewModel(
    private val repository: OfflineMapsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OfflineMapsUiState())
    val uiState: StateFlow<OfflineMapsUiState> get() = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val snapshot = repository.regions()
            _uiState.update {
                it.copy(
                    loading = false,
                    regions = snapshot.regions,
                    catalogUnavailable = snapshot.catalogUnavailable,
                )
            }
        }
    }

    /** Re-reads after a failure, showing the loading hint again so the button visibly does something. */
    fun retry() {
        _uiState.update { it.copy(loading = true) }
        refresh()
    }

    fun download(regionId: String, allowMetered: Boolean = false) {
        // One at a time, because the state carries **one** busy region: starting a second would move
        // the slot and leave the first row rendering as idle — with a download button — while its
        // fetch is in fact still running in the proxy. The proxy itself is safe either way (repeated
        // `downloadRegion` joins the in-flight job rather than starting a second), so this guard is
        // about not lying in the list. Same posture as Maps' `keepOffline`.
        if (_uiState.value.busyRegionId != null) return

        viewModelScope.launch {
            _uiState.update { it.copy(busyRegionId = regionId, downloadedBytes = 0L) }

            // Collect the proxy's pushed progress alongside the download (`maps-region-delivery.md`
            // §7.1): a tick per streamed chunk over the `downloadProgress` stream op. The stream
            // ends by itself when the download does; the collector is a child of this coroutine so it
            // also dies with the view model. Mirrors Maps' `PlaceDetailViewModel.keepOffline`, which
            // shows a determinate bar over this same op.
            val progressJob = launch {
                repository.downloadProgress(regionId).collect { written ->
                    // Guarded: a late tick from a download the user has already navigated past must
                    // not repaint the row that is busy now.
                    _uiState.update { if (it.busyRegionId == regionId) it.copy(downloadedBytes = written) else it }
                }
            }

            // runCatching, not a bare call: the region ops can throw on a transport failure, and an
            // escaping throw would skip the cleanup below and strand the row busy forever.
            val outcome = runCatchingCancellable { repository.download(regionId, allowMetered) }
                .getOrDefault(RegionDownloadOutcome.Failed)
            progressJob.cancel()
            _uiState.update { it.copy(busyRegionId = null, downloadedBytes = null) }

            when (outcome) {
                RegionDownloadOutcome.Landed -> refresh()
                // Ask, rather than reporting a failure: nothing is broken and the user has a real choice.
                // The region is carried whole so the prompt can name it and its size without a lookup.
                RegionDownloadOutcome.NeedsMeteredConsent -> _uiState.update { state ->
                    state.copy(meteredConsentRegion = state.regions.firstOrNull { it.regionId == regionId })
                }
                RegionDownloadOutcome.Failed ->
                    _uiState.update { it.copy(message = OfflineMapsMessage.DownloadFailed) }
            }
        }
    }

    fun delete(regionId: String) {
        // Same single-slot reason as [download] — and deleting the bundle a download is mid-way
        // through writing is not something to start by accident.
        if (_uiState.value.busyRegionId != null) return

        viewModelScope.launch {
            _uiState.update { it.copy(busyRegionId = regionId) }
            repository.delete(regionId)
            _uiState.update { it.copy(busyRegionId = null) }
            refresh()
        }
    }

    /** The user answered "yes, use mobile data" — retry the same region with the override. */
    fun confirmMeteredDownload() {
        val pending = _uiState.value.meteredConsentRegion ?: return
        _uiState.update { it.copy(meteredConsentRegion = null) }
        download(pending.regionId, allowMetered = true)
    }

    fun dismissMeteredConsent() = _uiState.update { it.copy(meteredConsentRegion = null) }

    fun search(query: String) = _uiState.update { it.copy(query = query) }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }
}
