package org.tenshu.sync.relay.auth

/**
 * Per-client budget for **failed** authentications, so a client that cannot authenticate stops
 * costing the relay a signature verification per request.
 *
 * ## Why this exists, and what it is not
 *
 * ⚠⚠ **Client-side backoff is not a control.** `RejectionBackoff` in `:sync-service-proxy` bounds a
 * *cooperating* client that has lost its registration — measured 2026-08-18, one that stormed
 * `POST /sync/log` 41×/min for 31 hours. An attacker does not run our client and will not back off,
 * so the relay cannot rely on that for anything. This class is the server-side half.
 *
 * ⚠ It is also **not** a defence against volumetric floods. A rejected request still costs a TCP
 * connection, a TLS session and a Caddy round trip; only a CDN/L3-L4 layer bounds that, which is a
 * deliberately open decision (`deploy-relay-digitalocean.md` §"DDoS & abuse resistance", item 1,
 * because terminating TLS at a third party hands it device JWTs and metadata). What this bounds is
 * the *asymmetric* part: Ed25519 verification is orders of magnitude more expensive than parsing a
 * header, so an unauthenticated peer could make the relay do real cryptographic work for free.
 * Past the budget the answer is produced without touching the crypto.
 *
 * ## Why the response stays `401`
 *
 * ⚠⚠ A `429` here would be actively harmful, and this is the trap worth remembering: the client
 * classifies a relay rejection by looking for a `401` (`isRelayUnauthorized`, and the marker
 * `RestSyncRelay` attaches to it). That classification is what arms its backoff, drives the
 * "this device isn't recognised" state and makes self-repair reachable. Answering `429` would make
 * every one of those go silent — i.e. throttling the storm would *re-create* the storm it exists to
 * stop, and the client would report a network outage instead of an identity problem. So the status
 * is unchanged and honest — the device really is unauthorized — and only the *cost* of saying so
 * changes. `Retry-After` is added as the advisory signal.
 *
 * ## Keying, and its known limit
 *
 * Keyed on the client IP, like the genesis limiter beside it, and for the same reason: behind Caddy
 * every request shares the proxy's socket address, so `XForwardedHeaders` must be installed for the
 * key to mean anything (otherwise the bucket is global and one abuser throttles everybody).
 *
 * ⚠ **NAT is the known weakness.** Devices in one household share a public IP, so a broken device
 * can spend the budget its neighbours would have used — they would then be answered `401` without
 * verification even though their token is good. That is why the default budget is deliberately
 * generous (a real fleet in the worst state measured produces a few hundred failures an hour, not
 * thousands) and why only *failures* consume it: a healthy device authenticates successfully and
 * never touches the bucket. A finer key — the token's claimed `(iss, sub)`, which is readable
 * without verifying anything — would isolate devices behind one NAT, but is only meaningful stacked
 * *under* an IP bucket, since a forger can mint claims freely. Left as future work rather than
 * guessed at.
 *
 * ⚠ Unauthenticated routes are untouched: this lives inside [JwtAuthenticator], so pairing, offer,
 * genesis and recovery stay reachable at full rate. That is load-bearing, not incidental — a device
 * being throttled here is exactly a device that needs to re-pair, and gating its way back in would
 * make the state unrecoverable.
 *
 * Token bucket with lazy refill: [limitPerHour] tokens, refilled continuously, one spent per
 * recorded failure. Not a sliding window — the refill is what bounds sustained rate, and the full
 * bucket absorbs a legitimate burst (a fleet restarting after a relay wipe) without tripping.
 */
class AuthFailureLimiter(
    private val limitPerHour: Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxTrackedKeys: Int = DEFAULT_MAX_TRACKED_KEYS,
) {

    private class Bucket(var tokens: Double, var lastRefillMs: Long)

    // Access-ordered so eviction drops the least-recently-touched key. Bounded because the key is
    // attacker-chosen: rotating source IPs would otherwise grow this map without limit, turning a
    // memory-safe rejection path into a memory exhaustion one.
    private val buckets = object : LinkedHashMap<String, Bucket>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bucket>): Boolean =
            size > maxTrackedKeys
    }

    /** Whether the limiter does anything at all; `limitPerHour <= 0` disables it entirely. */
    val enabled: Boolean get() = limitPerHour > 0

    /**
     * True when [key] has spent its budget — the caller should answer without verifying anything.
     * Read-only: it does not consume, so a client that stops failing recovers purely by the refill.
     */
    fun isOverBudget(key: String): Boolean {
        if (!enabled) return false
        synchronized(buckets) {
            val bucket = buckets[key] ?: return false
            refill(bucket)
            return bucket.tokens < 1.0
        }
    }

    /** Record one failed authentication against [key]. */
    fun recordFailure(key: String) {
        if (!enabled) return
        synchronized(buckets) {
            val bucket = buckets.getOrPut(key) {
                Bucket(tokens = limitPerHour.toDouble(), lastRefillMs = nowMs())
            }
            refill(bucket)
            if (bucket.tokens > 0.0) bucket.tokens -= 1.0
        }
    }

    private fun refill(bucket: Bucket) {
        val now = nowMs()
        val elapsedMs = now - bucket.lastRefillMs
        if (elapsedMs <= 0) return
        bucket.lastRefillMs = now
        val refilled = limitPerHour.toDouble() * elapsedMs / MILLIS_PER_HOUR
        bucket.tokens = minOf(limitPerHour.toDouble(), bucket.tokens + refilled)
    }

    companion object {
        private const val MILLIS_PER_HOUR = 3_600_000.0

        /**
         * Default per-IP hourly budget for failed authentications.
         *
         * ⚠ Sized against the worst real state measured, not against an attacker: on 2026-08-18 two
         * devices behind one NAT that had lost their registrations produced together roughly 370
         * failures an hour, and that was *with* the client backoff broken. 600 leaves headroom for a
         * larger household in that state while still cutting an unbounded abuser by orders of
         * magnitude — a flood is thousands per second, not hundreds per hour.
         */
        const val DEFAULT_AUTH_FAILURE_LIMIT_PER_HOUR: Int = 600

        /** Bounds the tracked-key map; see the eviction note above. */
        const val DEFAULT_MAX_TRACKED_KEYS: Int = 10_000
    }
}
