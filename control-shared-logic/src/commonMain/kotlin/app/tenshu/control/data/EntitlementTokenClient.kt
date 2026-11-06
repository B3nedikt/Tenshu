package app.tenshu.control.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.tenshu.core.common.security.peekEntitlementTokenExpiresAtSec
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * Source of the relay's bearer **entitlement TOKEN** (`commerce-billing-plane.md` §8 Phase 2c, §4
 * Q3 client-pull) — the credential the client presents on relay *writes* via `X-Entitlement`,
 * alongside its device-key JWT. Distinct from [EntitlementMintClient], which fetches the synced
 * *record*; this fetches the short-lived token the relay verifies on the hot path.
 *
 * Returns `null` when no token can be obtained (no bound email to gate on, not whitelisted, service
 * unreachable). A `null` is a benign no-op — the write simply carries no `X-Entitlement` header;
 * while enforcement is off the relay ignores its absence, and once on it returns `402` and the
 * client retries after a successful fetch. Unlike the entitlement *record*, the client **cannot**
 * mint a token in-process: the commerce issuer signing seed lives only in `:commerce-service`
 * (`CommerceConfig.DEV_ISSUER_SEED`; only its public half is pinned), so there is no dev-seed token
 * client — an unconfigured/mock local run simply has no token client and presents no header.
 */
interface EntitlementTokenClient {
    /**
     * Fetch a fresh entitlement token bound to [ownerId] (minimal variant), gated by [email]
     * (whitelist, Phase-1 bootstrap). `null` on any non-success / blank email / transport failure.
     */
    suspend fun fetchToken(ownerId: String, email: String?): String?
}

/**
 * The production token client: POSTs to `:commerce-service`'s `/commerce/entitlement/token` and
 * returns the signed token string it issues (signed with the commerce issuer key, `tenshu.commerce`).
 *
 * The endpoint is **session-gated** (`commerce-accounts-and-sessions.md` §4.3): the request carries
 * only a commerce `sessionToken`, and the service derives the tier + owner from the verified
 * account. So the [EntitlementTokenClient] interface's `ownerId`/`email` arguments are **ignored
 * here**. [sessionTokenProvider] supplies the token; a `null`/blank token short-circuits to `null`.
 * Any non-200 (401/403/5xx/transport) yields `null`. Mirrors [CommerceEntitlementMintClient].
 *
 * Phase 3a: the provider returns `null` (no device session flow yet) — dormant but contract-correct.
 */
class CommerceEntitlementTokenClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val sessionTokenProvider: () -> String?,
) : EntitlementTokenClient {

    override suspend fun fetchToken(ownerId: String, email: String?): String? {
        val sessionToken = sessionTokenProvider()?.takeIf { it.isNotBlank() } ?: return null

        val response: HttpResponse = runCatchingCancellable {
            httpClient.post("${baseUrl.trimEnd('/')}$TOKEN_PATH") {
                contentType(ContentType.Application.Json)
                setBody(TokenRequest(sessionToken = sessionToken))
            }
        }.getOrNull() ?: return null

        if (response.status != HttpStatusCode.OK) return null

        return runCatchingCancellable { response.body<TokenResponse>() }.getOrNull()?.token
    }

    private companion object {
        const val TOKEN_PATH = "/commerce/entitlement/token"
    }
}

/**
 * Caches the fetched token off the relay-write hot path (`commerce-billing-plane.md` §8 Phase 2c:
 * "caches off the hot path"). A write calls [token]; only a cache miss / near-expiry / owner change
 * triggers a network fetch, single-flighted by [mutex] so a burst of concurrent writes makes at most
 * one request. The cache TTL comes from the token's own `exp` (read unverified via
 * [peekEntitlementTokenExpiresAtSec]) minus [refreshSkewSec], so the client refreshes before the
 * relay would reject it.
 *
 * [client] is nullable: `null` means **disabled** (the unconfigured/mock local run with no commerce
 * origin) and [token] always returns `null` — folding the mock and real paths into one wiring type.
 * Owner-keyed: if [ownerIdProvider] reports a different owner (re-pair / account switch) the cached
 * token is discarded and a fresh one fetched. On a fetch failure a still-live cached token is kept
 * (graceful degradation) rather than dropped.
 */
class CachingEntitlementTokenProvider(
    private val client: EntitlementTokenClient?,
    private val ownerIdProvider: suspend () -> String?,
    private val emailProvider: suspend () -> String?,
    private val nowEpochSec: () -> Long,
    private val refreshSkewSec: Long = DEFAULT_REFRESH_SKEW_SEC,
    // The product id of the owner's currently-synced `Entitlement` record, or `null` when there is
    // none / the caller cannot supply it. See [token] for why this exists.
    //
    // ⚠ Defaults to `{ null }` so every existing construction site keeps today's behaviour exactly:
    // no record visible ⇒ no tier comparison ⇒ the cache behaves as it always has.
    private val recordTierProvider: suspend () -> String? = { null },
) {
    private class Cached(val ownerId: String, val token: String, val expiresAtSec: Long)

    private val mutex = Mutex()
    private var cached: Cached? = null

    /**
     * The record tier the cached token was last reconciled against. ⚠ Distinct from "the tier the
     * token asserts" on purpose — see the loop hazard in [token].
     */
    private var reconciledRecordTier: String? = null

    /** The current entitlement token to present, or `null` (disabled, no owner/email, or unreachable). */
    suspend fun token(): String? {
        val fetcher = client ?: return null
        val owner = ownerIdProvider() ?: return null
        val now = nowEpochSec()

        mutex.withLock {
            // ⚠⚠ A token is a 72-hour snapshot of the tier, and nothing else here would ever notice
            // that the tier had moved: the cache is keyed on owner and expiry alone, so after an
            // upgrade the device keeps presenting a token that says `free` for up to three days —
            // metered, and reported on the storage gauge, at the tier the user just stopped being on.
            //
            // The synced record is what moves first (it arrives by sync, needing no session), so a
            // change there is the signal that the cached token predates something.
            //
            // ⚠⚠ Compared against what we last *reconciled*, NOT against the tier the cached token
            // asserts. Those differ exactly when commerce keeps minting a tier the record disagrees
            // with — the state a missing account binding produces — and comparing against the token
            // would then re-fetch on literally every write, hammering `/token` forever. Keyed this
            // way, a persistent disagreement costs one refetch and then settles.
            val recordTier = recordTierProvider()
            val tierMoved = recordTier != null && recordTier != reconciledRecordTier
            if (tierMoved) reconciledRecordTier = recordTier

            // ⚠ Bypass the cache HIT, but deliberately do not discard the cached value: the
            // fetch-failure branch below still needs it. A token carrying a stale tier is a
            // credential the relay accepts — it just meters at the old figure — so during a
            // commerce outage it beats presenting nothing and going write-dead on 402.
            if (!tierMoved) {
                cached?.let { c ->
                    if (c.ownerId == owner && now < c.expiresAtSec - refreshSkewSec) return c.token
                }
            }

            val fresh = fetcher.fetchToken(owner, emailProvider())
            if (fresh == null) {
                // Fetch failed — keep a still-valid cached token (e.g. transient commerce outage)
                // rather than drop a credential the relay would still accept; otherwise give up.
                cached?.let { c -> if (c.ownerId == owner && now < c.expiresAtSec) return c.token }
                return null
            }

            // Unverified exp peek for scheduling only — the relay verifies authoritatively. Fall back
            // to a short hold if the token carries no parseable exp.
            val exp = peekEntitlementTokenExpiresAtSec(fresh) ?: (now + FALLBACK_TTL_SEC)
            cached = Cached(owner, fresh, exp)
            return fresh
        }
    }

    companion object {
        /** Refresh this many seconds before `exp` so a write never presents an about-to-expire token. */
        const val DEFAULT_REFRESH_SKEW_SEC: Long = 300L

        /** Hold time when a token has no parseable `exp` (defensive; the issuer always sets one). */
        const val FALLBACK_TTL_SEC: Long = 600L
    }
}

/** Token request wire shape — mirrors `:commerce-service`'s session-gated `TokenRequest` JSON. */
@Serializable
private data class TokenRequest(
    val sessionToken: String,
)

/** Token response wire shape — mirrors `:commerce-service`'s `TokenResponse` JSON. */
@Serializable
private data class TokenResponse(
    val token: String,
    val tier: String,
    val quotaBytes: Long,
)
