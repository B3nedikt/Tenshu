package app.tenshu.control.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.AppRegistry
import app.tenshu.control.data.BundledAppManifestSource
import app.tenshu.control.data.InstalledApp
import app.tenshu.control.data.OfflineMapsSuggestionPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OfflineMapsSuggestionUiState(val visible: Boolean = false)

/**
 * Home's "you could download a region" suggestion (`maps-region-delivery.md` §7 phase 7).
 *
 * ⚠⚠ **This is deliberately NOT the two-option onboarding screen §7 phase 7 describes.** That screen —
 * *"Start now: your maps download as you go"* vs *"Download maps: available offline, more private"* —
 * was designed when streaming was not yet the render default. D10 shipped and made it the default, so
 * the map already works on first launch with nothing chosen. Presenting that choice now would demand a
 * decision that no longer needs making, at the worst possible moment, and ask for hundreds of megabytes
 * before the user has seen a map at all. What is actually missing is not a choice but **discovery**: a
 * user who installs Fit never learns that offline regions exist or that Control is where they live.
 *
 * Shown when all three hold:
 *  - a map-capable app is installed ([MAP_CAPABLE_APP_IDS]);
 *  - **no** region is installed yet — with one already downloaded, the feature has been found;
 *  - the user has not dismissed it on this device.
 */
class OfflineMapsSuggestionViewModel(
    private val preferences: OfflineMapsSuggestionPreferences,
    installedApps: Flow<List<InstalledApp>>,
    /**
     * Whether any region is installed. `null` means **we could not tell** — the store read failed —
     * and the suggestion stays hidden.
     *
     * ⚠ `== false`, never `!= true`: the same trap `RecoveryAffordanceViewModel` documents, where an
     * unknown answer read as the alarming one. Here the cost is only a pointless nudge, but the rule
     * is worth keeping identical across both banners rather than reasoned about twice.
     */
    private val anyRegionInstalled: suspend () -> Boolean?,
) : ViewModel() {

    private val regionInstalled = MutableStateFlow<Boolean?>(null)

    init {
        viewModelScope.launch { regionInstalled.value = anyRegionInstalled() }
    }

    val uiState: StateFlow<OfflineMapsSuggestionUiState> = combine(
        preferences.observeDismissed().onStart { emit(false) },
        installedApps.onStart { emit(emptyList()) },
        regionInstalled,
    ) { dismissed, apps, installed ->
        val mapCapable = apps.any { it.appId in MAP_CAPABLE_APP_IDS }
        OfflineMapsSuggestionUiState(visible = !dismissed && mapCapable && installed == false)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = OfflineMapsSuggestionUiState(visible = false),
    )

    /** Hides the suggestion permanently on this device. */
    fun onDismiss() {
        viewModelScope.launch { preferences.setDismissed() }
    }

    companion object {
        /**
         * First-party apps that render a basemap, and therefore benefit from a downloaded region.
         *
         * ⚠⚠ **An explicit list, because no runtime signal answers this.** The obvious candidate —
         * `AppDetail.services`, the service ids an app's *declared permissions* touch — does not:
         * Maps declares `mapRegion/service` (for `downloadRegion`/`deleteRegion`) and Timeline does
         * (for `reverseGeocodeCandidates`/`railLinesIn`), but **Fit declares none at all** while
         * still drawing its workout routes. Those manifests list *sensitive* capabilities, not every
         * op an app calls, so gating on them would have silently excluded Fit — the exact app a
         * maps-curious user might be the only one to have installed.
         *
         * ⚠ The cost of that honesty is maintenance: a new map-rendering app must be added here, and
         * forgetting means its users never see the suggestion. That failure is quiet but small — the
         * feature stays reachable at Settings → Offline maps either way.
         */
        val MAP_CAPABLE_APP_IDS: Set<String> = setOf(
            BundledAppManifestSource.MAPS_APP_ID,
            BundledAppManifestSource.TIMELINE_APP_ID,
            BundledAppManifestSource.FIT_APP_ID,
        )
    }
}
