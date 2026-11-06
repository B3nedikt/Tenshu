package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.commerce.routing.AccountRateLimits
import org.tenshu.commerce.routing.StripeSupport
import org.tenshu.commerce.store.Acceptance
import org.tenshu.commerce.store.AccountStatus
import org.tenshu.commerce.store.ErasedAccounts
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.commerce.store.InvoiceDraft
import org.tenshu.commerce.store.MarketingConsent
import org.tenshu.commerce.stripe.BillingSubscription
import org.tenshu.commerce.stripe.ChargeRefund
import org.tenshu.commerce.stripe.CheckoutSessionRequest
import org.tenshu.commerce.stripe.InvoicePaymentEvidence
import org.tenshu.commerce.stripe.StripeClient
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Self-service account deletion (`commerce-accounts-and-sessions.md` §9.7): the two routes, what an
 * erasure removes and what it must keep, and the guards around it.
 *
 * ⚠ Every refusal case also asserts the account is **still intact** — a route that answered 401
 * while erasing anyway would pass a status-only test, and this one is irreversible.
 */
class AccountDeletionRoutingTest {

    private val store = InMemoryCommerceStore()
    private val sender = RecordingEmailSender()
    private val clock = AtomicLong(1_700_000_000_000L)
    private val stripe = RecordingStripe()

    private class RecordingStripe : StripeClient {
        val cancelledFor = mutableListOf<String>()
        override suspend fun createCheckoutSession(request: CheckoutSessionRequest): String? = null
        override suspend fun createPortalSession(customerId: String, returnUrl: String): String? = null
        override suspend fun billingSubscriptions(customerId: String): List<BillingSubscription>? = emptyList()
        override suspend fun refundsForCharge(chargeId: String): List<ChargeRefund>? = emptyList()
        override suspend fun invoicePaymentEvidence(invoiceId: String): InvoicePaymentEvidence? = null
        override suspend fun cancelActiveSubscriptions(customerId: String): Int {
            cancelledFor += customerId
            return 1
        }
    }

    /** Records the Play purchase tokens it was asked to stop renewing. */
    private class RecordingPlay : org.tenshu.commerce.play.GooglePlayClient {
        val cancelled = mutableListOf<String>()
        override suspend fun subscription(purchaseToken: String) = error("unused")
        override suspend fun acknowledge(subscriptionId: String, purchaseToken: String) = true
        override suspend fun revokeWithProratedRefund(purchaseToken: String) = true
        override suspend fun cancelRenewals(purchaseToken: String): Boolean {
            cancelled += purchaseToken
            return true
        }
    }

    private val play = RecordingPlay()

    private val webhookSecret = "whsec_test"

    private fun appTest(
        limits: AccountRateLimits = AccountRateLimits.unlimited(),
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        application {
            module(
                store = store,
                emailSender = sender,
                otpTtlMs = 60_000L,
                otpMaxAttempts = 3,
                sessionTtlMs = 3_600_000L,
                now = { clock.get() },
                limits = limits,
                stripe = StripeSupport(
                    client = stripe,
                    priceIds = emptyMap(),
                    webhookSecret = webhookSecret,
                    successUrl = "tenshu://ok",
                    cancelUrl = "tenshu://no",
                    portalReturnUrl = "tenshu://back",
                ),
                // Production's wiring: one canceller over both rails (Application.main).
                subscriptionCanceller = SubscriptionCanceller(store, stripe, play) { clock.get() },
            )
        }
        block(client)
    }

    private suspend fun HttpClient.json(path: String, body: String) = post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.requestBySession(token: String) =
        json("/commerce/account/delete/request", """{"sessionToken":"$token"}""")

    private suspend fun HttpClient.requestByEmail(email: String) =
        json("/commerce/account/delete/request", """{"email":"$email"}""")

    private suspend fun HttpClient.confirmBySession(token: String, code: String) =
        json("/commerce/account/delete/confirm", """{"sessionToken":"$token","code":"$code"}""")

    private suspend fun HttpClient.confirmByEmail(email: String, code: String) =
        json("/commerce/account/delete/confirm", """{"email":"$email","code":"$code"}""")

    private fun assertIntact(email: String) {
        val account = assertNotNull(store.findAccountByEmail(email), "the account must still exist")
        assertEquals(AccountStatus.Active, account.status)
        assertNull(account.erasedAt)
    }

    // --- The happy paths -----------------------------------------------------------------------

    @Test
    fun theAppDeletesWithItsSessionAndAFreshCode() = appTest { client ->
        val token = client.registerAndVerify(sender, "alice@example.com", ownerId = "owner-alice")
        val accountId = assertNotNull(store.findAccountByEmail("alice@example.com")).id

        assertEquals(HttpStatusCode.Accepted, client.requestBySession(token).status)
        val code = assertNotNull(sender.lastDeletionCode("alice@example.com"), "a deletion code is mailed")

        assertEquals(HttpStatusCode.OK, client.confirmBySession(token, code).status)

        val erased = assertNotNull(store.findAccountById(accountId))
        assertEquals(AccountStatus.Closed, erased.status)
        assertEquals(clock.get(), erased.closedAt, "the closure starts the grace clock now")
        assertEquals(clock.get(), erased.erasedAt)
        assertEquals(ErasedAccounts.tombstoneFor(accountId), erased.emailNormalised)
        assertFalse('@' in erased.emailNormalised, "a tombstone can never match a normalised address")
        assertNull(erased.emailVerifiedAt)
        assertNull(store.findAccountByEmail("alice@example.com"), "the address no longer finds anything")
        assertEquals(0, store.countActiveSessions(accountId, clock.get()), "every session is gone")
        // The session that asked is dead too: the app is signed out of commerce.
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.json("/commerce/account/session", """{"sessionToken":"$token"}""").status,
        )
        // The closure rides the existing reclamation path (Face A): the owner is minted for.
        assertEquals(listOf("owner-alice"), store.closedAccountsWithOwners().map { it.ownerId })
    }

    @Test
    fun theWebPageDeletesWithJustTheAddressAndACode() = appTest { client ->
        client.registerAndVerify(sender, "bob@example.com", ownerId = "owner-bob")
        val accountId = assertNotNull(store.findAccountByEmail("bob@example.com")).id

        assertEquals(HttpStatusCode.Accepted, client.requestByEmail("  Bob@Example.com ").status)
        val code = assertNotNull(sender.lastDeletionCode("bob@example.com"))
        assertEquals(HttpStatusCode.OK, client.confirmByEmail("bob@example.com", code).status)

        assertNotNull(store.findAccountById(accountId)?.erasedAt)
        assertNull(store.findAccountByEmail("bob@example.com"))
    }

    @Test
    fun theAddressIsGoneFromEveryReadSurface() = appTest { client ->
        val token = client.registerAndVerify(sender, "carol@example.com", ownerId = "owner-c3")
        val accountId = assertNotNull(store.findAccountByEmail("carol@example.com")).id
        store.setMarketingConsent(MarketingConsent(accountId, granted = true, changedAt = clock.get()))
        assertEquals(HttpStatusCode.Accepted, client.requestBySession(token).status)
        assertEquals(
            HttpStatusCode.OK,
            client.confirmBySession(token, assertNotNull(sender.lastDeletionCode("carol@example.com"))).status,
        )

        val rows = store.listAccountsForAdmin(query = null, nowMs = clock.get())
        assertTrue(rows.none { "carol" in it.email }, "the admin console must not show the erased address: $rows")
        assertTrue(store.listAccountsForAdmin(query = "carol", nowMs = clock.get()).isEmpty())
        assertNull(store.getEmailVerification("carol@example.com"))
        assertNull(store.getEmailVerification(ErasedAccounts.deletionOtpKey("carol@example.com")))
        assertNull(store.findAccountById(accountId)?.displayName)
    }

    // --- What erasure keeps ---------------------------------------------------------------------

    @Test
    fun invoicesAndAcceptanceSurviveTheDeletion() = appTest { client ->
        val token = client.registerAndVerify(sender, "dora@example.com", ownerId = "owner-dora")
        val accountId = assertNotNull(store.findAccountByEmail("dora@example.com")).id
        store.appendInvoice(
            InvoiceDraft(
                accountId = accountId, stripeInvoiceId = "in_1", amountMinor = 1190, currency = "eur",
                vatAmountMinor = 190, vatRateBasisPoints = 1900, billingCountry = "DE", ipCountry = "DE",
                issuedAt = clock.get(),
            ),
        )
        val acceptancesBefore = store.acceptances().count { it.accountId == accountId }
        assertTrue(acceptancesBefore > 0)

        client.requestBySession(token)
        client.confirmBySession(token, assertNotNull(sender.lastDeletionCode("dora@example.com")))

        val invoices = store.allInvoices()
        assertEquals(1, invoices.size, "a statutory invoice survives erasure (§5.2)")
        assertEquals(accountId, invoices.single().accountId, "and still points at the (now pseudonymous) row")
        assertEquals(acceptancesBefore, store.acceptances().count { it.accountId == accountId })
        assertNotNull(store.findAccountById(accountId), "the row stays — the sweeps and the ledger need it")
    }

    @Test
    fun deletingStopsTheStripeSubscription() = appTest { client ->
        val token = client.registerAndVerify(sender, "erin@example.com", ownerId = "owner-erin")
        val accountId = assertNotNull(store.findAccountByEmail("erin@example.com")).id
        store.attachStripeCustomer(accountId, "cus_erin")

        client.requestBySession(token)
        client.confirmBySession(token, assertNotNull(sender.lastDeletionCode("erin@example.com")))

        assertEquals(listOf("cus_erin"), stripe.cancelledFor)
        assertEquals(
            "cus_erin",
            store.findAccountById(accountId)?.stripeCustomerId,
            "the customer reference survives, so the cancellation sweep can retry",
        )
    }

    @Test
    fun deletingStopsTheGooglePlaySubscription() = appTest { client ->
        val token = client.registerAndVerify(sender, "gia@example.com", ownerId = "owner-gia")
        val accountId = assertNotNull(store.findAccountByEmail("gia@example.com")).id
        // What a verified Play purchase leaves behind (GooglePlayRouting): the store rail + token,
        // and a Google-sourced grant.
        store.setStoreRail(accountId, org.tenshu.commerce.store.Rail.IapGoogle, "play-token-gia")
        store.appendEntitlementHistory(
            org.tenshu.commerce.store.EntitlementHistory(
                accountId = accountId, productId = ProductCatalog.BASIC, validFrom = clock.get(),
                validUntil = clock.get() + 30L * 86_400_000L,
                source = org.tenshu.commerce.store.EntitlementSource.IapGoogle, issuedAt = clock.get(),
            ),
        )

        client.requestBySession(token)
        assertEquals(
            HttpStatusCode.OK,
            client.confirmBySession(token, assertNotNull(sender.lastDeletionCode("gia@example.com"))).status,
        )

        assertEquals(listOf("play-token-gia"), play.cancelled, "renewals stopped at Google")
        assertTrue(stripe.cancelledFor.isEmpty(), "no Stripe customer, nothing to cancel there")
        assertEquals(
            "play-token-gia",
            store.findAccountById(accountId)?.iapOriginalTxnId,
            "the purchase token survives erasure, so the cancellation sweep can retry",
        )
    }

    @Test
    fun anEarlierClosureKeepsItsStamp() = appTest { client ->
        client.registerAndVerify(sender, "fay@example.com", ownerId = "owner-fay")
        val accountId = assertNotNull(store.findAccountByEmail("fay@example.com")).id
        val closedAt = clock.get()
        store.closeAccount(accountId, closedAt)
        // Later — past the session's lifetime, so this goes through the web page's address variant.
        clock.addAndGet(10L * 24 * 3_600_000L)

        assertEquals(HttpStatusCode.Accepted, client.requestByEmail("fay@example.com").status)
        assertEquals(
            HttpStatusCode.OK,
            client.confirmByEmail("fay@example.com", assertNotNull(sender.lastDeletionCode("fay@example.com"))).status,
        )

        assertEquals(closedAt, store.findAccountById(accountId)?.closedAt, "erasure must not restart the grace clock")
    }

    // --- The proof ------------------------------------------------------------------------------

    @Test
    fun aSignInCodeCannotDelete() = appTest { client ->
        client.json(
            "/commerce/account/register",
            """{"email":"gus@example.com","tosVersion":"v1","privacyVersion":"v1"}""",
        )
        val signInCode = assertNotNull(sender.lastCode("gus@example.com"))

        assertEquals(HttpStatusCode.Unauthorized, client.confirmByEmail("gus@example.com", signInCode).status)
        assertIntact("gus@example.com")
    }

    @Test
    fun aDeletionCodeCannotSignIn() = appTest { client ->
        client.registerAndVerify(sender, "hal@example.com", ownerId = "owner-hal")
        client.requestByEmail("hal@example.com")
        val deletionCode = assertNotNull(sender.lastDeletionCode("hal@example.com"))

        val verify = client.json(
            "/commerce/account/verify",
            """{"email":"hal@example.com","code":"$deletionCode","ownerId":"owner-attacker"}""",
        )
        assertEquals(HttpStatusCode.Unauthorized, verify.status)
        assertEquals("owner-hal", store.liveOwner(assertNotNull(store.findAccountByEmail("hal@example.com")).id))
    }

    @Test
    fun aWrongCodeIsRefusedAndTheAttemptsRunOut() = appTest { client ->
        client.registerAndVerify(sender, "ida@example.com", ownerId = "owner-ida")
        client.requestByEmail("ida@example.com")
        val code = assertNotNull(sender.lastDeletionCode("ida@example.com"))
        val wrong = if (code == "000000") "111111" else "000000"

        repeat(3) { assertEquals(HttpStatusCode.Unauthorized, client.confirmByEmail("ida@example.com", wrong).status) }
        // Three wrong guesses spend the code: even the right one is refused now.
        assertEquals(HttpStatusCode.Unauthorized, client.confirmByEmail("ida@example.com", code).status)
        assertIntact("ida@example.com")
    }

    @Test
    fun anExpiredCodeIsRefused() = appTest { client ->
        client.registerAndVerify(sender, "jo@example.com", ownerId = "owner-jo")
        client.requestByEmail("jo@example.com")
        val code = assertNotNull(sender.lastDeletionCode("jo@example.com"))
        clock.addAndGet(60_001L)

        assertEquals(HttpStatusCode.Unauthorized, client.confirmByEmail("jo@example.com", code).status)
        assertIntact("jo@example.com")
    }

    @Test
    fun aDeadSessionCannotRequestOrConfirm() = appTest { client ->
        val token = client.registerAndVerify(sender, "kim@example.com", ownerId = "owner-kim")
        client.requestBySession(token)
        val code = assertNotNull(sender.lastDeletionCode("kim@example.com"))
        clock.addAndGet(3_600_001L)

        assertEquals(HttpStatusCode.Unauthorized, client.requestBySession(token).status)
        assertEquals(HttpStatusCode.Unauthorized, client.confirmBySession(token, code).status)
        assertEquals(HttpStatusCode.Unauthorized, client.requestBySession("not-a-session").status)
        assertIntact("kim@example.com")
    }

    @Test
    fun malformedRequestsAreRefused() = appTest { client ->
        val token = client.registerAndVerify(sender, "lee@example.com", ownerId = "owner-lee")
        // Exactly one of email / sessionToken.
        assertEquals(
            HttpStatusCode.BadRequest,
            client.json("/commerce/account/delete/request", """{"email":"lee@example.com","sessionToken":"$token"}""").status,
        )
        assertEquals(HttpStatusCode.BadRequest, client.json("/commerce/account/delete/request", "{}").status)
        assertEquals(HttpStatusCode.BadRequest, client.requestByEmail("no-at-sign").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.json("/commerce/account/delete/confirm", """{"email":"lee@example.com","code":""}""").status,
        )
        assertIntact("lee@example.com")
    }

    // --- No enumeration, and idempotency ----------------------------------------------------------

    @Test
    fun anUnknownAddressIsAnsweredTheSameWayWithANotice() = appTest { client ->
        val response = client.requestByEmail("nobody@example.com")

        assertEquals(HttpStatusCode.Accepted, response.status, "same status as for a real account")
        assertEquals(listOf("nobody@example.com"), sender.noAccountNotices, "and a mail all the same")
        assertNull(sender.lastDeletionCode("nobody@example.com"))
        assertNull(store.findAccountByEmail("nobody@example.com"), "asking must not create an account")
        assertEquals(HttpStatusCode.Unauthorized, client.confirmByEmail("nobody@example.com", "123456").status)
    }

    @Test
    fun aRepeatedDeletionChangesNothing() = appTest { client ->
        val token = client.registerAndVerify(sender, "max@example.com", ownerId = "owner-max")
        val accountId = assertNotNull(store.findAccountByEmail("max@example.com")).id
        client.requestBySession(token)
        val code = assertNotNull(sender.lastDeletionCode("max@example.com"))
        assertEquals(HttpStatusCode.OK, client.confirmByEmail("max@example.com", code).status)
        val first = assertNotNull(store.findAccountById(accountId))
        clock.addAndGet(1_000L)

        // The retry of a confirmation whose response was lost: a refusal, and no change.
        assertEquals(HttpStatusCode.Unauthorized, client.confirmByEmail("max@example.com", code).status)
        assertEquals(first, store.findAccountById(accountId))
        assertFalse(store.eraseAccount(accountId, clock.get()), "the store call is idempotent too")
        assertEquals(first, store.findAccountById(accountId))
        // Asking again now reads as "no account" — the honest answer, and how the page learns it worked.
        assertEquals(HttpStatusCode.Accepted, client.requestByEmail("max@example.com").status)
        assertEquals(listOf("max@example.com"), sender.noAccountNotices)
    }

    @Test
    fun theSameAddressCanRegisterAgainAsANewAccount() = appTest { client ->
        val token = client.registerAndVerify(sender, "ned@example.com", ownerId = "owner-ned")
        val oldId = assertNotNull(store.findAccountByEmail("ned@example.com")).id
        client.requestBySession(token)
        client.confirmBySession(token, assertNotNull(sender.lastDeletionCode("ned@example.com")))

        client.registerAndVerify(sender, "ned@example.com", ownerId = "owner-ned")

        val fresh = assertNotNull(store.findAccountByEmail("ned@example.com"))
        assertNotEquals(oldId, fresh.id, "an erased account is never resurrected")
        assertEquals(AccountStatus.Active, fresh.status)
        // ⚠⚠ The realm is live again under the new account, so the erased one must stop asking the
        // relay to delete it — otherwise the files the person just came back to would be reclaimed.
        assertTrue(
            store.closedAccountsWithOwners().none { it.ownerId == "owner-ned" },
            "a realm held by an open account is never minted for",
        )
    }

    @Test
    fun anErasedAccountIsNeverReopened() = appTest { client ->
        val token = client.registerAndVerify(sender, "ola@example.com", ownerId = "owner-ola")
        val accountId = assertNotNull(store.findAccountByEmail("ola@example.com")).id
        client.requestBySession(token)
        client.confirmBySession(token, assertNotNull(sender.lastDeletionCode("ola@example.com")))

        store.reopenAccount(accountId)

        assertEquals(AccountStatus.Closed, store.findAccountById(accountId)?.status)
    }

    @Test
    fun aLateStripeEventDoesNotRecreateTheErasedAccount() = appTest { client ->
        val token = client.registerAndVerify(sender, "pia@example.com", ownerId = "owner-pia")
        val accountId = assertNotNull(store.findAccountByEmail("pia@example.com")).id
        store.attachStripeCustomer(accountId, "cus_pia")
        client.requestBySession(token)
        client.confirmBySession(token, assertNotNull(sender.lastDeletionCode("pia@example.com")))

        val payload = """
            {"id":"evt_late","type":"invoice.paid","data":{"object":{
              "id":"in_late","customer":"cus_pia","customer_email":"pia@example.com",
              "amount_paid":1190,"currency":"eur","tax":190,
              "metadata":{"product_id":"${ProductCatalog.PREMIUM}","owner_id":"owner-pia"},
              "current_period_end":${clock.get() / 1000 + 86_400}}}}
        """.trimIndent()
        val ts = clock.get() / 1000
        val response = client.post("/commerce/webhook/stripe") {
            contentType(ContentType.Application.Json)
            header("Stripe-Signature", "t=$ts,v1=${hmacSha256Hex(webhookSecret, "$ts.$payload")}")
            setBody(payload)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(store.findAccountByEmail("pia@example.com"), "the erased address must not be re-collected")
    }

    // --- Budgets ------------------------------------------------------------------------------------

    @Test
    fun requestsAndGuessesAreRateLimited() = appTest(
        limits = AccountRateLimits.hourly(sendPerIpPerHour = 0, sendPerEmailPerHour = 1, verifyPerEmailPerHour = 1),
    ) { client ->
        client.registerAndVerify(sender, "quinn@example.com", ownerId = "owner-quinn")
        // /register already spent the address's one send this hour.
        sender.forget("quinn@example.com")
        assertEquals(HttpStatusCode.TooManyRequests, client.requestByEmail("quinn@example.com").status)
        assertNull(sender.lastDeletionCode("quinn@example.com"), "a refused request mails nothing")

        // /verify spent the one guess too.
        assertEquals(HttpStatusCode.TooManyRequests, client.confirmByEmail("quinn@example.com", "123456").status)
        assertIntact("quinn@example.com")
    }

    @Test
    fun aRealmBoundToAnotherOpenAccountIsNotReclaimed() {
        val nowMs = clock.get()
        val deleted = store.createAccount("r1@example.com", rail = org.tenshu.commerce.store.Rail.Free, createdAt = nowMs)
        val other = store.createAccount("r2@example.com", rail = org.tenshu.commerce.store.Rail.Free, createdAt = nowMs)
        store.bindOwner(deleted.id, "owner-shared", nowMs)
        store.bindOwner(other.id, "owner-shared", nowMs)
        store.recordAcceptance(Acceptance(deleted.id, "v1", "v1", nowMs))

        assertTrue(store.eraseAccount(deleted.id, nowMs))
        assertTrue(store.closedAccountsWithOwners().isEmpty(), "the other account still holds the realm")

        store.closeAccount(other.id, nowMs)
        assertEquals(
            setOf(deleted.id, other.id),
            store.closedAccountsWithOwners().map { it.accountId }.toSet(),
            "once nobody holds it, both closures name it",
        )
    }

    // --- Key-space separation ------------------------------------------------------------------

    @Test
    fun anAddressShapedLikeADeletionKeyIsRefused() = appTest { client ->
        client.registerAndVerify(sender, "sam@example.com", ownerId = "owner-sam")
        client.requestByEmail("sam@example.com")
        val pending = assertNotNull(store.getEmailVerification(ErasedAccounts.deletionOtpKey("sam@example.com")))

        // ⚠⚠ Would write a SIGN-IN code under Sam's deletion key, replacing his pending code.
        val register = client.json(
            "/commerce/account/register",
            """{"email":"account-deletion:sam@example.com","tosVersion":"v1","privacyVersion":"v1"}""",
        )
        assertEquals(HttpStatusCode.BadRequest, register.status)
        assertEquals(pending, store.getEmailVerification(ErasedAccounts.deletionOtpKey("sam@example.com")))
        assertEquals(HttpStatusCode.BadRequest, client.requestByEmail("account-deletion:sam@example.com").status)
        assertEquals(HttpStatusCode.BadRequest, client.requestByEmail("erased:x@example.com").status)
    }

    @Test
    fun anUnknownAddressWritesOnlyTheDecoy_neverARowNamingIt() = appTest { client ->
        client.requestByEmail("stranger@example.com")

        assertNotNull(store.getEmailVerification(ErasedAccounts.DECOY_OTP_KEY), "the same DB work as a real request")
        assertNull(store.getEmailVerification(ErasedAccounts.deletionOtpKey("stranger@example.com")))
        assertNull(store.getEmailVerification("stranger@example.com"))
    }
}
