package org.tenshu.sync.relay.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class ClientRateLimiterTest {

    @Test
    fun spendsItsBurstThenLimits_withARetryAfterForTheNextToken() {
        var now = 0L
        val limiter = ClientRateLimiter(limit = 6, periodMs = 60_000L, nowMs = { now })

        repeat(6) { assertEquals(ClientRateLimiter.Decision.Allowed, limiter.tryAcquire("ip"), "attempt ${it + 1}") }
        val limited = assertIs<ClientRateLimiter.Decision.Limited>(limiter.tryAcquire("ip"))
        // 6 a minute refills one token every 10 s.
        assertEquals(10L, limited.retryAfterSeconds)
    }

    @Test
    fun refillsOverTime_soARetryingUserGetsThrough() {
        var now = 0L
        val limiter = ClientRateLimiter(limit = 6, periodMs = 60_000L, nowMs = { now })
        repeat(6) { limiter.tryAcquire("ip") }
        assertIs<ClientRateLimiter.Decision.Limited>(limiter.tryAcquire("ip"))

        now += 9_999
        assertIs<ClientRateLimiter.Decision.Limited>(limiter.tryAcquire("ip"), "not a whole token yet")
        now += 1
        assertEquals(ClientRateLimiter.Decision.Allowed, limiter.tryAcquire("ip"), "one token after 10 s")
        assertIs<ClientRateLimiter.Decision.Limited>(limiter.tryAcquire("ip"), "and only one")

        now += 60_000
        repeat(6) { assertEquals(ClientRateLimiter.Decision.Allowed, limiter.tryAcquire("ip")) }
    }

    @Test
    fun aRejectedAttemptDoesNotSpendAnything() {
        var now = 0L
        val limiter = ClientRateLimiter(limit = 1, periodMs = 60_000L, nowMs = { now })
        limiter.tryAcquire("ip")
        // A flood of refused attempts must not push the next token further away.
        repeat(100) { assertIs<ClientRateLimiter.Decision.Limited>(limiter.tryAcquire("ip")) }
        now += 60_000
        assertEquals(ClientRateLimiter.Decision.Allowed, limiter.tryAcquire("ip"))
    }

    @Test
    fun budgetsAreIndependentPerKey() {
        val limiter = ClientRateLimiter(limit = 2, periodMs = 60_000L, nowMs = { 0L })
        repeat(2) { limiter.tryAcquire("noisy") }

        assertIs<ClientRateLimiter.Decision.Limited>(limiter.tryAcquire("noisy"))
        assertEquals(ClientRateLimiter.Decision.Allowed, limiter.tryAcquire("quiet"))
    }

    @Test
    fun trackedKeysAreBounded_soRotatingSourcesCannotGrowTheMap() {
        val limiter = ClientRateLimiter(limit = 1, periodMs = 60_000L, nowMs = { 0L }, maxTrackedKeys = 8)
        repeat(10_000) { limiter.tryAcquire("ip-$it") }

        assertEquals(8, limiter.trackedKeyCount)
        // LRU: the most recent source is still throttled, the oldest was forgotten.
        assertIs<ClientRateLimiter.Decision.Limited>(limiter.tryAcquire("ip-9999"))
        assertEquals(ClientRateLimiter.Decision.Allowed, limiter.tryAcquire("ip-0"))
    }

    // ---- the key ---------------------------------------------------------------------------------

    @Test
    fun ipv4IsKeyedAsIs() {
        assertEquals("203.0.113.7", clientNetworkKey("203.0.113.7"))
        assertNotEquals(clientNetworkKey("203.0.113.7"), clientNetworkKey("203.0.113.8"))
    }

    @Test
    fun ipv6IsKeyedByItsSlash64_soOneSubscriberCannotRotateAddresses() {
        val a = clientNetworkKey("2001:db8:1:2:aaaa:bbbb:cccc:dddd")
        val b = clientNetworkKey("2001:db8:1:2::1")
        val otherPrefix = clientNetworkKey("2001:db8:1:3::1")

        assertEquals("20010db800010002/64", a)
        assertEquals(a, b)
        assertNotEquals(a, otherPrefix)
        // Bracketed and zoned spellings of the same address land in the same bucket.
        assertEquals(a, clientNetworkKey("[2001:db8:1:2::1]"))
        assertEquals(a, clientNetworkKey("2001:db8:1:2::1%eth0"))
    }

    @Test
    fun anIpv4MappedIpv6AddressIsKeyedAsItsIpv4() {
        assertEquals("203.0.113.7", clientNetworkKey("::ffff:203.0.113.7"))
    }

    @Test
    fun anUnparseableValueIsReturnedUnchanged_andNeverResolved() {
        // `ab:cd` starts with a hex digit: parsed unbracketed, InetAddress would try DNS with it.
        assertEquals("ab:cd", clientNetworkKey("ab:cd"))
        assertEquals("not-an-ip", clientNetworkKey("not-an-ip"))
    }
}
