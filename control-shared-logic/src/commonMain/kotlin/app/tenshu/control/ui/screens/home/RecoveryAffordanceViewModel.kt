package app.tenshu.control.ui.screens.home

import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.getOrNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.RecoveryAffordancePreferences
import app.tenshu.control.data.SyncRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime
import kotlin.time.Clock as KotlinClock

/**
 * §7.2 Level 0 affordance state per `docs/proposals/onboarding-low-friction.md`.
 *
 * Show when *all* of these hold:
 *  - **this device holds a recovery phrase** and the user has not confirmed saving it;
 *
 *    ⚠⚠ **Corrected 2026-08-21.** This condition read *"the roster contains exactly one device
 *    (multi-device realms have a second copy → the recoverability story is already covered)"*. That
 *    premise is **false**: a joiner never receives the phrase — `PairingKeyBlob` carries derived
 *    keys, the realm encryption keypair and an A33 capability, and **no master key** — so a
 *    two-device realm whose second device was paired in holds exactly **one** copy of A1. The
 *    banner switched itself off at the moment it wrongly concluded the user was safe, and offered
 *    *"Add another device"* as a resolving action for a risk that action does not resolve. That is
 *    how a realm on this project lost its master identity
 *    (`admission-revocation-names-the-capability.md` §5b.11 / §5b.12.1).
 *
 *    ⭐ Device count answered a different question. "Has the user saved the phrase?" is local,
 *    unfalsifiable, and unaffected by pairing — and it is the question that was always meant.
 *  - the user hasn't dismissed the banner within the last 72h on this
 *    device (tracked via
 *    [app.tenshu.control.data.RecoveryAffordancePreferences]).
 *
 * ⚠ The old `== 1` guard existed because **zero meant "we have not looked", never "no devices"** —
 * guessing before the roster arrived made the banner lie on every fresh launch. Keying on the phrase
 * removes that failure mode by construction: `getRecoveryPhrase()` returning null is a *fact about
 * this device*, not a not-yet-loaded roster.
 *
 * ## What it says — two situations behind the one trigger
 *
 * ⚠⚠ The copy outlived the trigger once: after the 2026-08-21 correction the banner still read
 * *"This realm exists only on this device and isn't synced anywhere"* and led with *"Add another
 * device"* — the device-count story — on a realm that synced fine. The trigger asks about the
 * **phrase**; whether the realm syncs is a second, independent fact, and it picks the wording:
 *
 * | realm sync ([syncFunded]) | wording | primary action |
 * |---|---|---|
 * | `false` — no storage plan, nothing leaves this device | data is only here | Set up sync |
 * | `true` or `null` (unknown) | save your phrase | Save recovery phrase |
 *
 * ⚠ **Unknown takes the phrase wording**, never "only on this device": that sentence accuses the
 * user of a problem — the same optimistic-on-unknown rule as `evaluateRealmStorage`. The phrase
 * wording is true either way.
 *
 * ⚠⚠ **And `false` is not definite on arrival.** `EntitlementStorageProvider` seeds
 * `hasStorage = false`, and a start before the owner resolves derives `false` too, corrected by
 * the reader's owner poll about a second later. So a `false` must hold for [LOCAL_ONLY_GRACE_MS]
 * before it changes the wording; until then it reads as unknown. Without that, a funded realm
 * flashes "only on this device" + Set up sync right after unlock.
 *
 * Accepted gap (maintainer, 2026-10-30): once the phrase is saved the banner goes, even on a realm
 * with no storage plan, whose data is then still only on this device. Nagging a user who chose not
 * to sync is worse than the gap; do not widen the trigger to close it.
 *
 * ⚠⚠ **No "Add another device".** Pairing creates no second copy of A1 (a joiner gets no phrase),
 * and on a local-only realm it would only reach the plan picker that "Set up sync" opens directly.
 * Offering it read as a fix for a risk it does not touch. Only saving the phrase dismisses this
 * state — what [RecoveryAffordancePreferences.setPhraseSaved] records. Routing lives in `App.kt`.
 *
 * Level 1+ escalation (time/data-accrued tiers from §7.2) is Phase 4 work
 * — this VM only models Level 0.
 */
@OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
class RecoveryAffordanceViewModel(
    private val preferences: RecoveryAffordancePreferences,
    /** True when **this device** holds a recovery phrase — see the class KDoc for why not a count. */
    phraseHeld: Flow<Boolean>,
    /**
     * The synced entitlement's `hasStorage`: `false` = no plan, nothing syncs — or not read yet,
     * hence the grace in the class KDoc; `null` = no status row yet. Realm-wide, so it is right on
     * a device that never signed in here — unlike the local commerce account, which is null on the
     * A1 holder of a realm funded from another device.
     */
    syncFunded: Flow<Boolean?>,
    private val clock: () -> Long = { KotlinClock.System.now().toEpochMilliseconds() },
) : ViewModel() {

    /** Koin convenience: asks the repository whether this device holds a phrase. */
    constructor(
        preferences: RecoveryAffordancePreferences,
        syncRepository: SyncRepository,
        syncFunded: Flow<Boolean?>,
    ) : this(
        preferences = preferences,
        syncFunded = syncFunded,
        phraseHeld = kotlinx.coroutines.flow.flow {
            emit(
                runCatchingCancellable { syncRepository.getRecoveryPhrase().getOrNull() != null }
                    .getOrElse { false }
            )
        },
    )

    val uiState: StateFlow<RecoveryAffordanceUiState> = combine(
        preferences.observeDismissedUntilMs().onStart { emit(0L) },
        preferences.observePhraseSaved().onStart { emit(false) },
        phraseHeld.onStart { emit(false) },
        syncFunded.onStart { emit(null) }.transformLatest { funded ->
            // A `false` that survives the grace is believed; a `true` arriving inside it cancels it.
            if (funded == false) {
                emit(null)
                delay(LOCAL_ONLY_GRACE_MS)
            }
            emit(funded)
        },
    ) { dismissedUntilMs, phraseSaved, holdsPhrase, funded ->
        // ⚠ `holdsPhrase` starts false: a device that has not answered yet must not be told its
        // data is at risk. Same direction the old `== 1` guard protected — silence while unknown.
        val shouldShow = holdsPhrase && !phraseSaved && clock() >= dismissedUntilMs
        RecoveryAffordanceUiState(visible = shouldShow, localOnly = funded == false)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = RecoveryAffordanceUiState(visible = false, localOnly = false),
    )

    /** Hides the affordance for 72h on *this* device. */
    fun onDismiss() {
        viewModelScope.launch {
            preferences.setDismissedUntilMs(clock() + DISMISS_WINDOW_MS)
        }
    }

    companion object {
        const val DISMISS_WINDOW_MS: Long = 72L * 60L * 60L * 1000L

        /** How long `hasStorage == false` must hold before it is believed — see the class KDoc. */
        const val LOCAL_ONLY_GRACE_MS: Long = 3_000L
    }
}

data class RecoveryAffordanceUiState(
    val visible: Boolean,
    /** The realm has no storage plan, so nothing leaves this device — see the class KDoc table. */
    val localOnly: Boolean,
)
