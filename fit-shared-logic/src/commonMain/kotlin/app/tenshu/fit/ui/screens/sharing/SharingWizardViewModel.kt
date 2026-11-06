package app.tenshu.fit.ui.screens.sharing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.HealthPermissionRequester
import app.tenshu.fit.model.AppSharingState
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.HistoryAccess
import app.tenshu.fit.model.ImportNotice
import app.tenshu.fit.model.ImportStatus
import app.tenshu.fit.model.ImportWindow
import app.tenshu.fit.model.SharingReadiness
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.bestEffort

/**
 * One thing standing between the user and their workouts (`fit-app.md` §15.6.3).
 *
 * Ordered by dependency, not by importance: a deep-history grant is pointless while we cannot read
 * at all, and asking Google Fit to share is pointless while Health Connect is missing. The wizard
 * always shows the *first* unmet step, so the user is never asked to hold two problems at once.
 */
enum class SharingStep {
    /** Health Connect itself is absent. Terminal — see [SharingWizardUiState.actionable]. */
    InstallStore,

    /** We hold none (or not all) of the read permissions. **We** can prompt for these. */
    AllowReading,

    /** Health Connect will only share the last 30 days. **We** can prompt for this too. */
    AllowHistory,

    /**
     * Google Fit is installed and blocked. ⚠ The one step whose action we cannot perform: the grant
     * belongs to Google Fit, so all we can do is open Health Connect and re-probe on return.
     */
    AllowGoogleFit,

    /**
     * Google Fit is allowed *now*, but the last import ran before that was true — so what we know
     * about it is stale. An import is the only way to find out, and it is the user's call to make.
     */
    ConfirmSharing,

    /**
     * Permissions are done and Google Fit still wrote nothing. ⚠⚠ Terminal **by design**: sharing
     * covers new activity, not the history already inside Google Fit, and no further permission
     * will change that. This is where the wizard has to stop promising and name the export.
     */
    NeedsExport,

    /** Nothing left to fix. */
    Done,
}

data class SharingWizardUiState(
    val step: SharingStep = SharingStep.Done,
    val busy: Boolean = false,
    /**
     * Whether this shell can actually perform [step]'s action. False makes the step informational —
     * a wizard that offers a button leading nowhere is worse than one that explains and stops.
     */
    val actionable: Boolean = false,
)

/**
 * The wizard (`fit-app.md` §15.6): **a verifier, not a tutorial.**
 *
 * ⚠⚠ The design turns on a platform limit: Health Connect's per-app permission screen is guarded by
 * signature-level `GRANT_RUNTIME_PERMISSIONS`, so we can never land the user on Google Fit's own
 * page, and we get no callback when they change something there. We cannot shorten the journey.
 *
 * What we *can* do is re-probe, and that is enough to remove the need to describe the journey at
 * all. [refresh] runs on every resume, so the wizard advances on **observation** — never on the
 * user asserting they did something. That is what keeps it correct across Health Connect redesigns
 * and in every locale, neither of which click-by-click copy could survive.
 */
class SharingWizardViewModel(
    private val repository: FitRepository,
    private val permissionRequester: HealthPermissionRequester? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SharingWizardUiState())
    val uiState: StateFlow<SharingWizardUiState> get() = _uiState

    /**
     * ⚠ Set when this wizard *watches* Google Fit go from blocked to allowed, and cleared by the
     * next completed import. It is what distinguishes "allowed, and we have looked since" from
     * "allowed, and everything we know predates that" — a distinction no single probe can make,
     * because the permission and the import are read at different times.
     *
     * Deliberately in-memory: a wizard the user has closed and reopened should re-read the world,
     * not resume a claim about it.
     */
    private var awaitingConfirmation = false

    /**
     * Previous values, so transitions can be seen. ⚠ Both are tracked **in the collector**, not
     * around [refresh]: inferring "before" from the repository just before re-probing it makes the
     * verdict depend on refresh ordering, which a test caught immediately and a device would have
     * caught much later.
     */
    private var lastGoogleFit: AppSharingState? = null
    private var wasImporting = false

    init {
        viewModelScope.launch { refresh() }
        viewModelScope.launch {
            combine(repository.sharingReadiness(), repository.importStatus()) { readiness, status ->
                // Google Fit going from blocked to allowed is the moment everything we know about
                // its data becomes stale.
                if (lastGoogleFit == AppSharingState.NotPermitted &&
                    readiness.googleFit == AppSharingState.Permitted
                ) {
                    awaitingConfirmation = true
                }
                lastGoogleFit = readiness.googleFit

                // ⚠ Cleared on an import having *run*, not on the status merely *being* a completed
                // one — the status when the grant lands is already a completed import from before
                // it, and clearing on that would cancel the confirmation in the same breath as
                // raising it. Keying on the transition also survives a re-import returning a value
                // equal to the last one, which a `!=` check would have silently missed.
                val importing = status is ImportStatus.Running
                if (wasImporting && !importing) awaitingConfirmation = false
                wasImporting = importing

                val step = stepFor(readiness, status)
                SharingWizardUiState(step = step, busy = importing, actionable = actionable(step))
            }.collect { _uiState.value = it }
        }
    }

    /**
     * The first unmet condition, in dependency order.
     *
     * ⚠ `NotInstalled` Google Fit is [SharingStep.Done], not a step: an app the user does not have
     * is not a problem to solve, and §15.5's notice is silent in exactly that case for the same
     * reason.
     */
    private fun stepFor(readiness: SharingReadiness, status: ImportStatus): SharingStep = when {
        !readiness.storePresent -> SharingStep.InstallStore
        !readiness.canRead -> SharingStep.AllowReading
        readiness.historyAccess == HistoryAccess.Grantable -> SharingStep.AllowHistory
        readiness.googleFit == AppSharingState.NotPermitted -> SharingStep.AllowGoogleFit
        readiness.googleFit == AppSharingState.NotInstalled -> SharingStep.Done
        awaitingConfirmation -> SharingStep.ConfirmSharing
        status.notice == ImportNotice.GoogleFitNotSharing -> SharingStep.NeedsExport
        else -> SharingStep.Done
    }

    /**
     * ⚠ [SharingStep.InstallStore] is deliberately **not** actionable. On Android 14+ Health
     * Connect is a system module with no store page to send anyone to, and on older devices the
     * target differs — an "Install" button would be right on some phones and a dead end on others.
     * The honest version says so and stops. It is also nearly unreachable, which is why it must not
     * be allowed to shape the rest of the flow.
     */
    private fun actionable(step: SharingStep): Boolean = when (step) {
        SharingStep.InstallStore, SharingStep.Done -> false
        SharingStep.NeedsExport -> false // Profile owns the file picker; the wizard points at it.
        SharingStep.ConfirmSharing -> true
        SharingStep.AllowReading, SharingStep.AllowHistory, SharingStep.AllowGoogleFit ->
            permissionRequester != null
    }

    /**
     * Re-probe. Called on **every resume** — the user has just been somewhere we cannot see, and
     * this is the whole mechanism by which the wizard learns what they did.
     *
     * ⚠⚠ Deliberately does **not** start an import. `HealthImportBootstrap` already imports on every
     * launch, and an import fired by every return-from-settings would compound with it into a loop
     * the user cannot escape while the wizard is open. Data confirmation is
     * [SharingStep.ConfirmSharing]'s job, on an explicit tap.
     */
    /** [refresh] from a lifecycle callback, which has no scope of its own to launch in. */
    fun refreshOnResume() {
        viewModelScope.launch { refresh() }
    }

    suspend fun refresh() {
        repository.refreshSharingReadiness()
        repository.refreshHistoryAccess()
    }

    /** Performs the current step. A step whose action we cannot perform does nothing, by design. */
    fun act() {
        when (_uiState.value.step) {
            SharingStep.AllowReading -> permissionRequester?.request()
            SharingStep.AllowHistory -> permissionRequester?.requestFullHistory()
            SharingStep.AllowGoogleFit -> permissionRequester?.openHealthSettings()
            SharingStep.ConfirmSharing -> viewModelScope.launch {
                // FullHistory: the user is here *because* their history is missing, and by this
                // point the deep grant is either held or was declined — the importer clamps itself
                // either way (§15.3), so asking for depth costs nothing and skipping it would
                // quietly re-run the same shallow read that started this.
                bestEffort("FitSharing -> the full-history import") {
                    repository.startImport(CategoryId.entries.toSet(), ImportWindow.FullHistory)
                }
            }
            SharingStep.InstallStore, SharingStep.NeedsExport, SharingStep.Done -> Unit
        }
    }
}
