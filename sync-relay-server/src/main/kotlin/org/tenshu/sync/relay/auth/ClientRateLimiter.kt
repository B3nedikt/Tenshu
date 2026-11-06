package org.tenshu.sync.relay.auth

import java.net.Inet6Address
import java.net.InetAddress
import kotlin.math.ceil

/**
 * Per-client token bucket for an unauthenticated route: [limit] requests, refilled continuously over
 * [periodMs]. First used for `GET /sync/recovery-challenge` (threat model T-RC-08 residual (a)).
 *
 * ## Why not Ktor's `RateLimit` plugin, which the genesis and pairing caps use
 *
 * ⚠ Its registry is not bounded by size: an entry lives until its bucket refills, so a caller
 * rotating source addresses grows it at the request rate, and every entry also carries a coroutine
 * waiting for that refill. The recovery challenge was opened to anyone by F8, which is exactly the
 * route a rotating flood aims at. This map is access-ordered and capped at [maxTrackedKeys], the same
 * shape as [AuthFailureLimiter], so the limiter cannot itself become the memory problem.
 *
 * ⚠ The eviction fails *open* for the evicted key: a source pushed out of the map comes back with a
 * full bucket. That only happens to a caller holding more than [maxTrackedKeys] sources, who already
 * gets `limit × sources` per period without it. Bounding memory is the point; bounding a distributed
 * flood is the CDN decision in `deploy-relay-digitalocean.md` §"DDoS & abuse resistance".
 *
 * ⚠⚠ The key is an IP (or /64) and **must never be logged**: relay logs carry no client addresses
 * (`docs/how-to/server-logging.md`). Nothing in this class logs, and [Decision.Limited]
 * deliberately carries no key.
 */
class ClientRateLimiter(
    private val limit: Int,
    private val periodMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxTrackedKeys: Int = DEFAULT_MAX_TRACKED_KEYS,
) {
    init {
        require(limit > 0) { "limit must be positive; disable the limiter by not constructing it" }
        require(periodMs > 0) { "periodMs must be positive" }
        require(maxTrackedKeys > 0) { "maxTrackedKeys must be positive" }
    }

    sealed interface Decision {
        data object Allowed : Decision

        /** Over budget: answer `429` with `Retry-After: [retryAfterSeconds]`. */
        data class Limited(val retryAfterSeconds: Long) : Decision
    }

    private class Bucket(var tokens: Double, var lastRefillMs: Long)

    private val tokensPerMs: Double = limit.toDouble() / periodMs

    private val buckets = object : LinkedHashMap<String, Bucket>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bucket>): Boolean =
            size > maxTrackedKeys
    }

    /** Spend one token for [key] if it has one. */
    fun tryAcquire(key: String): Decision = synchronized(buckets) {
        val now = nowMs()
        val bucket = buckets.getOrPut(key) { Bucket(tokens = limit.toDouble(), lastRefillMs = now) }
        val elapsedMs = now - bucket.lastRefillMs
        if (elapsedMs > 0) {
            bucket.tokens = minOf(limit.toDouble(), bucket.tokens + elapsedMs * tokensPerMs)
            bucket.lastRefillMs = now
        }
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            Decision.Allowed
        } else {
            val waitMs = (1.0 - bucket.tokens) / tokensPerMs
            Decision.Limited(retryAfterSeconds = maxOf(1L, ceil(waitMs / 1_000.0).toLong()))
        }
    }

    /** How many sources the map holds; for the bound's test only. */
    internal val trackedKeyCount: Int get() = synchronized(buckets) { buckets.size }

    companion object {
        /** Bounds the tracked-key map; see the eviction note above. */
        const val DEFAULT_MAX_TRACKED_KEYS: Int = 10_000
    }
}

/**
 * The bucket key for a client address: an IPv4 address as is, an IPv6 address by its /64 prefix.
 *
 * ⚠ Per-address keying is meaningless for IPv6: one subscriber is routinely handed a whole /64
 * (2^64 addresses), so a single host could mint a fresh bucket per request. A /64 is the smallest
 * unit an ISP assigns to one customer, so it is the IPv6 equivalent of "one IPv4 address".
 * (Caddy's log filter masks to /48 for a different reason — anonymity, not identity.)
 *
 * ⚠ Never resolves a name: only a string containing `:` is parsed, and it is parsed **bracketed**,
 * which makes `InetAddress` treat it as an IPv6 literal and throw on a bad one instead of falling
 * through to DNS (an unbracketed `ab:cd` starts with a hex digit and would be looked up). Anything
 * unparseable is returned unchanged, which is no worse than keying on it.
 */
fun clientNetworkKey(address: String): String {
    if (':' !in address) return address
    val literal = address.removePrefix("[").substringBefore(']').substringBefore('%')
    val parsed = try {
        InetAddress.getByName("[$literal]")
    } catch (_: Exception) {
        return address
    }
    if (parsed !is Inet6Address) return parsed.hostAddress // an IPv4-mapped address
    return parsed.address.copyOfRange(0, 8).joinToString(separator = "", postfix = "/64") { "%02x".format(it) }
}
