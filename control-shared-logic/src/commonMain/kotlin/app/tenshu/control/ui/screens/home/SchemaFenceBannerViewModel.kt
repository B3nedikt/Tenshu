package app.tenshu.control.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Control's "Update Control to keep syncing" home banner
 * (`core-schema-skew-surface.md` §4 case "A, owner is Control").
 *
 * The schema pull fence stops this device pulling once another device in the realm writes at a
 * newer schema. Nothing else looks wrong while it holds — the proxy stays reachable, pushes keep
 * going, local reads work — so without this a fenced Control is indistinguishable from a healthy
 * one while it falls behind for days and stalls the realm's content reclamation.
 *
 * ⭐ **The app to name is always Control here.** The rule is "name the binary that owns the
 * runtime" (§3.1), and inside Control that is Control on every platform, Control-on-iOS
 * included; so unlike the per-app banners this needs no `RuntimeIdentity` read.
 *
 * ⚠ **Not dismissible**, unlike the other home banners: the condition lasts until the user
 * updates, and a dismissed banner for a durable condition is how a user ends up believing sync is
 * simply broken (§4). ⚠ `null` (not yet evaluated) hides it — unknown is never "fenced" (§7 Q3).
 *
 * ⚠ If Control ever shows a "Syncing…" catch-up state (`content-plane-cr2-quorumless.md` M1 /
 * B1), this banner must outrank it: a fenced device never catches up, so "Syncing…" there would be
 * the silent failure again in a new place.
 */
class SchemaFenceBannerViewModel(
    schemaFence: Flow<List<String>?>,
) : ViewModel() {

    val uiState: StateFlow<SchemaFenceBannerUiState> = schemaFence
        .map { ids -> SchemaFenceBannerUiState(visible = !ids.isNullOrEmpty()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SchemaFenceBannerUiState(visible = false),
        )
}

data class SchemaFenceBannerUiState(
    val visible: Boolean,
)
