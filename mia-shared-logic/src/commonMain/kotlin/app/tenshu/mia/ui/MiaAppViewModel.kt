package app.tenshu.mia.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.mia.data.OnboardingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.bestEffort

/**
 * The app-root gate (docs/proposals/home-and-mia-apps.md §4, M5, Fit's D5 shape): null
 * until the store's first emission (render nothing — no onboarding flash for an onboarded
 * user), then false → the one-time intro flow, true → the main shell.
 *
 * It is also where Mia **announces itself to Control**, for want of a better place: this view model
 * is built before any screen and regardless of the onboarding branch, which is the only point that
 * is reached by every launch.
 */
class MiaAppViewModel(
    store: OnboardingStore,
    /**
     * Null wherever no client is bound — the not-connected shell, tests, previews. Absence means
     * "nothing to announce to", never a failure.
     */
    private val announcer: LaunchAnnouncer? = null,
) : ViewModel() {

    private val _onboarded = MutableStateFlow<Boolean?>(null)
    val onboarded: StateFlow<Boolean?> get() = _onboarded

    init {
        viewModelScope.launch {
            store.onboarded().collect { _onboarded.value = it }
        }
        announceToControl()
    }

    /**
     * Open a session at launch so Control registers this app **without waiting for the user to use
     * it** (`docs/tech-debt.md`, 2026-08-14: an app registers on first *use*, not at launch).
     *
     * ⚠⚠ Deliberately fire-and-forget, and deliberately swallowing. `announce()` throws a typed
     * error when Control is unreachable or refuses admission, and neither is a reason to fail a
     * launch: Mia is designed to start standalone and say so per turn. The `sessionPreflight`
     * `wireSchemaAnnounce` installed still runs ahead of the first real call, so a failure here
     * costs nothing beyond the registration being late — exactly the old behaviour.
     *
     * ⚠ Not `createdAtStart` on the Koin binding: eager *construction* would only install the
     * preflight, which is what already happened and is precisely what did not register anything.
     * The announce has to be *called*.
     */
    private fun announceToControl() {
        val announcer = announcer ?: return
        viewModelScope.launch {
            bestEffort("Mia -> the announce to Control") { announcer.announce() }
        }
    }
}

/**
 * "Open a session with Control at launch" — narrowed to the one call the app root needs, so this
 * module does not take a dependency on `core-common`'s `SchemaAnnouncer` and a test can stand in
 * for it. The client module binds the real implementation over the announcer it wires.
 */
fun interface LaunchAnnouncer {
    suspend fun announce()
}
