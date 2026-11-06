package org.tenshu.commerce.store

import org.tenshu.commerce.ProductCatalog
import org.tenshu.commerce.allCreditNotes
import org.tenshu.commerce.allInvoices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behavioural pins for the in-memory store — the source of truth the Postgres impl mirrors. */
class InMemoryCommerceStoreTest {

    private val store = InMemoryCommerceStore()

    private fun account(email: String = "a@b.c"): Account =
        store.createAccount(email, rail = Rail.Free, createdAt = 100L)

    @Test
    fun bindOwner_supersedesThePriorLiveBinding() {
        val acct = account()
        store.bindOwner(acct.id, "owner-1", boundAt = 1L)
        assertEquals("owner-1", store.liveOwner(acct.id))
        assertEquals(acct.id, store.accountIdForOwner("owner-1"))

        store.bindOwner(acct.id, "owner-2", boundAt = 2L)
        assertEquals("owner-2", store.liveOwner(acct.id))
        assertEquals(acct.id, store.accountIdForOwner("owner-2"))
        assertNull(store.accountIdForOwner("owner-1"), "the prior owner is superseded")
    }

    @Test
    fun emailVerification_tracksAttempts_andClears() {
        store.putEmailVerification("a@b.c", codeHash = "h", expiresAt = 999L)
        store.incrementVerificationAttempts("a@b.c")
        store.incrementVerificationAttempts("a@b.c")
        assertEquals(2, store.getEmailVerification("a@b.c")?.attempts)

        store.deleteEmailVerification("a@b.c")
        assertNull(store.getEmailVerification("a@b.c"))
    }

    @Test
    fun session_validityFollowsExpiry() {
        store.createSession("hash", accountId = "acct", createdAt = 0L, expiresAt = 1000L)
        val session = store.getSession("hash")
        assertNotNull(session)
        assertTrue(session.isValid(999L))
        assertFalse(session.isValid(1001L))
    }

    @Test
    fun redemptionCode_useCountGatesRedeemability() {
        store.putRedemptionCode(RedemptionCode("h", productId = "p", maxUses = 2, usedCount = 0))
        assertTrue(store.getRedemptionCode("h")!!.isRedeemable(0L))
        store.incrementRedemptionUse("h")
        assertTrue(store.getRedemptionCode("h")!!.isRedeemable(0L))
        store.incrementRedemptionUse("h")
        assertFalse(store.getRedemptionCode("h")!!.isRedeemable(0L), "maxUses reached")
    }

    @Test
    fun currentEntitlement_picksAValidRowAndIgnoresExpired() {
        val acct = account()
        store.appendEntitlementHistory(
            EntitlementHistory(acct.id, "expired", validFrom = 0L, validUntil = 50L, source = EntitlementSource.Free, issuedAt = 0L),
        )
        assertNull(store.currentEntitlement(acct.id, nowMs = 100L), "only an expired row → none current")

        store.appendEntitlementHistory(
            EntitlementHistory(acct.id, "live", validFrom = 60L, validUntil = 200L, source = EntitlementSource.Free, issuedAt = 60L),
        )
        assertEquals("live", store.currentEntitlement(acct.id, nowMs = 100L)?.productId)
    }

    /**
     * ⚠⚠ A binding is superseded per ACCOUNT, never per owner — so one realm is live-bound to every
     * account it ever signed in with, and [CommerceStore.accountIdForOwner] names only one of them.
     */
    @Test
    fun accountIdsForOwner_namesEveryAccountTheRealmIsBoundTo() {
        val first = account("a@x.com")
        val second = account("b@x.com")
        val elsewhere = account("c@x.com")
        store.bindOwner(first.id, "owner-1", boundAt = 1L)
        store.bindOwner(second.id, "owner-1", boundAt = 2L)
        store.bindOwner(elsewhere.id, "owner-1", boundAt = 3L)
        store.bindOwner(elsewhere.id, "owner-2", boundAt = 4L) // moved away: no longer this realm's

        assertEquals(setOf(first.id, second.id), store.accountIdsForOwner("owner-1").toSet())
        assertEquals(listOf(elsewhere.id), store.accountIdsForOwner("owner-2"))
    }

    @Test
    fun revokePaidEntitlements_endsStripeGrants_andLeavesTheRest() {
        val acct = account()
        fun grant(product: String, source: EntitlementSource, until: Long) = store.appendEntitlementHistory(
            EntitlementHistory(acct.id, product, validFrom = 0L, validUntil = until, source = source, issuedAt = 0L),
        )
        grant(ProductCatalog.FREE, EntitlementSource.Free, until = 10_000L)
        grant(ProductCatalog.PREMIUM, EntitlementSource.Stripe, until = 5_000L)
        grant(ProductCatalog.BASIC, EntitlementSource.Stripe, until = 50L) // already over: untouched

        assertEquals(1, store.revokePaidEntitlements(acct.id, revokedAt = 100L))

        assertEquals(ProductCatalog.FREE, store.currentEntitlement(acct.id, nowMs = 100L)?.productId)
        // It was valid right up to the moment it was ended.
        assertEquals(ProductCatalog.PREMIUM, store.currentEntitlement(acct.id, nowMs = 99L)?.productId)
        assertEquals(0, store.revokePaidEntitlements(acct.id, revokedAt = 100L), "idempotent")
    }

    // --- WC-1: the money-out ledger ------------------------------------------------------------

    private fun creditNoteDraft(referenceId: String, amount: Long = 100L) = CreditNoteDraft(
        accountId = "acct-1",
        kind = CreditNoteKind.Refund,
        stripeReferenceId = referenceId,
        referencesInvoiceNumber = null,
        amountMinor = amount,
        currency = "eur",
        vatAmountMinor = null,
        vatRateBasisPoints = null,
        billingCountry = null,
        issuedAt = 1L,
    )

    @Test
    fun appendCreditNote_assignsItsOwnSequence_separateFromInvoices() {
        store.appendInvoice(
            InvoiceDraft(
                accountId = "acct-1", stripeInvoiceId = "in_1", amountMinor = 1190, currency = "eur",
                vatAmountMinor = 190, vatRateBasisPoints = 1900, billingCountry = "DE",
                ipCountry = null, issuedAt = 1L,
            ),
        )
        // A credit note starts at 1 in its own series — mixing it into the invoice sequence would
        // put gaps in the invoice numbering, which statutory numbering does not want (Q4).
        assertEquals(1L, store.appendCreditNote(creditNoteDraft("re_1"))?.number)
        assertEquals(2L, store.appendCreditNote(creditNoteDraft("re_2"))?.number)
        assertEquals(1L, store.allInvoices().single().number)
    }

    @Test
    fun appendCreditNote_isIdempotentOnTheStripeReferenceId() {
        assertNotNull(store.appendCreditNote(creditNoteDraft("re_1", amount = 500)))
        // ⚠ Null, not a second row: re-seeing a refund must not credit it twice, and the caller
        // distinguishes "credited" from "already had it" by exactly this.
        assertNull(store.appendCreditNote(creditNoteDraft("re_1", amount = 500)))
        assertEquals(1, store.allCreditNotes().size)
    }

    @Test
    fun findInvoiceByStripeId_findsTheWrittenRow_andIsNullForAnUnknownId() {
        store.appendInvoice(
            InvoiceDraft(
                accountId = "acct-1", stripeInvoiceId = "in_7", amountMinor = 500, currency = "eur",
                vatAmountMinor = 80, vatRateBasisPoints = 1900, billingCountry = "AT",
                ipCountry = null, issuedAt = 1L,
            ),
        )
        assertEquals("AT", store.findInvoiceByStripeId("in_7")?.billingCountry)
        assertNull(store.findInvoiceByStripeId("in_nope"))
    }

    // --- WC-2: the read primitives -------------------------------------------------------------

    private fun invoiceAt(issuedAt: Long, stripeId: String) = store.appendInvoice(
        InvoiceDraft(
            accountId = "acct-1", stripeInvoiceId = stripeId, amountMinor = 1190, currency = "eur",
            vatAmountMinor = 190, vatRateBasisPoints = 1900, billingCountry = "DE",
            ipCountry = null, issuedAt = issuedAt,
        ),
    )

    /**
     * ⚠⚠ The boundary rule a VAT return depends on. Adjacent filing periods share an instant; if the
     * range were closed on both ends a midnight row would land in **two** quarters. Half-open makes
     * consecutive windows tile the timeline exactly — nothing counted twice, nothing skipped.
     */
    @Test
    fun listInvoices_isHalfOpen_soAdjacentPeriodsTileWithoutDoubleCounting() {
        invoiceAt(100L, "in_before")
        invoiceAt(200L, "in_boundary")
        invoiceAt(300L, "in_after")

        val firstPeriod = store.allInvoices().filter { it.issuedAt < 200L }
        assertEquals(listOf("in_before"), firstPeriod.map { it.stripeInvoiceId })

        assertEquals(
            listOf("in_before"),
            store.listInvoices(0L, 200L).map { it.stripeInvoiceId },
            "the row exactly at `to` belongs to the NEXT period",
        )
        assertEquals(
            listOf("in_boundary", "in_after"),
            store.listInvoices(200L, 400L).map { it.stripeInvoiceId },
            "…and is included by `from`",
        )
        // Tiling: the two windows together are the whole ledger, each row exactly once.
        assertEquals(3, store.listInvoices(0L, 200L).size + store.listInvoices(200L, 400L).size)
    }

    @Test
    fun listInvoices_ordersByStatutoryNumber_notByTimestamp() {
        // Two rows sharing an instant: the sequence is what orders them, and it is deterministic.
        val first = invoiceAt(500L, "in_a")
        val second = invoiceAt(500L, "in_b")
        assertEquals(listOf(first.number, second.number), store.listInvoices(0L, 1000L).map { it.number })
    }

    @Test
    fun listCreditNotes_windowsTheMoneyOutSideTheSameWay() {
        store.appendCreditNote(creditNoteDraft("re_early").copy(issuedAt = 100L))
        store.appendCreditNote(creditNoteDraft("re_late").copy(issuedAt = 300L))

        assertEquals(listOf("re_early"), store.listCreditNotes(0L, 200L).map { it.stripeReferenceId })
        assertEquals(listOf("re_late"), store.listCreditNotes(200L, 400L).map { it.stripeReferenceId })
    }

    @Test
    fun listEntitlementHistory_returnsEveryRowOldestFirst_notJustTheWinner() {
        val acct = account("hist@x.com")
        store.appendEntitlementHistory(
            EntitlementHistory(acct.id, "free", validFrom = 0L, validUntil = 9_000L, source = EntitlementSource.Free, issuedAt = 10L),
        )
        store.appendEntitlementHistory(
            EntitlementHistory(acct.id, "premium", validFrom = 0L, validUntil = 5_000L, source = EntitlementSource.Stripe, issuedAt = 20L),
        )
        store.appendEntitlementHistory(
            EntitlementHistory("other-account", "premium", validFrom = 0L, validUntil = 5_000L, source = EntitlementSource.Stripe, issuedAt = 30L),
        )

        // ⚠ `currentEntitlement` deliberately returns ONE winner; the operator view needs all of them.
        assertEquals(listOf("free", "premium"), store.listEntitlementHistory(acct.id).map { it.productId })
        assertTrue(store.listEntitlementHistory("nobody").isEmpty())
    }

    @Test
    fun findAccountByStripeCustomerId_resolvesOnlyAfterTheCustomerIsAttached() {
        val acct = account("payer@x.com")
        assertNull(store.findAccountByStripeCustomerId("cus_1"))
        store.attachStripeCustomer(acct.id, "cus_1")
        assertEquals(acct.id, store.findAccountByStripeCustomerId("cus_1")?.id)
    }
}
