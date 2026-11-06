package org.tenshu.sync.relay.mailbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryChallengeStoreTest {

    @Test
    fun issueAndConsume_validChallenge_succeeds() {
        val store = RecoveryChallengeStore()
        val challenge = store.issue()!!
        assertTrue(store.consume(challenge))
    }

    @Test
    fun consume_isOneShot() {
        val store = RecoveryChallengeStore()
        val challenge = store.issue()!!
        assertTrue(store.consume(challenge))
        // Replay attempt — already consumed.
        assertFalse(store.consume(challenge))
    }

    @Test
    fun consume_unknownChallenge_returnsFalse() {
        val store = RecoveryChallengeStore()
        store.issue()
        assertFalse(store.consume(byteArrayOf(9, 9, 9)))
    }

    @Test
    fun expiredChallenge_returnsFalse() {
        var now = 1000L
        val store = RecoveryChallengeStore(
            ttlMillis = 5_000L,
            nowEpochMillis = { now }
        )
        val challenge = store.issue()!!
        now = 10_000L  // > issue + ttl
        assertFalse(store.consume(challenge))
    }

    @Test
    fun multipleChallenges_allValidIndependently() {
        val store = RecoveryChallengeStore()
        val a = store.issue()!!
        val b = store.issue()!!
        assertNotEquals(a.toList(), b.toList())
        assertTrue(store.consume(a))
        assertTrue(store.consume(b))
    }

    @Test
    fun issuedChallenge_hasExpectedSize() {
        val store = RecoveryChallengeStore(challengeBytes = 32)
        assertEquals(32, store.issue()!!.size)
    }

    /** F8: issue is unconditional now, so the outstanding set is bounded — and frees on expiry. */
    @Test
    fun fullStore_refuses_untilAnEntryExpires() {
        var now = 1000L
        val store = RecoveryChallengeStore(ttlMillis = 5_000L, maxOutstanding = 2, nowEpochMillis = { now })
        assertNotNull(store.issue())
        assertNotNull(store.issue())
        assertNull(store.issue(), "a full store refuses rather than growing")
        now = 10_000L
        assertNotNull(store.issue(), "expired entries are pruned to make room")
        assertEquals(1, store.size())
    }
}
