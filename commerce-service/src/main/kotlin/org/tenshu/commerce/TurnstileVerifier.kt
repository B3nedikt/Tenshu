package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val turnstileLogger = LoggerFactory.getLogger("org.tenshu.commerce.TurnstileVerifier")

/**
 * Cloudflare Turnstile on the two unauthenticated routes that send mail (`/register` and the
 * `{email}` variant of `/delete/request`) — `commerce-accounts-and-sessions.md` D16.
 *
 * Off unless `TURNSTILE_SECRET_KEY` is set ([CommerceConfig.turnstileSecretKey]). On, a request it
 * covers must carry a widget token that Cloudflare's `siteverify` accepts, and the check runs
 * **before** any rate-limit budget is spent or the store is read: a bot that cannot solve the
 * challenge then cannot drain the per-inbox or global send budgets that real sign-ins share.
 *
 * ⚠⚠ **Fail closed.** No answer from Cloudflare (timeout, 5xx, unparseable body) refuses with `503`,
 * not a pass. The widget itself loads from Cloudflare, so a Cloudflare outage has already stopped
 * the web flow; failing open would only reopen it to the scripts this exists to stop.
 */
class TurnstileVerifier(
    private val secretKey: String,
    private val client: HttpClient,
    private val verifyUrl: String = SITEVERIFY_URL,
) {
    enum class Outcome { Passed, Refused, Unavailable }

    suspend fun verify(token: String?, remoteIp: String?): Outcome {
        // Cloudflare documents a 2048-character ceiling; anything longer is not a token and is not
        // worth a round trip.
        if (token.isNullOrBlank() || token.length > MAX_TOKEN_LENGTH) return Outcome.Refused
        return try {
            val response = client.submitForm(
                url = verifyUrl,
                formParameters = parameters {
                    append("secret", secretKey)
                    append("response", token)
                    if (!remoteIp.isNullOrBlank()) append("remoteip", remoteIp)
                },
            )
            if (!response.status.isSuccess()) {
                turnstileLogger.warn("Turnstile siteverify answered {}; refusing as unavailable", response.status.value)
                return Outcome.Unavailable
            }
            val success = Json.parseToJsonElement(response.bodyAsText())
                .jsonObject["success"]?.jsonPrimitive?.booleanOrNull
            when (success) {
                true -> Outcome.Passed
                false -> Outcome.Refused
                null -> Outcome.Unavailable.also { turnstileLogger.warn("Turnstile siteverify answered without `success`") }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            turnstileLogger.warn("Turnstile siteverify failed; refusing as unavailable ({})", failure.javaClass.simpleName)
            Outcome.Unavailable
        }
    }

    companion object {
        const val SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify"
        const val MAX_TOKEN_LENGTH = 2048
        private const val TIMEOUT_MS = 5_000L

        /** The production client: a short timeout, since a hung siteverify holds a sign-in open. */
        fun create(secretKey: String, client: HttpClient): TurnstileVerifier =
            TurnstileVerifier(secretKey, client.config { install(HttpTimeout) { requestTimeoutMillis = TIMEOUT_MS } })
    }
}

/**
 * Whether this call carries a browser `Origin` — the line D16 draws between the web apex, which the
 * challenge covers, and the native apps, which send no `Origin` and cannot run the widget.
 *
 * ⚠⚠ **A classifier, not a credential.** Any non-browser client can omit the header, so `/register`
 * without one is unchallenged by design; what bounds that path is the rest of the budget stack
 * (per-IP, per-inbox, global), not this. What it does buy: every request a real browser sends —
 * including a bot driving the web form — is challenged, because a browser cannot drop `Origin` from
 * a cross-origin `fetch`.
 */
fun ApplicationCall.isBrowserCall(): Boolean = !request.headers[HttpHeaders.Origin].isNullOrBlank()

/**
 * Runs the challenge when [verifier] is set and [required]; answers the refusal itself and returns
 * false when it fails, so a route reads `if (!passesTurnstile(...)) return@post`.
 *
 * `403 challenge_failed` for a missing, malformed or rejected token (the web resets its widget and
 * lets the user try again); `503 challenge_unavailable` when Cloudflare could not be asked.
 */
suspend fun ApplicationCall.passesTurnstile(
    verifier: TurnstileVerifier?,
    token: String?,
    required: Boolean,
    remoteIp: String,
): Boolean {
    if (verifier == null || !required) return true
    return when (verifier.verify(token, remoteIp)) {
        TurnstileVerifier.Outcome.Passed -> true
        TurnstileVerifier.Outcome.Refused -> {
            respond(HttpStatusCode.Forbidden, mapOf("error" to "challenge_failed"))
            false
        }
        TurnstileVerifier.Outcome.Unavailable -> {
            respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "challenge_unavailable"))
            false
        }
    }
}
