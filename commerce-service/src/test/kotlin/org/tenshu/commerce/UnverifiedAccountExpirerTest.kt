package org.tenshu.commerce

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.commerce.store.Rail

class UnverifiedAccountExpirerTest {

    @Test
    fun unverifiedAccountsOlderThanThresholdAreDeleted_whileVerifiedOrRecentAreKept() = runTest {
        val store = InMemoryCommerceStore()
        val now = 100_000_000L
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000L

        // Old unverified (created 31 days ago)
        val oldUnverified = store.createAccount("old@example.com", Rail.Free, now - thirtyDaysMs - 1000L)
        // Recent unverified (created 1 day ago)
        val recentUnverified = store.createAccount("recent@example.com", Rail.Free, now - 1000L)
        // Old verified (created 31 days ago, verified)
        val oldVerified = store.createAccount("verified@example.com", Rail.Free, now - thirtyDaysMs - 1000L)
        store.markEmailVerified(oldVerified.id, now - thirtyDaysMs)

        val expirer = UnverifiedAccountExpirer(store, maxAgeMs = thirtyDaysMs) { now }
        val expiredCount = expirer.sweepOnce()

        assertEquals(1, expiredCount)
        assertNull(store.findAccountByEmail("old@example.com"))
        assertNotNull(store.findAccountByEmail("recent@example.com"))
        assertNotNull(store.findAccountByEmail("verified@example.com"))
    }
}
