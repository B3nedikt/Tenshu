package app.tenshu.fit.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.HealthPermissionRequester
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.FitProfile
import app.tenshu.fit.model.HistoryAccess
import app.tenshu.fit.model.ImportCategory
import app.tenshu.fit.model.ImportProgress
import app.tenshu.fit.model.ImportSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.tenshu.core.common.util.bestEffort
import org.tenshu.preference.BiologicalSex

/**
 * [DeepHistory] sits between the source choice and the import: the deep-history grant has to be
 * asked *before* the read, because a `Recent` import that already ran cannot retroactively deepen
 * itself. It is **skippable and self-skipping** — see [OnboardingUiState.needsHistoryStep].
 */
enum class OnboardingStep { Welcome, Permissions, DeepHistory, Importing, AboutYou }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    /** Runtime-detected, in display order (D5) — never blocks on a Google surface. */
    val sources: List<ImportSource> = emptyList(),
    val chosenSource: ImportSource? = null,
    val categories: List<ImportCategory> = emptyList(),
    val selection: Set<CategoryId> = emptySet(),
    val progress: List<ImportProgress>? = null,
    /** 0..1 across all selected categories — the overall progress ring. */
    val overallFraction: Float = 0f,
    /** Whether this device has a deep-history gate, and whether it is already open. */
    val historyAccess: HistoryAccess = HistoryAccess.Unsupported,
    /** The profile as it stands once the import has settled — drives [needsAboutYouStep]. */
    val profile: FitProfile? = null,
) {

    /**
     * Whether the [OnboardingStep.DeepHistory] step is worth showing at all. Only when the grant is
     * genuinely available *and* not already held — asking about something the platform does not
     * have, or that is already answered, is a step that teaches the user their choices here are
     * decorative.
     */
    val needsHistoryStep: Boolean get() = historyAccess == HistoryAccess.Grantable

    /**
     * Whether the [OnboardingStep.AboutYou] step has anything left to ask (`fit-app.md` §18.6).
     *
     * **Self-skipping, per field**, on the same reasoning as [needsHistoryStep]: asking for a value
     * the import just supplied teaches the user their answers here are decorative. On iOS the
     * import can fill all four (HealthKit has birth date, sex, height and weight), so the step
     * typically never appears; on Android **Health Connect has no demographics API at all**, so
     * birth date and sex are always still open and only the body measurements can be pre-filled.
     *
     * ⚠ Deliberately asks only what the app **cannot compute without** — energy expenditure needs
     * body mass, and anything resting- or HR-derived needs age and sex. The user's *name* is not an
     * input to anything, so it stays a Profile edit rather than onboarding friction.
     */
    val needsAboutYouStep: Boolean
        get() = profile != null && missingAboutYouFields.isNotEmpty()

    val missingAboutYouFields: Set<AboutYouField>
        get() = profile?.let { p ->
            buildSet {
                if (p.birthDate == null) add(AboutYouField.BirthDate)
                if (p.sex == null) add(AboutYouField.Sex)
                if (p.heightCm == null) add(AboutYouField.Height)
                if (p.weightKg == null) add(AboutYouField.Weight)
            }
        }.orEmpty()
    /**
     * The import finished while the user was still on the progress screen — the UI flips
     * from the mid-import framing ("Importing… / Start now") to a completion state
     * ("Import complete / Continue"). Requires at least one non-empty category so an
     * all-empty selection doesn't read as a completed import.
     */
    val importComplete: Boolean
        get() = !progress.isNullOrEmpty() && progress.all { it.done } && progress.any { it.total > 0 }
}

/** The values [OnboardingStep.AboutYou] may still need; each is asked only while absent. */
enum class AboutYouField { BirthDate, Sex, Height, Weight }

/**
 * Onboarding (Ph3, docs/proposals/fit-app.md §2 screens 1–3): welcome + source choice →
 * per-category consent → import progress. "Start fresh" completes immediately; the
 * import path lets the user in while the import keeps running ("you can start now") —
 * [FitRepository.completeOnboarding] flips the gate [app.tenshu.fit.ui.AppViewModel]
 * observes.
 */
class OnboardingViewModel(
    private val repository: FitRepository,
    /**
     * Shell-bound, so the deep-history step can reach the platform consent sheet. Absent on every
     * surface that cannot prompt — which is also every surface where [needsHistoryStep] is false,
     * so the step and its dependency appear and disappear together.
     */
    private val permissionRequester: HealthPermissionRequester? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        OnboardingUiState(
            sources = repository.availableSources(),
            categories = repository.importCategories(),
            // Non-empty categories are pre-checked; empty ones render dimmed.
            selection = repository.importCategories()
                .filter { it.estimatedCount > 0 }
                .map { it.id }
                .toSet(),
        ),
    )
    val uiState: StateFlow<OnboardingUiState> get() = _uiState

    init {
        viewModelScope.launch {
            repository.refreshHistoryAccess()
            repository.historyAccess().collect { access ->
                _uiState.update { it.copy(historyAccess = access) }
            }
        }
        // The About-you step reads this to decide what is still missing. Collected continuously,
        // not sampled once: the import writes height/weight while the user watches the progress
        // screen, so a snapshot taken at launch would ask for values that have since arrived.
        viewModelScope.launch {
            repository.profile().collect { profile ->
                _uiState.update { it.copy(profile = profile) }
            }
        }
        viewModelScope.launch {
            repository.importProgress().collect { progress ->
                _uiState.update {
                    it.copy(
                        progress = progress,
                        overallFraction = progress.overallFraction(),
                    )
                }
            }
        }
    }

    fun chooseSource(source: ImportSource) {
        val isLivePlatformSource =
            source == ImportSource.HealthConnect || source == ImportSource.AppleHealth
        when {
            source == ImportSource.StartFresh ->
                viewModelScope.launch { repository.completeOnboarding() }
            // A live platform source defines consent at the OS layer (Health Connect grants /
            // the HealthKit sheet), and the live repository cannot enumerate categories with
            // estimates (`importCategories()` is empty there) — so the in-app picker would be a
            // dead end with nothing selectable. Skip straight to the import with the full
            // selection; the importer narrows to whatever the OS grant actually allows.
            // Ask about deep history BEFORE reading: an import that already ran with the default
            // window cannot retroactively deepen itself, so the offer has to come first or not at
            // all. Self-skipping — if there is no gate, or it is already open, we go straight on.
            isLivePlatformSource && _uiState.value.needsHistoryStep ->
                _uiState.update { it.copy(chosenSource = source, step = OnboardingStep.DeepHistory) }
            isLivePlatformSource && _uiState.value.categories.isEmpty() -> {
                _uiState.update { it.copy(chosenSource = source, step = OnboardingStep.Importing) }
                startLiveImport()
            }
            else -> _uiState.update { it.copy(chosenSource = source, step = OnboardingStep.Permissions) }
        }
    }

    /**
     * "Allow past data" — hands off to the platform sheet and moves on. Deliberately does **not**
     * start an import: the shell owns the grant result and starts the read there (full-history on
     * a grant, ordinary on a refusal), so starting one here would race it — and lose, since
     * `startImport` holds a lock that makes the second caller step aside. The user is not made to
     * wait on an out-of-process sheet either way.
     */
    fun allowDeepHistory() {
        _uiState.update { it.copy(step = OnboardingStep.Importing) }
        permissionRequester?.requestFullHistory()
            // No requester bound (a surface that cannot prompt): there is nothing to wait for, so
            // fall back to the ordinary import rather than leaving the screen waiting forever.
            ?: startLiveImport()
    }

    /** "Not now" — import the default window; the Profile entry keeps the offer open. */
    fun skipDeepHistory() {
        _uiState.update { it.copy(step = OnboardingStep.Importing) }
        startLiveImport()
    }

    /**
     * Same failure posture as [importSelected]: a thrown import is recorded as `ImportStatus.Failed`
     * by the repository; don't take the scope down with it.
     */
    private fun startLiveImport() {
        viewModelScope.launch {
            bestEffort("FitOnboarding -> the live import") { repository.startImport(CategoryId.entries.toSet()) }
        }
    }

    fun toggleCategory(id: CategoryId) = _uiState.update {
        it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id)
    }

    fun importSelected() {
        val selection = _uiState.value.selection
        _uiState.update { it.copy(step = OnboardingStep.Importing) }
        // startImport records a failure as ImportStatus.Failed and then rethrows. Uncaught in a
        // viewModelScope that would take the process down with it — and crashing on the onboarding
        // import screen is a strictly worse way to report a failure than the banner it just recorded.
        viewModelScope.launch {
            bestEffort("FitOnboarding -> the selected import") { repository.startImport(selection) }
        }
    }

    /**
     * "Start now" on the progress screen — the import continues in the background.
     *
     * Detours through [OnboardingStep.AboutYou] when the import left something the app genuinely
     * needs. The ask comes **after** the import by design: the import may already have answered it,
     * and asking first would be asking for data we are about to be given (§18.6).
     */
    fun enterApp() {
        if (_uiState.value.needsAboutYouStep) {
            _uiState.update { it.copy(step = OnboardingStep.AboutYou) }
        } else {
            viewModelScope.launch { repository.completeOnboarding() }
        }
    }

    /** Leaves the About-you step, answered or skipped — a skip costs accuracy and nothing else. */
    fun finishAboutYou() {
        viewModelScope.launch { repository.completeOnboarding() }
    }

    fun saveBirthDate(date: LocalDate?) {
        viewModelScope.launch { repository.updateBirthDate(date) }
    }

    fun saveSex(sex: BiologicalSex?) {
        viewModelScope.launch { repository.updateSex(sex) }
    }

    fun saveHeight(centimetres: Int) {
        viewModelScope.launch { repository.recordHeight(centimetres) }
    }

    fun saveWeight(kilograms: Double) {
        viewModelScope.launch { repository.recordWeight(kilograms) }
    }

    fun back() = _uiState.update {
        when (it.step) {
            OnboardingStep.Permissions, OnboardingStep.DeepHistory ->
                it.copy(step = OnboardingStep.Welcome, chosenSource = null)
            else -> it
        }
    }

    private fun List<ImportProgress>?.overallFraction(): Float {
        val rows = this ?: return 0f
        val total = rows.sumOf { it.total }
        if (total <= 0) return 0f
        return rows.sumOf { it.imported }.toFloat() / total
    }
}
