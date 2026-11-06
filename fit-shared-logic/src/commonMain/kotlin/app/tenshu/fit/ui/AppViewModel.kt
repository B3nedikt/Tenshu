package app.tenshu.fit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.fit.data.FitRepository
import app.tenshu.reachability.UnreachableReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App-shell view model (Ph3, docs/proposals/fit-app.md D5): exposes the onboarding
 * gate. `null` while the first value hasn't arrived (App renders nothing for that
 * frame), then the back stack starts at Onboarding or Home accordingly.
 */
class AppViewModel(
    private val repository: FitRepository,
) : ViewModel() {

    private val _onboarded = MutableStateFlow<Boolean?>(null)
    val onboarded: StateFlow<Boolean?> get() = _onboarded

    /** Control reachability for the shell banner (fit-app.md §9.14); see [FitRepository.controlReachable]. */
    val controlReachable: StateFlow<Boolean?> = repository.controlReachable()

    /**
     * *Why* it is unreachable, which decides the banner's words
     * (`core-schema-skew-surface.md` SK-1). Separate from [controlReachable], which decides
     * whether the banner shows at all.
     */
    val unreachableReason: StateFlow<UnreachableReason?> = repository.controlUnreachableReason()
        // ⚠⚠ Seeded from the engine's CURRENT reason, never `null` (Codex P2 on 67e1533a4): a `null`
        // first value renders the interactive shell for a frame while the verdict is already PENDING.
        .let { it.stateIn(viewModelScope, SharingStarted.Eagerly, it.value) }

    /** "Try again" on the checking gate's retry wall. */
    fun recheckControl() = repository.recheckControl()

    init {
        viewModelScope.launch {
            repository.onboarded().collect { _onboarded.value = it }
        }
    }
}
