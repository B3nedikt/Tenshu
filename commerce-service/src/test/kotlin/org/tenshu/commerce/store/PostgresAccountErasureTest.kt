package org.tenshu.commerce.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The erasure SQL against a **real Postgres** (`PostgresCommerceStore` is otherwise verified only by
 * hand — its doc says so). Opt-in: runs only when `COMMERCE_TEST_DATABASE_URL` names a throwaway
 * database, e.g.
 *
 * ```
 * docker run -d --rm --name pg -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=commerce -p 127.0.0.1:55432:5432 postgres:16-alpine
 * COMMERCE_TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:55432/commerce?user=postgres&password=pw' \
 *   ./gradlew :commerce-service:test --tests '*PostgresAccountErasure*'
 * ```
 *
 * ⚠ Without the variable every test returns at once and **passes** — a green run of this class
 * proves nothing unless the variable was set. ⚠ Never point it at a database you care about: the
 * test writes accounts into it.
 */
class PostgresAccountErasureTest {

    private val url: String? = System.getenv("COMMERCE_TEST_DATABASE_URL")?.takeIf { it.isNotBlank() }

    private fun withStore(block: (PostgresCommerceStore) -> Unit) {
        val jdbc = url ?: return
        CommerceDatabase(jdbc).use { db -> block(PostgresCommerceStore(db)) }
    }

    private fun unique(prefix: String) = "$prefix-${System.nanoTime()}"

    @Test
    fun erasureRemovesThePersonAndKeepsTheLedger() = withStore { store ->
        val email = "${unique("pg")}@example.com"
        val owner = unique("owner")
        val account = store.createAccount(email, Rail.Free, createdAt = 1_000L, displayName = "Pat")
        store.markEmailVerified(account.id, 1_001L)
        store.bindOwner(account.id, owner, 1_002L)
        store.attachStripeCustomer(account.id, unique("cus"))
        store.createSession("h-${account.id}", account.id, 1_003L, Long.MAX_VALUE)
        store.putEmailVerification(email, "x", Long.MAX_VALUE)
        store.putEmailVerification(ErasedAccounts.deletionOtpKey(email), "y", Long.MAX_VALUE)
        store.setMarketingConsent(MarketingConsent(account.id, granted = true, changedAt = 1_004L))
        store.recordAcceptance(Acceptance(account.id, "v1", "v1", 1_005L, ip = "203.0.113.9"))
        val invoice = store.appendInvoice(
            InvoiceDraft(account.id, unique("in"), 1190, "eur", 190, 1900, "DE", "DE", 1_006L),
        )

        assertTrue(store.eraseAccount(account.id, 2_000L))

        val erased = assertNotNull(store.findAccountById(account.id))
        assertEquals(AccountStatus.Closed, erased.status)
        assertEquals(2_000L, erased.closedAt)
        assertEquals(2_000L, erased.erasedAt)
        assertEquals(ErasedAccounts.tombstoneFor(account.id), erased.emailNormalised)
        assertNull(erased.emailVerifiedAt)
        assertNull(erased.displayName)
        assertNotNull(erased.stripeCustomerId)
        assertNull(store.findAccountByEmail(email))
        assertNull(store.getSession("h-${account.id}"))
        assertNull(store.getEmailVerification(email))
        assertNull(store.getEmailVerification(ErasedAccounts.deletionOtpKey(email)))
        assertEquals(invoice.number, store.findInvoiceByStripeId(invoice.stripeInvoiceId!!)?.number)
        assertEquals(account.id, store.findInvoiceByStripeId(invoice.stripeInvoiceId!!)?.accountId)
        assertTrue(store.closedAccountsWithOwners().any { it.accountId == account.id && it.ownerId == owner })

        // Idempotent, and terminal.
        assertFalse(store.eraseAccount(account.id, 3_000L))
        store.reopenAccount(account.id)
        assertEquals(erased, store.findAccountById(account.id))
    }

    @Test
    fun anEarlierClosureKeepsItsStamp() = withStore { store ->
        val account = store.createAccount("${unique("pg")}@example.com", Rail.Free, createdAt = 1_000L)
        store.closeAccount(account.id, 1_500L)
        assertTrue(store.eraseAccount(account.id, 9_000L))
        assertEquals(1_500L, store.findAccountById(account.id)?.closedAt)
    }

    @Test
    fun aRealmHeldByAnOpenAccountIsNotMintedFor() = withStore { store ->
        val owner = unique("owner")
        val gone = store.createAccount("${unique("pg")}@example.com", Rail.Free, createdAt = 1_000L)
        val back = store.createAccount("${unique("pg")}@example.com", Rail.Free, createdAt = 1_000L)
        store.bindOwner(gone.id, owner, 1_001L)
        store.eraseAccount(gone.id, 2_000L)
        assertTrue(store.closedAccountsWithOwners().any { it.ownerId == owner })

        store.bindOwner(back.id, owner, 3_000L)
        assertTrue(store.closedAccountsWithOwners().none { it.ownerId == owner })
    }
}
