package org.tenshu.commerce

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.tenshu.commerce.routing.TokenAdminAuth
import org.tenshu.commerce.store.AccountStatus
import org.tenshu.commerce.store.CommerceStore
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.commerce.store.Rail
import org.tenshu.commerce.stripe.BillingSubscription
import org.tenshu.commerce.stripe.ChargeRefund
import org.tenshu.commerce.stripe.CheckoutSessionRequest
import org.tenshu.commerce.stripe.InvoicePaymentEvidence
import org.tenshu.commerce.stripe.StripeClient
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Closing an account must also stop it being billed
 * (`commerce-end-to-end-dry-run.md` CE-5 follow-up).
 *
 * CE-5 made a closed account un-grantable — `/mint`, `/token` and the webhook all refuse it. On its
 * own that turns "closed" into **"paying for nothing"**, because the Stripe subscription keeps
 * renewing against a service we now decline to provide. These tests pin the two properties that
 * make the fix trustworthy: the cancellation actually happens on close, and re-running it is free.
 */
class SubscriptionCancellerTest {

    private val clock = AtomicLong(1_700_000_000_000L)

    /** Records what it was asked to cancel, and can be told to fail. */
    private class RecordingStripe(private var failing: Boolean = false) : StripeClient {
        val cancelledFor = mutableListOf<String>()
        private val alreadyCancelled = mutableSetOf<String>()

        fun startFailing() { failing = true }
        fun stopFailing() { failing = false }

        override suspend fun createCheckoutSession(request: CheckoutSessionRequest): String? = null
        override suspend fun createPortalSession(customerId: String, returnUrl: String): String? = null

        override suspend fun billingSubscriptions(customerId: String): List<BillingSubscription>? = emptyList()
        override suspend fun refundsForCharge(chargeId: String): List<ChargeRefund>? = emptyList()
        override suspend fun invoicePaymentEvidence(invoiceId: String): InvoicePaymentEvidence? = null
        override suspend fun cancelActiveSubscriptions(customerId: String): Int? {
            if (failing) return null
            cancelledFor += customerId
            // Models the rail: an already-cancelled subscription is simply not in the billing set.
            return if (alreadyCancelled.add(customerId)) 1 else 0
        }
    }

    private fun CommerceStore.closedAccountWithCustomer(
        email: String,
        customerId: String,
        closedAt: Long = clock.get(),
    ) = createAccount(email, rail = Rail.Stripe, createdAt = clock.get()).also {
        attachStripeCustomer(it.id, customerId)
        closeAccount(it.id, closedAt)
    }

    @Test
    fun cancellingAClosedAccountStopsItsBilling() = runBlocking {
        val store = InMemoryCommerceStore()
        val stripe = RecordingStripe()
        val account = store.closedAccountWithCustomer("a@example.com", "cus_a")

        val ok = SubscriptionCanceller(store, stripe) { clock.get() }
            .cancelFor(assertNotNull(store.findAccountById(account.id)))

        assertTrue(ok)
        assertEquals(listOf("cus_a"), stripe.cancelledFor)
    }

    @Test
    fun anAccountWithNoStripeCustomerIsATrivialSuccess() = runBlocking {
        val store = InMemoryCommerceStore()
        val stripe = RecordingStripe()
        val account = store.createAccount("free@example.com", rail = Rail.Free, createdAt = clock.get())
        store.closeAccount(account.id, clock.get())

        val ok = SubscriptionCanceller(store, stripe) { clock.get() }
            .cancelFor(assertNotNull(store.findAccountById(account.id)))

        assertTrue(ok, "a free account has nothing to cancel; that is success, not failure")
        assertTrue(stripe.cancelledFor.isEmpty(), "and the rail must not be called at all")
    }

    @Test
    fun aRailFailureIsReportedSoItCanBeRetried() = runBlocking {
        val store = InMemoryCommerceStore()
        val stripe = RecordingStripe(failing = true)
        val account = store.closedAccountWithCustomer("fail@example.com", "cus_fail")

        val ok = SubscriptionCanceller(store, stripe) { clock.get() }
            .cancelFor(assertNotNull(store.findAccountById(account.id)))

        assertTrue(!ok, "a failed cancellation must not report success — it is owed a retry")
    }

    @Test
    fun theSweepFindsAndReconcilesAFailedCancellation() = runBlocking {
        // The self-healing property: Stripe was down when the account was closed, so nothing was
        // cancelled; once it recovers the sweep finishes the job with no queue and no flag to track.
        val store = InMemoryCommerceStore()
        val stripe = RecordingStripe(failing = true)
        val canceller = SubscriptionCanceller(store, stripe) { clock.get() }

        val account = store.closedAccountWithCustomer("outage@example.com", "cus_outage")
        assertTrue(!canceller.cancelFor(assertNotNull(store.findAccountById(account.id))))

        val duringOutage = canceller.sweepOnce(windowMs = HOUR)
        assertEquals(1, duringOutage.examined)
        assertEquals(1, duringOutage.failed)

        stripe.stopFailing()
        val afterRecovery = canceller.sweepOnce(windowMs = HOUR)
        assertEquals(1, afterRecovery.reconciled)
        assertEquals(0, afterRecovery.failed)
        assertEquals(listOf("cus_outage"), stripe.cancelledFor)
    }

    @Test
    fun reSweepingIsFreeRatherThanRepeatedlyCancelling() = runBlocking {
        // This is what lets the sweep run forever with no state: a second pass finds nothing
        // billing and reports 0, instead of needing a "already cancelled" flag on the account.
        val store = InMemoryCommerceStore()
        val stripe = RecordingStripe()
        val canceller = SubscriptionCanceller(store, stripe) { clock.get() }
        store.closedAccountWithCustomer("idem@example.com", "cus_idem")

        repeat(3) { canceller.sweepOnce(windowMs = HOUR) }

        assertEquals(3, stripe.cancelledFor.size, "the rail is asked each time — that is fine, it is cheap")
        // ...but only the first pass actually cancelled anything.
        assertEquals(AccountStatus.Closed, assertNotNull(store.findAccountByEmail("idem@example.com")).status)
    }

    @Test
    fun theSweepIgnoresOpenAccountsAndOnesOutsideTheWindow() = runBlocking {
        val store = InMemoryCommerceStore()
        val stripe = RecordingStripe()
        val canceller = SubscriptionCanceller(store, stripe) { clock.get() }

        // Active, with a live subscription: cancelling this would cut off a paying customer.
        val active = store.createAccount("active@example.com", rail = Rail.Stripe, createdAt = clock.get())
        store.attachStripeCustomer(active.id, "cus_active")

        // Closed, but long ago — outside the retry window that bounds the swept set.
        store.closedAccountWithCustomer("ancient@example.com", "cus_ancient", closedAt = clock.get() - 10 * HOUR)

        val report = canceller.sweepOnce(windowMs = HOUR)

        assertEquals(0, report.examined)
        assertTrue(stripe.cancelledFor.isEmpty(), "an ACTIVE customer must never be cancelled")
    }

    /**
     * ⚠⚠ The ordering decision, asserted: closing is durable even when the cancellation fails.
     * The reverse order would leave an *active* account with no subscription — the customer stops
     * paying, keeps the service, and nothing retries because nothing looks wrong.
     */
    @Test
    fun theCloseStandsEvenWhenCancellationFails() = testApplication {
        val store = InMemoryCommerceStore()
        val stripe = RecordingStripe(failing = true)
        val adminToken = "admin-secret"
        application {
            adminModule(
                store = store,
                adminAuth = TokenAdminAuth(adminToken),
                now = { clock.get() },
                subscriptionCanceller = SubscriptionCanceller(store, stripe) { clock.get() },
            )
        }
        val account = store.createAccount("order@example.com", rail = Rail.Stripe, createdAt = clock.get())
        store.attachStripeCustomer(account.id, "cus_order")

        val response = client.post("/admin/accounts/close") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=order@example.com")
        }

        assertEquals(HttpStatusCode.OK, response.status, "the operator asked to close; the close happened")
        assertEquals(
            AccountStatus.Closed,
            assertNotNull(store.findAccountById(account.id)).status,
            "a Stripe outage must not be able to veto a closure",
        )
    }

    @Test
    fun closingThroughTheAdminRouteCancelsTheSubscription() = testApplication {
        val store = InMemoryCommerceStore()
        val stripe = RecordingStripe()
        val adminToken = "admin-secret"
        application {
            adminModule(
                store = store,
                adminAuth = TokenAdminAuth(adminToken),
                now = { clock.get() },
                subscriptionCanceller = SubscriptionCanceller(store, stripe) { clock.get() },
            )
        }
        val account = store.createAccount("billed@example.com", rail = Rail.Stripe, createdAt = clock.get())
        store.attachStripeCustomer(account.id, "cus_billed")

        client.post("/admin/accounts/close") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=billed@example.com")
        }

        assertEquals(
            listOf("cus_billed"),
            stripe.cancelledFor,
            "closing must reach the billing rail — otherwise 'closed' means 'paying for nothing'",
        )
    }

    @Test
    fun reopeningDoesNotResurrectTheSubscription() = testApplication {
        // Worth pinning: reopen restores the *account*, not the billing relationship. The customer
        // has to re-subscribe, which is correct — we cancelled with them, not on their behalf.
        val store = InMemoryCommerceStore()
        val stripe = RecordingStripe()
        val adminToken = "admin-secret"
        application {
            adminModule(
                store = store,
                adminAuth = TokenAdminAuth(adminToken),
                now = { clock.get() },
                subscriptionCanceller = SubscriptionCanceller(store, stripe) { clock.get() },
            )
        }
        val account = store.createAccount("again@example.com", rail = Rail.Stripe, createdAt = clock.get())
        store.attachStripeCustomer(account.id, "cus_again")

        client.post("/admin/accounts/close") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=again@example.com")
        }
        client.post("/admin/accounts/reopen") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=again@example.com")
        }

        assertEquals(AccountStatus.Active, assertNotNull(store.findAccountById(account.id)).status)
        assertEquals(1, stripe.cancelledFor.size, "reopening must not un-cancel anything")
    }

    private companion object {
        const val HOUR: Long = 60L * 60 * 1000
    }
}
