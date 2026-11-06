package org.tenshu.commerce.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import jakarta.mail.MessagingException
import jakarta.mail.internet.InternetAddress
import org.tenshu.commerce.describeForLog
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.tenshu.commerce.EmailSender
import org.tenshu.commerce.OtpCodes
import org.tenshu.commerce.PersistedFailureLimit
import org.tenshu.commerce.ProductCatalog
import org.tenshu.commerce.RateLimiter
import org.tenshu.commerce.ReviewSignIn
import org.tenshu.commerce.SessionTokens
import org.tenshu.commerce.emailDomain
import org.tenshu.commerce.shortId
import org.tenshu.commerce.store.Acceptance
import org.tenshu.commerce.store.CommerceStore
import org.tenshu.commerce.store.EntitlementHistory
import org.tenshu.commerce.store.EntitlementSource
import org.tenshu.commerce.store.ErasedAccounts
import org.tenshu.commerce.SignupCap
import org.tenshu.commerce.store.Rail

private val accountLogger = LoggerFactory.getLogger("org.tenshu.commerce.routing.AccountRouting")

/**
 * The account-lifecycle endpoints (`commerce-accounts-and-sessions.md` §4.2): email-OTP
 * registration + verification and the session refresh. `/register` is also how an existing account
 * signs in or re-binds to a new `ownerId` (§3.4): it sends the OTP, and `/verify` supersedes the
 * binding. The **Free** rail
 * (D5): email + OTP up front, the verified email *is* the sybil gate (no payment to anchor the
 * account on).
 *
 * Content-free errors throughout (the relay's StatusPages posture): never echo which check failed.
 */
fun Route.configureAccountRouting(
    store: CommerceStore,
    emailSender: EmailSender,
    otpTtlMs: Long,
    otpMaxAttempts: Int,
    sessionTtlMs: Long,
    now: () -> Long = System::currentTimeMillis,
    /**
     * Abuse budgets (`commerce-end-to-end-dry-run.md` CE-2 / D4). Defaults are **unlimited**, which
     * is what tests and the in-process test module run with; production wires real budgets from
     * [org.tenshu.commerce.CommerceConfig].
     */
    limits: AccountRateLimits = AccountRateLimits.unlimited(),
    /**
     * The store-review sign-in (D13, T-CB-12); null — the default and every deployment without
     * `REVIEW_SIGNIN_*` — leaves both routes without a review address.
     */
    reviewSignIn: ReviewSignIn? = null,
    /** Per-address cap on failed code checks per rolling 24 h, shared with the deletion confirm (D15). */
    otpFailures: PersistedFailureLimit? = null,
    /** The operator's cap on new accounts (cost brake); [SignupCap.off] by default. */
    signupCap: SignupCap = SignupCap.off(),
) {
    post("/commerce/account/register") {
        val request = runCatching { call.receive<RegisterRequest>() }.getOrNull()
        val email = request?.email?.let(::normaliseEmail)
        if (request == null || email == null ||
            request.tosVersion.isBlank() || request.privacyVersion.isBlank()
        ) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
            return@post
        }

        val nowMs = now()
        if (!limits.allowSend(call.clientIp(), email, nowMs)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limited"))
            return@post
        }
        fun registrationOf(accountId: String) = Acceptance(
            accountId = accountId,
            tosVersion = request.tosVersion,
            privacyVersion = request.privacyVersion,
            acceptedAt = nowMs,
            ip = call.clientIp(),
        )
        val existing = store.findAccountByEmail(email)
        // Signup cap (cost brake). An address with no verified account gets a "sign-ups are paused"
        // mail instead of a code, and no account row is created. ⚠⚠ Same 202 and the same SMTP round
        // trip as a code, so the answer still says nothing about whether the address has an account.
        // Existing verified accounts sign in as usual: the cap limits new storage, not access.
        if (existing?.emailVerifiedAt == null && signupCap.enabled && signupCap.isReached(store, nowMs)) {
            try {
                emailSender.sendSignupsPausedNotice(email)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                accountLogger.warn("Signups-paused notice failed during /register; answering send_failed ({})", describeForLog(failure))
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "send_failed"))
                return@post
            }
            accountLogger.info("Signup cap reached: /register for a new {} address answered with the paused notice", emailDomain(email))
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "otp_sent"))
            return@post
        }
        var account = existing
            ?: store.createAccount(email, rail = Rail.Free, createdAt = nowMs)
        // Versioned acceptance (§5.1) — contract formation, recorded on every register.
        // ⚠ False = the unverified-account sweep deleted the account we just found: start a
        // fresh one, exactly as if the request had come a moment later.
        if (!store.recordAcceptance(registrationOf(account.id))) {
            account = store.createAccount(email, rail = Rail.Free, createdAt = nowMs)
            check(store.recordAcceptance(registrationOf(account.id))) { "account vanished twice during /register" }
        }
        // ⚠⚠ A review address never gets a mailed code (T-CB-12): its only code is the fixed one,
        // so a mailed one would be a second, weaker way in. Same 202, same budgets, same account +
        // acceptance writes, and the same generate-hash-upsert — onto the decoy key, the deletion
        // route's never-confirmable row — so the DB work matches; only the SMTP round trip is
        // absent, and what that reveals is membership of a two-address list the docs publish anyway.
        if (reviewSignIn?.covers(email) == true) {
            store.putEmailVerification(ErasedAccounts.DECOY_OTP_KEY, OtpCodes.hash(OtpCodes.generate()), expiresAt = nowMs + otpTtlMs)
            store.deleteEmailVerification(email)
            accountLogger.info("Review sign-in /register for account {} ({}); no mail sent", shortId(account.id), emailDomain(email))
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "otp_sent"))
            return@post
        }
        // A transport failure is its own answer, not an `internal_error`. StatusPages would map the
        // throw to a content-free 500, which the client cannot tell from a genuine bug, so it says
        // "Something went wrong" to someone whose only useful move is to try again shortly.
        //
        // ⚠⚠ Safe only because `/register` creates the account when it is absent: it always
        // attempts a send, so a 503 reveals nothing about prior existence. A route that sent only
        // for an account that exists could not answer this way without becoming an enumeration
        // oracle.
        //
        // ⚠ This covers only what fails *synchronously* — connect, auth, the relay refusing the
        // message. A send the relay accepts and later bounces (full mailbox, blocked) is already
        // past this point: see `commerce-otp-delivery-feedback.md`.
        try {
            issueOtp(store, emailSender, email, otpTtlMs, nowMs)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // The sender logs the recipient's domain and the cause; this records that a
            // *registration* died with it, which is the user-visible event and the one worth
            // grepping for. ⚠ A mail failure is described, not attached: Jakarta Mail's messages
            // name the address. Anything else (a store error) keeps its stack trace; the logback
            // backstop masks an address that turns up in it.
            if (generateSequence<Throwable>(failure) { it.cause }.take(5).any { it is MessagingException }) {
                accountLogger.warn("OTP send failed during /register; answering send_failed ({})", describeForLog(failure))
            } else {
                accountLogger.warn("OTP send failed during /register; answering send_failed", failure)
            }
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "send_failed"))
            return@post
        }
        // 202: the OTP is on its way; the client polls /verify.
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "otp_sent"))
    }

    post("/commerce/account/verify") {
        val request = runCatching { call.receive<VerifyRequest>() }.getOrNull()
        val email = request?.email?.let(::normaliseEmail)
        if (request == null || email == null || request.code.isBlank() || request.ownerId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
            return@post
        }

        val nowMs = now()
        // Bounds guessing *across* re-issued codes; the per-code attempt burn below only bounds
        // guessing within one. A 429 here leaks nothing about whether the account exists.
        if (!limits.allowVerify(email, nowMs)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limited"))
            return@post
        }
        // ⚠⚠ A review address is checked against the fixed code ONLY — a mailed code is never
        // consulted for it, and no other address ever reaches the fixed-code check (T-CB-12).
        if (reviewSignIn?.covers(email) == true) {
            val reviewAccount = store.findAccountByEmail(email)
            when (reviewSignIn.check(email, request.code, nowMs)) {
                ReviewSignIn.Outcome.LockedOut -> {
                    accountLogger.warn("Review sign-in locked out after repeated wrong codes ({})", emailDomain(email))
                    call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limited"))
                    return@post
                }
                ReviewSignIn.Outcome.Refused -> {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                    return@post
                }
                ReviewSignIn.Outcome.Accepted -> Unit
            }
            if (reviewAccount == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@post
            }
            accountLogger.info("Review sign-in accepted for account {} ({})", shortId(reviewAccount.id), emailDomain(email))
            val signedIn = completeSignIn(store, reviewAccount.id, request.ownerId, email, sessionTtlMs, nowMs)
            if (signedIn == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@post
            }
            call.respond(HttpStatusCode.OK, signedIn)
            return@post
        }
        // ⚠⚠ The daily failed-check cap (D15, T-CB-13): over it, refuse before looking at any code —
        // the right one included — with the same 429 the hourly budget answers.
        if (otpFailures?.isLocked(email, nowMs) == true) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limited"))
            return@post
        }
        val verification = store.getEmailVerification(email)
        val account = store.findAccountByEmail(email)
        // Unknown email, expired code, exhausted attempts → uniform 401, and burn an expired/spent code.
        // ⚠ Every 401 below counts as a failed check, whether or not a code was pending: counting only
        // real mismatches would make the cap's 429 an oracle for "a code was issued to this address".
        if (verification == null || account == null) {
            otpFailures?.recordFailure(email, nowMs)
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@post
        }
        if (nowMs > verification.expiresAt || verification.attempts >= otpMaxAttempts) {
            store.deleteEmailVerification(email)
            otpFailures?.recordFailure(email, nowMs)
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@post
        }
        if (!OtpCodes.matches(request.code, verification.codeHash)) {
            store.incrementVerificationAttempts(email)
            otpFailures?.recordFailure(email, nowMs)
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@post
        }

        // Null = the account was expired between the lookup and the stamp; the code went with it.
        val signedIn = completeSignIn(store, account.id, request.ownerId, email, sessionTtlMs, nowMs)
        if (signedIn == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@post
        }
        call.respond(HttpStatusCode.OK, signedIn)
    }

    post("/commerce/account/session") {
        val request = runCatching { call.receive<SessionRequest>() }.getOrNull()
        if (request == null || request.sessionToken.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
            return@post
        }
        val nowMs = now()
        val tokenHash = SessionTokens.hash(request.sessionToken)
        val session = store.getSession(tokenHash)
        if (session == null || !session.isValid(nowMs)) {
            if (session != null) store.deleteSession(tokenHash)
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@post
        }
        // Refresh: slide the expiry forward so an active client stays signed in.
        store.createSession(tokenHash, session.accountId, createdAt = session.createdAt, expiresAt = nowMs + sessionTtlMs)
        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    }
}

/**
 * The abuse budgets applied by [configureAccountRouting] (`commerce-end-to-end-dry-run.md` CE-2).
 * Two budgets guard the mail-sending routes and one guards `/verify`; they are separate objects
 * because they key on different things (address vs. IP) and expire independently.
 */
class AccountRateLimits(
    private val sendPerIp: RateLimiter,
    private val sendPerEmail: RateLimiter,
    private val verifyPerEmail: RateLimiter,
) {
    /**
     * Spend one unit of **both** send budgets, or neither.
     *
     * ⚠⚠ Decide-then-spend, not `tryAcquire(ip) && tryAcquire(email)`. The naive conjunction
     * charges the per-IP budget for a request the per-email budget then refuses, and the sequence
     * that produces is a realistic one: a user hits the 5/hour cap on their own address, keeps
     * pressing *resend* precisely **because** it is failing, and burns the shared per-IP budget
     * down to zero — locking every colleague behind the same NAT out of signing up for an hour.
     * Nothing was mailed in the meantime, so the charge bought nothing either. Refused requests are
     * therefore free; only requests that actually send mail cost budget, which is what the budgets
     * are for.
     *
     * The order still matters and is still IP-first: checking the recipient's budget first would
     * let an abusive source burn a *victim's* budget and lock them out of their own signup.
     *
     * ⚠ Peek-then-spend is not atomic across the two limiters — two concurrent requests can both
     * observe room and both commit, overshooting a budget by one. Accepted: these are coarse cost
     * bounds, and one extra email is not a failure mode worth a lock for.
     */
    fun allowSend(ip: String, email: String, nowMs: Long): Boolean {
        if (!sendPerIp.wouldAllow(ip, nowMs)) return false
        if (!sendPerEmail.wouldAllow(email, nowMs)) return false
        sendPerIp.tryAcquire(ip, nowMs)
        sendPerEmail.tryAcquire(email, nowMs)
        return true
    }

    fun allowVerify(email: String, nowMs: Long): Boolean = verifyPerEmail.tryAcquire(email, nowMs)

    companion object {
        /** Every budget disabled — the default for tests and the in-process test module. */
        fun unlimited(): AccountRateLimits = AccountRateLimits(
            sendPerIp = RateLimiter(limit = 0, windowMs = HOUR_MS),
            sendPerEmail = RateLimiter(limit = 0, windowMs = HOUR_MS),
            verifyPerEmail = RateLimiter(limit = 0, windowMs = HOUR_MS),
        )

        /** Hourly budgets from config. `<= 0` on any of them disables that one. */
        fun hourly(
            sendPerIpPerHour: Int,
            sendPerEmailPerHour: Int,
            verifyPerEmailPerHour: Int,
            onTableFull: () -> Unit = {},
        ): AccountRateLimits = AccountRateLimits(
            sendPerIp = RateLimiter(sendPerIpPerHour, HOUR_MS, onTableFull = onTableFull),
            sendPerEmail = RateLimiter(sendPerEmailPerHour, HOUR_MS, onTableFull = onTableFull),
            verifyPerEmail = RateLimiter(verifyPerEmailPerHour, HOUR_MS, onTableFull = onTableFull),
        )

        const val HOUR_MS: Long = 60L * 60L * 1000L
    }
}

/**
 * The **client** IP for rate-limiting and for the acceptance record.
 *
 * ⚠⚠ Deliberately `request.origin.remoteAddress`, not `request.local.remoteHost`. In production
 * every request arrives from Caddy, so the socket peer is Caddy's own address — a per-IP budget
 * keyed on it would put the entire internet in one bucket and let a single abuser throttle
 * everybody, and an acceptance record stamped with it would be worthless as evidence of contract
 * formation. `XForwardedHeaders` (installed in `Application.module`) makes `origin` reflect the
 * forwarded client, falling back to the real peer on a direct/dev bind. Trusting `X-Forwarded-For`
 * is safe here for the same reason it is on the relay: Caddy is the only ingress and the container
 * port is not otherwise reachable.
 */
internal fun ApplicationCall.clientIp(): String = request.origin.remoteAddress

/**
 * Verified — by a mailed code or the review code. Mark, (re-)bind the owner (supersede, §3.4), grant
 * the Free entitlement, drop any pending code, open a session.
 */
private fun completeSignIn(
    store: CommerceStore,
    accountId: String,
    ownerId: String,
    email: String,
    sessionTtlMs: Long,
    nowMs: Long,
): VerifyResponse? {
    // ⚠⚠ First, and checked: if the expiry sweep took the account, stop before bindOwner /
    // entitlement / session write orphans keyed by a deleted id (no foreign keys).
    if (!store.markEmailVerified(accountId, nowMs)) return null
    store.bindOwner(accountId, ownerId, nowMs)
    store.appendEntitlementHistory(
        EntitlementHistory(
            accountId = accountId,
            productId = ProductCatalog.FREE,
            validFrom = nowMs,
            validUntil = nowMs + FREE_ENTITLEMENT_WINDOW_MS,
            source = EntitlementSource.Free,
            issuedAt = nowMs,
        ),
    )
    store.deleteEmailVerification(email)

    val token = SessionTokens.generate()
    store.createSession(SessionTokens.hash(token), accountId, createdAt = nowMs, expiresAt = nowMs + sessionTtlMs)
    return VerifyResponse(sessionToken = token)
}

/** Generate a fresh OTP, store its hash, and hand the code to the [EmailSender]. */
private fun issueOtp(store: CommerceStore, emailSender: EmailSender, email: String, otpTtlMs: Long, nowMs: Long) {
    val code = OtpCodes.generate()
    store.putEmailVerification(email, OtpCodes.hash(code), expiresAt = nowMs + otpTtlMs)
    emailSender.sendOtp(email, code)
}

/**
 * Trim + lowercase, then validate; null if it is not a plain address. The single
 * email-normalisation point.
 *
 * ⚠⚠ **This is what keeps the `email_verification` key spaces disjoint.** That table holds sign-in
 * codes under the bare address and deletion codes under `account-deletion:<address>`
 * ([org.tenshu.commerce.store.ErasedAccounts.deletionOtpKey]); erased rows carry `erased:<id>`. An
 * input that normalised to `account-deletion:victim@x.com` would let `/register` overwrite a
 * victim's pending deletion code with a sign-in code. So a normalised address never contains `:`
 * (a legal character only inside a quoted local part, which no real signup needs), and must parse
 * as one strict RFC 822 address.
 */
internal fun normaliseEmail(raw: String): String? {
    val normalised = raw.trim().lowercase()
    if (normalised.isEmpty() || ':' in normalised || normalised.count { it == '@' } != 1) return null
    if (normalised.any { it.isWhitespace() || it == '"' || it == '<' || it == '>' || it == ',' }) return null
    val parsed = runCatching { InternetAddress(normalised, true) }.getOrNull() ?: return null
    return normalised.takeIf { parsed.address == normalised && it.substringAfter('@').contains('.') }
}

/**
 * Free entitlements don't lapse — the history row is effectively permanent (~10 years), so
 * `currentEntitlement` keeps gating `/mint` open while the device re-fetches short-lived signed
 * records. Paid/redeem windows are per-issue (the Stripe webhook / the code's product).
 */
const val FREE_ENTITLEMENT_WINDOW_MS: Long = 3650L * 24 * 60 * 60 * 1000

@Serializable
data class RegisterRequest(val email: String, val tosVersion: String, val privacyVersion: String)

@Serializable
data class VerifyRequest(val email: String, val code: String, val ownerId: String)

@Serializable
data class VerifyResponse(val sessionToken: String)


@Serializable
data class SessionRequest(val sessionToken: String)
