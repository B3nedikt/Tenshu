package app.tenshu.control.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.security.EntitlementTokenClaims
import org.tenshu.core.common.security.encodeEntitlementToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Client-side entitlement-TOKEN fetch + cache (`commerce-billing-plane.md` §8 Phase 2c). The HTTP
 * client maps a `200` into the token string, sends only its **session token** (the endpoint is
 * session-gated, `commerce-accounts-and-sessions.md` §4.3), short-circuits when there is no session,
 * and no-ops a non-200 to `null`. The caching provider serves a cached token off the hot path,
 * refreshes on expiry / owner change, and degrades gracefully on a fetch failure.
 */
class EntitlementTokenClientTest {

    // The provider only peeks `exp` (unverified) for cache TTL, so any signer works for test tokens.
    private fun tokenWithExp(expSec: Long, ownerId: String = "owner-1"): String =
        encodeEntitlementToken(
            EntitlementTokenClaims(
                ownerId = ownerId,
                tier = "tenshu.drive.pro",
                quotaBytes = 1_000L,
                issuedAtSec = 0L,
                expiresAtSec = expSec,
            ),
        ) { ByteArray(64) }

    // ── CommerceEntitlementTokenClient ──────────────────────────────────────

    @Test
    fun commerceTokenClient_parses200_returnsToken_andSendsSessionToken() = runTest {
        var seenBody: String? = null
        val engine = MockEngine { request ->
            seenBody = (request.body as TextContent).text
            respond(
                content = """{"token":"tok-abc","tier":"tenshu.drive.premium","quotaBytes":1024}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = CommerceEntitlementTokenClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(engine),
            sessionTokenProvider = { "sess-token-123" },
        )

        // ownerId/email args are ignored — the account behind the session decides the tier + owner.
        val token = client.fetchToken(ownerId = "ignored", email = "ignored@example.com")

        assertEquals("tok-abc", token)
        assertEquals(true, "sess-token-123" in seenBody!!, "the request carries the session token")
        assertEquals(false, "ignored@example.com" in seenBody!!, "no email/PII is sent")
    }

    @Test
    fun commerceTokenClient_noSession_shortCircuits_withoutRequest() = runTest {
        var called = false
        val engine = MockEngine { called = true; respond("{}", HttpStatusCode.OK) }
        val client = CommerceEntitlementTokenClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(engine),
            sessionTokenProvider = { null },
        )

        assertNull(client.fetchToken(ownerId = "owner-1", email = "alice@example.com"))
        assertEquals(false, called, "no session → no request to the gated endpoint")
    }

    @Test
    fun commerceTokenClient_401_isNull() = runTest {
        val engine = MockEngine { respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized) }
        val client = CommerceEntitlementTokenClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(engine),
            sessionTokenProvider = { "stale-session" },
        )

        assertNull(client.fetchToken(ownerId = "owner-1", email = null))
    }

    // ── CachingEntitlementTokenProvider ─────────────────────────────────────

    @Test
    fun cachingProvider_nullClient_isDisabled() = runTest {
        val provider = CachingEntitlementTokenProvider(
            client = null,
            ownerIdProvider = { "owner-1" },
            emailProvider = { "alice@example.com" },
            nowEpochSec = { 0L },
        )
        assertNull(provider.token())
    }

    @Test
    fun cachingProvider_cachesWithinTtl_refetchesAfterExpiry() = runTest {
        var now = 1_000L
        val fake = CountingTokenClient { tokenWithExp(expSec = now + 1_000L) }
        val provider = CachingEntitlementTokenProvider(
            client = fake,
            ownerIdProvider = { "owner-1" },
            emailProvider = { "alice@example.com" },
            nowEpochSec = { now },
            refreshSkewSec = 100L,
        )

        provider.token()
        provider.token()
        assertEquals(1, fake.calls, "a second call within TTL must serve the cache, not refetch")

        // Advance past (exp - skew) → the cached token is now refresh-due.
        now += 1_000L
        provider.token()
        assertEquals(2, fake.calls, "a call past the refresh skew must refetch")
    }

    @Test
    fun cachingProvider_refetchesOnOwnerChange() = runTest {
        var owner = "owner-1"
        val fake = CountingTokenClient { tokenWithExp(expSec = 10_000L, ownerId = owner) }
        val provider = CachingEntitlementTokenProvider(
            client = fake,
            ownerIdProvider = { owner },
            emailProvider = { "alice@example.com" },
            nowEpochSec = { 0L },
        )

        provider.token()
        owner = "owner-2"
        provider.token()
        assertEquals(2, fake.calls, "a different owner must discard the cached token and refetch")
    }

    @Test
    fun cachingProvider_keepsCachedTokenOnFetchFailure() = runTest {
        var now = 0L
        var failNext = false
        val fake = CountingTokenClient { if (failNext) null else tokenWithExp(expSec = 10_000L) }
        val provider = CachingEntitlementTokenProvider(
            client = fake,
            ownerIdProvider = { "owner-1" },
            emailProvider = { "alice@example.com" },
            nowEpochSec = { now },
            refreshSkewSec = 100L,
        )

        val first = provider.token()
        assertEquals(true, first != null)

        // Past the refresh skew (so a refetch is attempted) but before exp, and the fetch fails:
        // the still-valid cached token is kept rather than dropped.
        now = 9_950L
        failNext = true
        assertEquals(first, provider.token(), "a still-valid token survives a failed refresh")
    }

    @Test
    fun cachingProvider_returnsNull_whenNoOwner() = runTest {
        val fake = CountingTokenClient { tokenWithExp(expSec = 10_000L) }
        val provider = CachingEntitlementTokenProvider(
            client = fake,
            ownerIdProvider = { null },
            emailProvider = { "alice@example.com" },
            nowEpochSec = { 0L },
        )
        assertNull(provider.token())
        assertEquals(0, fake.calls, "no owner → no fetch")
    }

    /**
     * ⚠⚠ A token is a 72-hour snapshot of the tier and the cache is keyed on owner + expiry alone,
     * so nothing here used to notice a tier change: after an upgrade the device kept presenting a
     * token saying `free` for up to three days — metered, and shown on the gauge, at the tier the
     * user had just stopped being on. The synced record moves first (it arrives by sync, needing no
     * commerce session), so it is the available signal that the cached token predates something.
     */
    @Test
    fun cachingProvider_refetchesWhenTheSyncedRecordChangesTier() = runTest {
        var recordTier = "tenshu.drive.free"
        val fake = CountingTokenClient { tokenWithExp(expSec = 100_000L) }
        val provider = CachingEntitlementTokenProvider(
            client = fake,
            ownerIdProvider = { "owner-1" },
            emailProvider = { "alice@example.com" },
            nowEpochSec = { 1_000L },
            recordTierProvider = { recordTier },
        )

        provider.token()
        provider.token()
        assertEquals(1, fake.calls, "precondition: a long-lived token is served from cache")

        recordTier = "tenshu.drive.premium" // the upgrade syncs in
        provider.token()

        assertEquals(2, fake.calls, "a tier change must invalidate a token minted before it")
    }

    /**
     * ⚠⚠ The loop hazard, and the reason the comparison is against the last *reconciled* record
     * tier rather than the tier the cached token asserts.
     *
     * Those two differ exactly when commerce keeps minting a tier the record disagrees with — the
     * state a missing account binding produces, and one we were actually in on 2026-08-08. Compare
     * against the token and every single write re-fetches, hammering `/token` indefinitely for a
     * disagreement no refetch can resolve.
     */
    @Test
    fun aPersistentTierDisagreement_costsOneRefetch_notOnePerCall() = runTest {
        // The record says premium; commerce keeps minting something else. The mismatch never heals.
        val fake = CountingTokenClient { tokenWithExp(expSec = 100_000L) }
        val provider = CachingEntitlementTokenProvider(
            client = fake,
            ownerIdProvider = { "owner-1" },
            emailProvider = { "alice@example.com" },
            nowEpochSec = { 1_000L },
            recordTierProvider = { "tenshu.drive.premium" },
        )

        repeat(5) { provider.token() }

        assertEquals(1, fake.calls, "an unresolvable disagreement must settle, not refetch forever")
    }

    /**
     * ⚠ A stale-tier token is still a credential the relay accepts — it merely meters at the old
     * figure. Dropping it during a commerce outage would leave the device write-dead on `402`,
     * which is strictly worse, so the tier check bypasses the cache *hit* without discarding the
     * cached value the failure path falls back to.
     */
    @Test
    fun tierChangedButCommerceUnreachable_keepsPresentingTheStaleToken() = runTest {
        var recordTier = "tenshu.drive.free"
        var reachable = true
        val good = tokenWithExp(expSec = 100_000L)
        val fake = CountingTokenClient { if (reachable) good else null }
        val provider = CachingEntitlementTokenProvider(
            client = fake,
            ownerIdProvider = { "owner-1" },
            emailProvider = { "alice@example.com" },
            nowEpochSec = { 1_000L },
            recordTierProvider = { recordTier },
        )
        assertEquals(good, provider.token())

        recordTier = "tenshu.drive.premium"
        reachable = false

        assertEquals(
            good,
            provider.token(),
            "an outage must not turn a tier refresh into having no credential at all",
        )
    }

    /** ⚠ No record visible (the default) ⇒ the cache behaves exactly as it always has. */
    @Test
    fun noRecordTierAvailable_leavesCachingUnchanged() = runTest {
        val fake = CountingTokenClient { tokenWithExp(expSec = 100_000L) }
        val provider = CachingEntitlementTokenProvider(
            client = fake,
            ownerIdProvider = { "owner-1" },
            emailProvider = { "alice@example.com" },
            nowEpochSec = { 1_000L },
        )

        repeat(3) { provider.token() }

        assertEquals(1, fake.calls)
    }

    private class CountingTokenClient(private val supply: () -> String?) : EntitlementTokenClient {
        var calls = 0
            private set

        override suspend fun fetchToken(ownerId: String, email: String?): String? {
            calls++
            return supply()
        }
    }

    private fun httpClientFor(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }
}
