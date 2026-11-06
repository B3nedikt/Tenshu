package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.slf4j.event.Level
import org.tenshu.commerce.routing.AccountRateLimits
import org.tenshu.commerce.routing.AdminAuth
import org.tenshu.commerce.routing.StripeSupport
import org.tenshu.commerce.routing.TokenAdminAuth
import org.tenshu.commerce.routing.WebReturnUrls
import org.tenshu.commerce.routing.configureAccountDeletionRouting
import org.tenshu.commerce.routing.configureAccountRouting
import org.tenshu.commerce.routing.configureAdminRouting
import org.tenshu.commerce.play.RealGooglePlayClient
import org.tenshu.commerce.routing.GooglePlaySupport
import org.tenshu.commerce.routing.configureBillingRouting
import org.tenshu.commerce.routing.configureGooglePlayRouting
import org.tenshu.commerce.routing.configureCheckoutRouting
import org.tenshu.commerce.routing.configureFakeStripeRouting
import org.tenshu.commerce.routing.configureHealthRouting
import org.tenshu.commerce.routing.configureMintRouting
import org.tenshu.commerce.routing.configureRedeemRouting
import org.tenshu.commerce.routing.configureStripeWebhookRouting
import org.tenshu.commerce.routing.configureTokenRouting
import org.tenshu.commerce.store.CommerceDatabase
import org.tenshu.commerce.store.CommerceStore
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.commerce.store.PostgresCommerceStore
import org.tenshu.commerce.store.RedemptionCode
import org.tenshu.commerce.stripe.FakeStripeClient
import org.tenshu.commerce.stripe.RealStripeClient
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.security.Ed25519Backend

private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_HOUR = 60L * MS_PER_MINUTE

/**
 * Post one closure record to the relay, returning its HTTP status (`null` = no response).
 *
 * ⚠ Never an exception: a failed delivery is retried by the next sweep whatever the cause. The
 * status is returned anyway because the *remedies* differ even though the retry does not — an
 * unreachable relay heals itself, while a `401` (signature, key id or clock) repeats on every sweep
 * until an operator acts, and the sweep log is the only place that would ever show it.
 */
private suspend fun postClosureRecord(client: HttpClient, url: String, token: String): Int? =
    runCatching {
        client.post(url) { setBody(token) }.status.value
    }.getOrNull()

fun main() {
    // All runtime config comes from the environment, falling back to local-dev defaults (dev
    // seed, devMode on loopback, in-memory store) so an unconfigured `run` is unchanged. A
    // deployment supplies ISSUER_SEED / DATABASE_URL / DEV_MODE=false / BIND_HOST etc. —
    // see CommerceConfig.fromEnv.
    val config = CommerceConfig.fromEnv()
    config.validate()

    val ed25519: Ed25519Backend = BouncyCastleEd25519Backend()
    val issuerKeypair = IssuerKeypair(config.issuerSeed, ed25519)
    val minter = EntitlementMinter(config, issuerKeypair)
    val tokenMinter = EntitlementTokenMinter(config, issuerKeypair)

    // Durable Postgres store when DATABASE_URL is set; in-memory otherwise (the default —
    // commerce-accounts-and-sessions.md §9.2). The store is the A28 PII boundary (House Rule #1).
    val store: CommerceStore = config.databaseUrl
        ?.let { PostgresCommerceStore(CommerceDatabase(it)) }
        ?: InMemoryCommerceStore()
    // Seed founder/privacy redemption codes (§4.3 — the whitelist's successor). Hashed before storage.
    config.redemptionSeeds.forEach { seed ->
        store.putRedemptionCode(
            RedemptionCode(
                codeHash = OtpCodes.hash(seed.code),
                productId = seed.productId,
                maxUses = seed.maxUses,
                usedCount = 0,
                expiresAt = seed.expiresAtMs,
            ),
        )
    }
    // Real SMTP when SMTP_HOST + SMTP_FROM are configured; the dev log sender otherwise.
    // `validate()` above has already refused the dev sender under DEV_MODE=false, so reaching
    // LoggingEmailSender here means this is genuinely a dev run (CE-2 / D3).
    val emailSender: EmailSender = config.smtp?.let(::SmtpEmailSender) ?: LoggingEmailSender()

    // Stripe rail (§6) — opt-in via STRIPE_SECRET_KEY (+ STRIPE_WEBHOOK_SECRET, required together).
    // Off by default so a free/redeem-only deployment (and an unconfigured `run`) is unchanged; when
    // off, the checkout/portal/webhook routes answer 503. The secret key is a commerce secret (A28).
    //
    // CE-3: `STRIPE_MODE=fake` swaps ONLY the processor. The webhook secret is generated in-process
    // (both signer and verifier live here, so there is nothing to configure and nothing to leak),
    // and synthetic price ids make the tiers purchasable with no Stripe dashboard. `validate()` has
    // already refused this combination under DEV_MODE=false.
    val fakeStripeClient: FakeStripeClient? =
        if (config.stripeFakeRail) FakeStripeClient(config.publicBaseUrl) else null
    val webReturnUrls = config.webAppOrigin?.let(::WebReturnUrls)
    val stripe: StripeSupport? = when {
        fakeStripeClient != null -> StripeSupport(
            client = fakeStripeClient,
            priceIds = config.stripePriceIds.ifEmpty { FakeStripeClient.fakePriceIds() },
            webhookSecret = config.stripeWebhookSecret ?: SessionTokens.generate(),
            successUrl = config.checkoutSuccessUrl,
            cancelUrl = config.checkoutCancelUrl,
            portalReturnUrl = config.portalReturnUrl,
            webReturnUrls = webReturnUrls,
        )
        config.stripeEnabled -> StripeSupport(
            client = RealStripeClient(HttpClient(CIO), config.stripeSecretKey!!, config.stripeApiBase),
            priceIds = config.stripePriceIds,
            webhookSecret = config.stripeWebhookSecret!!,
            successUrl = config.checkoutSuccessUrl,
            cancelUrl = config.checkoutCancelUrl,
            portalReturnUrl = config.portalReturnUrl,
            webReturnUrls = webReturnUrls,
        )
        else -> null
    }
    val googlePlay: GooglePlaySupport? = config.googlePlay?.let {
        GooglePlaySupport(
            client = RealGooglePlayClient(HttpClient(CIO), it.serviceAccountJson, it.packageName),
            products = it.products,
            rtdnToken = it.rtdnToken!!,
        )
    }

    // One canceller for every closure path — the admin close, the self-service deletion, and the
    // retry sweep — so a rail added here reaches all three at once.
    // ⚠ Both rails: Stripe and Google Play (`fix/store-billing-review`). Apple cannot be stopped by us.
    val subscriptionCanceller: SubscriptionCanceller? = if (stripe != null || googlePlay != null) {
        SubscriptionCanceller(store, stripe?.client, googlePlay?.client)
    } else {
        null
    }

    println(
        "Commerce issuer '${config.issuerId}' starting on ${config.bindHost}:${config.port} " +
            "(devMode=${config.devMode}, store=${if (config.databaseUrl != null) "postgres" else "in-memory"}, " +
            "redemptionSeeds=${config.redemptionSeeds.size})"
    )
    // The relay verifies tokens against this public key. Print it (base64) so a deployment can copy
    // it straight into the relay's ENTITLEMENT_ISSUER_PUBKEY (+ ENTITLEMENT_ISSUER_KEY_ID = the
    // token `kid` if SIGNING_KEY_ID is set). The PRIVATE seed never leaves this process (A29).
    println(
        "Commerce issuer public key (set relay ENTITLEMENT_ISSUER_PUBKEY to this): " +
            java.util.Base64.getEncoder().encodeToString(issuerKeypair.publicKey) +
            (config.signingKeyId?.let { " [kid=$it]" } ?: "")
    )

    // Internal admin console (§4.5) on its own host/port, opt-in via ADMIN_TOKEN. Started first
    // (non-blocking) so the public API's start(wait=true) is what keeps the process alive. It is
    // never started without a token (locked by default); production fronts it with a Caddy
    // allowlist / VPN so it is also off the public internet.
    if (config.adminToken != null) {
        embeddedServer(
            Netty,
            port = config.adminPort,
            host = config.adminBindHost,
            module = {
                adminModule(
                    store = store,
                    adminAuth = TokenAdminAuth(config.adminToken),
                    // Closing an account stops its billing too, when there is a rail to stop.
                    subscriptionCanceller = subscriptionCanceller,
                    totp = AdminTotpVerifier(config.adminTotpSecret),
                )
            },
        ).start(wait = false)
        val factors = if (config.adminTotpSecret.isNullOrBlank()) "token-gated, NO second factor" else "token + TOTP"
        println("Commerce admin console on ${config.adminBindHost}:${config.adminPort} ($factors; keep off public DNS)")
    } else {
        println("Commerce admin console disabled (set ADMIN_TOKEN to enable)")
    }
    println(
        when {
            fakeStripeClient != null ->
                "Commerce payment rail: ⚠ FAKE (dev only) — confirm page at ${config.publicBaseUrl}${FakeStripeClient.CHECKOUT_PATH}/<session>"
            stripe != null -> "Commerce Stripe rail enabled"
            else -> "Commerce payment rail disabled (set STRIPE_SECRET_KEY, or STRIPE_MODE=fake for the dev rail)"
        }
    )
    println(
        config.smtp?.let { "Commerce email: SMTP ${it.host}:${it.port} from ${it.fromAddress}" }
            ?: "Commerce email: DEV LOG SENDER — the OTP is printed to this log (set SMTP_HOST + SMTP_FROM for real mail)"
    )
    println(
        "Commerce OTP budgets: ${config.otpSendPerIpPerHour}/ip/h send, " +
            "${config.otpSendPerEmailPerHour}/email/h send, ${config.otpVerifyPerEmailPerHour}/email/h verify, " +
            "${config.otpFailedPerEmailPerDay} failed codes/email/24h"
    )
    // ⚠ Counts only — never an address, a digest or the key (T-CB-12).
    val reviewSignIn = config.reviewSignIn(store)
    // A rotated or withdrawn review code must not live on through the sessions it minted. Run with an
    // empty set when the feature is off, so removing the config ends those sessions too.
    val reviewSessionsReset = reviewSignIn?.endSessionsOnRotation()
        ?: ReviewSignIn.endReviewSessionsOnRotation(store, emptyMap())
    println(
        (
            reviewSignIn?.let {
                "Commerce review sign-in: ENABLED for ${it.accountCount} account(s) " +
                    "(lockout ${ReviewSignIn.MAX_FAILURES} wrong codes per account per rolling 24 h)"
            } ?: "Commerce review sign-in disabled (set REVIEW_SIGNIN_ACCOUNTS + REVIEW_SIGNIN_HMAC_KEY to enable)"
        ) + if (reviewSessionsReset > 0) "; ended the sessions of $reviewSessionsReset rotated/withdrawn review account(s)" else ""
    )

    // Account-closure delivery (retention-quota-model.md Face A / D4) — runs only when a relay URL
    // is configured. ⚠⚠ This sweep is the only thing in the system that can lead to a user's content
    // being deleted, and it takes both ends: without ACCOUNT_CLOSURE_RELAY_URL here nothing is ever
    // posted, and without ACCOUNT_CLOSURE_RECLAIM there the route does not even exist. Both default
    // off and both are ON in production since 2026-10-24 (maintainer decision; ⚖️ counsel review of
    // the retention text still owed).
    val closureRelayUrl = config.accountClosureRelayUrl
    if (closureRelayUrl != null) {
        val closureClient = HttpClient(CIO)
        val delivery = AccountClosureDelivery(
            minter = AccountClosureMinter(config, issuerKeypair, store),
            post = { token -> postClosureRecord(closureClient, closureRelayUrl, token) },
        )
        // Daemon thread: delivery must never hold the process open, and a sweep that dies must not
        // take the API down with it — the store stays the durable truth either way.
        Thread {
            runBlocking {
                delivery.runForever(
                    intervalMs = config.accountClosureSweepMinutes * MS_PER_MINUTE,
                    onSweep = { report ->
                        // Counts only — a record is a delete capability and never belongs in a log.
                        if (report.attempted > 0) {
                            println("Account-closure sweep: ${report.summary()}")
                        }
                    },
                )
            }
        }.apply { isDaemon = true; name = "account-closure-delivery" }.start()
        println("Account-closure delivery enabled → $closureRelayUrl (every ${config.accountClosureSweepMinutes} min)")
    } else {
        println("Account-closure delivery disabled (set ACCOUNT_CLOSURE_RELAY_URL to enable)")
    }

    // Retry tail for subscription cancellation. Closing already cancels inline; this catches the
    // case where that call failed (Stripe down, process died between the close and the cancel), so
    // a closed customer cannot be left billing indefinitely. Idempotent, so it needs no state.
    if (subscriptionCanceller != null && config.subscriptionCancelSweepMinutes > 0) {
        val canceller = subscriptionCanceller
        Thread {
            runBlocking {
                canceller.runForever(
                    intervalMs = config.subscriptionCancelSweepMinutes * MS_PER_MINUTE,
                    windowMs = config.subscriptionCancelWindowMs,
                    onSweep = { report ->
                        // Only speak up when there was something to do — a quiet sweep is the norm.
                        if (report.reconciled > 0 || report.failed > 0) {
                            println(
                                "Subscription-cancel sweep: ${report.reconciled} reconciled, " +
                                    "${report.failed} still owed (of ${report.examined} closed accounts)"
                            )
                        }
                    },
                )
            }
        }.apply { isDaemon = true; name = "subscription-cancel-sweep" }.start()
        println("Subscription-cancel sweep enabled (every ${config.subscriptionCancelSweepMinutes} min)")
    }

    if (config.unverifiedAccountExpireSweepMinutes > 0) {
        val expirer = UnverifiedAccountExpirer(store)
        Thread {
            runBlocking {
                expirer.runForever(
                    intervalMs = config.unverifiedAccountExpireSweepMinutes * MS_PER_MINUTE,
                    onSweep = { expired ->
                        if (expired > 0) {
                            println("Unverified-account expire sweep: deleted $expired never-verified account(s)")
                        }
                    },
                )
            }
        }.apply { isDaemon = true; name = "unverified-account-expire-sweep" }.start()
        println("Unverified-account expire sweep enabled (every ${config.unverifiedAccountExpireSweepMinutes} min)")
    }

    embeddedServer(
        Netty,
        port = config.port,
        host = config.bindHost,
        module = {
            module(
                minter = minter,
                tokenMinter = tokenMinter,
                store = store,
                emailSender = emailSender,
                otpTtlMs = config.otpTtlMinutes * MS_PER_MINUTE,
                otpMaxAttempts = config.otpMaxAttempts,
                sessionTtlMs = config.sessionTtlHours * MS_PER_HOUR,
                stripe = stripe,
                googlePlay = googlePlay,
                subscriptionCanceller = subscriptionCanceller,
                entitlementTtlMs = config.entitlementTtlHours * MS_PER_HOUR,
                webAppOrigin = config.webAppOrigin,
                fakeStripe = fakeStripeClient,
                fakeStripeWebhookPost = fakeStripeClient?.let { _ ->
                    val selfClient = HttpClient(CIO)
                    val url = "${config.publicBaseUrl}/commerce/webhook/stripe"
                    // A genuine HTTP POST to our OWN webhook route — not an internal function call.
                    // That is the whole point of the fake rail: the event travels the same path a
                    // Stripe event does, through signature verification and StatusPages.
                    { payload: String, signature: String ->
                        try {
                            val response = selfClient.post(url) {
                                header("Stripe-Signature", signature)
                                setBody(payload)
                            }
                            if (!response.status.isSuccess()) {
                                // Say WHY. A bare false here reads as "the rail is broken" when it
                                // is usually the webhook rejecting a payload we built wrong — which
                                // is exactly the class of bug the fake rail exists to surface early.
                                System.err.println(
                                    "[fake-stripe] webhook responded ${response.status}: ${response.bodyAsText()}"
                                )
                            }
                            response.status.isSuccess()
                        } catch (e: Exception) {
                            System.err.println("[fake-stripe] webhook self-call to $url failed: $e")
                            false
                        }
                    }
                },
                reviewSignIn = reviewSignIn,
                otpFailedPerEmailPerDay = config.otpFailedPerEmailPerDay,
                signupCap = SignupCap(perDay = config.signupLimitPerDay, total = config.signupLimitTotal).also {
                    SignupCap.logConfigured(it, config.signupLimitPerDay, config.signupLimitTotal)
                },
                limits = AccountRateLimits.hourly(
                    sendPerIpPerHour = config.otpSendPerIpPerHour,
                    sendPerEmailPerHour = config.otpSendPerEmailPerHour,
                    verifyPerEmailPerHour = config.otpVerifyPerEmailPerHour,
                    // The fail-closed table ceiling is a real-attack signal, not routine — say so
                    // once it happens, since the visible symptom is otherwise "signups stopped".
                    onTableFull = { System.err.println("WARNING: OTP rate-limiter key table full — refusing new keys") },
                ),
            )
        },
    ).start(wait = true)
}

/**
 * Production / test entry point. Collaborators are threaded as params with test defaults so the
 * ktor test-host can inject a fixed-seed issuer, an in-memory store, and a no-op email sender
 * (mirrors the relay's `module`). [now] is injectable so OTP/session expiry is deterministic.
 */
fun Application.module(
    minter: EntitlementMinter = EntitlementMinter(
        CommerceConfig.fromEnv(),
        IssuerKeypair(CommerceConfig.DEV_ISSUER_SEED, BouncyCastleEd25519Backend()),
    ),
    tokenMinter: EntitlementTokenMinter = EntitlementTokenMinter(
        CommerceConfig.fromEnv(),
        IssuerKeypair(CommerceConfig.DEV_ISSUER_SEED, BouncyCastleEd25519Backend()),
    ),
    store: CommerceStore = InMemoryCommerceStore(),
    emailSender: EmailSender = LoggingEmailSender(),
    otpTtlMs: Long = CommerceConfig.DEFAULT_OTP_TTL_MINUTES * MS_PER_MINUTE,
    otpMaxAttempts: Int = CommerceConfig.DEFAULT_OTP_MAX_ATTEMPTS,
    sessionTtlMs: Long = CommerceConfig.DEFAULT_SESSION_TTL_HOURS * MS_PER_HOUR,
    stripe: StripeSupport? = null,
    /** The Google Play rail (P2); null = its routes answer 503 and Google sells nothing. */
    googlePlay: GooglePlaySupport? = null,
    /** Stops a self-deleted account's billing (§9.7). Null = no paid rail to stop. */
    subscriptionCanceller: SubscriptionCanceller? = null,
    entitlementTtlMs: Long = CommerceConfig.DEFAULT_ENTITLEMENT_TTL_HOURS * MS_PER_HOUR,
    now: () -> Long = System::currentTimeMillis,
    /** Unlimited by default so the ktor test-host module is unchanged; main() passes real budgets. */
    limits: AccountRateLimits = AccountRateLimits.unlimited(),
    /** Non-null only in `STRIPE_MODE=fake`; mounts the dev rail's browser routes (CE-3). */
    fakeStripe: FakeStripeClient? = null,
    /** How the fake rail delivers its signed event; injected so a test can capture it. */
    fakeStripeWebhookPost: (suspend (payload: String, signature: String) -> Boolean)? = null,
    /**
     * Browser origin permitted to call this service (`WEB_APP_ORIGIN`). Null — the default, and the
     * posture this service shipped with — installs no CORS at all, so no browser can reach it.
     */
    webAppOrigin: String? = null,
    /** Store-review sign-in (D13, T-CB-12); null = off, the default and the posture without `REVIEW_SIGNIN_*`. */
    reviewSignIn: ReviewSignIn? = null,
    /**
     * Per-address cap on failed code checks per rolling 24 h (D15). Production's default here too, so
     * the test host runs what the box runs; `<= 0` disables.
     */
    otpFailedPerEmailPerDay: Int = CommerceConfig.DEFAULT_OTP_FAILED_PER_EMAIL_PER_DAY,
    /** The operator's cap on new accounts; off by default so the test host is unchanged. */
    signupCap: SignupCap = SignupCap.off(),
) {
    val otpFailures = PersistedFailureLimit(
        store,
        PersistedFailureLimit.OTP_FAILURES_PREFIX,
        otpFailedPerEmailPerDay,
        hashAddress = true,
    )
    install(ContentNegotiation) {
        json()
    }
    // ⚠⚠ Until the web account surface existed, every caller here was a native HTTP client, which
    // is not subject to the same-origin policy — so this service had no CORS and needed none. The
    // apex is a browser page, and a browser will not even send the request without this.
    //
    // ⚠ Exactly one configured origin, never `anyHost()`. `/commerce/account/register` is
    // deliberately unauthenticated (the verified email IS the sybil gate), so a wildcard would let
    // any page on the internet drive our outbound mail through a victim's browser.
    //
    // ⚠ `allowCredentials` stays FALSE. The session token rides in the JSON body, never a cookie,
    // so nothing here is ambient authority a cross-site request could borrow — and keeping it off
    // is what makes a stolen origin header useless on its own.
    if (webAppOrigin != null) {
        install(CORS) {
            allowHost(
                host = webAppOrigin.substringAfter("://"),
                schemes = listOf(webAppOrigin.substringBefore("://")),
            )
            allowMethod(HttpMethod.Post)
            allowHeader(HttpHeaders.ContentType)
        }
    }
    // Behind Caddy every request's socket peer is Caddy, so without this the per-IP budget would
    // be one global bucket (and the recorded acceptance IP would be Caddy's). See `clientIp()`.
    install(XForwardedHeaders)
    install(CallLogging) {
        level = Level.INFO
    }
    // Never echo internal exception text to clients (the relay's content-free StatusPages
    // posture, sync-backend-relay-plan.md §4.2): log server-side, respond with a typed,
    // content-free error.
    val appLogger = log
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            appLogger.error("Unhandled exception serving ${call.request.path()}", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error"))
        }
    }

    routing {
        get("/") {
            // ⚠ Liveness of the PROCESS only — a static string that cannot fail. "Can this service
            // serve?" is /health, which round-trips the store. Do not add a check here.
            call.respondText("Tenshu Commerce issuer is running!")
        }
        configureHealthRouting(store)
        configureMintRouting(minter, store, now)
        configureTokenRouting(tokenMinter, store, now)
        configureAccountRouting(store, emailSender, otpTtlMs, otpMaxAttempts, sessionTtlMs, now, limits, reviewSignIn, otpFailures, signupCap)
        configureAccountDeletionRouting(store, emailSender, otpTtlMs, otpMaxAttempts, subscriptionCanceller, now, limits, reviewSignIn, otpFailures)
        configureRedeemRouting(store, minter, now)
        configureCheckoutRouting(stripe, store, now)
        configureBillingRouting(
            store = store,
            // Apple sells nothing until its verification exists (P4).
            sellable = mapOf(
                PurchaseRail.Stripe to stripe?.priceIds?.keys.orEmpty(),
                PurchaseRail.Google to googlePlay?.products?.values.orEmpty(),
            ),
            now = now,
        )
        configureGooglePlayRouting(googlePlay, stripe, store, now)
        configureStripeWebhookRouting(stripe, store, entitlementTtlMs, now, googlePlay)
        // Dev rail browser surface — mounted only when the fake client is present, which
        // `CommerceConfig.validate()` allows only in dev mode.
        if (fakeStripe != null && stripe != null && fakeStripeWebhookPost != null) {
            configureFakeStripeRouting(fakeStripe, stripe.webhookSecret, fakeStripeWebhookPost, now)
        }
    }
}

/**
 * The internal admin console module (§4.5) — a **separate** Ktor application from [module] so it
 * binds its own host/port and can be firewalled independently. Read-only; the only route is the
 * guarded users table. It shares the same [store] instance as the public module (same process).
 */
fun Application.adminModule(
    store: CommerceStore,
    adminAuth: AdminAuth,
    now: () -> Long = System::currentTimeMillis,
    subscriptionCanceller: SubscriptionCanceller? = null,
    /**
     * The second factor (WC-5, §5.3). Unconfigured ⇒ the bearer token alone applies, which
     * `CommerceConfig.validate()` permits only when `DEV_MODE=true`.
     */
    totp: AdminTotpVerifier = AdminTotpVerifier(null),
) {
    install(CallLogging) {
        level = Level.INFO
    }
    val appLogger = log
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            appLogger.error("Unhandled exception serving ${call.request.path()}", cause)
            call.respond(HttpStatusCode.InternalServerError, "internal_error")
        }
    }

    // ⚠⚠ Authorisation lives HERE, once, for the whole admin application — not per route. This is
    // the highest-risk surface in the system (A28 ⨯ A30 in the clear, and since WC-4 an export), and
    // a per-route check is one forgotten line away from an open door. Intercepting the pipeline
    // means a route added later is covered by construction rather than by remembering.
    install(
        createApplicationPlugin("AdminAuthGate") {
            onCall { call ->
                val credentialOk = adminAuth.isAuthorized(call.request.headers["Authorization"])
                val secondFactorOk = !totp.isConfigured ||
                    totp.verify(call.request.headers[ADMIN_TOTP_HEADER], now())
                if (credentialOk && secondFactorOk) return@onCall

                // ⚠ The log distinguishes the two failures; the RESPONSE does not. Telling a caller
                // that the bearer token was right but the code was wrong confirms a stolen token is
                // live — exactly the fact a second factor exists to keep from being useful.
                appLogger.warn(
                    "[admin] auth failure on {} (credential={}, second-factor={}) from {}",
                    // Path only: the query can carry `?email=`.
                    call.request.path(),
                    if (credentialOk) "ok" else "bad",
                    if (secondFactorOk) "ok" else "bad",
                    call.request.local.remoteHost,
                )
                call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
            }
        },
    )

    routing {
        configureAdminRouting(store, adminAuth, now, subscriptionCanceller)
    }
}

/**
 * Where the admin TOTP code rides. A header, because the console is bearer-authenticated and
 * curl-driven — a browser form cannot send either factor, which is why the mutations were always
 * curl calls (§4.5).
 */
const val ADMIN_TOTP_HEADER = "X-Admin-Totp"
