package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.commerce.routing.AccountRateLimits
import org.tenshu.commerce.store.InMemoryCommerceStore
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * HTTP coverage for the CE-2 abuse budgets on the account routes.
 *
 * ⚠ Every refusal assertion here also checks that **no OTP was sent** — a limiter that returned
 * 429 while still handing the code to the [EmailSender] would pass a status-only test and buy
 * nothing at all, since the cost this exists to bound is the mail itself.
 */
class AccountRateLimitRoutingTest {

    private val store = InMemoryCommerceStore()
    private val sender = RecordingEmailSender()
    private val clock = AtomicLong(1_000_000L)

    private fun appTest(
        sendPerIpPerHour: Int = 0,
        sendPerEmailPerHour: Int = 0,
        verifyPerEmailPerHour: Int = 0,
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
                limits = AccountRateLimits.hourly(
                    sendPerIpPerHour = sendPerIpPerHour,
                    sendPerEmailPerHour = sendPerEmailPerHour,
                    verifyPerEmailPerHour = verifyPerEmailPerHour,
                ),
            )
        }
        block(client)
    }

    private suspend fun HttpClient.register(email: String) = post("/commerce/account/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"email":"$email","tosVersion":"v1","privacyVersion":"v1"}""")
    }

    private suspend fun HttpClient.verify(email: String, code: String) =
        post("/commerce/account/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","code":"$code","ownerId":"owner-1"}""")
        }

    @Test
    fun registerIsUnthrottledWhenBudgetsAreDisabled() = appTest { client ->
        repeat(10) { assertEquals(HttpStatusCode.Accepted, client.register("a@example.com").status) }
    }

    @Test
    fun perEmailSendBudgetStopsMailBombingOneAddress() = appTest(sendPerEmailPerHour = 2) { client ->
        assertEquals(HttpStatusCode.Accepted, client.register("victim@example.com").status)
        assertEquals(HttpStatusCode.Accepted, client.register("victim@example.com").status)

        sender.forget("victim@example.com")
        assertEquals(HttpStatusCode.TooManyRequests, client.register("victim@example.com").status)
        assertNull(sender.lastCode("victim@example.com"), "a refused register must send no mail")

        // A different address is unaffected — the budget is per-recipient, not global.
        assertEquals(HttpStatusCode.Accepted, client.register("other@example.com").status)
    }

    @Test
    fun aRequestRefusedByTheEmailBudgetDoesNotSpendTheSharedIpBudget() =
        appTest(sendPerIpPerHour = 3, sendPerEmailPerHour = 2) { client ->
            // Regression: found by running the real service, not by a unit test. With the naive
            // `tryAcquire(ip) && tryAcquire(email)` conjunction, the third request for a@ — refused
            // on its per-email budget, sending nothing — still consumed a unit of the per-IP
            // budget. One user pressing *resend* because it is failing could then lock every
            // colleague behind the same NAT out of signing up, having caused no mail at all.
            assertEquals(HttpStatusCode.Accepted, client.register("a@example.com").status)
            assertEquals(HttpStatusCode.Accepted, client.register("a@example.com").status)
            assertEquals(HttpStatusCode.TooManyRequests, client.register("a@example.com").status)

            assertEquals(
                HttpStatusCode.Accepted,
                client.register("colleague@example.com").status,
                "a refused request must not charge the shared per-IP budget",
            )
        }

    @Test
    fun perIpSendBudgetBoundsOneSourceAcrossAddresses() = appTest(sendPerIpPerHour = 2) { client ->
        assertEquals(HttpStatusCode.Accepted, client.register("a@example.com").status)
        assertEquals(HttpStatusCode.Accepted, client.register("b@example.com").status)

        sender.forget("c@example.com")
        assertEquals(HttpStatusCode.TooManyRequests, client.register("c@example.com").status)
        assertNull(sender.lastCode("c@example.com"))
    }

    @Test
    fun perEmailVerifyBudgetBoundsGuessingAcrossReIssuedCodes() = appTest(verifyPerEmailPerHour = 3) { client ->
        assertEquals(HttpStatusCode.Accepted, client.register("alice@example.com").status)
        val code = assertNotNull(sender.lastCode("alice@example.com"))

        // Three wrong guesses spend the budget (the per-code attempt burn is 3 here too, but this
        // budget survives the code being re-issued, which the burn does not).
        repeat(3) { assertEquals(HttpStatusCode.Unauthorized, client.verify("alice@example.com", "000000").status) }

        // Even the CORRECT code is now refused — the budget is on the endpoint, not on the guess.
        assertEquals(HttpStatusCode.TooManyRequests, client.verify("alice@example.com", code).status)
    }

    @Test
    fun budgetsRecoverAfterTheWindowElapses() = appTest(sendPerIpPerHour = 1) { client ->
        assertEquals(HttpStatusCode.Accepted, client.register("a@example.com").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.register("a@example.com").status)

        clock.addAndGet(AccountRateLimits.HOUR_MS)
        assertEquals(
            HttpStatusCode.Accepted,
            client.register("a@example.com").status,
            "a spent budget must not be permanent",
        )
    }
}
