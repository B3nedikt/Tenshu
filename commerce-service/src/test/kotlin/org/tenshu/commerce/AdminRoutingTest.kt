package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.tenshu.commerce.routing.AdminAuth
import org.tenshu.commerce.routing.TokenAdminAuth
import org.tenshu.commerce.store.CreditNoteDraft
import org.tenshu.commerce.store.CreditNoteKind
import org.tenshu.commerce.store.EntitlementHistory
import org.tenshu.commerce.store.EntitlementSource
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.commerce.store.InvoiceDraft
import org.tenshu.commerce.store.Rail
import org.tenshu.identity.EntitlementSignature
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for the §4.5 admin console: auth (no/invalid credential → 401, never the issuer key) and
 * the read-only users view (renders the A28⨯A30 join; search filters; no mutation route exists).
 */
class AdminRoutingTest {

    private val store = InMemoryCommerceStore()
    private val adminToken = "super-secret-admin-token"

    private fun adminApp(auth: AdminAuth = TokenAdminAuth(adminToken), block: suspend (HttpClient) -> Unit) =
        testApplication {
            application { adminModule(store = store, adminAuth = auth, now = { NOW }) }
            block(client)
        }

    private fun seedAlice() {
        val account = store.createAccount("alice@example.com", rail = Rail.Free, createdAt = NOW)
        store.markEmailVerified(account.id, NOW)
        store.bindOwner(account.id, "owner-alice", NOW)
        store.appendEntitlementHistory(
            EntitlementHistory(account.id, ProductCatalog.FREE, validFrom = 0, validUntil = NOW + 1_000, source = EntitlementSource.Free, issuedAt = NOW),
        )
    }

    @Test
    fun noCredential_isUnauthorized_andLeaksNoKey() = adminApp { client ->
        val response = client.get("/admin/users")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val body = response.bodyAsText()
        val issuerKeyB64 = Base64.getEncoder().encodeToString(EntitlementSignature.COMMERCE_PUBLIC_KEY)
        assertTrue(issuerKeyB64 !in body, "a 401 must never echo the issuer key")
        assertTrue("alice@example.com" !in body)
    }

    @Test
    fun wrongCredential_isUnauthorized() = adminApp { client ->
        val response = client.get("/admin/users") { header("Authorization", "Bearer not-the-token") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun unsetToken_locksTheConsole() = adminApp(auth = TokenAdminAuth(null)) { client ->
        // Locked-by-default: even a "Bearer " of nothing or anything is rejected.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/users") { header("Authorization", "Bearer x") }.status)
    }

    @Test
    fun validCredential_rendersAccounts() = adminApp { client ->
        seedAlice()
        val response = client.get("/admin/users") { header("Authorization", "Bearer $adminToken") }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("alice@example.com" in body, "shows the account email (A28)")
        assertTrue("owner-alice" in body, "shows the live owner (A30 join)")
        assertTrue(ProductCatalog.FREE in body, "shows the current tier")
    }

    @Test
    fun searchFiltersByEmailOrOwner() = adminApp { client ->
        seedAlice()
        store.createAccount("bob@example.com", rail = Rail.Free, createdAt = NOW).also {
            store.bindOwner(it.id, "owner-bob", NOW)
        }
        val onlyAlice = client.get("/admin/users?q=alice") { header("Authorization", "Bearer $adminToken") }.bodyAsText()
        assertTrue("alice@example.com" in onlyAlice)
        assertTrue("bob@example.com" !in onlyAlice, "the query filters bob out")
    }

    @Test
    fun noMutationRoute_exists() = adminApp { client ->
        seedAlice()
        // The console is read-only: POSTing to the users path is not a 2xx success.
        val response = client.post("/admin/users") { header("Authorization", "Bearer $adminToken") }
        assertTrue(response.status.value !in 200..299, "no mutation route should accept a POST")
    }

    // --- WC-3: purchase visibility -------------------------------------------------------------

    private fun seedLedger() {
        store.appendInvoice(
            InvoiceDraft(
                accountId = "acct-1", stripeInvoiceId = "in_in_window", amountMinor = 1190,
                currency = "eur", vatAmountMinor = 190, vatRateBasisPoints = 1900,
                billingCountry = "DE", ipCountry = "AT", issuedAt = AUGUST_5,
            ),
        )
        store.appendInvoice(
            InvoiceDraft(
                accountId = "acct-1", stripeInvoiceId = "in_out_of_window", amountMinor = 5000,
                currency = "eur", vatAmountMinor = 798, vatRateBasisPoints = 1900,
                billingCountry = "DE", ipCountry = null, issuedAt = SEPTEMBER_2,
            ),
        )
        store.appendCreditNote(
            CreditNoteDraft(
                accountId = "acct-1", kind = CreditNoteKind.Refund, stripeReferenceId = "re_1",
                referencesInvoiceNumber = null, amountMinor = 500, currency = "eur",
                vatAmountMinor = null, vatRateBasisPoints = null, billingCountry = null,
                issuedAt = AUGUST_5,
            ),
        )
    }

    @Test
    fun purchases_requiresTheAdminCredential() = adminApp { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/purchases").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/admin/account?email=alice@example.com").status,
        )
    }

    @Test
    fun purchases_listsOnlyRowsInsideTheWindow() = adminApp { client ->
        seedLedger()
        val body = client.get("/admin/purchases?from=2026-08-01&to=2026-08-31") {
            header("Authorization", "Bearer $adminToken")
        }.bodyAsText()

        assertTrue("in_in_window" in body)
        assertTrue("in_out_of_window" !in body, "September must not appear in an August window")
        assertTrue("11.90 EUR" in body, "amounts render as money, not minor units")
        assertTrue("2026-08-01 to 2026-08-31 (inclusive, UTC)" in body, "the window is shown back")
    }

    /**
     * ⚠⚠ The two rows an operator must chase, named on the page rather than left as blank cells:
     * an invoice with only one location evidence cannot fully defend its VAT rate, and a credit
     * note whose VAT could not be apportioned cannot be turned into a correction.
     */
    @Test
    fun purchases_flagsSingleEvidenceInvoicesAndUnapportionedCreditNotes() = adminApp { client ->
        seedLedger()
        val august = client.get("/admin/purchases?from=2026-08-01&to=2026-08-31") {
            header("Authorization", "Bearer $adminToken")
        }.bodyAsText()
        // The August credit note has null VAT and no linked invoice.
        assertTrue("unknown — reconcile by hand" in august)
        assertTrue("unlinked" in august)
        // ⚠ And it must NOT be rendered as a confident zero.
        assertTrue("0.00 EUR" !in august, "unapportioned VAT must never read as 0.00")

        val september = client.get("/admin/purchases?from=2026-09-01&to=2026-09-30") {
            header("Authorization", "Bearer $adminToken")
        }.bodyAsText()
        assertTrue("1 evidence only" in september, "the September invoice has no ipCountry")
    }

    /**
     * ⚠⚠ The aggregate must be as honest as the cell. Coalescing a null VAT to `0` in the total —
     * the obvious way to write the sum, and what the first version of this page did — restates at
     * the summary level exactly the false claim the nullable column exists to prevent.
     */
    @Test
    fun purchases_neverSumsAnUnapportionedVatAsZero() = adminApp { client ->
        seedLedger() // one August credit note, VAT unknown
        val allUnknown = client.get("/admin/purchases?from=2026-08-01&to=2026-08-31") {
            header("Authorization", "Bearer $adminToken")
        }.bodyAsText()
        assertTrue("VAT unknown" in allUnknown, "no known row ⇒ the total must not claim a figure")

        // Add an apportioned one: the total becomes a floor, never a complete figure.
        store.appendCreditNote(
            CreditNoteDraft(
                accountId = "acct-1", kind = CreditNoteKind.Refund, stripeReferenceId = "re_2",
                referencesInvoiceNumber = 1L, amountMinor = 595, currency = "eur",
                vatAmountMinor = 95, vatRateBasisPoints = 1900, billingCountry = "DE",
                issuedAt = AUGUST_5,
            ),
        )
        val partiallyKnown = client.get("/admin/purchases?from=2026-08-01&to=2026-08-31") {
            header("Authorization", "Bearer $adminToken")
        }.bodyAsText()
        assertTrue("VAT ≥ 0.95 EUR" in partiallyKnown, "a partial total is a floor")
        assertTrue("excludes 1 unapportioned row(s)" in partiallyKnown)
    }

    /**
     * Money in and money out are different kinds of figure, and both used to render as a bare
     * "Total" — which invites adding them. Found by reading the page, not by an assertion.
     */
    @Test
    fun purchases_labelsTotalsWithTheirDirection() = adminApp { client ->
        seedLedger()
        val body = client.get("/admin/purchases?from=2026-08-01&to=2026-08-31") {
            header("Authorization", "Bearer $adminToken")
        }.bodyAsText()
        assertTrue("invoiced 11.90 EUR" in body)
        assertTrue("credited 5.00 EUR" in body)
    }

    @Test
    fun purchases_saysItIsNotTheFiling() = adminApp { client ->
        val body = client.get("/admin/purchases") { header("Authorization", "Bearer $adminToken") }.bodyAsText()
        // An operator must not file a VAT return off a listing that merely looks authoritative.
        assertTrue("do not file from this page" in body)
    }

    @Test
    fun purchases_rejectsAnUnparseableOrBackwardsRange() = adminApp { client ->
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/admin/purchases?from=nonsense") { header("Authorization", "Bearer $adminToken") }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/admin/purchases?from=2026-08-31&to=2026-08-01") {
                header("Authorization", "Bearer $adminToken")
            }.status,
        )
    }

    @Test
    fun account_showsEntitlementHistoryAndASessionCount() = adminApp { client ->
        seedAlice()
        val account = store.findAccountByEmail("alice@example.com")!!
        store.appendEntitlementHistory(
            EntitlementHistory(account.id, ProductCatalog.PREMIUM, validFrom = NOW, validUntil = NOW + 90_000, source = EntitlementSource.Stripe, issuedAt = NOW + 10),
        )
        store.createSession("hash-live", account.id, createdAt = NOW, expiresAt = NOW + 10_000)
        store.createSession("hash-expired", account.id, createdAt = 0, expiresAt = NOW - 1)

        val body = client.get("/admin/account?email=alice@example.com") {
            header("Authorization", "Bearer $adminToken")
        }.bodyAsText()

        // Both rows, because several entitlements are legitimately valid at once.
        assertTrue(ProductCatalog.FREE in body)
        assertTrue(ProductCatalog.PREMIUM in body)
        assertTrue("Active sessions" in body)
        // One live, one expired ⇒ 1. And never the token hashes themselves.
        assertTrue("hash-live" !in body, "a session credential must not reach the page")
    }

    @Test
    fun account_isAnHonest404ForAnUnknownAddress() = adminApp { client ->
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/admin/account?email=nobody@example.com") {
                header("Authorization", "Bearer $adminToken")
            }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/admin/account") { header("Authorization", "Bearer $adminToken") }.status,
        )
    }

    // --- WC-4: the export ----------------------------------------------------------------------

    @Test
    fun export_requiresTheAdminCredential() = adminApp { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/export/ledger.csv?from=2026-08-01&to=2026-08-31").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/export/vat-summary.csv?from=2026-08-01&to=2026-08-31").status)
    }

    /**
     * ⚠⚠ Unlike the *view*, an export must not default its range: one absent parameter away from
     * "everything" is precisely the bulk-extraction affordance D8 refused, and a filing is always
     * for a stated period.
     */
    @Test
    fun export_refusesWithoutAnExplicitRange() = adminApp { client ->
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/admin/export/ledger.csv") { header("Authorization", "Bearer $adminToken") }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/admin/export/ledger.csv?from=2026-08-01") { header("Authorization", "Bearer $adminToken") }.status,
            "half a range is still not a range",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/admin/export/vat-summary.csv?from=2026-08-31&to=2026-08-01") {
                header("Authorization", "Bearer $adminToken")
            }.status,
        )
    }

    @Test
    fun export_servesCsvForTheRequestedWindowOnly() = adminApp { client ->
        seedLedger()
        val response = client.get("/admin/export/ledger.csv?from=2026-08-01&to=2026-08-31") {
            header("Authorization", "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("text/csv" in response.headers["Content-Type"].orEmpty())

        val csv = response.bodyAsText()
        assertTrue("in_in_window" in csv)
        assertTrue("in_out_of_window" !in csv, "September must not leave in an August export")
        assertTrue("document_type,number,issued_at" in csv, "the header is present")
    }

    /**
     * ⚠⚠ The D12 mitigation, asserted on what actually leaves the building. The export exists only
     * because a VAT return needs neither side of the A28 ⨯ A30 join; a field reappearing here would
     * undo the reasoning that justified reversing D8.
     */
    @Test
    fun export_neverCarriesTheIdentityJoin() = adminApp { client ->
        seedAlice()
        seedLedger()
        val csv = client.get("/admin/export/ledger.csv?from=2026-08-01&to=2026-08-31") {
            header("Authorization", "Bearer $adminToken")
        }.bodyAsText()

        assertTrue("alice@example.com" !in csv, "no email")
        assertTrue("owner-alice" !in csv, "no ownerId")
        assertTrue("acct-1" !in csv, "not even the internal account id")
    }

    @Test
    fun vatSummaryExport_totalsPerCountryAndSurfacesTheUnreconciled() = adminApp { client ->
        seedLedger() // one DE invoice, one credit note with unknown VAT
        val csv = client.get("/admin/export/vat-summary.csv?from=2026-08-01&to=2026-08-31") {
            header("Authorization", "Bearer $adminToken")
        }.bodyAsText()

        assertTrue("country,currency,net_minor,vat_minor,gross_minor,documents,note" in csv)
        assertTrue("DE,EUR,1000,190,1190,1," in csv, "the German invoice, net+vat=gross")
        // ⚠ The unapportioned reversal is visible rather than silently dropped from the totals.
        assertTrue("(unreconciled)" in csv)
        assertTrue("reconcile before filing" in csv)
    }

    // --- WC-5: the second factor ---------------------------------------------------------------

    private val totpSecret = "JBSWY3DPEHPK3PXP"

    private fun mfaApp(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            adminModule(
                store = store,
                adminAuth = TokenAdminAuth(adminToken),
                now = { NOW },
                totp = AdminTotpVerifier(totpSecret),
            )
        }
        block(client)
    }

    private fun currentCode(): String =
        AdminTotp.code(AdminTotp.decodeBase32(totpSecret)!!, AdminTotp.stepIndex(NOW))

    /**
     * ⚠⚠ The gate lives in the admin *application*, not on each route, so this holds for every path
     * — including ones added later. A per-route check is one forgotten line away from an open door
     * on the surface that joins A28 ⨯ A30 and, since WC-4, exports.
     */
    @Test
    fun everyAdminPathRequiresBothFactors() = mfaApp { client ->
        val paths = listOf(
            "/admin/users",
            "/admin/purchases?from=2026-08-01&to=2026-08-31",
            "/admin/account?email=alice@example.com",
            "/admin/export/ledger.csv?from=2026-08-01&to=2026-08-31",
            "/admin/export/vat-summary.csv?from=2026-08-01&to=2026-08-31",
        )
        paths.forEach { path ->
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get(path) { header("Authorization", "Bearer $adminToken") }.status,
                "$path must refuse a valid token with no second factor",
            )
        }
        // Mutations too — they go through the same application-level gate.
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/admin/accounts/close") { header("Authorization", "Bearer $adminToken") }.status,
        )
    }

    @Test
    fun bothFactorsTogetherAreAccepted() = mfaApp { client ->
        seedAlice()
        val response = client.get("/admin/users") {
            header("Authorization", "Bearer $adminToken")
            header(ADMIN_TOTP_HEADER, currentCode())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("alice@example.com" in response.bodyAsText())
    }

    @Test
    fun aValidCodeWithoutTheTokenIsStillRefused() = mfaApp { client ->
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/admin/users") { header(ADMIN_TOTP_HEADER, currentCode()) }.status,
        )
    }

    /** ⚠⚠ Replay refusal, end to end: a captured request cannot simply be sent again. */
    @Test
    fun aCapturedRequestCannotBeReplayed() = mfaApp { client ->
        val code = currentCode()
        assertEquals(
            HttpStatusCode.OK,
            client.get("/admin/users") {
                header("Authorization", "Bearer $adminToken")
                header(ADMIN_TOTP_HEADER, code)
            }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/admin/users") {
                header("Authorization", "Bearer $adminToken")
                header(ADMIN_TOTP_HEADER, code)
            }.status,
            "the same code must not work twice, even inside its own step",
        )
    }

    /**
     * ⚠ One opaque 401 whichever factor failed. Distinguishing them would confirm to a caller
     * holding a stolen token that the token is live — precisely the fact the second factor exists to
     * keep from being useful. The server log tells them apart; the response does not.
     */
    @Test
    fun theRefusalDoesNotSayWhichFactorFailed() = mfaApp { client ->
        val wrongToken = client.get("/admin/users") {
            header("Authorization", "Bearer nope")
            header(ADMIN_TOTP_HEADER, currentCode())
        }
        val wrongCode = client.get("/admin/users") {
            header("Authorization", "Bearer $adminToken")
            header(ADMIN_TOTP_HEADER, "000000")
        }
        assertEquals(wrongToken.status, wrongCode.status)
        assertEquals(wrongToken.bodyAsText(), wrongCode.bodyAsText())
    }

    private companion object {
        const val NOW = 1_000_000L
        val AUGUST_5: Long = Instant.parse("2026-08-05T10:00:00Z").toEpochMilli()
        val SEPTEMBER_2: Long = Instant.parse("2026-09-02T10:00:00Z").toEpochMilli()
    }
}
