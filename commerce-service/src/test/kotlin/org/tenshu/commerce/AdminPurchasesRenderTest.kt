package org.tenshu.commerce

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import org.tenshu.commerce.routing.TokenAdminAuth
import org.tenshu.commerce.store.CreditNoteDraft
import org.tenshu.commerce.store.CreditNoteKind
import org.tenshu.commerce.store.EntitlementHistory
import org.tenshu.commerce.store.EntitlementSource
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.commerce.store.InvoiceDraft
import org.tenshu.commerce.store.Rail
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Writes the WC-3 admin pages to `build/admin-render/` so a human can **look at them**, following
 * the `ServicesCardRenderTest` precedent (CE-6). Not a golden-image gate — nothing here fails on a
 * layout change.
 *
 * ⚠ It exists because CE-6's manual pass found two real defects by looking at a render that every
 * string assertion had already passed: a tier displayed as "2048 GB" on the screen we ask people to
 * buy from, and a progress gauge that rendered as a 4dp underline. String assertions confirm the
 * data reached the page; only a person can see that it reads wrong.
 */
class AdminPurchasesRenderTest {

    private val store = InMemoryCommerceStore()
    private val adminToken = "render-token"

    @Test
    fun writeAdminPagesForReview() = testApplication {
        application { adminModule(store = store, adminAuth = TokenAdminAuth(adminToken), now = { NOW }) }
        seed()

        val outputDir = File("build/admin-render").apply { mkdirs() }
        val pages = mapOf(
            "purchases.html" to "/admin/purchases?from=2026-08-01&to=2026-08-31",
            "purchases-empty.html" to "/admin/purchases?from=2026-01-01&to=2026-01-31",
            "account.html" to "/admin/account?email=alice@example.com",
            "users.html" to "/admin/users",
            // The export is opened in a spreadsheet by an accountant — worth reading before it is.
            "ledger.csv" to "/admin/export/ledger.csv?from=2026-08-01&to=2026-08-31",
            "vat-summary.csv" to "/admin/export/vat-summary.csv?from=2026-08-01&to=2026-08-31",
        )
        pages.forEach { (fileName, path) ->
            val body = client.get(path) { header("Authorization", "Bearer $adminToken") }.bodyAsText()
            File(outputDir, fileName).writeText(body)
            assertTrue(body.isNotBlank(), "$path rendered nothing")
        }
        println("[admin-render] wrote ${pages.size} pages to ${outputDir.absolutePath}")
    }

    private fun seed() {
        val account = store.createAccount("alice@example.com", rail = Rail.Stripe, createdAt = NOW)
        store.markEmailVerified(account.id, NOW)
        store.bindOwner(account.id, "owner-alice-0f3a9c", NOW)
        store.attachStripeCustomer(account.id, "cus_9aX2")
        // Registered first (Free), then bought (Premium) — the real order, so the "oldest first"
        // claim on the page can actually be checked by reading it.
        store.appendEntitlementHistory(
            EntitlementHistory(account.id, ProductCatalog.FREE, validFrom = AUGUST_1, validUntil = AUGUST_1 + TEN_YEARS, source = EntitlementSource.Free, issuedAt = AUGUST_1),
        )
        store.appendEntitlementHistory(
            EntitlementHistory(account.id, ProductCatalog.PREMIUM, validFrom = AUGUST_5, validUntil = SEPTEMBER_2, source = EntitlementSource.Stripe, issuedAt = AUGUST_5),
        )
        store.createSession("hash-a", account.id, createdAt = NOW, expiresAt = NOW + TEN_YEARS)

        // A well-formed sale, a single-evidence one, and an unapportioned credit note — the three
        // shapes an operator needs to be able to tell apart at a glance.
        store.appendInvoice(
            InvoiceDraft(
                accountId = account.id, stripeInvoiceId = "in_9aX2b", amountMinor = 1190,
                currency = "eur", vatAmountMinor = 190, vatRateBasisPoints = 1900,
                billingCountry = "DE", ipCountry = "DE", issuedAt = AUGUST_5,
            ),
        )
        store.appendInvoice(
            InvoiceDraft(
                accountId = account.id, stripeInvoiceId = "in_9aX2c", amountMinor = 24_900,
                currency = "eur", vatAmountMinor = 3975, vatRateBasisPoints = 1900,
                billingCountry = "AT", ipCountry = null, issuedAt = AUGUST_11,
            ),
        )
        store.appendCreditNote(
            CreditNoteDraft(
                accountId = account.id, kind = CreditNoteKind.Refund, stripeReferenceId = "re_4Kd1",
                referencesInvoiceNumber = 1L, amountMinor = 595, currency = "eur",
                vatAmountMinor = 95, vatRateBasisPoints = 1900, billingCountry = "DE",
                issuedAt = AUGUST_12,
            ),
        )
        // ⚠ WC-6: a chargeback now resolves its invoice through the payment, so it carries the
        // country and an apportioned VAT and nets out of AT's total instead of sitting unattributed.
        store.appendCreditNote(
            CreditNoteDraft(
                accountId = account.id, kind = CreditNoteKind.Chargeback, stripeReferenceId = "dp_7Qz",
                referencesInvoiceNumber = 2L, amountMinor = 24_900, currency = "eur",
                vatAmountMinor = 3975, vatRateBasisPoints = 1900, billingCountry = "AT",
                issuedAt = AUGUST_20,
            ),
        )
        // …and one that still cannot be linked — a dispute on a sale invoiced before WC-6, which is
        // why the `(unreconciled)` bucket has to stay.
        store.appendCreditNote(
            CreditNoteDraft(
                accountId = account.id, kind = CreditNoteKind.Chargeback, stripeReferenceId = "dp_legacy",
                referencesInvoiceNumber = null, amountMinor = 4_900, currency = "eur",
                vatAmountMinor = null, vatRateBasisPoints = null, billingCountry = null,
                issuedAt = AUGUST_20,
            ),
        )
    }

    private companion object {
        val NOW: Long = Instant.parse("2026-08-09T12:00:00Z").toEpochMilli()
        val AUGUST_1: Long = Instant.parse("2026-08-01T09:00:00Z").toEpochMilli()
        val AUGUST_5: Long = Instant.parse("2026-08-05T10:00:00Z").toEpochMilli()
        val AUGUST_11: Long = Instant.parse("2026-08-11T09:30:00Z").toEpochMilli()
        val AUGUST_12: Long = Instant.parse("2026-08-12T14:00:00Z").toEpochMilli()
        val AUGUST_20: Long = Instant.parse("2026-08-20T08:15:00Z").toEpochMilli()
        const val TEN_YEARS: Long = 10L * 365 * 24 * 60 * 60 * 1000
        val SEPTEMBER_2: Long = Instant.parse("2026-09-02T10:00:00Z").toEpochMilli()
    }
}
