package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.commerce.store.InMemoryCommerceStore
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The operator's signup cap ([SignupCap]): past it, a new address is mailed "sign-ups are paused"
 * instead of a code, with the same `202` — and existing accounts still sign in.
 */
class SignupCapRoutingTest {

    private val store = InMemoryCommerceStore()
    private val sender = RecordingEmailSender()
    private val clock = AtomicLong(10L * DAY_MS)

    private fun appTest(cap: SignupCap, block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            module(store = store, emailSender = sender, now = { clock.get() }, signupCap = cap)
        }
        block(client)
    }

    private suspend fun HttpClient.register(email: String) = post("/commerce/account/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"email":"$email","tosVersion":"v1","privacyVersion":"v1"}""")
    }

    @Test
    fun dailyCapReached_newAddressGetsThePausedNotice_sameAnswer_noAccount() = appTest(SignupCap(perDay = 1, total = 0)) { client ->
        client.registerAndVerify(sender, "first@example.com", ownerId = "owner-1")

        val response = client.register("second@example.com")
        assertEquals(HttpStatusCode.Accepted, response.status, "same answer as a code, so no enumeration oracle")
        assertTrue(response.bodyAsText().contains("otp_sent"))
        assertEquals(listOf("second@example.com"), sender.signupsPausedNotices)
        assertNull(sender.lastCode("second@example.com"), "no code, so no way in")
        assertNull(store.findAccountByEmail("second@example.com"), "no account row while paused")
    }

    @Test
    fun existingVerifiedAccount_stillSignsIn_whileTheCapIsReached() = appTest(SignupCap(perDay = 0, total = 1)) { client ->
        client.registerAndVerify(sender, "first@example.com", ownerId = "owner-1")
        sender.forget("first@example.com")

        assertEquals(HttpStatusCode.Accepted, client.register("first@example.com").status)
        assertNotNull(sender.lastCode("first@example.com"), "the cap limits new storage, not access")
        assertTrue(sender.signupsPausedNotices.isEmpty())
    }

    @Test
    fun dailyCap_rollsOver_after24Hours() = appTest(SignupCap(perDay = 1, total = 0)) { client ->
        client.registerAndVerify(sender, "first@example.com", ownerId = "owner-1")
        clock.addAndGet(DAY_MS + 1)

        client.register("second@example.com")
        assertNotNull(sender.lastCode("second@example.com"))
    }

    @Test
    fun returningSignIns_doNotSpendTheDailyCap() = appTest(SignupCap(perDay = 1, total = 0)) { client ->
        client.registerAndVerify(sender, "first@example.com", ownerId = "owner-1")
        clock.addAndGet(2 * DAY_MS)
        // ⚠ Every sign-in re-verifies the email. Counted by that time, this returning user would fill
        // today's budget for a new account.
        client.registerAndVerify(sender, "first@example.com", ownerId = "owner-1")

        client.register("second@example.com")
        assertNotNull(sender.lastCode("second@example.com"), "a returning sign-in is not a new account")
        assertTrue(sender.signupsPausedNotices.isEmpty())
    }

    @Test
    fun unverifiedCodeRequests_doNotFillTheCap() = appTest(SignupCap(perDay = 1, total = 0)) { client ->
        // Anyone can ask for codes to addresses they do not own; that must not close sign-ups.
        repeat(5) { i -> client.register("spam$i@example.com") }
        client.register("real@example.com")
        assertNotNull(sender.lastCode("real@example.com"))
        assertTrue(sender.signupsPausedNotices.isEmpty())
    }

    @Test
    fun config_parsesBothCaps_offByDefault_andRefusesATypo() {
        val default = CommerceConfig.fromEnv { null }
        assertEquals(0, default.signupLimitPerDay)
        assertEquals(0, default.signupLimitTotal)
        val set = CommerceConfig.fromEnv { mapOf("SIGNUP_LIMIT_PER_DAY" to "50", "SIGNUP_LIMIT_TOTAL" to "1000")[it] }
        assertEquals(50, set.signupLimitPerDay)
        assertEquals(1000, set.signupLimitTotal)
        assertFailsWith<IllegalArgumentException> { CommerceConfig.fromEnv { if (it == "SIGNUP_LIMIT_PER_DAY") "50/day" else null } }
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1_000L
    }
}
