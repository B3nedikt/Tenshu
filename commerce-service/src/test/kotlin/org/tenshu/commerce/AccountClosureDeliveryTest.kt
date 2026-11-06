package org.tenshu.commerce

import kotlinx.coroutines.test.runTest
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.commerce.store.Rail
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Delivery of closure records to the relay (`retention-quota-model.md` Face A / D4).
 *
 * The behaviour worth pinning is that delivery is **stateless and repeating**: nothing is queued,
 * nothing is remembered, and the store is re-read every sweep.
 */
class AccountClosureDeliveryTest {

    private val day = 24L * 60 * 60 * 1000

    private val config = CommerceConfig.fromEnv { null }
    private val ed = BouncyCastleEd25519Backend()

    private fun fixture(): Pair<InMemoryCommerceStore, AccountClosureMinter> {
        val store = InMemoryCommerceStore()
        return store to AccountClosureMinter(config, IssuerKeypair(config.issuerSeed, ed), store)
    }

    private fun InMemoryCommerceStore.closedAccount(email: String, ownerId: String) {
        val account = createAccount(email, Rail.Free, createdAt = 0)
        bindOwner(account.id, ownerId, boundAt = 0)
        closeAccount(account.id, closedAt = 1_000)
    }

    @Test
    fun `a sweep delivers one record per closed account`() = runTest {
        val (store, minter) = fixture()
        store.closedAccount("a@example.com", "owner-a")
        store.closedAccount("b@example.com", "owner-b")
        val posted = mutableListOf<String>()

        val report = AccountClosureDelivery(minter) { posted += it; 202 }.deliverOnce(nowMs = day)

        assertEquals(2, report.attempted)
        assertEquals(2, report.delivered)
        assertEquals(2, posted.size)
    }

    @Test
    fun `an open account is never posted`() = testSweep { store, delivery ->
        val account = store.createAccount("a@example.com", Rail.Free, createdAt = 0)
        store.bindOwner(account.id, "owner-a", boundAt = 0)

        assertEquals(0, delivery.deliverOnce(nowMs = day).attempted)
    }

    @Test
    fun `one failed delivery does not abandon the rest of the sweep`() = runTest {
        val (store, minter) = fixture()
        store.closedAccount("a@example.com", "owner-a")
        store.closedAccount("b@example.com", "owner-b")
        var first = true

        // ⚠ The accounts are independent; an unreachable relay must not starve whichever owner
        // happens to sort first, and a thrown transport error must not escape the sweep.
        val report = AccountClosureDelivery(minter) {
            if (first) { first = false; error("connection refused") } else 202
        }.deliverOnce(nowMs = day)

        assertEquals(2, report.attempted)
        assertEquals(1, report.delivered)
        assertEquals("1 delivered (1×202 within grace), 1 failed (1×no response)", report.summary())
    }

    @Test
    fun `a failed delivery is simply re-minted next sweep - there is no queue`() = runTest {
        val (store, minter) = fixture()
        store.closedAccount("a@example.com", "owner-a")
        var accept = false
        val delivery = AccountClosureDelivery(minter) { if (accept) 202 else 401 }

        assertEquals(0, delivery.deliverOnce(nowMs = day).delivered)
        accept = true

        // The store is the durable truth; the previous failure needed no bookkeeping at all.
        assertEquals(1, delivery.deliverOnce(nowMs = 2 * day).delivered)
    }

    @Test
    fun `reopening an account stops delivery by itself`() = runTest {
        val (store, minter) = fixture()
        val account = store.createAccount("a@example.com", Rail.Free, createdAt = 0)
        store.bindOwner(account.id, "owner-a", boundAt = 0)
        store.closeAccount(account.id, closedAt = 1_000)
        val delivery = AccountClosureDelivery(minter) { 202 }
        assertTrue(delivery.deliverOnce(nowMs = day).delivered == 1)

        store.reopenAccount(account.id)

        // ⚠⚠ Nothing has to be *un*-said to the relay: the sweep stops finding the account, no
        // fresh record is minted, and the last one expires on its own.
        assertEquals(0, delivery.deliverOnce(nowMs = 2 * day).attempted)
    }

    @Test
    fun `the sweep report counts outcomes by status so a rejection storm is visible`() = runTest {
        val (store, minter) = fixture()
        listOf("a", "b", "c", "d").forEach { store.closedAccount("$it@example.com", "owner-$it") }
        val statuses = ArrayDeque(listOf(202, 200, 401, 401))

        val report = AccountClosureDelivery(minter) { statuses.removeFirst() }.deliverOnce(nowMs = day)

        // ⚠ A 401 repeats every sweep until an operator acts (signature, key id, clock) — it must not
        // read like an outage that heals itself. And the line carries counts only: no owner, no record.
        assertEquals(2, report.delivered)
        assertEquals("2 delivered (1×200 reclaimed, 1×202 within grace), 2 failed (2×401)", report.summary())
        assertTrue("owner-" !in report.summary())
    }

    @Test
    fun `a sweep with nothing accepted still says why`() {
        val report = AccountClosureDelivery.Report(attempted = 3, byStatus = mapOf(401 to 2, null to 1))

        assertEquals(0, report.delivered)
        assertEquals("0 delivered, 3 failed (2×401, 1×no response)", report.summary())
    }

    private fun testSweep(body: suspend (InMemoryCommerceStore, AccountClosureDelivery) -> Unit) = runTest {
        val (store, minter) = fixture()
        body(store, AccountClosureDelivery(minter) { 202 })
    }
}
