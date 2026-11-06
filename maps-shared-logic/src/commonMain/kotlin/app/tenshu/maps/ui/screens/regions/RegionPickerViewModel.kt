package app.tenshu.maps.ui.screens.regions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.maps.data.RegionRepository
import app.tenshu.maps.data.RegionsRead
import app.tenshu.maps.model.RegionItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Transient user-facing signal, as a locale-independent code the UI maps to text (ADR 0016).
 * [DownloadUnavailable] is emitted while the region-download op is a later slice (the seam
 * returns `false`).
 */
enum class RegionPickerMessage {
    DownloadUnavailable,

    /**
     * ⚠ The catalogue could not be read — distinct from a catalogue with nothing in it. Without
     * this the picker rendered an empty list and let the user conclude there are no maps to
     * download, which is the one thing this screen exists to offer.
     */
    CatalogUnavailable,
}

data class RegionPickerUiState(
    val loading: Boolean = true,
    val regions: List<RegionItem> = emptyList(),
    val message: RegionPickerMessage? = null,
)

/**
 * Region-picker view model (`maps-region-delivery.md` D5 — Maps owns the picker). Lists the
 * catalog with install state + sizes, deletes an installed region, and offers download (the seam,
 * coming-soon for now). Snapshot + re-fetch after a mutation (the picker's install state is small
 * and changes only on explicit user action).
 */
class RegionPickerViewModel(
    private val repository: RegionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RegionPickerUiState())
    val uiState: StateFlow<RegionPickerUiState> get() = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            when (val read = repository.regions()) {
                // ⚠ `regions` is left as it was rather than cleared: on a refresh after a failed
                // mutation the last good catalogue is better than a blank screen, and the message
                // is what says the list may be stale.
                is RegionsRead.Unavailable ->
                    _uiState.update {
                        it.copy(loading = false, message = RegionPickerMessage.CatalogUnavailable)
                    }

                is RegionsRead.Regions ->
                    _uiState.update { it.copy(loading = false, regions = read.regions) }
            }
        }
    }

    fun download(regionId: String) {
        viewModelScope.launch {
            if (repository.download(regionId)) {
                refresh()
            } else {
                _uiState.update { it.copy(message = RegionPickerMessage.DownloadUnavailable) }
            }
        }
    }

    fun delete(regionId: String) {
        viewModelScope.launch {
            repository.delete(regionId)
            refresh()
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }
}
