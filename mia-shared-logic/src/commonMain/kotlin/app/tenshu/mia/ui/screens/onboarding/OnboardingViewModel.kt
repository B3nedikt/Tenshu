package app.tenshu.mia.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.mia.data.OnboardingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The onboarding steps (docs/proposals/home-and-mia-apps.md §4, M5), in walk order. */
enum class OnboardingStep { Welcome, Assistant, Done }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
)

/**
 * The one-time intro flow's view model: Welcome (what Mia is) → Assistant (set as default
 * on Android; informational elsewhere) → Done. Every step is skippable — onboarding must
 * never gate the product, only introduce it. [complete] persists the device-local flag;
 * the App gate then mounts the main shell.
 *
 * Voice-ID setup joins as a step when the voice pipeline lands (it is a Settings entry
 * until then).
 */
class OnboardingViewModel(
    private val store: OnboardingStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> get() = _uiState

    fun next() = _uiState.update {
        it.copy(
            step = when (it.step) {
                OnboardingStep.Welcome -> OnboardingStep.Assistant
                OnboardingStep.Assistant -> OnboardingStep.Done
                OnboardingStep.Done -> OnboardingStep.Done
            },
        )
    }

    fun back() = _uiState.update {
        it.copy(
            step = when (it.step) {
                OnboardingStep.Welcome -> OnboardingStep.Welcome
                OnboardingStep.Assistant -> OnboardingStep.Welcome
                OnboardingStep.Done -> OnboardingStep.Assistant
            },
        )
    }

    fun complete() {
        viewModelScope.launch { store.setOnboarded() }
    }
}
