package org.tenshu.commerce.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import jakarta.mail.MessagingException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.tenshu.commerce.EmailSender
import org.tenshu.commerce.OtpCodes
import org.tenshu.commerce.PersistedFailureLimit
import org.tenshu.commerce.ReviewSignIn
import org.tenshu.commerce.SessionTokens
import org.tenshu.commerce.SubscriptionCanceller
import org.tenshu.commerce.TurnstileVerifier
import org.tenshu.commerce.passesTurnstile
import org.tenshu.commerce.describeForLog
import org.tenshu.commerce.emailDomain
import org.tenshu.commerce.shortId
import org.tenshu.commerce.store.Account
import org.tenshu.commerce.store.CommerceStore
import org.tenshu.commerce.store.ErasedAccounts

private val deletionLogger = LoggerFactory.getLogger("org.tenshu.commerce.routing.AccountDeletionRouting")

/**
 * Self-service **account deletion** (`commerce-accounts-and-sessions.md` §9.7) — the in-app and web
 * route Google Play requires of an app that creates accounts.
 *
 * Two steps, both on the public API:
 *
 * - `POST /commerce/account/delete/request` `{sessionToken}` (the app) **or** `{email}` (the public
 *   web page, which has no session) → `202`, and a **deletion code** is mailed to the account's
 *   address.
 * - `POST /commerce/account/delete/confirm` `{sessionToken | email, code}` → `200 deleted`.
 *
 * **Possession of the inbox is the proof, even with a session.** An account here *is* an address plus
 * the codes mailed to it; a session token is a bearer credential living on a device, and a deletion
 * is irreversible, so a stolen or left-signed-in device must not be enough on its own. Both variants
 * therefore require a fresh code, mailed for this purpose only ([ErasedAccounts.deletionOtpKey]): a
 * sign-in code cannot delete and a deletion code cannot sign in.
 *
 * **Confirming** erases the account ([CommerceStore.eraseAccount]: closed, email and sessions gone,
 * statutory rows kept) and then stops its billing ([SubscriptionCanceller]) — close first, cancel
 * second, for the asymmetric-failure reason the admin close gives. The closure then rides the
 * existing reclamation path to the relay with nothing new here (`retention-quota-model.md` Face A):
 * ⚠⚠ which deletes stored content **only** when both `ACCOUNT_CLOSURE_RELAY_URL` and the relay's
 * `ACCOUNT_CLOSURE_RECLAIM` are set — a deploy-time decision, not this route's.
 *
 * ⚠⚠ **An address no account uses gets NO mail.** The `{email}` variant is unauthenticated and
 * answers any address, so a mail per request makes it an open cannon pointed wherever a caller
 * likes. That is not hypothetical: from 2026-11-09 a form-spam bot fed the public web page ~500
 * scraped and mangled addresses (Gmail dot-variants, random prefixes), 77% of which bounced — each
 * bounce charged to `tenshu.app`'s sender reputation, which the sign-in codes share. So only an
 * address with an account is mailed (`commerce-accounts-and-sessions.md` §9.7).
 *
 * ⚠ **Still no enumeration.** Both branches answer the same `202` after the same DB work (an unknown
 * address upserts a throwaway code under the fixed decoy key). The unknown branch has no SMTP round
 * trip, so it waits [noAccountPad] instead — a jittered pause in the range a real send takes — so the
 * response time does not say whether the account exists. Every refusal on `/confirm` is the same
 * `401`. Budgets are `/register`'s and `/verify`'s ([AccountRateLimits]): a deletion code is mail,
 * and a guess is a guess.
 *
 * ⚠ **Idempotent where it can be.** A second confirmation (a retry after a lost response) finds no
 * account or no code and answers `401`; a repeated request for the address answers `202` and mails
 * nothing, which the web page's copy already allows for ("If a Tenshu account uses…"). The store
 * side is idempotent outright.
 */
fun Route.configureAccountDeletionRouting(
    store: CommerceStore,
    emailSender: EmailSender,
    otpTtlMs: Long,
    otpMaxAttempts: Int,
    /** Stops Stripe (and, once wired, Google) billing. Null on a deployment with no paid rail. */
    subscriptionCanceller: SubscriptionCanceller?,
    now: () -> Long = System::currentTimeMillis,
    limits: AccountRateLimits = AccountRateLimits.unlimited(),
    /**
     * The store-review sign-in (D13, T-CB-12). A reviewer may test deletion, and has no inbox to
     * receive a deletion code — so for a listed address the fixed code confirms instead, and no
     * deletion code is ever mailed or accepted. Null leaves both routes as they were.
     */
    reviewSignIn: ReviewSignIn? = null,
    /** Per-address cap on failed code checks per rolling 24 h, shared with `/verify` (D15). */
    otpFailures: PersistedFailureLimit? = null,
    /**
     * Stands in for the SMTP round trip on the no-account branch, so its response time matches the
     * mailing branch's. Production passes [jitteredSendPad]; tests leave it instant.
     */
    noAccountPad: suspend () -> Unit = {},
    /**
     * Cloudflare Turnstile (D16); null = off. Challenges every `{email}` request, browser or not:
     * only the web page sends that variant (the apps send `{sessionToken}`, which already proves
     * the inbox), so no native caller needs exempting.
     */
    turnstile: TurnstileVerifier? = null,
) {
    post("/commerce/account/delete/request") {
        val request = runCatching { call.receive<DeletionRequest>() }.getOrNull()
        // ⚠⚠ Before resolving the target — a session or account lookup — and before any budget (D16).
        if (!call.passesTurnstile(turnstile, request?.turnstileToken, required = !request?.email.isNullOrBlank(), call.clientIp())) {
            return@post
        }
        val target = request?.let { store.resolveDeletionTarget(it.email, it.sessionToken, now()) }
        when (target) {
            null, DeletionTarget.Malformed -> {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
                return@post
            }
            DeletionTarget.NoSession -> {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@post
            }
            is DeletionTarget.Resolved -> Unit
        }

        val nowMs = now()
        if (!limits.allowSend(call.clientIp(), target.email, nowMs)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limited"))
            return@post
        }
        // ⚠ A review address gets no deletion code: the fixed code confirms. Same 202, same budget,
        // the same generate-hash-upsert onto the decoy key — only the SMTP round trip is absent.
        if (reviewSignIn?.covers(target.email) == true) {
            store.putEmailVerification(ErasedAccounts.DECOY_OTP_KEY, OtpCodes.hash(OtpCodes.generate()), expiresAt = nowMs + otpTtlMs)
            store.deleteEmailVerification(ErasedAccounts.deletionOtpKey(target.email))
            deletionLogger.info("Review deletion request ({}); no mail sent", emailDomain(target.email))
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "code_sent"))
            return@post
        }
        try {
            // ⚠ Both branches do the same DB work — generate, hash, one upsert — and take about as
            // long: the mail on one, [noAccountPad] on the other. The unknown-address branch writes
            // to a single fixed decoy key rather than under the address: it must not leave a row
            // naming someone who has no account with us. It mails nobody (class note).
            val code = OtpCodes.generate()
            val key = if (target.account != null) ErasedAccounts.deletionOtpKey(target.email) else ErasedAccounts.DECOY_OTP_KEY
            store.putEmailVerification(key, OtpCodes.hash(code), expiresAt = nowMs + otpTtlMs)
            if (target.account != null) {
                emailSender.sendAccountDeletionCode(target.email, code)
            } else {
                noAccountPad()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // ⚠ Only the account branch can reach this, so a 503 does say "an account exists" — but
            // only when OUR transport is failing, which a caller cannot cause, and then for every
            // account alike. Accepted: answering 202 instead would tell a real user a code is coming
            // when none is (`commerce-otp-delivery-feedback.md`).
            if (generateSequence<Throwable>(failure) { it.cause }.take(5).any { it is MessagingException }) {
                deletionLogger.warn("Deletion mail failed; answering send_failed ({})", describeForLog(failure))
            } else {
                deletionLogger.warn("Deletion mail failed; answering send_failed", failure)
            }
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "send_failed"))
            return@post
        }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "code_sent"))
    }

    post("/commerce/account/delete/confirm") {
        val request = runCatching { call.receive<DeletionConfirm>() }.getOrNull()
        if (request == null || request.code.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
            return@post
        }
        val nowMs = now()
        val target = when (val resolved = store.resolveDeletionTarget(request.email, request.sessionToken, nowMs)) {
            DeletionTarget.Malformed -> {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
                return@post
            }
            DeletionTarget.NoSession -> {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@post
            }
            is DeletionTarget.Resolved -> resolved
        }
        // Before the lookup, keyed on the address, exactly as /verify: bounds guessing across
        // re-issued codes, and a 429 leaks nothing about whether the account exists.
        if (!limits.allowVerify(target.email, nowMs)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limited"))
            return@post
        }
        val account = target.account
        if (reviewSignIn?.covers(target.email) == true) {
            // ⚠⚠ The fixed code only; a mailed deletion code is never consulted for a review address.
            when (reviewSignIn.check(target.email, request.code, nowMs)) {
                ReviewSignIn.Outcome.LockedOut -> {
                    deletionLogger.warn("Review deletion locked out after repeated wrong codes ({})", emailDomain(target.email))
                    call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limited"))
                    return@post
                }
                ReviewSignIn.Outcome.Refused -> {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                    return@post
                }
                ReviewSignIn.Outcome.Accepted -> Unit
            }
            if (account == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@post
            }
        } else {
            // ⚠⚠ The daily failed-check cap, shared with /verify (D15) — same rules, same oracle-free
            // counting of every 401, same 429 as the hourly budget.
            if (otpFailures?.isLocked(target.email, nowMs) == true) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limited"))
                return@post
            }
            val key = ErasedAccounts.deletionOtpKey(target.email)
            val verification = store.getEmailVerification(key)
            if (verification == null || account == null) {
                otpFailures?.recordFailure(target.email, nowMs)
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@post
            }
            if (nowMs > verification.expiresAt || verification.attempts >= otpMaxAttempts) {
                store.deleteEmailVerification(key)
                otpFailures?.recordFailure(target.email, nowMs)
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@post
            }
            if (!OtpCodes.matches(request.code.trim(), verification.codeHash)) {
                store.incrementVerificationAttempts(key)
                otpFailures?.recordFailure(target.email, nowMs)
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@post
            }
        }

        // ⚠⚠ Erase (which closes) FIRST, cancel second — see SubscriptionCanceller. A crash in
        // between leaves a closed account still billing, which the cancellation sweep finds; the
        // other order could leave a live account whose subscription is silently gone.
        val erased = store.eraseAccount(account.id, nowMs)
        // The pre-erasure row: cancellation reads only the billing references, which erasure keeps.
        subscriptionCanceller?.cancelFor(account)
        if (erased) deletionLogger.warn("Account {} deleted by its owner (self-service)", shortId(account.id))
        call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
    }
}

/** Who a deletion request is about. [Resolved.account] is null when no (un-erased) account uses the address. */
private sealed interface DeletionTarget {
    data object Malformed : DeletionTarget
    data object NoSession : DeletionTarget
    data class Resolved(val email: String, val account: Account?) : DeletionTarget
}

/**
 * Exactly one of [email] / [sessionToken]. A session resolves to its account's address; an expired,
 * unknown, or erased-account session is [DeletionTarget.NoSession] (the client signs in again or uses
 * the web page). An address resolves to its account, or to none.
 */
private fun CommerceStore.resolveDeletionTarget(email: String?, sessionToken: String?, nowMs: Long): DeletionTarget {
    val hasEmail = !email.isNullOrBlank()
    val hasSession = !sessionToken.isNullOrBlank()
    if (hasEmail == hasSession) return DeletionTarget.Malformed
    if (hasSession) {
        val session = getSession(SessionTokens.hash(sessionToken!!)) ?: return DeletionTarget.NoSession
        if (!session.isValid(nowMs)) {
            deleteSession(session.tokenHash)
            return DeletionTarget.NoSession
        }
        val account = findAccountById(session.accountId)?.takeIf { it.erasedAt == null }
            ?: return DeletionTarget.NoSession
        return DeletionTarget.Resolved(account.emailNormalised, account)
    }
    val normalised = normaliseEmail(email!!) ?: return DeletionTarget.Malformed
    return DeletionTarget.Resolved(normalised, findAccountByEmail(normalised)?.takeIf { it.erasedAt == null })
}

@Serializable
data class DeletionRequest(
    val email: String? = null,
    val sessionToken: String? = null,
    /** The Turnstile widget's token (D16); the web page's `{email}` variant only. */
    val turnstileToken: String? = null,
)

@Serializable
data class DeletionConfirm(val email: String? = null, val sessionToken: String? = null, val code: String)

/**
 * The production [configureAccountDeletionRouting] `noAccountPad`: a uniform pause over the span the
 * TEM log shows a real send taking (≈0.1–2 s, most under 1 s), so the no-mail branch is not
 * measurably faster than the mailing one.
 */
suspend fun jitteredSendPad() {
    kotlinx.coroutines.delay(kotlin.random.Random.nextLong(NO_ACCOUNT_PAD_MIN_MS, NO_ACCOUNT_PAD_MAX_MS))
}

private const val NO_ACCOUNT_PAD_MIN_MS = 300L
private const val NO_ACCOUNT_PAD_MAX_MS = 1_500L
