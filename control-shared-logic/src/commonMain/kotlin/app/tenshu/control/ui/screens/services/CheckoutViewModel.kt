package app.tenshu.control.ui.screens.services

import app.tenshu.control.data.BillingRail
import app.tenshu.control.data.StoreBilling
import app.tenshu.control.data.StorePurchaseHost
import app.tenshu.control.data.StorePurchaseOutcome


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.CommerceProducts
import app.tenshu.control.data.PublisherClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * §6 paid-tier checkout for [productId] (one of [CommerceProducts]).
 *
 * **Two-layer confirmation (`commerce-accounts-and-sessions.md` §9.5).** Fulfilment is driven by the
 * Stripe webhook server-side, never by the browser return — so this VM confirms the purchase by the
 * *authoritative* Layer-1 signal: the entitlement arriving on the synced [StorageStatusModel]
 * ([storageStatusFlow]). When the synced quota reaches the purchased tier's target, the flow
 * completes. This collector runs regardless of whether (or how) the user returns from the browser;
 * a deep-link back into the app would only accelerate it, never replace it (deferred UX slice).
 *
 * Flow:
 *  - [CheckoutStep.Acknowledge] — the customer must confirm they understand the service starts
 *    immediately, which is what disapplies the EU 14-day right of withdrawal (policy §5.4). ⚠ This
 *    is a real gate, not a formality: the service **refuses** a checkout without the
 *    acknowledgement, so skipping the step would simply fail the request.
 *  - [CheckoutStep.Launching] — asked the service for a hosted checkout URL.
 *  - [CheckoutStep.AwaitingConfirmation] — URL handed to the browser; waiting for the synced
 *    entitlement. The mock has no browser leg and jumps straight to [CheckoutStep.Done].
 *  - [CheckoutStep.Done] — the paid tier is active on this device.
 *  - [CheckoutStep.Error] — the service was unreachable / unavailable / rejected the request.
 */
class CheckoutViewModel(
    private val publisherClient: PublisherClient,
    private val storageStatusFlow: Flow<StorageStatusModel?>,
    private val productId: String,
    private val planPollIntervalMs: Long = PLAN_POLL_INTERVAL_MS,
    private val planPollAttempts: Int = PLAN_POLL_ATTEMPTS,
    /**
     * The rail this build sells through. [BillingRail.STRIPE] is the hosted browser checkout above;
     * a store rail (Play) shows the store's own sheet instead (`commerce-store-link-out.md` P3).
     */
    private val billingRail: BillingRail = BillingRail.STRIPE,
    /** The store's billing on a store rail (bound only in a Play build); null elsewhere. */
    private val storeBilling: StoreBilling? = null,
) : ViewModel() {

    private val targetQuotaBytes: Long? = CommerceProducts.quotaBytesFor(productId)

    private val _uiState = MutableStateFlow(CheckoutUiState(viaStore = billingRail != BillingRail.STRIPE))
    val uiState: StateFlow<CheckoutUiState> = _uiState.asStateFlow()

    private var lastCheckoutUrl: String? = null

    /** The store purchase awaiting commerce's verification; kept so a retry re-verifies, never re-buys. */
    private var unverifiedPurchaseToken: String? = null

    private val sellsThroughStore = billingRail != BillingRail.STRIPE

    init {
        observeConfirmation()
        // ⚠ No acknowledgement step on a store rail: the store is the seller there, with its own
        // terms and withdrawal handling. The waiver in policy §5.4 is for OUR sale (Stripe).
        if (sellsThroughStore) start()
    }

    /** The acknowledgement checkbox changed. */
    fun onAcknowledgementChanged(acknowledged: Boolean) {
        _uiState.update { it.copy(acknowledged = acknowledged, error = null) }
    }

    /**
     * Proceed from [CheckoutStep.Acknowledge]. ⚠ Guarded here **as well as** on the server: the
     * button is disabled without the tick, and the service refuses it anyway — the client gate is
     * for the user's benefit; the server's is what makes the waiver real.
     */
    fun confirmAcknowledgement() {
        if (!_uiState.value.acknowledged) return
        start()
    }

    /** (Re)start checkout — called once the acknowledgement is given, and by the retry affordance. */
    fun start() {
        if (targetQuotaBytes == null) {
            _uiState.update {
                it.copy(step = CheckoutStep.Error, busy = false, error = UNKNOWN_PLAN, launchUrl = null)
            }
            return
        }
        if (_uiState.value.busy) return
        if (sellsThroughStore) {
            unverifiedPurchaseToken?.let { verify(it) } ?: startStorePurchase()
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(step = CheckoutStep.Launching, busy = true, error = null, launchUrl = null)
            }
            when (
                val result = publisherClient.startCheckout(
                    productId = productId,
                    withdrawalAcknowledged = _uiState.value.acknowledged,
                )
            ) {
                is PublisherClient.CheckoutResult.Ready -> {
                    lastCheckoutUrl = result.url
                    _uiState.update {
                        it.copy(
                            step = CheckoutStep.AwaitingConfirmation,
                            busy = false,
                            launchUrl = result.url,
                        )
                    }
                    pollForThePlan()
                }
                is PublisherClient.CheckoutResult.SignInRequired ->
                    _uiState.update { it.copy(step = CheckoutStep.Error, busy = false, error = SIGN_IN_FIRST) }
                is PublisherClient.CheckoutResult.AlreadySubscribed ->
                    _uiState.update { it.copy(step = CheckoutStep.Error, busy = false, error = ALREADY_SUBSCRIBED) }
                is PublisherClient.CheckoutResult.PlanChangeRequired ->
                    _uiState.update { it.copy(step = CheckoutStep.Error, busy = false, error = PLAN_CHANGE) }
                is PublisherClient.CheckoutResult.SubscribedUnderAnotherAccount ->
                    _uiState.update { it.copy(step = CheckoutStep.Error, busy = false, error = SUBSCRIBED_ELSEWHERE) }
                is PublisherClient.CheckoutResult.CompletedLocally ->
                    _uiState.update { it.copy(step = CheckoutStep.Done, busy = false, launchUrl = null) }
                is PublisherClient.CheckoutResult.Unavailable ->
                    _uiState.update { it.copy(step = CheckoutStep.Error, busy = false, error = UNAVAILABLE) }
                is PublisherClient.CheckoutResult.Error ->
                    _uiState.update { it.copy(step = CheckoutStep.Error, busy = false, error = result.message) }
            }
        }
    }

    /**
     * Ask commerce whether [productId] may be bought here (D6: only above the current plan, never
     * over an Apple-billed one off Apple), then have the screen show the store's sheet.
     */
    private fun startStorePurchase() {
        viewModelScope.launch {
            _uiState.update { it.copy(step = CheckoutStep.Launching, busy = true, error = null) }
            when (val billing = publisherClient.billing(billingRail)) {
                is PublisherClient.BillingResult.Ready -> {
                    val error = when {
                        productId in billing.offers -> null
                        billing.upgradeOn == "Apple" -> UPGRADE_ON_APPLE
                        // The realm's other accounts leave nothing to buy here (commerce says why).
                        billing.refusal != null -> refusalCopy(billing.refusal)
                        billing.productId == productId -> ALREADY_SUBSCRIBED
                        else -> NOT_SOLD_HERE
                    }
                    _uiState.update {
                        if (error != null) {
                            it.copy(step = CheckoutStep.Error, busy = false, error = error)
                        } else {
                            it.copy(
                                step = CheckoutStep.AwaitingStore,
                                busy = false,
                                storePurchase = StorePurchaseRequest(productId, billing.accountId),
                            )
                        }
                    }
                }
                is PublisherClient.BillingResult.SignInRequired ->
                    _uiState.update { it.copy(step = CheckoutStep.Error, busy = false, error = SIGN_IN_FIRST) }
                is PublisherClient.BillingResult.Unavailable ->
                    _uiState.update { it.copy(step = CheckoutStep.Error, busy = false, error = UNAVAILABLE) }
                is PublisherClient.BillingResult.Error ->
                    _uiState.update { it.copy(step = CheckoutStep.Error, busy = false, error = billing.message) }
            }
        }
    }

    /**
     * The screen hands over its foreground UI ([host]; null where none can host a sheet) for the
     * pending [CheckoutUiState.storePurchase]: the store's sheet opens and its answer goes to
     * commerce. Consumes the one-shot, so a second call (a recomposition) does nothing.
     *
     * ⚠⚠ The purchase runs in [viewModelScope], never in the screen's composition. The screen
     * clears the one-shot by calling this, which recomposes it; a purchase run inside the screen's
     * effect was cancelled by exactly that recomposition (the sheet never opened, or a paid
     * answer was lost and the screen spun forever) — and a rotation would cancel it too. [host]
     * is passed on through a [StorePurchaseHost], so nothing here keeps the Activity once the
     * sheet has opened.
     */
    fun launchStorePurchase(host: Any?) {
        val request = _uiState.value.storePurchase ?: return
        _uiState.update { it.copy(storePurchase = null) }
        val billing = storeBilling
        if (billing == null || host == null) {
            onStorePurchaseOutcome(StorePurchaseOutcome.Failed(STORE_UNAVAILABLE))
            return
        }
        val handle = StorePurchaseHost(host)
        viewModelScope.launch {
            onStorePurchaseOutcome(billing.purchase(handle, request.productId, request.accountId))
        }
    }

    /** What the store's sheet answered. Only commerce's verification makes it count. */
    private fun onStorePurchaseOutcome(outcome: StorePurchaseOutcome) {
        when (outcome) {
            is StorePurchaseOutcome.Purchased -> {
                unverifiedPurchaseToken = outcome.purchaseToken
                verify(outcome.purchaseToken)
            }
            StorePurchaseOutcome.Pending ->
                _uiState.update { it.copy(step = CheckoutStep.Error, busy = false, error = PAYMENT_PENDING) }
            StorePurchaseOutcome.Cancelled ->
                _uiState.update { it.copy(step = CheckoutStep.Error, busy = false, error = CANCELLED) }
            is StorePurchaseOutcome.Failed ->
                _uiState.update { it.copy(step = CheckoutStep.Error, busy = false, error = outcome.message) }
        }
    }

    private fun verify(purchaseToken: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(step = CheckoutStep.Launching, busy = true, error = null) }
            when (val result = publisherClient.verifyStorePurchase(billingRail, purchaseToken)) {
                is PublisherClient.StoreVerifyResult.Verified -> {
                    unverifiedPurchaseToken = null
                    // Done comes from the synced plan, as for Stripe; verify already asked for it.
                    _uiState.update { it.copy(step = CheckoutStep.AwaitingConfirmation, busy = false) }
                    pollForThePlan()
                }
                is PublisherClient.StoreVerifyResult.Refused -> {
                    unverifiedPurchaseToken = null
                    _uiState.update { it.copy(step = CheckoutStep.Error, busy = false, error = refusalCopy(result.reason)) }
                }
                is PublisherClient.StoreVerifyResult.SignInRequired ->
                    _uiState.update { it.copy(step = CheckoutStep.Error, busy = false, error = SIGN_IN_FIRST) }
                is PublisherClient.StoreVerifyResult.Retry ->
                    _uiState.update { it.copy(step = CheckoutStep.Error, busy = false, error = NOT_CONFIRMED_YET) }
            }
        }
    }

    private fun refusalCopy(reason: String): String = when (reason) {
        "billed_by_apple" -> UPGRADE_ON_APPLE
        "not_an_upgrade" -> ALREADY_SUBSCRIBED
        "subscribed_under_another_account" -> SUBSCRIBED_ELSEWHERE
        "google_subscription_not_replaced" -> NOT_REPLACED
        else -> REFUSED
    }

    /** The screen has opened [CheckoutUiState.launchUrl]; clear the one-shot so it isn't reopened. */
    fun onLaunchUrlOpened() {
        _uiState.update { it.copy(launchUrl = null) }
    }

    /** Re-open the hosted checkout page — the browser may not have launched, or the user came back. */
    fun reopenCheckout() {
        val url = lastCheckoutUrl
        if (url == null) {
            start()
        } else {
            _uiState.update { it.copy(launchUrl = url) }
            // The customer is going back to pay: the fetching may have run out while they were away.
            pollForThePlan()
        }
    }

    /**
     * The screen is in front again — usually the customer returning from the browser.
     *
     * ⚠⚠ Android suspends a backgrounded app, so the poll does not run while the customer is
     * paying (measured on the first device pass: it stopped ~70 s after Chrome came forward). And
     * it is bounded, so a payment that completes after the window found nobody asking and left
     * this screen waiting forever. Coming back therefore asks at once and starts the window again.
     */
    fun onResumed() {
        if (_uiState.value.step != CheckoutStep.AwaitingConfirmation) return
        viewModelScope.launch { publisherClient.syncPlan() }
        pollForThePlan()
    }

    /**
     * Fetch the purchase while the customer is in the browser.
     *
     * ⚠⚠ [observeConfirmation] only *watches* the synced record; nothing wrote it. The webhook
     * records the sale in commerce's ledger, and the device has to ask for the signed plan
     * ([PublisherClient.syncPlan]) — so without this the screen waited on a record that arrived
     * only at the writer's next renewal tick. Confirmation still comes from the synced status,
     * never from this call's answer.
     *
     * ⚠ Bounded: a customer who abandons the browser must not leave a request loop running.
     */
    private fun pollForThePlan() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            repeat(planPollAttempts) {
                delay(planPollIntervalMs)
                if (_uiState.value.step != CheckoutStep.AwaitingConfirmation) return@launch
                publisherClient.syncPlan()
            }
        }
    }

    private var pollJob: Job? = null

    private fun observeConfirmation() {
        val target = targetQuotaBytes ?: return
        viewModelScope.launch {
            storageStatusFlow.collect { status ->
                // Only the always-on, authoritative signal flips us to Done: the synced quota
                // reaching the purchased tier. (Re-buying a tier already held confirms at once,
                // which is harmless — the user already has it.)
                if (_uiState.value.step == CheckoutStep.AwaitingConfirmation &&
                    status != null && status.hasStorage && status.limitBytes >= target
                ) {
                    _uiState.update { it.copy(step = CheckoutStep.Done, launchUrl = null) }
                }
            }
        }
    }

    private companion object {
        const val UNAVAILABLE = "Paid plans aren't available right now. Please try again later."
        const val UNKNOWN_PLAN = "That plan isn't available."
        const val SIGN_IN_FIRST = "Sign in with your email before buying a plan."
        const val ALREADY_SUBSCRIBED = "You are already on this plan. Nothing was charged."
        const val PLAN_CHANGE =
            "You already have a paid plan. Change it from Manage subscription on the Services screen, " +
                "so you keep one subscription and are only charged the difference."

        const val UPGRADE_ON_APPLE =
            "Your plan is billed by Apple. Change it on your iPhone or iPad, so you never pay twice."
        const val NOT_SOLD_HERE = "That plan can't be bought here."
        const val NOT_REPLACED =
            "You already have a Google Play subscription for Tenshu, possibly on hold or paused. " +
                "Fix or cancel it in Google Play first. This purchase is refunded automatically within three days."

        const val STORE_UNAVAILABLE = "Google Play isn't available on this device right now."
        const val PAYMENT_PENDING =
            "Your payment is still pending with Google Play. The plan starts as soon as it goes through."
        const val CANCELLED = "The purchase was cancelled. Nothing was charged."
        const val NOT_CONFIRMED_YET =
            "Your purchase went through, but it isn't confirmed yet. Try again in a moment; you won't be charged twice."
        const val REFUSED =
            "This purchase can't be used for your account. Google Play refunds it automatically within three days."

        const val SUBSCRIBED_ELSEWHERE =
            "This device's storage already has a paid plan under a different email address. " +
                "Nothing was charged. Sign in with that email to manage or change it."

        /** Every 5 s for 15 minutes — long enough for a 3-D Secure detour, short enough to stop. */
        const val PLAN_POLL_INTERVAL_MS = 5_000L
        const val PLAN_POLL_ATTEMPTS = 180
    }
}

enum class CheckoutStep {
    Acknowledge,
    Launching,
    /** A store rail: the screen shows the store's purchase sheet ([CheckoutUiState.storePurchase]). */
    AwaitingStore,
    AwaitingConfirmation,
    Done,
    Error,
}

/** What the screen hands the store's sheet. */
data class StorePurchaseRequest(val productId: String, val accountId: String)

data class CheckoutUiState(
    val step: CheckoutStep = CheckoutStep.Acknowledge,
    /** The customer has ticked the immediate-performance acknowledgement (policy §5.4). */
    val acknowledged: Boolean = false,
    val busy: Boolean = false,
    /** One-shot: a URL the screen should open in the browser, then clear via [CheckoutViewModel.onLaunchUrlOpened]. */
    val launchUrl: String? = null,
    /** The purchase goes through the store's sheet, not the browser (copy differs). */
    val viaStore: Boolean = false,
    /** One-shot: the screen hands over its host via [CheckoutViewModel.launchStorePurchase]. */
    val storePurchase: StorePurchaseRequest? = null,
    val error: String? = null,
)
