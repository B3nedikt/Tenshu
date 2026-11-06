package org.tenshu.commerce

import org.tenshu.commerce.store.CommerceStore
import org.tenshu.identity.EntitlementSignature
import java.util.Base64

/**
 * Server-side config gate for the commerce-plane entitlement issuer. [fromEnv] parses it
 * from the process environment, falling back to local-dev defaults so an unconfigured
 * `./gradlew :commerce-service:run` is unchanged. Mirrors `RelayConfig` in `:sync-relay-server`
 * (`docs/proposals/commerce-service-phase1-plan.md` §2.1).
 *
 * This module is a **separate trust boundary** from the relay: it owns the issuer's private
 * Ed25519 signing key (A29) and the durable account store (A28, the real PII). Neither ever
 * leaves this process — the relay never learns "paid", only the signed entitlement does (House
 * Rule #1; the commerce↔relay bridge is an entitlement token, not shared state).
 */
data class CommerceConfig(
    val devMode: Boolean,
    val bindHost: String,
    val port: Int,
    /** The commerce `publisherId` stamped onto minted entitlements (e.g. `tenshu.commerce`). */
    val issuerId: String,
    /**
     * 32-byte Ed25519 seed — the issuer's **private** signing key (A29). Required when
     * `!devMode` (no committed production seed; supplied via `ISSUER_SEED` as base64). In dev
     * mode this falls back to a committed dev seed whose public half is pinned in
     * [EntitlementSignature.COMMERCE_PUBLIC_KEY] — insecure by design, like the relay's
     * `X-Dev-Owner` bypass.
     */
    val issuerSeed: ByteArray,
    /** Product id minted for the top tier (e.g. `tenshu.drive.premium`). */
    val topTierProductId: String,
    /** Validity window per mint, in hours. */
    val entitlementTtlHours: Long,
    /**
     * Validity window for the bearer **entitlement token** (`commerce-billing-plane.md` §4):
     * short-lived (24–72 h) so revocation is mostly "stop reissuing" — no relay-side revocation
     * list on the hot path. Distinct from [entitlementTtlHours] (the synced-record window).
     */
    val tokenTtlHours: Long,
    /** Storage quota (bytes) the top tier's token asserts. */
    val topTierQuotaBytes: Long,
    /**
     * Issuer signing-key id stamped into the token (`kid`), for A29 rotation: the relay holds
     * current + previous public keys keyed by this. `null` = the issuer's default/unkeyed key.
     */
    val signingKeyId: String?,
    /**
     * JDBC URL of the durable Postgres account store (`commerce-accounts-and-sessions.md` §4).
     * Null → the in-memory store (the default; an unconfigured `run` is unchanged). Parsed from
     * `DATABASE_URL`. Mirrors `RelayConfig.databaseUrl`.
     */
    val databaseUrl: String? = null,
    /** Email-OTP validity window (minutes). */
    val otpTtlMinutes: Long = DEFAULT_OTP_TTL_MINUTES,
    /** Max OTP verification attempts before the code is burned. */
    val otpMaxAttempts: Int = DEFAULT_OTP_MAX_ATTEMPTS,
    /** Commerce-session validity window (hours). */
    val sessionTtlHours: Long = DEFAULT_SESSION_TTL_HOURS,
    /**
     * Redemption-code seeds imported into the store at startup (§4.3 — the whitelist's successor as
     * a founder/privacy grant seed). Each `code:productId:maxUses[:expiresAtMs]`; hashed before
     * storage so the raw code never persists in cleartext.
     */
    val redemptionSeeds: List<RedemptionSeed> = emptyList(),
    /**
     * Bind host for the internal admin console (§4.5). Defaults to loopback — the admin surface is
     * **off the public internet**; production fronts it with a Caddy allowlist/VPN/mTLS.
     */
    val adminBindHost: String = DEFAULT_ADMIN_BIND_HOST,
    /** Admin console port — **distinct from the public API port** so it can be firewalled apart. */
    val adminPort: Int = DEFAULT_ADMIN_PORT,
    /**
     * Bearer credential the admin console requires (`ADMIN_TOKEN`), separate from any user/commerce
     * session and from the issuer key (A29). **Null/blank → the admin console is not started**
     * (locked by default); a deployment opts in by setting it. The second factor is
     * [adminTotpSecret], which [validate] makes mandatory outside dev mode.
     */
    val adminToken: String? = null,
    /**
     * Base32 TOTP seed for the admin console's **second factor** (`ADMIN_TOTP_SECRET`, WC-5 / §5.3),
     * as an authenticator app would show it. Null/blank → only the bearer token applies, which
     * [validate] permits **only when [devMode]**.
     *
     * ⚠⚠ §5.3 calls MFA here "non-negotiable before it holds real users", and WC-4 raised the stake
     * by adding an export to the one surface that joins A28 ⨯ A30 in the clear. A checklist item
     * would be forgotten; a startup refusal cannot be.
     */
    val adminTotpSecret: String? = null,
    /**
     * Stripe **secret** API key (`STRIPE_SECRET_KEY`). Null → the Stripe rail is off (checkout +
     * webhook routes answer `503`); a deployment opts into paid storage by setting it. A commerce
     * secret, never exposed to clients or the relay.
     */
    val stripeSecretKey: String? = null,
    /**
     * Stripe **webhook signing** secret (`STRIPE_WEBHOOK_SECRET`). Verifying the `Stripe-Signature`
     * with it is load-bearing (§5.3, T-CB-07) — an unverified webhook is a mint-forgery vector. Must
     * be set whenever [stripeSecretKey] is (enforced by [validate]).
     */
    val stripeWebhookSecret: String? = null,
    /**
     * Our-productId → Stripe **price id** map (`STRIPE_PRICE_IDS=tenshu.drive.basic=price_x,…`). The
     * checkout route maps the requested tier to the Stripe price; a tier absent here can't be bought.
     */
    val stripePriceIds: Map<String, String> = emptyMap(),
    /** Deep-link Stripe redirects to on success (`CHECKOUT_SUCCESS_URL`) — back into Control. */
    val checkoutSuccessUrl: String = DEFAULT_CHECKOUT_SUCCESS_URL,
    /** Deep-link Stripe redirects to on cancel (`CHECKOUT_CANCEL_URL`). */
    val checkoutCancelUrl: String = DEFAULT_CHECKOUT_CANCEL_URL,
    /** Deep-link the Stripe billing portal returns to (`PORTAL_RETURN_URL`). */
    val portalReturnUrl: String = DEFAULT_PORTAL_RETURN_URL,
    /** Stripe REST base (`STRIPE_API_BASE`) — injectable so a test can point it at a stub. */
    val stripeApiBase: String = DEFAULT_STRIPE_API_BASE,
    /**
     * Origin of the web app allowed to call this service from a browser (`WEB_APP_ORIGIN`, e.g.
     * `https://tenshu.app`). **Null → no browser may call it at all**, which is the posture this
     * service shipped with and stays the default.
     *
     * Two things hang off this one value, deliberately:
     * - the CORS allow-list (exactly this origin; never a wildcard), and
     * - the post-checkout redirect targets for `client=web`, derived as
     *   `<webAppOrigin>/control/services/checkout-result?status=…`.
     *
     * ⚠ One field rather than a `WEB_*_URL` trio because they cannot legitimately disagree: a
     * deployment that lets an origin call it must send that same origin its customers back. Three
     * fields would let an operator point returns at a host that cannot even reach the API.
     *
     * ⚠⚠ Redirect targets are DERIVED here, never taken from the request. A caller-supplied
     * `returnUrl` on an unauthenticated route is an open redirect wearing a checkout's clothes —
     * and this one would carry a just-paid customer.
     */
    val webAppOrigin: String? = null,
    /**
     * Absolute URL of the relay's `POST /sync/account/closure` endpoint
     * (`ACCOUNT_CLOSURE_RELAY_URL`). **Null → closure records are never delivered** — the sweep is
     * not started at all (`retention-quota-model.md` Face A / D4).
     *
     * ⚠ Deliberately a second, independent opt-in: the relay has its own `ACCOUNT_CLOSURE_RECLAIM`
     * flag, and content is only ever deleted when **both** ends are switched on. Either side alone
     * deletes nothing, so the decision is made at deploy time rather than by code review. Both are
     * ON in production since 2026-10-24 (maintainer decision; ⚖️ counsel review of the retention
     * text owed).
     */
    val accountClosureRelayUrl: String? = null,
    /** How often the closure sweep runs (`ACCOUNT_CLOSURE_SWEEP_MINUTES`). */
    val accountClosureSweepMinutes: Long = DEFAULT_ACCOUNT_CLOSURE_SWEEP_MINUTES,
    /**
     * Outbound SMTP transport for the OTP email (`commerce-end-to-end-dry-run.md` CE-2 / D3). Null
     * → the dev [LoggingEmailSender], which prints the code to the server log; [validate] refuses
     * that combination when `!devMode`.
     */
    val smtp: SmtpConfig? = null,
    /**
     * Per-IP hourly budget on the route that sends mail (`/register`).
     * `<= 0` disables (the relay's `GENESIS_RATE_LIMIT_PER_HOUR` convention).
     */
    val otpSendPerIpPerHour: Int = DEFAULT_OTP_SEND_PER_IP_PER_HOUR,
    /** Per-**email** hourly budget on the same two routes — the anti-mail-bombing half. */
    val otpSendPerEmailPerHour: Int = DEFAULT_OTP_SEND_PER_EMAIL_PER_HOUR,
    /** Per-email hourly budget on `/verify`, above the per-code [otpMaxAttempts] burn. */
    val otpVerifyPerEmailPerHour: Int = DEFAULT_OTP_VERIFY_PER_EMAIL_PER_HOUR,
    /**
     * Cost brake: verified new accounts per rolling 24 h (`SIGNUP_LIMIT_PER_DAY`); past it a new
     * address is mailed "sign-ups are paused" instead of a code. `<= 0` (the default) = no cap.
     * See [SignupCap] and `docs/how-to/relay-storage-ceiling.md`.
     */
    val signupLimitPerDay: Int = 0,
    /** Same, for verified open accounts in total (`SIGNUP_LIMIT_TOTAL`). `<= 0` = no cap. */
    val signupLimitTotal: Int = 0,
    /**
     * `STRIPE_MODE=fake` selects the **dev** payment rail (`commerce-end-to-end-dry-run.md` CE-3):
     * a commerce-hosted confirm page that posts a genuinely-signed event into the real webhook, so
     * the purchase flow is walkable with no Stripe account. [validate] refuses it when `!devMode`.
     */
    val stripeFakeRail: Boolean = false,
    /**
     * Absolute origin the fake rail's confirm page is reachable at (`PUBLIC_BASE_URL`), and the
     * origin the service posts its own fake webhook to. Only read in fake-rail mode.
     */
    val publicBaseUrl: String = DEFAULT_PUBLIC_BASE_URL,
    /**
     * How often the subscription-cancellation retry sweep runs
     * (`SUBSCRIPTION_CANCEL_SWEEP_MINUTES`). Only started when the Stripe rail is on. `<= 0`
     * disables the sweep — closing still cancels inline, it just stops being retried.
     */
    val subscriptionCancelSweepMinutes: Long = DEFAULT_SUBSCRIPTION_CANCEL_SWEEP_MINUTES,
    /**
     * How far back the sweep looks (`SUBSCRIPTION_CANCEL_WINDOW_MS`). Bounds the retry set so it
     * cannot grow forever as closed accounts accumulate; an account still failing after this long
     * is an operational problem no retry loop will fix.
     */
    val subscriptionCancelWindowMs: Long = DEFAULT_SUBSCRIPTION_CANCEL_WINDOW_MS,
    /**
     * How often the unverified account expiration sweep runs (`UNVERIFIED_ACCOUNT_EXPIRE_SWEEP_MINUTES`).
     * `<= 0` disables the sweep.
     */
    val unverifiedAccountExpireSweepMinutes: Long = DEFAULT_UNVERIFIED_ACCOUNT_EXPIRE_SWEEP_MINUTES,
    /** The Google Play rail (`commerce-store-link-out.md` P2); null = off. */
    val googlePlay: GooglePlayConfig? = null,
    /**
     * Store-review sign-in (`commerce-accounts-and-sessions.md` D13, T-CB-12): `REVIEW_SIGNIN_ACCOUNTS`
     * as parsed — one `(address, hex HMAC-SHA256 of that account's 6-digit code)` per entry, the
     * address trimmed and lower-cased. ⚠⚠ The addresses are **unpublished** and live only here and in
     * the stores' review forms. Empty, with [reviewSignInHmacKey] null = off. [validate] refuses one
     * without the other and every malformed entry.
     */
    val reviewSignInAccounts: List<Pair<String, String>> = emptyList(),
    /**
     * HMAC key for the digests (`REVIEW_SIGNIN_HMAC_KEY`, ≥ 32 characters). ⚠⚠ Why a key: a plain
     * SHA-256 of a 6-digit code is reversed by trying all 10⁶, so a leaked digest alone would be a
     * leaked code. A secret like the issuer seed; never log it.
     */
    val reviewSignInHmacKey: String? = null,
    /**
     * Per-address cap on **failed** one-time-code checks in a rolling 24 hours, across `/verify` and
     * `/delete/confirm` and across every code issued (`OTP_FAILED_PER_EMAIL_PER_DAY`, D15, T-CB-13).
     * Persisted. `<= 0` disables.
     */
    val otpFailedPerEmailPerDay: Int = DEFAULT_OTP_FAILED_PER_EMAIL_PER_DAY,
) {

    /** A parsed `REDEMPTION_CODES` entry: the raw code + what it grants. */
    data class RedemptionSeed(
        val code: String,
        val productId: String,
        val maxUses: Int,
        val expiresAtMs: Long?,
    ) {
        /** Redacted: a seed's code grants storage. */
        override fun toString(): String = "RedemptionSeed(productId=$productId, maxUses=$maxUses, expiresAtMs=$expiresAtMs)"
    }

    /**
     * Validates the config or throws [InvalidCommerceConfigException]. Call from the server
     * bootstrap before `embeddedServer` starts.
     */
    fun validate() {
        // A29 custody: no committed production seed. A non-dev deployment must supply its own.
        if (!devMode && issuerSeed.contentEquals(DEV_ISSUER_SEED)) {
            throw InvalidCommerceConfigException(
                "Refusing to start: ISSUER_SEED is required when DEV_MODE=false " +
                    "(the committed dev seed is insecure by design)."
            )
        }
        if (issuerSeed.size != ED25519_SEED_SIZE) {
            throw InvalidCommerceConfigException(
                "ISSUER_SEED must decode to exactly $ED25519_SEED_SIZE bytes, got ${issuerSeed.size}."
            )
        }
        // The service is a *real* issuer; `tenshu.dev` is the in-process dev mock's id and must
        // never be impersonated here (mirrors the §10.3 "release rejects the dev key" guard).
        if (issuerId == EntitlementSignature.DEV_PUBLISHER_ID) {
            throw InvalidCommerceConfigException(
                "Refusing to start: ISSUER_ID must not be '${EntitlementSignature.DEV_PUBLISHER_ID}' " +
                    "— that id belongs to the in-process dev mock, not a real issuer."
            )
        }
        // A Stripe secret without a webhook secret means unverified webhooks — a mint-forgery vector
        // (§5.3, T-CB-07). Refuse the half-configured state whether or not devMode.
        if (!stripeSecretKey.isNullOrBlank() && stripeWebhookSecret.isNullOrBlank()) {
            throw InvalidCommerceConfigException(
                "STRIPE_WEBHOOK_SECRET is required when STRIPE_SECRET_KEY is set " +
                    "(an unverified Stripe webhook is a mint-forgery vector)."
            )
        }
        // The dev EmailSender prints the OTP — the account model's ONLY factor — to the server log.
        // Shipping it is the same class of mistake as shipping the committed dev signing seed, so it
        // gets the same treatment: a non-dev deployment must supply a real transport (D3).
        // The fake rail mints entitlements from a page anyone can click. It is a dev instrument,
        // and the config gate is the only thing standing between it and a real deployment.
        // Play's notifications are the only way a refund or a lapse reaches us; an endpoint nobody
        // can authenticate to would have to accept anyone, so refuse the half-configured rail.
        if (googlePlay != null && googlePlay.rtdnToken.isNullOrBlank()) {
            throw InvalidCommerceConfigException(
                "GOOGLE_PLAY_RTDN_TOKEN is required when GOOGLE_PLAY_SERVICE_ACCOUNT_JSON is set.",
            )
        }
        if (!devMode && stripeFakeRail) {
            throw InvalidCommerceConfigException(
                "Refusing to start: STRIPE_MODE=fake is a dev-only payment rail and is illegal " +
                    "when DEV_MODE=false (it grants paid tiers from an unauthenticated page)."
            )
        }
        if (!devMode && smtp == null) {
            throw InvalidCommerceConfigException(
                "Refusing to start: SMTP_HOST + SMTP_FROM are required when DEV_MODE=false " +
                    "(the dev EmailSender prints the verification code to the server log)."
            )
        }
        // ⚠⚠ A single-factor admin console is a production configuration we refuse to hold. This is
        // the one surface joining A28 (real PII) ⨯ A30 (ownerId) in the clear, and since WC-4 it can
        // also export — so a leaked bearer token is a full de-anonymisation. §5.3 called MFA
        // non-negotiable; enforcing it at startup is what makes that true of the deployment rather
        // than of the document.
        if (!devMode && adminToken != null && adminTotpSecret.isNullOrBlank()) {
            throw InvalidCommerceConfigException(
                "Refusing to start: ADMIN_TOTP_SECRET is required alongside ADMIN_TOKEN when " +
                    "DEV_MODE=false (a single-factor admin console exports A28 ⨯ A30 on one stolen token)."
            )
        }
        validateReviewSignIn()
        // ⚠⚠ The CORS install hands this to Ktor's `allowHost`, which treats a host of `*` as
        // `anyHost()`. So `*` or `https://*` would open the unauthenticated /register to every page
        // on the internet (T-CB-09). A value with no scheme would instead never match. Both are
        // refused here, because deploy.env is edited by hand and bypasses the Terraform check.
        if (webAppOrigin != null) {
            val shape = if (devMode) WEB_APP_ORIGIN_DEV else WEB_APP_ORIGIN_PRODUCTION
            if (!shape.matches(webAppOrigin)) {
                throw InvalidCommerceConfigException(
                    "Refusing to start: WEB_APP_ORIGIN must be one bare lower-case origin " +
                        "(${if (devMode) "http or https" else "https"}, optional port, no path, " +
                        "no wildcard), e.g. https://tenshu.app."
                )
            }
        }
        if (adminTotpSecret != null && adminTotpSecret.isNotBlank() && AdminTotp.decodeBase32(adminTotpSecret) == null) {
            // ⚠ Fail at startup, not at the first login attempt: an unparseable seed would otherwise
            // present as "MFA configured" while rejecting every code, i.e. a locked-out console.
            throw InvalidCommerceConfigException(
                "Refusing to start: ADMIN_TOTP_SECRET is not valid base32."
            )
        }
    }

    /**
     * ⚠⚠ The review sign-in is a static credential on the production auth path, so every
     * half-configured or malformed state refuses to start rather than running in some guessed mode
     * (T-CB-12). ⚠ The messages name the variable, never its value.
     */
    private fun validateReviewSignIn() {
        val hasAccounts = reviewSignInAccounts.isNotEmpty()
        val hasKey = !reviewSignInHmacKey.isNullOrBlank()
        if (!hasAccounts && !hasKey) return
        if (hasAccounts != hasKey) {
            throw InvalidCommerceConfigException(
                "Refusing to start: REVIEW_SIGNIN_ACCOUNTS and REVIEW_SIGNIN_HMAC_KEY must be set together " +
                    "(missing: ${if (hasAccounts) "REVIEW_SIGNIN_HMAC_KEY" else "REVIEW_SIGNIN_ACCOUNTS"}).",
            )
        }
        reviewSignInAccounts.forEachIndexed { index, (address, digest) ->
            // ⚠ Entries are named by position, never by address or digest.
            val entry = "REVIEW_SIGNIN_ACCOUNTS entry ${index + 1}"
            if (address.isEmpty() || digest.isEmpty()) {
                throw InvalidCommerceConfigException("Refusing to start: $entry must be `<address>=<hex HMAC>`.")
            }
            if (org.tenshu.commerce.routing.normaliseEmail(address) != address) {
                throw InvalidCommerceConfigException(
                    "Refusing to start: $entry must name one plain, exact address (no wildcards, domains or display names).",
                )
            }
            if (!ReviewSignIn.isSha256Hex(digest)) {
                throw InvalidCommerceConfigException(
                    "Refusing to start: $entry's digest must be 64 hex characters (the HMAC of the code, not the code).",
                )
            }
        }
        if (reviewSignInAccounts.map { it.first }.toSet().size != reviewSignInAccounts.size) {
            throw InvalidCommerceConfigException("Refusing to start: REVIEW_SIGNIN_ACCOUNTS names an address twice.")
        }
        if (reviewSignInAccounts.map { it.second }.toSet().size != reviewSignInAccounts.size) {
            throw InvalidCommerceConfigException(
                "Refusing to start: REVIEW_SIGNIN_ACCOUNTS gives two accounts the same code — each store gets its own.",
            )
        }
        if (reviewSignInHmacKey!!.length < ReviewSignIn.MIN_KEY_LENGTH) {
            throw InvalidCommerceConfigException(
                "Refusing to start: REVIEW_SIGNIN_HMAC_KEY must be at least ${ReviewSignIn.MIN_KEY_LENGTH} characters " +
                    "(e.g. `openssl rand -hex 32`).",
            )
        }
    }

    /**
     * The configured review sign-in over [store] (which persists its lockout), or null when it is
     * off. Call after [validate].
     */
    fun reviewSignIn(store: CommerceStore): ReviewSignIn? =
        if (reviewSignInAccounts.isEmpty() || reviewSignInHmacKey.isNullOrBlank()) {
            null
        } else {
            ReviewSignIn(reviewSignInAccounts.toMap(), reviewSignInHmacKey, store)
        }

    /**
     * ⚠⚠ Redacting. A data class prints every field, and this one holds the issuer seed, the database
     * URL (with its password), the admin token + TOTP seed, the Stripe and SMTP secrets, the Play key,
     * redemption codes and the review sign-in — so any `println(config)` would publish them all.
     * Secrets print as `set` / `unset`; nothing else here is secret.
     */
    override fun toString(): String {
        fun secret(value: Any?): String = when (value) {
            null -> "unset"
            is String -> if (value.isBlank()) "unset" else "set"
            is Collection<*> -> if (value.isEmpty()) "unset" else "set(${value.size})"
            else -> "set"
        }
        return "CommerceConfig(devMode=$devMode, bindHost=$bindHost, port=$port, issuerId=$issuerId, " +
            "issuerSeed=${if (issuerSeed.contentEquals(DEV_ISSUER_SEED)) "dev" else "set"}, " +
            "topTierProductId=$topTierProductId, entitlementTtlHours=$entitlementTtlHours, tokenTtlHours=$tokenTtlHours, " +
            "topTierQuotaBytes=$topTierQuotaBytes, signingKeyId=$signingKeyId, databaseUrl=${secret(databaseUrl)}, " +
            "otpTtlMinutes=$otpTtlMinutes, otpMaxAttempts=$otpMaxAttempts, sessionTtlHours=$sessionTtlHours, " +
            "redemptionSeeds=${secret(redemptionSeeds)}, adminBindHost=$adminBindHost, adminPort=$adminPort, " +
            "adminToken=${secret(adminToken)}, adminTotpSecret=${secret(adminTotpSecret)}, " +
            "stripeSecretKey=${secret(stripeSecretKey)}, stripeWebhookSecret=${secret(stripeWebhookSecret)}, " +
            "stripePriceIds=$stripePriceIds, checkoutSuccessUrl=$checkoutSuccessUrl, checkoutCancelUrl=$checkoutCancelUrl, " +
            "portalReturnUrl=$portalReturnUrl, stripeApiBase=$stripeApiBase, webAppOrigin=$webAppOrigin, " +
            "accountClosureRelayUrl=$accountClosureRelayUrl, accountClosureSweepMinutes=$accountClosureSweepMinutes, " +
            "smtp=$smtp, otpSendPerIpPerHour=$otpSendPerIpPerHour, otpSendPerEmailPerHour=$otpSendPerEmailPerHour, " +
            "otpVerifyPerEmailPerHour=$otpVerifyPerEmailPerHour, otpFailedPerEmailPerDay=$otpFailedPerEmailPerDay, " +
            "stripeFakeRail=$stripeFakeRail, publicBaseUrl=$publicBaseUrl, " +
            "subscriptionCancelSweepMinutes=$subscriptionCancelSweepMinutes, subscriptionCancelWindowMs=$subscriptionCancelWindowMs, " +
            "unverifiedAccountExpireSweepMinutes=$unverifiedAccountExpireSweepMinutes, " +
            "googlePlay=$googlePlay, reviewSignInAccounts=${secret(reviewSignInAccounts)}, " +
            "reviewSignInHmacKey=${secret(reviewSignInHmacKey)})"
    }

    /**
     * Whether a payment rail is available at all: real Stripe (secret + webhook secret) **or** the
     * dev fake. When false the checkout / portal / webhook routes answer 503 and only the
     * free/redeem paths work.
     */
    val stripeEnabled: Boolean
        get() = stripeFakeRail || (!stripeSecretKey.isNullOrBlank() && !stripeWebhookSecret.isNullOrBlank())

    // Equals/hashCode over the ByteArray seed for completeness (data class would compare by
    // reference otherwise). Config is rarely compared, but the tests assert on it.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommerceConfig) return false
        return devMode == other.devMode &&
            bindHost == other.bindHost &&
            port == other.port &&
            issuerId == other.issuerId &&
            issuerSeed.contentEquals(other.issuerSeed) &&
            topTierProductId == other.topTierProductId &&
            entitlementTtlHours == other.entitlementTtlHours &&
            tokenTtlHours == other.tokenTtlHours &&
            topTierQuotaBytes == other.topTierQuotaBytes &&
            signingKeyId == other.signingKeyId &&
            databaseUrl == other.databaseUrl &&
            otpTtlMinutes == other.otpTtlMinutes &&
            otpMaxAttempts == other.otpMaxAttempts &&
            sessionTtlHours == other.sessionTtlHours &&
            redemptionSeeds == other.redemptionSeeds &&
            adminBindHost == other.adminBindHost &&
            adminPort == other.adminPort &&
            adminToken == other.adminToken &&
            adminTotpSecret == other.adminTotpSecret &&
            stripeSecretKey == other.stripeSecretKey &&
            stripeWebhookSecret == other.stripeWebhookSecret &&
            stripePriceIds == other.stripePriceIds &&
            checkoutSuccessUrl == other.checkoutSuccessUrl &&
            checkoutCancelUrl == other.checkoutCancelUrl &&
            portalReturnUrl == other.portalReturnUrl &&
            stripeApiBase == other.stripeApiBase &&
            webAppOrigin == other.webAppOrigin &&
            accountClosureRelayUrl == other.accountClosureRelayUrl &&
            accountClosureSweepMinutes == other.accountClosureSweepMinutes &&
            smtp == other.smtp &&
            otpSendPerIpPerHour == other.otpSendPerIpPerHour &&
            otpSendPerEmailPerHour == other.otpSendPerEmailPerHour &&
            otpVerifyPerEmailPerHour == other.otpVerifyPerEmailPerHour &&
            stripeFakeRail == other.stripeFakeRail &&
            googlePlay == other.googlePlay &&
            publicBaseUrl == other.publicBaseUrl &&
            subscriptionCancelSweepMinutes == other.subscriptionCancelSweepMinutes &&
            subscriptionCancelWindowMs == other.subscriptionCancelWindowMs &&
            unverifiedAccountExpireSweepMinutes == other.unverifiedAccountExpireSweepMinutes &&
            reviewSignInAccounts == other.reviewSignInAccounts &&
            reviewSignInHmacKey == other.reviewSignInHmacKey &&
            otpFailedPerEmailPerDay == other.otpFailedPerEmailPerDay
    }

    override fun hashCode(): Int {
        var result = devMode.hashCode()
        result = 31 * result + bindHost.hashCode()
        result = 31 * result + port
        result = 31 * result + issuerId.hashCode()
        result = 31 * result + issuerSeed.contentHashCode()
        result = 31 * result + topTierProductId.hashCode()
        result = 31 * result + entitlementTtlHours.hashCode()
        result = 31 * result + tokenTtlHours.hashCode()
        result = 31 * result + topTierQuotaBytes.hashCode()
        result = 31 * result + (signingKeyId?.hashCode() ?: 0)
        result = 31 * result + (databaseUrl?.hashCode() ?: 0)
        result = 31 * result + otpTtlMinutes.hashCode()
        result = 31 * result + otpMaxAttempts
        result = 31 * result + sessionTtlHours.hashCode()
        result = 31 * result + redemptionSeeds.hashCode()
        result = 31 * result + adminBindHost.hashCode()
        result = 31 * result + adminPort
        result = 31 * result + (adminToken?.hashCode() ?: 0)
        result = 31 * result + (stripeSecretKey?.hashCode() ?: 0)
        result = 31 * result + (stripeWebhookSecret?.hashCode() ?: 0)
        result = 31 * result + stripePriceIds.hashCode()
        result = 31 * result + checkoutSuccessUrl.hashCode()
        result = 31 * result + checkoutCancelUrl.hashCode()
        result = 31 * result + portalReturnUrl.hashCode()
        result = 31 * result + stripeApiBase.hashCode()
        result = 31 * result + (webAppOrigin?.hashCode() ?: 0)
        result = 31 * result + (accountClosureRelayUrl?.hashCode() ?: 0)
        result = 31 * result + accountClosureSweepMinutes.hashCode()
        result = 31 * result + (smtp?.hashCode() ?: 0)
        result = 31 * result + otpSendPerIpPerHour
        result = 31 * result + otpSendPerEmailPerHour
        result = 31 * result + otpVerifyPerEmailPerHour
        result = 31 * result + stripeFakeRail.hashCode()
        result = 31 * result + (googlePlay?.hashCode() ?: 0)
        result = 31 * result + publicBaseUrl.hashCode()
        result = 31 * result + subscriptionCancelSweepMinutes.hashCode()
        result = 31 * result + subscriptionCancelWindowMs.hashCode()
        result = 31 * result + unverifiedAccountExpireSweepMinutes.hashCode()
        result = 31 * result + reviewSignInAccounts.hashCode()
        result = 31 * result + (reviewSignInHmacKey?.hashCode() ?: 0)
        result = 31 * result + otpFailedPerEmailPerDay
        return result
    }

    companion object {
        const val ED25519_SEED_SIZE = 32

        /** Default commerce `publisherId` (A real issuer id, distinct from the dev mock). */
        const val DEFAULT_ISSUER_ID = "tenshu.commerce"

        /** Default top-tier product id. */
        const val DEFAULT_TOP_TIER_PRODUCT_ID = "tenshu.drive.premium"

        /**
         * Default synced-record validity window per mint — 7 days. Short enough to bound the
         * forced-revocation window (T-CB-08) now that the client renews records before expiry
         * (the `initEntitlementWriter` renewal tick re-mints a good-standing record past the
         * halfway point), long enough that the session-holding device can be offline up to a week
         * before a peer's storage lapses. Tunable via `ENTITLEMENT_TTL_HOURS`.
         *
         * ⚠ This is a **fallback**, not the normal path for a Stripe subscriber: the paid window
         * comes from the invoice's billing period (`resolvePaidUntil`, WC-1b). It previously said
         * "Stripe subscribers use `currentPeriodEnd` regardless", which was an assumption rather
         * than behaviour — `current_period_end` is a *Subscription* field and appears on neither
         * the Checkout Session nor the Invoice, so every purchase silently landed on this 7-day
         * value while being billed monthly.
         */
        const val DEFAULT_ENTITLEMENT_TTL_HOURS = 168L

        /** Default bearer-token validity — 72 h (the upper end of the §4 24–72 h range). */
        const val DEFAULT_TOKEN_TTL_HOURS = 72L

        /** Default email-OTP validity — 15 minutes. */
        const val DEFAULT_OTP_TTL_MINUTES = 15L

        /** Default max OTP attempts before the code is burned. */
        const val DEFAULT_OTP_MAX_ATTEMPTS = 5

        /** Default commerce-session validity — 30 days. */
        const val DEFAULT_SESSION_TTL_HOURS = 720L

        /** Default admin-console bind host — loopback (off the public internet, §4.5). */
        const val DEFAULT_ADMIN_BIND_HOST = "127.0.0.1"

        /** A `WEB_APP_ORIGIN` outside dev mode: one https origin, optional port, nothing else. */
        internal val WEB_APP_ORIGIN_PRODUCTION = Regex("""https://[a-z0-9.-]+(:[0-9]{1,5})?""")

        /** In dev mode `http://localhost:5173` (the Vite dev server) must pass too. */
        internal val WEB_APP_ORIGIN_DEV = Regex("""https?://[a-z0-9.-]+(:[0-9]{1,5})?""")

        /** Default admin-console port — distinct from the public API (8081). */
        const val DEFAULT_ADMIN_PORT = 8082

        /** Default top-tier token quota — 2 TiB (the Premium tier). */
        const val DEFAULT_TOP_TIER_QUOTA_BYTES = 2L * 1024L * 1024L * 1024L * 1024L

        /**
         * Default closure-sweep interval — hourly. Frequent enough that a due reclamation starts
         * within an hour of becoming due, cheap enough to be a no-op query on a store with no
         * closed accounts (the normal case).
         */
        const val DEFAULT_ACCOUNT_CLOSURE_SWEEP_MINUTES = 60L

        /** Stripe REST base. Injectable via `STRIPE_API_BASE` so tests point it at a stub. */
        const val DEFAULT_STRIPE_API_BASE = "https://api.stripe.com"

        /** Hourly: frequent enough that a transient Stripe failure is fixed within the hour. */
        const val DEFAULT_SUBSCRIPTION_CANCEL_SWEEP_MINUTES = 60L

        /** Hourly: unverified account expiration sweep. */
        const val DEFAULT_UNVERIFIED_ACCOUNT_EXPIRE_SWEEP_MINUTES = 60L

        /**
         * 30 days — the same order as the published closure grace, so the retry tail outlives any
         * realistic outage while keeping the swept set bounded.
         */
        const val DEFAULT_SUBSCRIPTION_CANCEL_WINDOW_MS: Long = 30L * 24 * 60 * 60 * 1000

        /** Default public origin — the loopback dev bind, which is where the fake rail lives. */
        const val DEFAULT_PUBLIC_BASE_URL = "http://127.0.0.1:8081"

        /** Default SMTP submission port — 587 (STARTTLS submission), not 25. */
        const val DEFAULT_SMTP_PORT = 587

        /**
         * Default per-IP hourly OTP-send budget. Generous enough that a shared NAT (a household, a
         * small office) never trips it, small enough to bound both the mail spend and the sender
         * reputation damage a single source can do.
         */
        const val DEFAULT_OTP_SEND_PER_IP_PER_HOUR = 20

        /**
         * Default per-**email** hourly OTP-send budget. A real user needs one code and perhaps two
         * resends; this is the cap that stops someone using our registration form to mail-bomb a
         * third party, which no per-IP budget can prevent on its own.
         */
        const val DEFAULT_OTP_SEND_PER_EMAIL_PER_HOUR = 5

        /**
         * Default per-email hourly `/verify` budget. The per-code [DEFAULT_OTP_MAX_ATTEMPTS] burn
         * already stops guessing *one* code; this bounds guessing across repeatedly re-issued ones.
         */
        const val DEFAULT_OTP_VERIFY_PER_EMAIL_PER_HOUR = 20

        /**
         * Default per-address cap on **failed** code checks per rolling 24 h (D15). The hourly budget
         * above alone allowed ~480 guesses a day against a known address — ≈ 16 % a year for a patient
         * attacker; this makes it 20 a day, ≈ 0.7 % a year. A real user mistypes a few times.
         */
        const val DEFAULT_OTP_FAILED_PER_EMAIL_PER_DAY = 20

        /**
         * `REVIEW_SIGNIN_ACCOUNTS` → `(address, digest)` pairs: comma-separated entries, each split on
         * its **last** `=` (a local part may contain `=`; hex never does). Address trimmed and
         * lower-cased, digest trimmed and lower-cased. Shape errors are left to [validate], which can
         * refuse without echoing a secret.
         */
        internal fun parseReviewAccounts(raw: String?): List<Pair<String, String>> =
            raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
                if ('=' !in entry) "" to "" else
                    entry.substringBeforeLast('=').trim().lowercase() to entry.substringAfterLast('=').trim().lowercase()
            }

        /** Default post-checkout deep-links back into Control (control-subscription-ux.md §4b). */
        const val DEFAULT_CHECKOUT_SUCCESS_URL = "tenshu://checkout-result?status=success"
        const val DEFAULT_CHECKOUT_CANCEL_URL = "tenshu://checkout-result?status=cancel"
        const val DEFAULT_PORTAL_RETURN_URL = "tenshu://checkout-result?status=portal"

        /**
         * Committed dev signing seed for the commerce issuer (insecure by design — same posture
         * as `EntitlementSignature.DEV_SEED`). Its public half is pinned in
         * [EntitlementSignature.COMMERCE_PUBLIC_KEY] and guarded by `EntitlementSignatureKeyTest`.
         * Distinct byte pattern from the publisher dev seed (1..32) so the two dev keys never
         * collide. The matching production seed is an ops secret supplied via `ISSUER_SEED`.
         */
        val DEV_ISSUER_SEED: ByteArray = ByteArray(ED25519_SEED_SIZE) { (it + 65).toByte() }

        /**
         * Parse the runtime config from the process environment, falling back to local-dev
         * defaults (dev seed, `devMode=true`, bind `127.0.0.1:8081`) so an unconfigured `run`
         * works. A deployment supplies these as secrets/env:
         *
         * | Var                    | Default              | Notes                                       |
         * |------------------------|----------------------|---------------------------------------------|
         * | `PORT`                 | `8081`               | Listen port (distinct from the relay 8080). |
         * | `BIND_HOST`            | `127.0.0.1`          | Bind address (loopback by default — §2.5).  |
         * | `ISSUER_ID`            | `tenshu.commerce`    | The commerce `publisherId`.                 |
         * | `ISSUER_SEED`          | _(dev seed)_         | base64 32-byte Ed25519 seed; req when !dev. |
         * | `DEV_MODE`             | `true`               | Gates the dev seed; **set `false` in prod**.|
         * | `TOP_TIER_PRODUCT_ID`  | `tenshu.drive.premium`| Product id minted for whitelist accounts.  |
         * | `ENTITLEMENT_TTL_HOURS`| `720` (30d)          | Validity window per synced-record mint.     |
         * | `TOKEN_TTL_HOURS`      | `72`                 | Bearer entitlement-token validity window.   |
         * | `TOP_TIER_QUOTA_BYTES` | `2 TiB`              | Quota the top-tier token asserts.           |
         * | `SIGNING_KEY_ID`       | _(unset)_            | Token `kid` for A29 rotation; unset = default.|
         * | `DATABASE_URL`         | _(unset)_            | JDBC Postgres URL → durable store; unset = in-mem.|
         * | `OTP_TTL_MINUTES`      | `15`                 | Email-OTP validity window.                  |
         * | `OTP_MAX_ATTEMPTS`     | `5`                  | Max OTP attempts before the code is burned. |
         * | `SESSION_TTL_HOURS`    | `720` (30d)          | Commerce-session validity window.           |
         * | `REDEMPTION_CODES`     | _(empty)_            | `code:productId:maxUses[:expiresAtMs]`, comma-sep.|
         * | `ADMIN_BIND_HOST`      | `127.0.0.1`          | Admin console bind host (loopback — §4.5).  |
         * | `ADMIN_PORT`           | `8082`               | Admin console port (distinct from the API). |
         * | `ADMIN_TOKEN`          | _(unset)_            | Admin bearer credential; unset = console off.|
         * | `ADMIN_TOTP_SECRET`    | _(unset)_            | Base32 TOTP seed; **required with ADMIN_TOKEN when `DEV_MODE=false`**. |
         * | `STRIPE_SECRET_KEY`    | _(unset)_            | Stripe secret key; unset = Stripe rail off.  |
         * | `STRIPE_WEBHOOK_SECRET`| _(unset)_            | Webhook signing secret; req when secret set. |
         * | `STRIPE_PRICE_IDS`     | _(empty)_           | `productId=priceId,…` tier→Stripe price map. |
         * | `CHECKOUT_SUCCESS_URL` | `tenshu://…success` | Stripe success redirect (Control deep-link). |
         * | `CHECKOUT_CANCEL_URL`  | `tenshu://…cancel`  | Stripe cancel redirect.                      |
         * | `PORTAL_RETURN_URL`    | `tenshu://…portal`  | Billing-portal return redirect.              |
         * | `WEB_APP_ORIGIN`       | *(unset)*           | Browser origin allowed to call this service. |
         * | `STRIPE_API_BASE`      | `https://api.stripe.com` | Stripe REST base (stub in tests).       |
         * | `ACCOUNT_CLOSURE_RELAY_URL` | _(unset)_       | Relay closure endpoint; unset = no delivery.|
         * | `ACCOUNT_CLOSURE_SWEEP_MINUTES` | `60`        | Closure re-delivery sweep interval.         |
         * | `SMTP_HOST`            | _(unset)_            | SMTP server; with `SMTP_FROM` enables real mail. |
         * | `SMTP_FROM`            | _(unset)_            | Sender address; **required when `SMTP_HOST` is set**. |
         * | `SMTP_PORT`            | `587`                | Submission port (STARTTLS).                 |
         * | `SMTP_USERNAME`        | _(unset)_            | AUTH user; omit for an unauthenticated relay.|
         * | `SMTP_PASSWORD`        | _(unset)_            | AUTH password (a deployment secret).        |
         * | `SMTP_FROM_NAME`       | _(unset)_            | Display name on the `From` header.          |
         * | `SMTP_STARTTLS`        | `true`               | Require STARTTLS; false only for a local sink.|
         * | `OTP_SEND_PER_IP_PER_HOUR` | `20`             | Per-IP budget on `/register`; `<= 0` disables.            |
         * | `OTP_SEND_PER_EMAIL_PER_HOUR` | `5`           | Per-email budget on the same routes.         |
         * | `OTP_VERIFY_PER_EMAIL_PER_HOUR` | `20`        | Per-email budget on `/verify`.               |
         * | `STRIPE_MODE`          | `real`               | `fake` = the dev rail; **illegal when `DEV_MODE=false`**. |
         * | `PUBLIC_BASE_URL`      | `http://127.0.0.1:$PORT` | Origin the fake confirm page + self-webhook use. |
         * | `SUBSCRIPTION_CANCEL_SWEEP_MINUTES` | `60`   | Retry cadence for cancelling closed accounts' subs; `<= 0` disables. |
         * | `SUBSCRIPTION_CANCEL_WINDOW_MS` | 30 d       | How far back that retry looks.              |
         * | `OTP_FAILED_PER_EMAIL_PER_DAY` | `20`         | Per-address cap on failed code checks per rolling 24 h (persisted); `<= 0` disables. |
         * | `REVIEW_SIGNIN_ACCOUNTS` | _(unset)_          | `<address>=<hex HMAC of its code>`, comma-sep; one per store (T-CB-12). |
         * | `REVIEW_SIGNIN_HMAC_KEY` | _(unset)_          | ≥ 32-char secret key for those HMACs. Both or neither. |
         *
         * [getenv] is injectable so the parsing is unit-testable.
         */
        fun fromEnv(getenv: (String) -> String? = System::getenv): CommerceConfig {
            val devMode = getenv("DEV_MODE")?.toBooleanStrictOrNull() ?: true
            val seedB64 = getenv("ISSUER_SEED")?.takeIf { it.isNotBlank() }
            val issuerSeed = seedB64?.let { Base64.getDecoder().decode(it.trim()) }
                ?: DEV_ISSUER_SEED
            return CommerceConfig(
                devMode = devMode,
                bindHost = getenv("BIND_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
                port = getenv("PORT")?.toIntOrNull() ?: 8081,
                issuerId = getenv("ISSUER_ID")?.takeIf { it.isNotBlank() } ?: DEFAULT_ISSUER_ID,
                issuerSeed = issuerSeed,
                topTierProductId = getenv("TOP_TIER_PRODUCT_ID")?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_TOP_TIER_PRODUCT_ID,
                entitlementTtlHours = getenv("ENTITLEMENT_TTL_HOURS")?.toLongOrNull()
                    ?: DEFAULT_ENTITLEMENT_TTL_HOURS,
                tokenTtlHours = getenv("TOKEN_TTL_HOURS")?.toLongOrNull() ?: DEFAULT_TOKEN_TTL_HOURS,
                topTierQuotaBytes = getenv("TOP_TIER_QUOTA_BYTES")?.toLongOrNull()
                    ?: DEFAULT_TOP_TIER_QUOTA_BYTES,
                signingKeyId = getenv("SIGNING_KEY_ID")?.takeIf { it.isNotBlank() },
                databaseUrl = getenv("DATABASE_URL")?.takeIf { it.isNotBlank() },
                otpTtlMinutes = getenv("OTP_TTL_MINUTES")?.toLongOrNull() ?: DEFAULT_OTP_TTL_MINUTES,
                otpMaxAttempts = getenv("OTP_MAX_ATTEMPTS")?.toIntOrNull() ?: DEFAULT_OTP_MAX_ATTEMPTS,
                sessionTtlHours = getenv("SESSION_TTL_HOURS")?.toLongOrNull() ?: DEFAULT_SESSION_TTL_HOURS,
                redemptionSeeds = parseRedemptionSeeds(getenv("REDEMPTION_CODES")),
                adminBindHost = getenv("ADMIN_BIND_HOST")?.takeIf { it.isNotBlank() } ?: DEFAULT_ADMIN_BIND_HOST,
                adminPort = getenv("ADMIN_PORT")?.toIntOrNull() ?: DEFAULT_ADMIN_PORT,
                adminToken = getenv("ADMIN_TOKEN")?.takeIf { it.isNotBlank() },
                adminTotpSecret = getenv("ADMIN_TOTP_SECRET")?.takeIf { it.isNotBlank() },
                stripeSecretKey = getenv("STRIPE_SECRET_KEY")?.takeIf { it.isNotBlank() },
                stripeWebhookSecret = getenv("STRIPE_WEBHOOK_SECRET")?.takeIf { it.isNotBlank() },
                stripePriceIds = parsePriceIds(getenv("STRIPE_PRICE_IDS")),
                checkoutSuccessUrl = getenv("CHECKOUT_SUCCESS_URL")?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_CHECKOUT_SUCCESS_URL,
                checkoutCancelUrl = getenv("CHECKOUT_CANCEL_URL")?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_CHECKOUT_CANCEL_URL,
                portalReturnUrl = getenv("PORTAL_RETURN_URL")?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_PORTAL_RETURN_URL,
                webAppOrigin = getenv("WEB_APP_ORIGIN")?.takeIf { it.isNotBlank() }?.trimEnd('/'),
                stripeApiBase = getenv("STRIPE_API_BASE")?.takeIf { it.isNotBlank() } ?: DEFAULT_STRIPE_API_BASE,
                accountClosureRelayUrl = getenv("ACCOUNT_CLOSURE_RELAY_URL")?.takeIf { it.isNotBlank() },
                accountClosureSweepMinutes = getenv("ACCOUNT_CLOSURE_SWEEP_MINUTES")?.toLongOrNull()
                    ?: DEFAULT_ACCOUNT_CLOSURE_SWEEP_MINUTES,
                smtp = parseSmtp(
                    getenv,
                    ttlMinutes = getenv("OTP_TTL_MINUTES")?.toLongOrNull() ?: DEFAULT_OTP_TTL_MINUTES,
                ),
                googlePlay = GooglePlayConfig.fromEnv(getenv),
                otpSendPerIpPerHour = getenv("OTP_SEND_PER_IP_PER_HOUR")?.toIntOrNull()
                    ?: DEFAULT_OTP_SEND_PER_IP_PER_HOUR,
                otpSendPerEmailPerHour = getenv("OTP_SEND_PER_EMAIL_PER_HOUR")?.toIntOrNull()
                    ?: DEFAULT_OTP_SEND_PER_EMAIL_PER_HOUR,
                otpVerifyPerEmailPerHour = getenv("OTP_VERIFY_PER_EMAIL_PER_HOUR")?.toIntOrNull()
                    ?: DEFAULT_OTP_VERIFY_PER_EMAIL_PER_HOUR,
                signupLimitPerDay = signupLimit(getenv("SIGNUP_LIMIT_PER_DAY"), "SIGNUP_LIMIT_PER_DAY"),
                signupLimitTotal = signupLimit(getenv("SIGNUP_LIMIT_TOTAL"), "SIGNUP_LIMIT_TOTAL"),
                stripeFakeRail = getenv("STRIPE_MODE")?.trim()?.equals("fake", ignoreCase = true) ?: false,
                publicBaseUrl = getenv("PUBLIC_BASE_URL")?.takeIf { it.isNotBlank() }?.trimEnd('/')
                    ?: "http://127.0.0.1:${getenv("PORT")?.toIntOrNull() ?: 8081}",
                subscriptionCancelSweepMinutes = getenv("SUBSCRIPTION_CANCEL_SWEEP_MINUTES")?.toLongOrNull()
                    ?: DEFAULT_SUBSCRIPTION_CANCEL_SWEEP_MINUTES,
                subscriptionCancelWindowMs = getenv("SUBSCRIPTION_CANCEL_WINDOW_MS")?.toLongOrNull()
                    ?: DEFAULT_SUBSCRIPTION_CANCEL_WINDOW_MS,
                unverifiedAccountExpireSweepMinutes = getenv("UNVERIFIED_ACCOUNT_EXPIRE_SWEEP_MINUTES")?.toLongOrNull()
                    ?: DEFAULT_UNVERIFIED_ACCOUNT_EXPIRE_SWEEP_MINUTES,
                // Trimmed + lower-cased here; [validate] then refuses any entry `normaliseEmail`
                // would not return unchanged, so the set holds exactly what a request normalises to.
                reviewSignInAccounts = parseReviewAccounts(getenv("REVIEW_SIGNIN_ACCOUNTS")),
                reviewSignInHmacKey = getenv("REVIEW_SIGNIN_HMAC_KEY")?.trim()?.takeIf { it.isNotEmpty() },
                otpFailedPerEmailPerDay = getenv("OTP_FAILED_PER_EMAIL_PER_DAY")?.toIntOrNull()
                    ?: DEFAULT_OTP_FAILED_PER_EMAIL_PER_DAY,
            )
        }

        /**
         * ⚠ Refuse, never ignore: a mistyped cap that silently meant "no cap" would leave the
         * operator believing sign-ups are bounded when they are not.
         */
        private fun signupLimit(raw: String?, name: String): Int {
            if (raw.isNullOrBlank()) return 0
            return raw.trim().toIntOrNull()
                ?: throw IllegalArgumentException("$name must be a whole number (<= 0 = no cap); got '$raw'")
        }

        /**
         * Parse the SMTP transport from `SMTP_*`. Present iff **both** `SMTP_HOST` and `SMTP_FROM`
         * are set; a half-configured pair throws rather than silently falling back to the dev
         * log sender — the same posture as the Stripe key/webhook-secret pair, and for the same
         * reason: quietly degrading to "no real email" is exactly the state an operator would not
         * notice until a user could not sign in.
         *
         * Credentials are optional: a relay on a private network or an IP-allowlisted submission
         * host legitimately needs none, so requiring them would rule out a valid deployment.
         */
        internal fun parseSmtp(getenv: (String) -> String?, ttlMinutes: Long): SmtpConfig? {
            val host = getenv("SMTP_HOST")?.takeIf { it.isNotBlank() }?.trim()
            val from = getenv("SMTP_FROM")?.takeIf { it.isNotBlank() }?.trim()
            if (host == null && from == null) return null
            if (host == null || from == null) {
                throw InvalidCommerceConfigException(
                    "SMTP_HOST and SMTP_FROM must be set together (got " +
                        "${if (host == null) "SMTP_FROM" else "SMTP_HOST"} only)."
                )
            }
            return SmtpConfig(
                host = host,
                port = getenv("SMTP_PORT")?.toIntOrNull() ?: DEFAULT_SMTP_PORT,
                username = getenv("SMTP_USERNAME")?.takeIf { it.isNotBlank() },
                password = getenv("SMTP_PASSWORD")?.takeIf { it.isNotBlank() },
                fromAddress = from,
                fromName = getenv("SMTP_FROM_NAME")?.takeIf { it.isNotBlank() }?.trim(),
                startTls = getenv("SMTP_STARTTLS")?.toBooleanStrictOrNull() ?: true,
                ttlMinutes = ttlMinutes,
            )
        }

        /**
         * Parse `STRIPE_PRICE_IDS` — a comma-separated list of `productId=priceId`. Malformed
         * entries (missing `=`, blank half) are skipped rather than failing startup.
         */
        internal fun parsePriceIds(raw: String?): Map<String, String> {
            val value = raw?.takeIf { it.isNotBlank() } ?: return emptyMap()
            return value.splitToSequence(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { entry ->
                    val kv = entry.split("=", limit = 2)
                    if (kv.size != 2) return@mapNotNull null
                    val productId = kv[0].trim()
                    val priceId = kv[1].trim()
                    if (productId.isEmpty() || priceId.isEmpty()) return@mapNotNull null
                    productId to priceId
                }
                .toMap()
        }

        /**
         * Parse `REDEMPTION_CODES` — a comma-separated list of `code:productId:maxUses[:expiresAtMs]`.
         * Malformed entries (missing fields, non-numeric `maxUses`) are skipped rather than failing
         * startup. `maxUses` defaults to 1 if zero/negative.
         */
        internal fun parseRedemptionSeeds(raw: String?): List<RedemptionSeed> {
            val value = raw?.takeIf { it.isNotBlank() } ?: return emptyList()
            return value.splitToSequence(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { entry ->
                    val parts = entry.split(":")
                    if (parts.size < 3) return@mapNotNull null
                    val code = parts[0].trim()
                    val productId = parts[1].trim()
                    val maxUses = parts[2].trim().toIntOrNull() ?: return@mapNotNull null
                    val expiresAtMs = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()
                    if (code.isEmpty() || productId.isEmpty()) return@mapNotNull null
                    RedemptionSeed(code, productId, maxUses.coerceAtLeast(1), expiresAtMs)
                }
                .toList()
        }
    }
}

class InvalidCommerceConfigException(message: String) : IllegalStateException(message)

/**
 * SMTP transport settings for [SmtpEmailSender] (`commerce-end-to-end-dry-run.md` CE-2 / D3).
 * Provider-agnostic on purpose: every transactional-email vendor speaks SMTP, so switching
 * providers is a change to `deploy.env`, not to this module.
 *
 * ⚠ [password] is a deployment secret and, like the issuer seed, must never be logged. There is
 * deliberately no `toString` override adding it back.
 */
data class SmtpConfig(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    /** Envelope + header `From`. Must be a domain with SPF/DKIM/DMARC or the OTP lands in spam. */
    val fromAddress: String,
    val fromName: String?,
    /** Upgrade the connection with STARTTLS, and refuse to continue if the server won't. */
    val startTls: Boolean,
    /** Mirrors `CommerceConfig.otpTtlMinutes`, so the email states the same window it is issued for. */
    val ttlMinutes: Long,
) {
    /** True when both halves of a credential are present; drives `mail.smtp.auth`. */
    val hasCredentials: Boolean get() = !username.isNullOrBlank() && !password.isNullOrBlank()

    /** Redacted: the password must never reach a log; the username is a provider credential too. */
    override fun toString(): String =
        "SmtpConfig(host=$host, port=$port, credentials=${if (hasCredentials) "set" else "unset"}, " +
            "fromAddress=$fromAddress, fromName=$fromName, startTls=$startTls, ttlMinutes=$ttlMinutes)"
}

/**
 * The Google Play rail's settings (`commerce-store-link-out.md` P2). Present only when the
 * service-account key is set; [CommerceConfig.validate] then requires the notification secret.
 *
 * | Env var | Default | Meaning |
 * |---|---|---|
 * | `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | _(unset)_ | the service account's JSON key, raw or base64; unset = rail off |
 * | `GOOGLE_PLAY_PACKAGE` | `app.tenshu.control.android` | the app that sells |
 * | `GOOGLE_PLAY_PRODUCTS` | Basic and Premium, same ids | `playSubscriptionId=productId,…` |
 * | `GOOGLE_PLAY_RTDN_TOKEN` | _(unset)_ | the secret Pub/Sub puts in the push URL (`?token=`) |
 */
data class GooglePlayConfig(
    val serviceAccountJson: String,
    val packageName: String = DEFAULT_PACKAGE,
    val products: Map<String, String> = DEFAULT_PRODUCTS,
    val rtdnToken: String?,
) {
    /** Redacted: the key and the secret must never reach a log. */
    override fun toString(): String = "GooglePlayConfig(packageName=$packageName, products=$products)"

    companion object {
        const val DEFAULT_PACKAGE = "app.tenshu.control.android"
        val DEFAULT_PRODUCTS = mapOf(
            ProductCatalog.BASIC to ProductCatalog.BASIC,
            ProductCatalog.PREMIUM to ProductCatalog.PREMIUM,
        )

        fun fromEnv(getenv: (String) -> String?): GooglePlayConfig? {
            val raw = getenv("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON")?.trim()?.takeIf { it.isNotBlank() } ?: return null
            // Base64 is accepted because the key is multi-line JSON and a `deploy.env` line is not.
            val json = if (raw.startsWith("{")) raw else String(java.util.Base64.getDecoder().decode(raw))
            return GooglePlayConfig(
                serviceAccountJson = json,
                packageName = getenv("GOOGLE_PLAY_PACKAGE")?.takeIf { it.isNotBlank() } ?: DEFAULT_PACKAGE,
                products = CommerceConfig.parsePriceIds(getenv("GOOGLE_PLAY_PRODUCTS")).ifEmpty { DEFAULT_PRODUCTS },
                rtdnToken = getenv("GOOGLE_PLAY_RTDN_TOKEN")?.takeIf { it.isNotBlank() },
            )
        }
    }
}
