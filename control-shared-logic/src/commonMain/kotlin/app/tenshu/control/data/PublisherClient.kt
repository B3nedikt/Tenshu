package app.tenshu.control.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.identity.EntitlementSignature

/**
 * The device-side commerce account surface (`docs/proposals/commerce-accounts-and-sessions.md`
 * §3–§4). The commerce plane mediates the relay's per-owner entitlements (free-tier storage, paid
 * subscriptions, redeemed codes) and is the only PII-bearing component in the otherwise
 * zero-knowledge stack — the relay sees ciphertext only (House Rule #1).
 *
 * **Phase 3b makes this real.** The earlier mock spoke a poll-based "check your email" contract;
 * the commerce service ([`:commerce-service`]) actually issues a short numeric **email-OTP** that
 * the user types back, and on success returns a **session token** bound to the device's `ownerId`.
 * The contract here mirrors that:
 *
 *  - [register] → `POST /commerce/account/register` (emails an OTP);
 *  - [verifyOtp] → `POST /commerce/account/verify` (binds the owner, opens a session, sets
 *    [account]);
 *  - [redeem] → `POST /commerce/entitlement/redeem` (identity-light; mints a signed entitlement
 *    directly, no email/session — §2.3);
 *  - [currentSessionToken] feeds the session-gated entitlement mint/token clients
 *    ([CommerceEntitlementMintClient] / [CommerceEntitlementTokenClient]) so a freshly-verified
 *    session immediately unlocks the off-device mint and the relay's `X-Entitlement` token.
 *
 * Two impls: [MockPublisherClient] (dev / `TENSHU_BILLING_MOCK`, no service standing) and
 * [CommercePublisherClient] (real HTTP). Selected per platform alongside the entitlement minter
 * (`PlatformProxyModule`), under the same dev-seed gate.
 */
interface PublisherClient {
    /** Null when no account is bound; populated after a successful [verifyOtp] / [redeem]. */
    val account: StateFlow<PublisherAccount?>

    /**
     * Hot stream of entitlements granted via [redeem] — each a **commerce-signed**
     * [EntitlementSignature.SignedEntitlement] the proxy persists into the realm (the
     * identity-light redeem path has no session to drive the ordinary mint writer, so the
     * already-signed grant rides this seam instead). The [MockPublisherClient] grants the redeem
     * tier through [account] + the in-process dev-key writer and so emits nothing here.
     */
    val redeemedEntitlements: Flow<EntitlementSignature.SignedEntitlement>
        get() = emptyFlow()

    /**
     * The current commerce **session token**, or `null` when not signed in. Read **synchronously**
     * by [CommerceEntitlementMintClient] / [CommerceEntitlementTokenClient] on the request path — a
     * cached in-memory value (rehydrated from secure storage at construction), never a network hop.
     */
    fun currentSessionToken(): String?

    /**
     * §3.2 Free path, step 1: register [email] and trigger an OTP email. [displayName] is kept
     * locally for the account card; the commerce `/register` endpoint does not take it.
     */
    suspend fun register(email: String, displayName: String?): RegisterResult

    /**
     * §3.2 Free path, step 2: verify the emailed [code]. On success the service binds this device's
     * `ownerId`, opens a session (stored, see [currentSessionToken]), and [account] flips to Free —
     * which drives the ordinary entitlement mint writer to fetch the owner's signed Free record.
     */
    suspend fun verifyOtp(code: String): VerifyResult

    /**
     * §2.3 advanced redemption: redeem [code] for its entitlement. Identity-light — no email, no
     * session; the granted record arrives on [redeemedEntitlements] (real) or via the dev-key
     * writer (mock).
     */
    suspend fun redeem(code: String): RedeemResult

    /**
     * §6 paid-tier checkout, step 1: open a hosted Stripe Checkout session for [productId] (one of
     * [CommerceProducts]).
     *
     * ⚠⚠ **Signed in, or [CheckoutResult.SignInRequired]** (§9.4f, amending D5). The session rides
     * with the request so Stripe is handed the account's email instead of collecting one: the
     * webhook finds the account by the email on its event, and the plan is fetched with a session
     * ([syncPlan]), so a free-typed address put the purchase on an account this device could not
     * reach. It is also what lets the service refuse a second subscription for the same customer.
     *
     * On [CheckoutResult.Ready] the UI opens the returned URL in the system browser; the actual
     * grant is confirmed out-of-band by the entitlement arriving on the synced `StorageStatus`
     * (the webhook is the only source of truth — the browser return is never trusted for
     * fulfilment). The mock has no browser leg and reports [CheckoutResult.CompletedLocally].
     *
     * ⚠ [withdrawalAcknowledged] is the customer's acknowledgement that the service starts
     * immediately, which is what disapplies the EU 14-day right of withdrawal (policy §5.4). The
     * service **refuses** a checkout without it, so passing `false` is a rejected request rather
     * than a silently weaker purchase — the tick has to be real.
     */
    suspend fun startCheckout(productId: String, withdrawalAcknowledged: Boolean): CheckoutResult

    /**
     * §6 manage-subscription: open a Stripe billing portal session for the signed-in account (update
     * card, cancel, view invoices). **Session-gated** — needs a bound session ([currentSessionToken])
     * and a prior Stripe purchase ([PortalResult.NoSubscription] otherwise). On [PortalResult.Ready]
     * the UI opens the URL in the system browser. The mock has no Stripe customer → [PortalResult.Unavailable].
     */
    suspend fun openBillingPortal(): PortalResult

    /**
     * Ask commerce for the account's **current** plan and hand the signed record to the proxy to
     * save (it rides [redeemedEntitlements], the seam for any commerce-signed grant).
     *
     * ⚠⚠ Nothing else brings a purchase to the device promptly. The webhook writes commerce's
     * ledger only, and the account-driven writer asks for the product the *account* names — always
     * Free — then skips a record that already says Free, so a buyer stayed on Free until the
     * renewal tick. Called after a checkout and whenever the Services screen opens.
     */
    suspend fun syncPlan(): PlanSyncResult

    /** Drops the local account binding + session token. */
    suspend fun disconnect()

    /**
     * Account deletion, step 1 (`commerce-accounts-and-sessions.md` §9.7): ask commerce to mail a
     * **deletion code** to the signed-in account's address (`POST /commerce/account/delete/request`
     * with this device's session). The session alone is not enough to delete — the code proves the
     * inbox, because deletion is irreversible and a signed-in device can be someone else's hands.
     */
    suspend fun requestAccountDeletion(): AccountDeletionResult = AccountDeletionResult.Unavailable

    /**
     * Account deletion, step 2: confirm with the mailed [code]. On [AccountDeletionResult.Deleted]
     * the account is gone on commerce and this client has already dropped its local binding, as
     * [disconnect] does — the session it held no longer exists.
     */
    suspend fun confirmAccountDeletion(code: String): AccountDeletionResult = AccountDeletionResult.Unavailable

    sealed class AccountDeletionResult {
        /** Step 1 done: a code is on its way to the account's address. */
        data object CodeSent : AccountDeletionResult()

        /** Step 2 done: the account is deleted, and this device is signed out of it. */
        data object Deleted : AccountDeletionResult()

        /** The code was wrong, expired or used up (401) — retry, or ask for a new one. */
        data object InvalidCode : AccountDeletionResult()

        /**
         * No session here, or commerce no longer knows it (401 on the request). The way forward is
         * the public web page, which needs only the address — or signing in again.
         */
        data object SignInRequired : AccountDeletionResult()

        /**
         * Commerce no longer knows this device's session, so the local binding was dropped. ⚠⚠ The
         * likeliest cause right after a confirm is that **the deletion went through** and only the
         * response was lost: a retry then finds no account and answers 401. The UI must say so
         * rather than "wrong code", or the user keeps retrying against an account that is gone.
         */
        data object SignedOut : AccountDeletionResult()

        /** This build cannot delete (the dev mock); the web page still can. */
        data object Unavailable : AccountDeletionResult()
        data class Error(val message: String) : AccountDeletionResult()
    }

    /**
     * The signed-in account's plan, who bills it, and what this build's [rail] may buy
     * (`POST /commerce/account/billing`, `commerce-store-link-out.md` D6). The account id it
     * returns is what a store purchase must name.
     */
    suspend fun billing(rail: BillingRail): BillingResult = BillingResult.Unavailable

    /**
     * Hand a store purchase to commerce, which checks it with the store, books it to this account
     * and acknowledges it. Idempotent: verifying the same purchase twice books it once, so a
     * [StoreVerifyResult.Retry] is always safe to repeat.
     */
    suspend fun verifyStorePurchase(rail: BillingRail, purchaseToken: String): StoreVerifyResult =
        StoreVerifyResult.Retry("Store purchases are not supported here.")

    sealed class BillingResult {
        data class Ready(
            val accountId: String,
            /** The current plan; null without any valid entitlement. */
            val productId: String?,
            /** `Free`, `Code`, `Stripe`, `Apple` or `Google`; null without a plan. */
            val billedBy: String?,
            val validUntil: Long?,
            /** What this build may buy, lowest first. */
            val offers: List<String>,
            /** Set when the plan can only be upgraded on that rail's devices. */
            val upgradeOn: String?,
            /** Why [offers] is empty when the realm's other accounts are the reason (a refusal code). */
            val refusal: String? = null,
        ) : BillingResult()


        data object SignInRequired : BillingResult()
        data object Unavailable : BillingResult()
        data class Error(val message: String) : BillingResult()
    }

    sealed class StoreVerifyResult {
        data class Verified(val productId: String, val validUntil: Long) : StoreVerifyResult()

        /**
         * Commerce refused the purchase (it named another account, or would make the user pay
         * twice). It stays unacknowledged, so the store refunds it.
         */
        data class Refused(val reason: String) : StoreVerifyResult()

        data object SignInRequired : StoreVerifyResult()

        /** Not verified yet (unreachable, the store did not answer); safe to repeat. */
        data class Retry(val message: String) : StoreVerifyResult()
    }

    sealed class CheckoutResult {
        /** Open [url] (a hosted Stripe Checkout page) in the system browser. */
        data class Ready(val url: String) : CheckoutResult()

        /** Mock/dev only: the tier was granted locally, no browser leg — treat as done. */
        data object CompletedLocally : CheckoutResult()

        /** No session on this device (or commerce no longer knows it, 401): sign in, then buy. */
        data object SignInRequired : CheckoutResult()

        /** This plan is already billing (409). A checkout would start a second subscription. */
        data object AlreadySubscribed : CheckoutResult()

        /** Another plan is billing (409): change it on that subscription, in the billing portal. */
        data object PlanChangeRequired : CheckoutResult()

        /**
         * This realm already pays under a **different account** (409). The portal is per account,
         * so this session cannot manage that subscription — the way forward is that email.
         */
        data object SubscribedUnderAnotherAccount : CheckoutResult()

        /** The Stripe rail isn't configured on the service (503) — paid tiers unavailable here. */
        data object Unavailable : CheckoutResult()
        data class Error(val message: String) : CheckoutResult()
    }

    /** What [syncPlan] found. [Synced] names a plan read off a record commerce signed. */
    sealed class PlanSyncResult {
        data class Synced(val productId: String) : PlanSyncResult()

        /** No session here, or commerce no longer recognises it (the dead session is dropped). */
        data object SignedOut : PlanSyncResult()

        /** The account holds no live plan. */
        data object Unavailable : PlanSyncResult()
        data class Error(val message: String) : PlanSyncResult()
    }

    sealed class PortalResult {
        /** Open [url] (a hosted Stripe billing portal) in the system browser. */
        data class Ready(val url: String) : PortalResult()

        /** The account has never completed a Stripe purchase (409) — nothing to manage. */
        data object NoSubscription : PortalResult()

        /** No / expired session (401) — the caller must sign in again. */
        data object Unauthorized : PortalResult()

        /** The Stripe rail isn't configured on the service (503), or the mock has no customer. */
        data object Unavailable : PortalResult()
        data class Error(val message: String) : PortalResult()
    }

    sealed class RegisterResult {
        /** The OTP email was sent (or, for the mock, would have been). */
        data object OtpSent : RegisterResult()
        data class Error(val message: String) : RegisterResult()
    }

    sealed class VerifyResult {
        data object Verified : VerifyResult()
        /** The code was wrong / expired / exhausted — let the user retry. */
        data object InvalidCode : VerifyResult()
        data class Error(val message: String) : VerifyResult()
    }

    sealed class RedeemResult {
        data object Redeemed : RedeemResult()
        /** The code was unknown / exhausted / expired. */
        data object Invalid : RedeemResult()
        data class Error(val message: String) : RedeemResult()
    }
}

/**
 * Account state observed by the Services tab and any flow that needs to know if the user has bound
 * an account yet (e.g. `[Add another device]` routes through the §2.2 picker first when this is
 * null).
 *
 * ⚠⚠ **This carries identity, never figures.** It used to hold `storageUsedBytes` /
 * `storageLimitBytes`, and both were hardcoded literals on the *real* client that nothing ever
 * revised — so a signed-in device showed "Free · 0 B of 5 GiB" however much it had actually bought
 * or stored (`commerce-end-to-end-dry-run.md` §1.1 / D5). Plan and usage now come from the synced
 * `Entitlement` via `StorageStatus`, which is the one place they are true on every device. The
 * fields are **deleted** rather than left unread, so a later change cannot quietly start trusting
 * them again.
 *
 * [plan] survives only because the dev-key entitlement writer maps it to a product id
 * (`EntitlementStorageInitialization.productIdForPlan`) in mock/dev mode — it is an input to
 * minting, not something to render.
 */
@Serializable
data class PublisherAccount(
    val plan: Plan,
    val identityMethod: IdentityMethod,
    val displayName: String?,
) {
    @Serializable
    enum class Plan { Free, Rei }

    @Serializable
    sealed class IdentityMethod {
        @Serializable
        data class Email(val address: String) : IdentityMethod()

        @Serializable
        data class ReiKey(val source: String) : IdentityMethod()
    }
}

/**
 * The purchasable paid tiers (`commerce-accounts-and-sessions.md` §2.1). Product ids + quotas mirror
 * the canonical `EntitlementProducts` in `:storage-service-proxy`; they're duplicated here because a
 * logic module must not depend on a proxy module (and [MockPublisherClient] already carries the same
 * byte constants). Free isn't purchasable, so it's absent. The id is what `/commerce/checkout` maps
 * to a Stripe price; the quota is the confirmation target the UI watches on the synced `StorageStatus`.
 */
object CommerceProducts {
    const val BASIC = "tenshu.drive.basic"
    const val PREMIUM = "tenshu.drive.premium"

    const val BASIC_LIMIT_BYTES: Long = 50L * 1024L * 1024L * 1024L // 50 GiB
    const val PREMIUM_LIMIT_BYTES: Long = 2L * 1024L * 1024L * 1024L * 1024L // 2 TiB

    /** The storage quota a purchased [productId] grants, or `null` for an unknown id. */
    fun quotaBytesFor(productId: String): Long? = when (productId) {
        BASIC -> BASIC_LIMIT_BYTES
        PREMIUM -> PREMIUM_LIMIT_BYTES
        else -> null
    }
}

/**
 * The dev / `TENSHU_BILLING_MOCK` stand-in: no commerce service, no real email. [register] always
 * "sends"; [verifyOtp] accepts any 6-digit numeric code (the real service emails a 6-digit OTP, so
 * this keeps the same UI shape) and binds a Free account; [redeem] grants the redeem tier through
 * [account] (the in-process dev-key writer then signs + persists it). Account state is mirrored to
 * [SecureStorageDelegate] so a signup survives an app restart.
 */
class MockPublisherClient(
    private val storageDelegate: SecureStorageDelegate,
) : PublisherClient {

    private val _account = MutableStateFlow<PublisherAccount?>(loadAccount())
    override val account: StateFlow<PublisherAccount?> = _account.asStateFlow()

    private var pendingEmail: String? = null
    private var pendingDisplayName: String? = null
    private var sessionToken: String? = if (_account.value != null) MOCK_SESSION_TOKEN else null

    override fun currentSessionToken(): String? = sessionToken

    override suspend fun register(email: String, displayName: String?): PublisherClient.RegisterResult {
        if (email.isBlank() || !email.contains('@')) {
            return PublisherClient.RegisterResult.Error("Enter a valid email address.")
        }
        pendingEmail = email.trim()
        pendingDisplayName = displayName?.takeIf { it.isNotBlank() }?.trim()
        return PublisherClient.RegisterResult.OtpSent
    }

    override suspend fun verifyOtp(code: String): PublisherClient.VerifyResult {
        val email = pendingEmail
            ?: return PublisherClient.VerifyResult.Error("Start by entering your email.")
        if (!isSixDigit(code)) return PublisherClient.VerifyResult.InvalidCode
        sessionToken = MOCK_SESSION_TOKEN
        setAccount(
            PublisherAccount(
                plan = PublisherAccount.Plan.Free,
                identityMethod = PublisherAccount.IdentityMethod.Email(email),
                displayName = pendingDisplayName,
            ),
        )
        pendingEmail = null
        pendingDisplayName = null
        return PublisherClient.VerifyResult.Verified
    }

    override suspend fun redeem(code: String): PublisherClient.RedeemResult {
        if (code.isBlank()) return PublisherClient.RedeemResult.Invalid
        // The mock has no commerce signer, so it grants the redeem tier through the account →
        // dev-key mint writer instead of [redeemedEntitlements]. Rei maps to the Basic quota
        // (`EntitlementStorageInitialization.productIdForPlan`).
        setAccount(
            PublisherAccount(
                plan = PublisherAccount.Plan.Rei,
                identityMethod = PublisherAccount.IdentityMethod.ReiKey(source = "code"),
                displayName = null,
            ),
        )
        return PublisherClient.RedeemResult.Redeemed
    }

    override suspend fun startCheckout(
        productId: String,
        withdrawalAcknowledged: Boolean,
    ): PublisherClient.CheckoutResult {
        // No Stripe, no browser: grant the tier locally through the account → dev-key writer, the
        // same way [redeem] does, and report it as already-complete so the UI skips the browser leg.
        // ⚠ The mock's dev-key writer mints a fixed product per `Plan`, so the mock cannot express
        // per-tier quotas any more precisely than Rei→Basic. That is a limitation of the *mock*, and
        // the reason the dry run uses the real service with `STRIPE_MODE=fake` (CE-3) instead.
        CommerceProducts.quotaBytesFor(productId)
            ?: return PublisherClient.CheckoutResult.Error("Unknown plan.")
        setAccount(
            PublisherAccount(
                plan = PublisherAccount.Plan.Rei,
                identityMethod = PublisherAccount.IdentityMethod.ReiKey(source = "checkout"),
                displayName = _account.value?.displayName,
            ),
        )
        return PublisherClient.CheckoutResult.CompletedLocally
    }

    override suspend fun openBillingPortal(): PublisherClient.PortalResult =
        // The mock has no Stripe customer to manage.
        PublisherClient.PortalResult.Unavailable

    // Nothing to fetch: the mock's plan IS the account, and the dev-key writer already follows it.
    override suspend fun syncPlan(): PublisherClient.PlanSyncResult =
        if (_account.value == null) PublisherClient.PlanSyncResult.SignedOut
        else PublisherClient.PlanSyncResult.Unavailable

    override suspend fun disconnect() {
        setAccount(null)
        pendingEmail = null
        pendingDisplayName = null
        sessionToken = null
    }

    // The mock mirrors the real two-step shape so the UI can be exercised in dev: any 6-digit code
    // "deletes", which for the mock means forgetting the local account.
    override suspend fun requestAccountDeletion(): PublisherClient.AccountDeletionResult =
        if (_account.value == null) PublisherClient.AccountDeletionResult.SignInRequired
        else PublisherClient.AccountDeletionResult.CodeSent

    override suspend fun confirmAccountDeletion(code: String): PublisherClient.AccountDeletionResult {
        if (_account.value == null) return PublisherClient.AccountDeletionResult.SignInRequired
        if (!isSixDigit(code.trim())) return PublisherClient.AccountDeletionResult.InvalidCode
        disconnect()
        return PublisherClient.AccountDeletionResult.Deleted
    }

    private fun setAccount(next: PublisherAccount?) {
        _account.value = next
        if (next == null) {
            storageDelegate.remove(STORAGE_KEY)
        } else {
            storageDelegate.storeSecret(STORAGE_KEY, json.encodeToString(next))
        }
    }

    private fun loadAccount(): PublisherAccount? {
        val raw = storageDelegate.getSecret(STORAGE_KEY) ?: return null
        return runCatching { json.decodeFromString<PublisherAccount>(raw) }
            .getOrNull()
            .also { if (it == null) storageDelegate.remove(STORAGE_KEY) }
    }

    companion object {
        const val FREE_TIER_LIMIT_BYTES: Long = 5L * 1024L * 1024L * 1024L // 5 GiB
        const val BASIC_TIER_LIMIT_BYTES: Long = 50L * 1024L * 1024L * 1024L // 50 GiB

        /** A non-null stand-in so dev gating that reads [currentSessionToken] behaves as "signed in". */
        const val MOCK_SESSION_TOKEN: String = "mock-session"

        /**
         * SecureStorageDelegate key for the persisted account JSON. Tied to the mock; the real
         * client persists its own session record (see [CommercePublisherClient]).
         */
        const val STORAGE_KEY: String = "mock_publisher_account"

        private val json = Json { ignoreUnknownKeys = true }

        private fun isSixDigit(code: String): Boolean =
            code.length == 6 && code.all { it.isDigit() }
    }
}
