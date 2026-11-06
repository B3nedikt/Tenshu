package app.tenshu.control.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.QuotaSoftLimitPreferences
import app.tenshu.control.ui.screens.services.StorageStatusModel
import app.tenshu.control.ui.screens.services.SyncedPlanTier
import app.tenshu.control.ui.screens.services.planTierForLimit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Free-tier quota soft-limit prompt state per
 * `docs/proposals/control-subscription-ux.md` §2.3 / `retention-quota-model.md`
 * P1b (Face C).
 *
 * Shows the one-time "You're using 80% of your free Tenshu storage" banner when
 * *all* hold:
 *  - the device has an active storage plan on the **Free** tier
 *    ([StorageStatusModel.hasStorage] + [planTierForLimit] == [SyncedPlanTier.Free]);
 *  - usage has crossed [SOFT_LIMIT_PERCENT]% of the free limit;
 *  - the banner hasn't already been dismissed/actioned on this device
 *    ([QuotaSoftLimitPreferences]).
 *
 * It is **the only marketing prompt in the app**: one-time, and both *dismiss*
 * and *See plans* mark it permanently gone (§2.3, "no re-prompts"). The *See
 * plans* navigation itself lives in `App.kt` (routing, not state) — mirroring
 * [RecoveryAffordanceViewModel]; this VM only owns visibility + the persist.
 */
class QuotaSoftLimitViewModel(
    private val preferences: QuotaSoftLimitPreferences,
    storageStatusFlow: Flow<StorageStatusModel?>,
) : ViewModel() {

    val uiState: StateFlow<QuotaSoftLimitUiState> = combine(
        preferences.observeDismissed().onStart { emit(false) },
        storageStatusFlow,
    ) { dismissed, status ->
        QuotaSoftLimitUiState(visible = !dismissed && shouldShow(status))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = QuotaSoftLimitUiState(visible = false),
    )

    /** Permanently dismiss (the user tapped the close affordance). */
    fun onDismiss() = markDismissed()

    /**
     * The user tapped *See plans*: permanently dismiss too, so the one-time
     * prompt never returns. The navigation is wired separately in `App.kt`.
     */
    fun onSeePlans() = markDismissed()

    private fun markDismissed() {
        viewModelScope.launch { preferences.setDismissed() }
    }

    private fun shouldShow(status: StorageStatusModel?): Boolean {
        if (status == null || !status.hasStorage || status.limitBytes <= 0L) return false
        if (planTierForLimit(status.limitBytes) != SyncedPlanTier.Free) return false
        // Integer form of `used >= 0.80 * limit`, avoiding float rounding at the boundary.
        return status.usedBytes * 100L >= status.limitBytes * SOFT_LIMIT_PERCENT
    }

    companion object {
        const val SOFT_LIMIT_PERCENT: Long = 80L
    }
}

data class QuotaSoftLimitUiState(
    val visible: Boolean,
)
