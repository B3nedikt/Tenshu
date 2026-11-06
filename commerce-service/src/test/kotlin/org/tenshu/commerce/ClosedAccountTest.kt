package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.commerce.routing.StripeSupport
import org.tenshu.commerce.routing.TokenAdminAuth
import org.tenshu.commerce.stripe.FakeStripeClient
import org.tenshu.commerce.store.AccountStatus
import org.tenshu.commerce.store.InMemoryCommerceStore
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CE-5: a **closed** account must stop being issued credentials, and an operator must be able to
 * close and reopen one at all.
 *
 * The defect this pins: `gateBySession` checked the session, the current entitlement and the live
 * owner — but never `AccountStatus`. Since `/verify` writes a Free ledger row with a ~10-year
 * window, a closed account kept minting synced records and relay tokens for a decade while the
 * closure sweep was telling the relay to delete its content. The two halves of retention P3
 * disagreed with each other.
 */
class ClosedAccountTest {

    private val store = InMemoryCommerceStore()
    private val sender = RecordingEmailSender()
    private val clock = AtomicLong(1_700_000_000_000L)
    private val adminToken = "admin-secret"

    private fun appTest(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            module(store = store, emailSender = sender, now = { clock.get() })
        }
        block(client)
    }

    /** The admin console is a separate Ktor application on its own host/port; mirror that here. */
    private fun adminTest(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            adminModule(store = store, adminAuth = TokenAdminAuth(adminToken), now = { clock.get() })
        }
        block(client)
    }

    private suspend fun HttpClient.signUp(email: String, ownerId: String): String =
        registerAndVerify(sender, email, ownerId)

    private suspend fun HttpClient.mint(session: String) = post("/commerce/entitlement/mint") {
        contentType(ContentType.Application.Json)
        setBody("""{"sessionToken":"$session"}""")
    }

    private suspend fun HttpClient.token(session: String) = post("/commerce/entitlement/token") {
        contentType(ContentType.Application.Json)
        setBody("""{"sessionToken":"$session"}""")
    }

    @Test
    fun anActiveAccountMintsBothCarriers() = appTest { client ->
        val session = client.signUp("active@example.com", "owner-active")
        assertEquals(HttpStatusCode.OK, client.mint(session).status)
        assertEquals(HttpStatusCode.OK, client.token(session).status)
    }

    @Test
    fun aClosedAccountMintsNeitherCarrier() = appTest { client ->
        val session = client.signUp("closed@example.com", "owner-closed")
        // Sanity: it worked a moment ago, so a later refusal is caused by the closure and not by
        // some unrelated gate.
        assertEquals(HttpStatusCode.OK, client.mint(session).status)

        val account = assertNotNull(store.findAccountByEmail("closed@example.com"))
        store.closeAccount(account.id, clock.get())

        assertEquals(HttpStatusCode.Forbidden, client.mint(session).status)
        assertEquals(HttpStatusCode.Forbidden, client.token(session).status)
    }

    @Test
    fun theSessionItselfStaysValid_onlyMintingIsRefused() = appTest { client ->
        // Deliberate: closure blocks credentials, not authentication. A future reopen flow has to
        // be able to identify the user, so burning the session here would paint us into a corner.
        val session = client.signUp("session@example.com", "owner-session")
        val account = assertNotNull(store.findAccountByEmail("session@example.com"))
        store.closeAccount(account.id, clock.get())

        val refresh = client.post("/commerce/account/session") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionToken":"$session"}""")
        }
        assertEquals(HttpStatusCode.OK, refresh.status, "the session is still a valid session")
    }

    @Test
    fun reopeningRestoresMinting() = appTest { client ->
        val session = client.signUp("reopen@example.com", "owner-reopen")
        val account = assertNotNull(store.findAccountByEmail("reopen@example.com"))

        store.closeAccount(account.id, clock.get())
        assertEquals(HttpStatusCode.Forbidden, client.mint(session).status)

        store.reopenAccount(account.id)
        assertEquals(HttpStatusCode.OK, client.mint(session).status)
    }

    @Test
    fun aStripeEventForAClosedAccountGrantsNothing() = testApplication {
        // ⚠ Deliberately a refusal rather than an automatic reopen: closing does not cancel the
        // customer's Stripe subscription, so recurring `invoice.paid` events keep arriving — and
        // reopening on payment would silently resurrect an account closed for erasure.
        val webhookSecret = "whsec_closed"
        application {
            module(
                store = store,
                emailSender = sender,
                now = { clock.get() },
                stripe = StripeSupport(
                    client = FakeStripeClient(publicBaseUrl = ""),
                    priceIds = FakeStripeClient.fakePriceIds(),
                    webhookSecret = webhookSecret,
                    successUrl = "s",
                    cancelUrl = "c",
                    portalReturnUrl = "p",
                ),
            )
        }

        val session = client.signUp("stripe@example.com", "owner-stripe")
        val account = assertNotNull(store.findAccountByEmail("stripe@example.com"))
        store.closeAccount(account.id, clock.get())

        val payload = """{"id":"evt_closed","type":"checkout.session.completed","data":{"object":{"id":"cs_closed","customer_email":"stripe@example.com","amount_total":999,"currency":"eur","metadata":{"product_id":"tenshu.drive.premium"}}}}"""
        val ts = clock.get() / 1000
        val signature = "t=$ts,v1=${hmacSha256Hex(webhookSecret, "$ts.$payload")}"
        val response = client.post("/commerce/webhook/stripe") {
            header("Stripe-Signature", signature)
            setBody(payload)
        }
        // Still 200: a refused grant must not make Stripe retry the event forever.
        assertEquals(HttpStatusCode.OK, response.status)

        assertEquals(
            AccountStatus.Closed,
            assertNotNull(store.findAccountById(account.id)).status,
            "paying must not silently resurrect a closed account",
        )
        assertEquals(0, store.allInvoices().size, "no grant, so no invoice")
        assertEquals(HttpStatusCode.Forbidden, client.mint(session).status)
    }

    // --- admin lifecycle routes ----------------------------------------------------------------

    @Test
    fun adminCanCloseAndReopenAnAccount() = adminTest { admin ->
        store.createAccount("lifecycle@example.com", rail = org.tenshu.commerce.store.Rail.Free, createdAt = clock.get())

        val close = admin.post("/admin/accounts/close") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=lifecycle@example.com")
        }
        assertEquals(HttpStatusCode.OK, close.status)
        assertEquals(AccountStatus.Closed, assertNotNull(store.findAccountByEmail("lifecycle@example.com")).status)

        val reopen = admin.post("/admin/accounts/reopen") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=lifecycle@example.com")
        }
        assertEquals(HttpStatusCode.OK, reopen.status)
        assertEquals(AccountStatus.Active, assertNotNull(store.findAccountByEmail("lifecycle@example.com")).status)
    }

    @Test
    fun theMutationsRequireTheAdminCredential() = adminTest { admin ->
        store.createAccount("guarded@example.com", rail = org.tenshu.commerce.store.Rail.Free, createdAt = clock.get())

        val noToken = admin.post("/admin/accounts/close") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=guarded@example.com")
        }
        assertEquals(HttpStatusCode.Unauthorized, noToken.status)

        val wrongToken = admin.post("/admin/accounts/close") {
            header("Authorization", "Bearer nope")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=guarded@example.com")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongToken.status)

        // ⚠ Status-only assertions would pass against a route that answered 401 and closed anyway.
        assertEquals(AccountStatus.Active, assertNotNull(store.findAccountByEmail("guarded@example.com")).status)
    }

    @Test
    fun anUnknownAddressIsAnHonestNotFound() = adminTest { admin ->
        // No enumeration concern: the caller already holds the admin credential and can list every
        // account, so a uniform success would only hide an operator's typo.
        val response = admin.post("/admin/accounts/close") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=nobody@example.com")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun theUsersPageStatesHowToInvokeTheMutations() = adminTest { admin ->
        val body = admin.get("/admin/users") { header("Authorization", "Bearer $adminToken") }.bodyAsText()
        assertTrue(body.contains("/admin/accounts/close"), "the page should tell an operator the route")
        assertTrue(body.contains("/admin/accounts/reopen"))
    }

    @Test
    fun closingIsIdempotent() = appTest { _ ->
        val account = store.createAccount("idem@example.com", rail = org.tenshu.commerce.store.Rail.Free, createdAt = clock.get())
        store.closeAccount(account.id, clock.get())
        val firstClosedAt = assertNotNull(store.findAccountById(account.id)).closedAt

        clock.addAndGet(60_000)
        store.closeAccount(account.id, clock.get())

        assertEquals(
            firstClosedAt,
            assertNotNull(store.findAccountById(account.id)).closedAt,
            "a repeat close must not move the stamp — the grace window runs from the FIRST closure, " +
                "or a retry would silently extend retention past the published terms",
        )
    }
}
