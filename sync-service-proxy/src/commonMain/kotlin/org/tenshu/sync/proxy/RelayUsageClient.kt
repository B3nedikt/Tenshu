package org.tenshu.sync.proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reads the authenticated owner's **content-plane usage** from the relay
 * (`docs/proposals/retention-quota-model.md` §5 / D5, P1): a single authed
 * `GET /sync/usage` returning `{ "usedBytes": <long> }` for the caller's own
 * `ownerId`. This is the "one missing wire" that turns the `StorageStatus`
 * gauge's `usedBytes` from the mock zero (`EntitlementStorageProvider`) into a
 * real fill level.
 *
 * **Best-effort, never throws.** Usage is approximate / eventually-consistent
 * and orthogonal to the sync hot path, so every failure mode — no realm-bound
 * owner yet (no JWT to mint), a non-2xx, a transport error, or a malformed body
 * — collapses to `null`. The caller (`initEntitlementStorageReader`) keeps the
 * last-known usage and simply skips the update; nothing user-facing breaks when
 * the relay is unreachable.
 *
 * Same JWT seam as [RestContentBlobStore]: the device-key `authTokenProvider()`
 * → `Authorization: Bearer <jwt>`; the relay resolves `ownerId` from that token
 * and scopes the count strictly to the caller (no cross-owner peek). House Rule
 * #1 intact — `usedBytes` is the byte COUNT the relay already keeps for the
 * per-owner quota gate, not anything derived from decrypting blob contents.
 */
/**
 * What the relay reports about this owner's content plane.
 *
 * ⚠ [limitBytes] is **null when unknown**, never `0`. The relay answers null when it cannot
 * determine the tier — no credential presented, or one it cannot verify — and "I could not
 * determine your tier" must not be rendered as "you have no storage". A caller that collapses the
 * two will show an alarming and wrong `0 B of 0 B` to a paying user whose credential simply had
 * not loaded yet.
 */
data class RelayUsage(val usedBytes: Long, val limitBytes: Long?)

class RelayUsageClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val authTokenProvider: suspend () -> String?,
    // `entitlement-trust-anchor-distribution.md` P1b: the SAME credential pair the write paths
    // present (`RestSyncRelay` / `RestContentBlobStore`). The relay keeps no entitlement state of
    // its own, so it can only report the limit that the credential the caller presents grants.
    // ⚠ Both default to `{ null }` — a caller that presents nothing still gets `usedBytes`, and
    // `limitBytes` is simply unknown. Reading usage must never require an entitlement.
    private val entitlementTokenProvider: suspend () -> String? = { null },
    private val entitlementProofProvider: suspend () -> String? = { null },
) {

    /**
     * Fetch the owner's total stored content bytes, or `null` on any failure
     * (unauthenticated / offline / unexpected response). Returning `null` rather
     * than throwing keeps usage a soft, self-healing signal.
     */
    suspend fun fetchUsage(): RelayUsage? {
        return try {
            // No token → no realm-bound owner yet; don't even make the call.
            val token = authTokenProvider() ?: return null
            val response: HttpResponse = httpClient.get("$baseUrl/sync/usage") {
                header("Authorization", "Bearer $token")
                // P1b: the relay keeps no entitlement state, so it can only report the limit the
                // credential we present grants — the same pair the write paths already send.
                entitlementTokenProvider()?.let { header("X-Entitlement", it) }
                entitlementProofProvider()?.let { header("X-Entitlement-Proof", it) }
            }
            if (!response.status.isSuccess()) return null
            val body = JSON.decodeFromString(UsageResponse.serializer(), response.bodyAsText())
            RelayUsage(usedBytes = body.usedBytes, limitBytes = body.limitBytes)
        } catch (_: Exception) {
            null
        }
    }

    @Serializable
    private data class UsageResponse(val usedBytes: Long, val limitBytes: Long? = null)

    private companion object {
        // Tolerant parse: additive fields on the relay's reply must never break an
        // older client (the reply is not a positional composite — plain JSON).
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
