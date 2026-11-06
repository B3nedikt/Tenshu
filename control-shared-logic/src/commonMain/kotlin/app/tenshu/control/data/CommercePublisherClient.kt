package app.tenshu.control.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.identity.EntitlementSignature
import kotlin.io.encoding.Base64

/**
 * The real device-side commerce account client (`commerce-accounts-and-sessions.md` §3–§4):
 * drives the `:commerce-service` account/session/redeem endpoints over HTTP.
 *
 * - [register] → `POST /commerce/account/register` (`{email, tosVersion, privacyVersion}` → 202).
 * - [verifyOtp] → `POST /commerce/account/verify` (`{email, code, ownerId}` → 200 `{sessionToken}`);
 *   the service binds this device's [ownerIdProvider] owner and opens a session. The token is
 *   persisted (so it survives a restart) and surfaced synchronously via [currentSessionToken] for
 *   the session-gated entitlement clients.
 * - [redeem] → `POST /commerce/entitlement/redeem` (`{code, ownerId}` → 200 signed entitlement); the
 *   grant is emitted on [redeemedEntitlements] for the proxy to persist (identity-light — no session
 *   to drive the ordinary mint writer).
 *
 * The session record (email + token) lives in [SecureStorageDelegate]; email is **device-local PII**
 * and never crosses to the relay (House Rule #1). All endpoints are content-free on error; any
 * non-success / transport failure maps to a typed result, never an echo of which check failed.
 */
class CommercePublisherClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val ownerIdProvider: () -> String?,
    private val storageDelegate: SecureStorageDelegate,
    private val tosVersion: String = TOS_VERSION,
    private val privacyVersion: String = PRIVACY_VERSION,
) : PublisherClient {

    private val stored: StoredSession? = loadSession()

    private val _account = MutableStateFlow(stored?.let(::accountFor))
    override val account: StateFlow<PublisherAccount?> = _account.asStateFlow()

    private val _redeemed = MutableSharedFlow<EntitlementSignature.SignedEntitlement>(
        replay = 1,
        extraBufferCapacity = 8,
    )
    override val redeemedEntitlements: Flow<EntitlementSignature.SignedEntitlement> =
        _redeemed.asSharedFlow()

    private var sessionToken: String? = stored?.sessionToken
    private var pendingEmail: String? = null
    private var pendingDisplayName: String? = null

    override fun currentSessionToken(): String? = sessionToken?.takeIf { it.isNotBlank() }

    override suspend fun register(email: String, displayName: String?): PublisherClient.RegisterResult {
        val normalised = email.trim()
        if (normalised.isBlank() || !normalised.contains('@')) {
            return PublisherClient.RegisterResult.Error("Enter a valid email address.")
        }
        val response = runCatchingCancellable {
            httpClient.post("${baseUrl.trimEnd('/')}$REGISTER_PATH") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        email = normalised,
                        tosVersion = tosVersion,
                        privacyVersion = privacyVersion,
                    ),
                )
            }
        }.getOrNull() ?: return PublisherClient.RegisterResult.Error(UNREACHABLE)

        // ⚠ The service tells these apart — `rate_limited`, `send_failed`, everything else — so
        // never collapse them into GENERIC. "Something went wrong. Please try again." is actively
        // wrong advice for a spent budget, where trying again is the one thing that cannot work
        // for an hour.
        return when (response.status) {
            HttpStatusCode.Accepted -> {
                pendingEmail = normalised
                pendingDisplayName = displayName?.takeIf { it.isNotBlank() }?.trim()
                PublisherClient.RegisterResult.OtpSent
            }
            HttpStatusCode.TooManyRequests -> PublisherClient.RegisterResult.Error(RATE_LIMITED)
            HttpStatusCode.ServiceUnavailable -> PublisherClient.RegisterResult.Error(SEND_FAILED)
            // 400 `bad_request` lands here too, deliberately: a malformed body is our bug, not
            // something the person reading the screen can act on differently.
            else -> PublisherClient.RegisterResult.Error(GENERIC)
        }
    }

    override suspend fun verifyOtp(code: String): PublisherClient.VerifyResult {
        val email = pendingEmail
            ?: return PublisherClient.VerifyResult.Error("Start by entering your email.")
        val ownerId = ownerIdProvider()?.takeIf { it.isNotBlank() }
            ?: return PublisherClient.VerifyResult.Error(NO_IDENTITY)

        val response: HttpResponse = runCatchingCancellable {
            httpClient.post("${baseUrl.trimEnd('/')}$VERIFY_PATH") {
                contentType(ContentType.Application.Json)
                setBody(VerifyRequest(email = email, code = code.trim(), ownerId = ownerId))
            }
        }.getOrNull() ?: return PublisherClient.VerifyResult.Error(UNREACHABLE)

        return when (response.status) {
            HttpStatusCode.OK -> {
                val token = runCatchingCancellable { response.body<VerifyResponse>() }.getOrNull()?.sessionToken
                    ?: return PublisherClient.VerifyResult.Error(GENERIC)
                persistSession(StoredSession(email = email, sessionToken = token))
                sessionToken = token
                // Identity only — plan and usage come from the synced entitlement (D5), never from
                // literals here.
                _account.value = PublisherAccount(
                    plan = PublisherAccount.Plan.Free,
                    identityMethod = PublisherAccount.IdentityMethod.Email(email),
                    displayName = pendingDisplayName,
                )
                pendingEmail = null
                pendingDisplayName = null
                PublisherClient.VerifyResult.Verified
            }
            // 401 = unknown email / wrong / expired / exhausted OTP (the service answers uniformly).
            HttpStatusCode.Unauthorized -> PublisherClient.VerifyResult.InvalidCode
            else -> PublisherClient.VerifyResult.Error(GENERIC)
        }
    }

    override suspend fun redeem(code: String): PublisherClient.RedeemResult {
        val trimmed = code.trim()
        if (trimmed.isBlank()) return PublisherClient.RedeemResult.Invalid
        val ownerId = ownerIdProvider()?.takeIf { it.isNotBlank() }
            ?: return PublisherClient.RedeemResult.Error(NO_IDENTITY)

        val response: HttpResponse = runCatchingCancellable {
            httpClient.post("${baseUrl.trimEnd('/')}$REDEEM_PATH") {
                contentType(ContentType.Application.Json)
                setBody(
                    RedeemRequest(
                        code = trimmed,
                        ownerId = ownerId,
                        sessionToken = currentSessionToken(),
                    ),
                )
            }
        }.getOrNull() ?: return PublisherClient.RedeemResult.Error(UNREACHABLE)

        return when (response.status) {
            HttpStatusCode.OK -> {
                val signed = runCatchingCancellable { response.body<RedeemedEntitlementResponse>() }
                    .getOrNull()?.toSigned()
                    ?: return PublisherClient.RedeemResult.Error(GENERIC)
                _redeemed.emit(signed)
                PublisherClient.RedeemResult.Redeemed
            }
            HttpStatusCode.Forbidden -> PublisherClient.RedeemResult.Invalid
            else -> PublisherClient.RedeemResult.Error(GENERIC)
        }
    }

    override suspend fun startCheckout(
        productId: String,
        withdrawalAcknowledged: Boolean,
    ): PublisherClient.CheckoutResult {
        val ownerId = ownerIdProvider()?.takeIf { it.isNotBlank() }
            ?: return PublisherClient.CheckoutResult.Error(NO_IDENTITY)
        val token = currentSessionToken()
            ?: return PublisherClient.CheckoutResult.SignInRequired

        val response: HttpResponse = runCatchingCancellable {
            httpClient.post("${baseUrl.trimEnd('/')}$CHECKOUT_PATH") {
                contentType(ContentType.Application.Json)
                setBody(
                    CheckoutRequest(
                        ownerId = ownerId,
                        productId = productId,
                        withdrawalAcknowledged = withdrawalAcknowledged,
                        // The version the customer actually saw when they ticked, so the stored
                        // waiver names the terms it was given against.
                        tosVersion = tosVersion,
                        sessionToken = token,
                    ),
                )
            }
        }.getOrNull() ?: return PublisherClient.CheckoutResult.Error(UNREACHABLE)

        return when (response.status) {
            HttpStatusCode.OK -> {
                val url = runCatching { response.body<CheckoutResponse>() }.getOrNull()?.checkoutUrl
                    ?: return PublisherClient.CheckoutResult.Error(GENERIC)
                PublisherClient.CheckoutResult.Ready(url)
            }
            // A token the service no longer knows is dead; keeping it would leave the device
            // showing a signed-in account that can buy nothing.
            HttpStatusCode.Unauthorized -> {
                disconnect()
                PublisherClient.CheckoutResult.SignInRequired
            }
            // 409 = this customer already has a subscription billing. The body says which case.
            HttpStatusCode.Conflict -> {
                val error = runCatchingCancellable { response.body<CommerceErrorResponse>() }.getOrNull()?.error
                when (error) {
                    ALREADY_SUBSCRIBED -> PublisherClient.CheckoutResult.AlreadySubscribed
                    SUBSCRIBED_ELSEWHERE -> PublisherClient.CheckoutResult.SubscribedUnderAnotherAccount
                    else -> PublisherClient.CheckoutResult.PlanChangeRequired
                }
            }
            // 503 = the service has no Stripe rail configured (paid tiers unavailable on this relay).
            HttpStatusCode.ServiceUnavailable -> PublisherClient.CheckoutResult.Unavailable
            else -> PublisherClient.CheckoutResult.Error(GENERIC)
        }
    }

    override suspend fun billing(rail: BillingRail): PublisherClient.BillingResult {
        val token = currentSessionToken() ?: return PublisherClient.BillingResult.SignInRequired
        val response: HttpResponse = runCatchingCancellable {
            httpClient.post("${baseUrl.trimEnd('/')}$BILLING_PATH") {
                contentType(ContentType.Application.Json)
                setBody(BillingRequest(sessionToken = token, rail = rail.wire))
            }
        }.getOrNull() ?: return PublisherClient.BillingResult.Error(UNREACHABLE)
        return when (response.status) {
            HttpStatusCode.OK -> {
                val body = runCatchingCancellable { response.body<BillingResponse>() }.getOrNull()
                    ?: return PublisherClient.BillingResult.Error(GENERIC)
                PublisherClient.BillingResult.Ready(
                    accountId = body.accountId,
                    productId = body.productId,
                    billedBy = body.billedBy,
                    validUntil = body.validUntil,
                    offers = body.offers,
                    upgradeOn = body.upgradeOn,
                    refusal = body.refusal,
                )
            }
            HttpStatusCode.Unauthorized -> {
                disconnect()
                PublisherClient.BillingResult.SignInRequired
            }
            // An older commerce without the route answers 404; treat it as "nothing to sell".
            HttpStatusCode.NotFound, HttpStatusCode.ServiceUnavailable -> PublisherClient.BillingResult.Unavailable
            else -> PublisherClient.BillingResult.Error(GENERIC)
        }
    }

    override suspend fun verifyStorePurchase(
        rail: BillingRail,
        purchaseToken: String,
    ): PublisherClient.StoreVerifyResult {
        val path = when (rail) {
            BillingRail.GOOGLE_PLAY -> GOOGLE_VERIFY_PATH
            else -> return PublisherClient.StoreVerifyResult.Retry(GENERIC)
        }
        val token = currentSessionToken() ?: return PublisherClient.StoreVerifyResult.SignInRequired
        val response: HttpResponse = runCatchingCancellable {
            httpClient.post("${baseUrl.trimEnd('/')}$path") {
                contentType(ContentType.Application.Json)
                setBody(StoreVerifyRequest(sessionToken = token, purchaseToken = purchaseToken))
            }
        }.getOrNull() ?: return PublisherClient.StoreVerifyResult.Retry(UNREACHABLE)
        return when {
            response.status == HttpStatusCode.OK -> {
                val body = runCatchingCancellable { response.body<StoreVerifyResponse>() }.getOrNull()
                    ?: return PublisherClient.StoreVerifyResult.Retry(GENERIC)
                // The grant is booked; fetch the signed record now rather than at the next renewal.
                syncPlan()
                PublisherClient.StoreVerifyResult.Verified(body.productId, body.validUntil)
            }
            response.status == HttpStatusCode.Unauthorized -> {
                disconnect()
                PublisherClient.StoreVerifyResult.SignInRequired
            }
            // 4xx other than auth is a refusal commerce will not change its mind about.
            response.status.value in 400..499 -> {
                val error = runCatchingCancellable { response.body<CommerceErrorResponse>() }.getOrNull()?.error
                PublisherClient.StoreVerifyResult.Refused(error ?: "refused")
            }
            else -> PublisherClient.StoreVerifyResult.Retry(GENERIC)
        }
    }

    override suspend fun syncPlan(): PublisherClient.PlanSyncResult {
        val token = currentSessionToken() ?: return PublisherClient.PlanSyncResult.SignedOut

        val response: HttpResponse = runCatchingCancellable {
            httpClient.post("${baseUrl.trimEnd('/')}$MINT_PATH") {
                contentType(ContentType.Application.Json)
                setBody(PlanMintRequest(sessionToken = token))
            }
        }.getOrNull() ?: return PublisherClient.PlanSyncResult.Error(UNREACHABLE)

        return when (response.status) {
            HttpStatusCode.OK -> {
                val signed = runCatchingCancellable { response.body<RedeemedEntitlementResponse>() }
                    .getOrNull()?.toSigned()
                    ?: return PublisherClient.PlanSyncResult.Error(GENERIC)
                handOnIfNews(signed)
                PublisherClient.PlanSyncResult.Synced(signed.productId)
            }
            HttpStatusCode.Unauthorized -> {
                disconnect()
                PublisherClient.PlanSyncResult.SignedOut
            }
            // 403 = no live entitlement, or the account is closed.
            HttpStatusCode.Forbidden -> PublisherClient.PlanSyncResult.Unavailable
            else -> PublisherClient.PlanSyncResult.Error(GENERIC)
        }
    }

    /**
     * ⚠⚠ Every `/mint` answer is a NEW record: same plan, a `validUntil` a few seconds later. The
     * proxy saves a later `validUntil` as a renewal, so handing each one on would turn the checkout
     * screen's five-second poll into a stored-row write and a sync block per tick — up to 180 from
     * one abandoned checkout. Only a changed plan, or a window that has genuinely moved on, is news.
     */
    private suspend fun handOnIfNews(signed: EntitlementSignature.SignedEntitlement) {
        val last = lastHandedOn
        val news = last == null ||
            last.productId != signed.productId ||
            signed.validUntil - last.validUntil >= RENEWAL_STEP_MS
        if (!news) return
        lastHandedOn = signed
        _redeemed.emit(signed)
    }

    private var lastHandedOn: EntitlementSignature.SignedEntitlement? = null

    override suspend fun openBillingPortal(): PublisherClient.PortalResult {
        val token = currentSessionToken()
            ?: return PublisherClient.PortalResult.Unauthorized

        val response: HttpResponse = runCatchingCancellable {
            httpClient.post("${baseUrl.trimEnd('/')}$PORTAL_PATH") {
                contentType(ContentType.Application.Json)
                setBody(PortalRequest(sessionToken = token))
            }
        }.getOrNull() ?: return PublisherClient.PortalResult.Error(UNREACHABLE)

        return when (response.status) {
            HttpStatusCode.OK -> {
                val url = runCatchingCancellable { response.body<PortalResponse>() }.getOrNull()?.portalUrl
                    ?: return PublisherClient.PortalResult.Error(GENERIC)
                PublisherClient.PortalResult.Ready(url)
            }
            HttpStatusCode.Unauthorized -> PublisherClient.PortalResult.Unauthorized
            HttpStatusCode.Conflict -> PublisherClient.PortalResult.NoSubscription
            HttpStatusCode.ServiceUnavailable -> PublisherClient.PortalResult.Unavailable
            else -> PublisherClient.PortalResult.Error(GENERIC)
        }
    }

    override suspend fun requestAccountDeletion(): PublisherClient.AccountDeletionResult {
        val token = currentSessionToken() ?: return PublisherClient.AccountDeletionResult.SignInRequired
        val response = runCatchingCancellable {
            httpClient.post("${baseUrl.trimEnd('/')}$DELETE_REQUEST_PATH") {
                contentType(ContentType.Application.Json)
                setBody(DeletionRequestBody(sessionToken = token))
            }
        }.getOrNull() ?: return PublisherClient.AccountDeletionResult.Error(UNREACHABLE)
        return when (response.status) {
            HttpStatusCode.Accepted -> PublisherClient.AccountDeletionResult.CodeSent
            // The session is gone on commerce (expired, or the account was deleted): holding it on
            // would only fail every later call. Drop it, and say what most likely happened.
            HttpStatusCode.Unauthorized -> {
                disconnect()
                PublisherClient.AccountDeletionResult.SignedOut
            }
            HttpStatusCode.TooManyRequests -> PublisherClient.AccountDeletionResult.Error(RATE_LIMITED)
            HttpStatusCode.ServiceUnavailable -> PublisherClient.AccountDeletionResult.Error(SEND_FAILED)
            else -> PublisherClient.AccountDeletionResult.Error(GENERIC)
        }
    }

    override suspend fun confirmAccountDeletion(code: String): PublisherClient.AccountDeletionResult {
        val token = currentSessionToken() ?: return PublisherClient.AccountDeletionResult.SignInRequired
        val response = runCatchingCancellable {
            httpClient.post("${baseUrl.trimEnd('/')}$DELETE_CONFIRM_PATH") {
                contentType(ContentType.Application.Json)
                setBody(DeletionConfirmBody(sessionToken = token, code = code.trim()))
            }
        }.getOrNull() ?: return PublisherClient.AccountDeletionResult.Error(UNREACHABLE)
        return when (response.status) {
            HttpStatusCode.OK -> {
                // The account and every session of it are gone on commerce; holding the dead token
                // would only make the next screen fail. Same local effect as disconnecting.
                disconnect()
                PublisherClient.AccountDeletionResult.Deleted
            }
            // ⚠⚠ 401 is the uniform "wrong / expired / spent code" — but also what a RETRY gets
            // after a confirm whose response was lost: the account is gone, and so is the session.
            // Telling them apart takes one more question: is the session still alive?
            HttpStatusCode.Unauthorized ->
                if (sessionIsDead(token)) {
                    disconnect()
                    PublisherClient.AccountDeletionResult.SignedOut
                } else {
                    PublisherClient.AccountDeletionResult.InvalidCode
                }
            HttpStatusCode.TooManyRequests -> PublisherClient.AccountDeletionResult.Error(RATE_LIMITED_GUESSES)
            else -> PublisherClient.AccountDeletionResult.Error(GENERIC)
        }
    }

    /**
     * True only when commerce **says** the session is gone (401 on `/account/session`). ⚠ Anything
     * else — a network failure, a 5xx — is not evidence of a dead session, and dropping a live one
     * on it would sign the user out for nothing.
     */
    private suspend fun sessionIsDead(token: String): Boolean = runCatchingCancellable {
        httpClient.post("${baseUrl.trimEnd('/')}$SESSION_PATH") {
            contentType(ContentType.Application.Json)
            setBody(SessionProbeBody(sessionToken = token))
        }.status == HttpStatusCode.Unauthorized
    }.getOrDefault(false)

    override suspend fun disconnect() {
        storageDelegate.remove(STORAGE_KEY)
        sessionToken = null
        pendingEmail = null
        pendingDisplayName = null
        lastHandedOn = null
        _account.value = null
    }

    private fun accountFor(session: StoredSession): PublisherAccount = PublisherAccount(
        plan = PublisherAccount.Plan.Free,
        identityMethod = PublisherAccount.IdentityMethod.Email(session.email),
        displayName = null,
    )

    private fun persistSession(session: StoredSession) {
        storageDelegate.storeSecret(STORAGE_KEY, json.encodeToString(session))
    }

    private fun loadSession(): StoredSession? {
        val raw = storageDelegate.getSecret(STORAGE_KEY) ?: return null
        return runCatching { json.decodeFromString<StoredSession>(raw) }
            .getOrNull()
            .also { if (it == null) storageDelegate.remove(STORAGE_KEY) }
    }

    @Serializable
    private data class StoredSession(val email: String, val sessionToken: String)

    companion object {
        const val FREE_TIER_LIMIT_BYTES: Long = 5L * 1024L * 1024L * 1024L // 5 GiB

        /**
         * Acceptance versions recorded by `/register` (§5.1): the commerce store keeps them per
         * account for the audit trail. [TOS_VERSION] names the terms text, [PRIVACY_VERSION] the
         * privacy policy, each by its date.
         *
         * Terms history: `2026-08-04` added the **data-retention & account-closure** clause
         * (`docs/policy/acceptable-use-and-limits.md` §4) — the ⚖️ prerequisite for the
         * account-closure reclamation path (`retention-quota-model.md` Face A / D4); `2026-08-06`
         * added the cancellation & refund terms (policy §5): cancelling stops future charges and does
         * not refund the current period, and **closing an account forfeits the remainder** of a paid
         * period — which the closure surface has to say before the action; `2026-10-28` is the
         * first **published** terms, `TERMS_DATE` in `web/apex/support-pages/terms.ts`, at
         * `https://tenshu.app/legal/terms/<lang>`, linked from the sign-up screen. Rows carrying the
         * earlier dates name drafts nobody was shown. ⚠ Move it with `TERMS_DATE` (and
         * `web/apex/.../commerceApi.ts`), never on its own: `TermsVersionParityTest` and
         * `commerceApi.test.ts` pin both copies to it.
         *
         * ⚠ A row is written on **every** `/register` (i.e. every sign-in), not once at
         * registration; there is no re-acceptance or change-notice flow, so an account that has not
         * signed in since keeps the older versions in its newest row. Closure reclamation is live,
         * so a re-acceptance flow is owed before the first change to the terms that disadvantages
         * users (the terms' "changes" clause promises to ask).
         */
        const val TOS_VERSION: String = "2026-10-28"

        /**
         * The privacy policy the user is shown: `PRIVACY_POLICY_DATE` in
         * `web/apex/support-pages/privacy.ts`, published at `https://tenshu.app/legal/privacy/<lang>`.
         * ⚠ Move it with that date (and `web/apex/.../commerceApi.ts`, whose test pins it), never on
         * its own. `2026-10-23` is the first published policy. Rows carrying `2026-08-04` as their
         * privacy version name no readable text: that is the date of the terms' retention clause
         * above, never a published privacy policy.
         * `2026-10-28`: closure / erased-data-store record / pairing retention, and sealed
         * share-link names. `2026-11-04`: Stripe also for Android apps from the website, Cloudflare
         * also serves those downloads (android-direct-apk-distribution.md).
         * `2026-11-05`: iOS device backups — the data store is excluded (ios-shared-store-sqlite.md
         * D3/D3b), and what still is backed up is listed. ⚠ A build
         * released before a bump keeps sending the older date while its link opens the live page; the
         * superseded texts are in git history only.
         */
        const val PRIVACY_VERSION: String = "2026-11-05"

        /** SecureStorageDelegate key for the persisted commerce session record (email + token). */
        const val STORAGE_KEY: String = "commerce_session"

        private const val UNREACHABLE = "Couldn't reach the account service. Check your connection and try again."
        private const val GENERIC = "Something went wrong. Please try again."
        private const val NO_IDENTITY = "Set up this device before binding an account."

        /**
         * `429 rate_limited`. ⚠ Says *wait*, never *try again* — the budget is per hour
         * (`AccountRateLimits`), so retrying is precisely what will not work. Deliberately vague
         * about the number: the limits are a deployment knob, and quoting one here would go stale
         * the first time it moves.
         */
        private const val RATE_LIMITED =
            "Too many codes requested for this address. Wait an hour, then try again."

        /**
         * `503 send_failed` — the service accepted the request but its mail transport refused the
         * message. ⚠ Not the same as a mailbox that bounces later; nothing on this path can see
         * that (`commerce-otp-delivery-feedback.md`), so the copy promises only that *this attempt*
         * failed, not that the address is bad.
         */
        private const val SEND_FAILED =
            "We couldn't send the email just now. Try again in a moment."

        /** `429` on a confirmation: too many wrong codes for this address this hour. */
        private const val RATE_LIMITED_GUESSES =
            "Too many wrong codes for this address. Wait an hour, then try again."

        private const val SESSION_PATH = "/commerce/account/session"
        private const val DELETE_REQUEST_PATH = "/commerce/account/delete/request"
        private const val DELETE_CONFIRM_PATH = "/commerce/account/delete/confirm"
        private const val REGISTER_PATH = "/commerce/account/register"
        private const val VERIFY_PATH = "/commerce/account/verify"
        private const val REDEEM_PATH = "/commerce/entitlement/redeem"
        private const val CHECKOUT_PATH = "/commerce/checkout"
        private const val PORTAL_PATH = "/commerce/portal"
        private const val MINT_PATH = "/commerce/entitlement/mint"
        private const val BILLING_PATH = "/commerce/account/billing"
        private const val GOOGLE_VERIFY_PATH = "/commerce/iap/google/verify"

        /** How far a record's window must have advanced before an unchanged plan is saved again. */
        private const val RENEWAL_STEP_MS = 12L * 60 * 60 * 1000

        /** `/commerce/checkout`'s 409 body when the requested plan itself is already billing. */
        private const val ALREADY_SUBSCRIBED = "already_subscribed"

        /** …and when the realm pays under another account, which this session cannot manage. */
        private const val SUBSCRIBED_ELSEWHERE = "subscribed_under_another_account"

        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** Mirrors `:commerce-service`'s `DeletionRequest` (session variant; the web uses the email one). */
@Serializable
private data class DeletionRequestBody(val sessionToken: String)

/** Mirrors `:commerce-service`'s `SessionRequest`. */
@Serializable
private data class SessionProbeBody(val sessionToken: String)

/** Mirrors `:commerce-service`'s `DeletionConfirm` (session variant). */
@Serializable
private data class DeletionConfirmBody(val sessionToken: String, val code: String)

/** Register request — mirrors `:commerce-service`'s `RegisterRequest` JSON. */
@Serializable
private data class RegisterRequest(
    val email: String,
    val tosVersion: String,
    val privacyVersion: String,
)

/** Verify request — mirrors `:commerce-service`'s `VerifyRequest` JSON. */
@Serializable
private data class VerifyRequest(
    val email: String,
    val code: String,
    val ownerId: String,
)

/** Verify response — mirrors `:commerce-service`'s `VerifyResponse` JSON. */
@Serializable
private data class VerifyResponse(
    val sessionToken: String,
)

/** Redeem request — mirrors `:commerce-service`'s `RedeemRequest` JSON. */
@Serializable
private data class RedeemRequest(
    val code: String,
    val ownerId: String,
    /**
     * ⚠ Sent whenever this device holds one. Without it the redeemed tier is invisible to the money
     * path: `/token` mints from the ACCOUNT's ledger, the relay treats that token as final, and the
     * user is shown — and metered at — their old tier while holding a valid signed record for the
     * new one. Absent is still legal and keeps the identity-light path (a code with no account).
     */
    val sessionToken: String? = null,
)

/**
 * Redeem response — mirrors `:commerce-service`'s `MintedEntitlement` JSON (the same shape `/mint`
 * returns; `publisherSignature` is base64).
 */
@Serializable
private data class RedeemedEntitlementResponse(
    val ownerId: String,
    val publisherId: String,
    val productId: String,
    val validFrom: Long,
    val validUntil: Long,
    val publisherSignature: String,
) {
    /** Null when the signature is not base64 — a malformed grant is not saved. */
    fun toSigned(): EntitlementSignature.SignedEntitlement? {
        val signature = runCatching { Base64.Default.decode(publisherSignature) }.getOrNull() ?: return null
        return EntitlementSignature.SignedEntitlement(
            ownerId = ownerId,
            publisherId = publisherId,
            productId = productId,
            validFrom = validFrom,
            validUntil = validUntil,
            signature = signature,
        )
    }
}

/** Mint request — mirrors `:commerce-service`'s session-gated `PlanMintRequest` JSON. */
@Serializable
private data class PlanMintRequest(val sessionToken: String)

@Serializable
private data class BillingRequest(val sessionToken: String, val rail: String)

@Serializable
private data class BillingResponse(
    val accountId: String,
    val productId: String? = null,
    val billedBy: String? = null,
    val validUntil: Long? = null,
    val offers: List<String> = emptyList(),
    val upgradeOn: String? = null,
    val refusal: String? = null,
)


@Serializable
private data class StoreVerifyRequest(val sessionToken: String, val purchaseToken: String)

@Serializable
private data class StoreVerifyResponse(val productId: String, val validUntil: Long)

/** The service's uniform, content-free error body. */
@Serializable
private data class CommerceErrorResponse(val error: String? = null)

/** Checkout request — mirrors `:commerce-service`'s `CheckoutRequest` JSON. */
@Serializable
private data class CheckoutRequest(
    val ownerId: String,
    val productId: String,
    val withdrawalAcknowledged: Boolean,
    val tosVersion: String,
    /** Pins the email Stripe charges to this account, and lets the service see what it already bills. */
    val sessionToken: String,
)

/** Checkout response — mirrors `:commerce-service`'s `CheckoutResponse` JSON. */
@Serializable
private data class CheckoutResponse(
    val checkoutUrl: String,
)

/** Portal request — mirrors `:commerce-service`'s `PortalRequest` JSON. */
@Serializable
private data class PortalRequest(
    val sessionToken: String,
)

/** Portal response — mirrors `:commerce-service`'s `PortalResponse` JSON. */
@Serializable
private data class PortalResponse(
    val portalUrl: String,
)
