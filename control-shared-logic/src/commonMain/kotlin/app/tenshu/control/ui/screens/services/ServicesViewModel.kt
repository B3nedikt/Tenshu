package app.tenshu.control.ui.screens.services

import app.tenshu.control.data.StoreBilling

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.CommerceProducts
import app.tenshu.control.data.PublisherAccount
import app.tenshu.control.data.PublisherClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Services tab state per `docs/proposals/onboarding-low-friction.md` §6.1.
 *
 * ⚠⚠ **One source for the figures.** Plan and usage always come from the synced
 * `StorageStatus` (which the provider derives from the synced `Entitlement`); the
 * device-local account contributes **only** identity and which actions are offered.
 * Previously the two were separate cards and the synced one was gated on
 * `account == null` — which meant signing in *replaced* live, correct figures with
 * the hardcoded literals the local account carried, so a paired device that had
 * never signed in showed the truth and regressed the moment it did
 * (`commerce-end-to-end-dry-run.md` §1.1 / D5).
 *
 * The descriptor-vs-session boundary (commerce-accounts-and-sessions.md §3.5 / D11)
 * survives intact, because it was always about **actions**, never about the number:
 * viewing the plan rides the synced entitlement on any device, while billing and
 * identity actions still require a per-device email login.
 *
 * No mutation lives here; signup / sign-in / disconnect / etc. are entered from
 * dedicated screens with their own VMs.
 */
class ServicesViewModel(
    private val publisherClient: PublisherClient,
    storageStatusFlow: Flow<StorageStatusModel?>,
    /** The store's billing in a Play build; null on the Stripe rail. */
    private val storeBilling: StoreBilling? = null,
) : ViewModel() {

    val account: StateFlow<PublisherAccount?> = publisherClient.account

    /** Everything the card renders: synced figures + (optionally) this device's identity. */
    val cardState: StateFlow<ServicesCardState> =
        combine(publisherClient.account, storageStatusFlow) { account, status ->
            servicesCardState(account, status)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServicesCardState.EMPTY)

    private val _portalUrl = MutableStateFlow<String?>(null)

    /** One-shot: a Stripe billing-portal URL the screen should open, then clear via [onPortalUrlOpened]. */
    val portalUrl: StateFlow<String?> = _portalUrl.asStateFlow()

    private val _portalNotice = MutableStateFlow<String?>(null)

    /** An inline message shown on the account card when the portal can't be opened (no sub / etc.). */
    val portalNotice: StateFlow<String?> = _portalNotice.asStateFlow()

    private val _portalBusy = MutableStateFlow(false)
    val portalBusy: StateFlow<Boolean> = _portalBusy.asStateFlow()

    /** §6 manage-subscription: fetch a Stripe billing-portal URL and hand it to the browser. */
    fun openBillingPortal() {
        if (_portalBusy.value) return
        viewModelScope.launch {
            _portalBusy.value = true
            _portalNotice.value = null
            when (val result = publisherClient.openBillingPortal()) {
                is PublisherClient.PortalResult.Ready -> _portalUrl.value = result.url
                is PublisherClient.PortalResult.NoSubscription ->
                    _portalNotice.value = "No paid subscription to manage yet."
                is PublisherClient.PortalResult.Unauthorized ->
                    _portalNotice.value = "Please sign in again to manage your subscription."
                is PublisherClient.PortalResult.Unavailable ->
                    _portalNotice.value = "Managing your subscription isn't available right now."
                is PublisherClient.PortalResult.Error -> _portalNotice.value = result.message
            }
            _portalBusy.value = false
        }
    }

    /** The screen has opened [portalUrl] in the browser; clear the one-shot. */
    fun onPortalUrlOpened() {
        _portalUrl.value = null
    }

    /**
     * The screen is in front — first shown, or returned to from the browser. A signed-in device
     * asks commerce for its current plan: that is what brings a purchase finished in a closed
     * browser, a plan change made in the billing portal, or a lapse, onto the card.
     *
     * ⚠ On every resume, not once per sign-in: the billing portal changes the plan while this
     * view model stays alive and the account stays signed in, so nothing else would notice.
     */
    fun onResumed() {
        if (publisherClient.account.value == null) return
        viewModelScope.launch {
            restoreStorePurchases()
            publisherClient.syncPlan()
        }
    }

    /**
     * Hand every purchase the store holds for us to commerce again (P3 restore).
     *
     * ⚠⚠ This is what saves a purchase interrupted between the store's sheet and commerce's
     * verification (app killed, no network): the store refunds a purchase nobody acknowledged
     * within three days. Verification is idempotent, so re-sending a booked purchase is harmless.
     */
    private suspend fun restoreStorePurchases() {
        val store = storeBilling ?: return
        for (token in store.ownedPurchaseTokens().orEmpty()) {
            publisherClient.verifyStorePurchase(store.rail, token)
        }
    }

    // ⚠ Last in the class on purpose: on Kotlin/Native `viewModelScope` is `Main.immediate`, so a
    // launch in an `init` above the fields it reads runs before they are assigned.
    init {
        // Signing in while this screen's view model is alive (the sign-in screen sits on top of
        // it) is not a resume of anything [onResumed] has seen as signed in.
        viewModelScope.launch {
            publisherClient.account.map { it != null }.distinctUntilChanged().drop(1).collect { signedIn ->
                if (signedIn) publisherClient.syncPlan()
            }
        }
    }
}

/** Minimal projection of the derived `StorageStatus` the Services tab consumes. */
data class StorageStatusModel(
    val hasStorage: Boolean,
    val usedBytes: Long,
    val limitBytes: Long,
)

/**
 * The storage figures + derived plan **tier**, read straight off the synced entitlement —
 * no local PII, and true on every device including one that never signed in here (D11).
 */
data class SyncedStorage(
    val usedBytes: Long,
    val limitBytes: Long,
    val plan: SyncedPlanTier,
)

/** The plan a synced entitlement grants, as far as the descriptor needs to name it. */
enum class SyncedPlanTier { Free, Basic, Premium }

/**
 * What the Services card shows. [storage] is the **only** source of plan and usage;
 * [identity] is non-null exactly when this device holds a commerce session, and decides
 * whether billing/identity actions are offered.
 *
 * The four combinations are all reachable and all mean something:
 * - storage + identity → the full account card (this device subscribed here)
 * - storage, no identity → a paired device: real plan, *Sign in to manage*
 * - identity, no storage → just signed in / redeemed; the entitlement has not synced back yet
 * - neither → no account, no funded storage: *Sign up*
 */
data class ServicesCardState(
    val storage: SyncedStorage?,
    val identity: PublisherAccount.IdentityMethod?,
) {
    /** True when this device holds a commerce session, so billing actions may be offered. */
    val signedInHere: Boolean get() = identity != null

    companion object {
        val EMPTY = ServicesCardState(storage = null, identity = null)
    }
}

/**
 * The funded-storage projection of a [StorageStatusModel] — **not** gated on the local
 * account. That gate is exactly what made signing in regress the figures (D5).
 */
internal fun fundedStorage(status: StorageStatusModel?): SyncedStorage? =
    if (status != null && status.hasStorage) {
        SyncedStorage(
            usedBytes = status.usedBytes,
            limitBytes = status.limitBytes,
            plan = planTierForLimit(status.limitBytes),
        )
    } else {
        null
    }

/** Pure derivation of the card state from the two signals the VM binds. */
internal fun servicesCardState(
    account: PublisherAccount?,
    status: StorageStatusModel?,
): ServicesCardState = ServicesCardState(
    storage = fundedStorage(status),
    identity = account?.identityMethod,
)

/**
 * Reverse-map the synced entitlement's quota to a plan tier (D11: "no new synced
 * field required — the tier is derivable from the synced `Entitlement`"). The limit
 * on the derived `StorageStatus` is set from `EntitlementProducts.quotaBytes`, so
 * it is exactly one of the tier constants; an unrecognised value falls back to Free
 * (the safe floor — never over-claims a paid tier). If usage-reporting ever makes
 * the limit no longer equal a tier constant, carry the `productId` on the local
 * (never-synced) `StorageStatus` instead.
 */
internal fun planTierForLimit(limitBytes: Long): SyncedPlanTier = when (limitBytes) {
    CommerceProducts.PREMIUM_LIMIT_BYTES -> SyncedPlanTier.Premium
    CommerceProducts.BASIC_LIMIT_BYTES -> SyncedPlanTier.Basic
    else -> SyncedPlanTier.Free
}
