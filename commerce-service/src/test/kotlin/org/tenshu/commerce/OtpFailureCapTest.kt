package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.commerce.store.ErasedAccounts
import org.tenshu.commerce.store.InMemoryCommerceStore
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The daily cap on failed one-time-code checks for ordinary accounts (`commerce-accounts-and-sessions.md`
 * D15, threat model T-CB-13): 20 per address per rolling 24 h by default, across every code issued and
 * across `/verify` and `/delete/confirm`, persisted, and then a 429 even for the right code.
 */
class OtpFailureCapTest {

    private val store = InMemoryCommerceStore()
    private val sender = RecordingEmailSender()
    private val clock = AtomicLong(1_700_000_000_000L)
    private val cap = CommerceConfig.DEFAULT_OTP_FAILED_PER_EMAIL_PER_DAY

    private fun appTest(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            // Production's default cap — the module's default — and a short per-code burn, so the
            // failures have to span several issued codes.
            module(store = store, emailSender = sender, otpMaxAttempts = 3, now = { clock.get() })
        }
        block(client)
    }

    private suspend fun HttpClient.json(path: String, body: String) = post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.register(email: String) =
        json("/commerce/account/register", """{"email":"$email","tosVersion":"v1","privacyVersion":"v1"}""")

    private suspend fun HttpClient.verify(email: String, code: String) =
        json("/commerce/account/verify", """{"email":"$email","code":"$code","ownerId":"owner-x"}""")

    /** [n] wrong guesses for [email], re-requesting a code whenever the current one is burned. */
    private suspend fun HttpClient.failTimes(email: String, n: Int) {
        repeat(n) { i ->
            if (i % 3 == 0) register(email)
            clock.addAndGet(1_000L)
            val wrong = if (sender.lastCode(email) == "000000") "111111" else "000000"
            assertEquals(HttpStatusCode.Unauthorized, verify(email, wrong).status, "guess ${i + 1}")
        }
    }

    @Test
    fun theCapCountsFailuresAcrossIssuedCodesThenRefusesTheRightCode() = appTest { client ->
        assertEquals(20, cap)
        client.failTimes("alice@example.com", cap) // seven codes, three wrong guesses each
        client.register("alice@example.com")
        val right = assertNotNull(sender.lastCode("alice@example.com"))
        val refused = client.verify("alice@example.com", right)
        assertEquals(HttpStatusCode.TooManyRequests, refused.status, "over the cap, even the right code")
        assertEquals("""{"error":"rate_limited"}""", refused.bodyAsTextCompact(), "the existing 429 shape")
        assertNull(store.findAccountByEmail("alice@example.com")!!.emailVerifiedAt)
    }

    @Test
    fun oneUnderTheCapStillSignsIn() = appTest { client ->
        client.failTimes("alice@example.com", cap - 1)
        client.register("alice@example.com")
        assertEquals(HttpStatusCode.OK, client.verify("alice@example.com", sender.lastCode("alice@example.com")!!).status)
    }

    @Test
    fun theCapSurvivesARestart() {
        appTest { client -> client.failTimes("alice@example.com", cap) }
        // A new process over the same durable store.
        appTest { client ->
            client.register("alice@example.com")
            assertEquals(HttpStatusCode.TooManyRequests, client.verify("alice@example.com", sender.lastCode("alice@example.com")!!).status)
        }
    }

    @Test
    fun theWindowRollsAfter24Hours() = appTest { client ->
        val start = clock.get()
        client.failTimes("alice@example.com", cap)
        clock.set(start + PersistedFailureLimit.DAY_MS - 1)
        client.register("alice@example.com")
        assertEquals(HttpStatusCode.TooManyRequests, client.verify("alice@example.com", sender.lastCode("alice@example.com")!!).status)
        // 24 h after the first counted failure (plus the per-guess clock steps), a slot frees.
        clock.set(start + PersistedFailureLimit.DAY_MS + 1_000L)
        client.register("alice@example.com")
        assertEquals(HttpStatusCode.OK, client.verify("alice@example.com", sender.lastCode("alice@example.com")!!).status)
    }

    @Test
    fun theCapIsPerAddress() = appTest { client ->
        client.failTimes("alice@example.com", cap)
        client.register("bob@example.com")
        assertEquals(HttpStatusCode.OK, client.verify("bob@example.com", sender.lastCode("bob@example.com")!!).status)
    }

    @Test
    fun theDeletionConfirmSharesTheCap() = appTest { client ->
        val token = client.registerAndVerify(sender, "alice@example.com", ownerId = "owner-alice")
        client.failTimes("alice@example.com", cap)
        client.json("/commerce/account/delete/request", """{"sessionToken":"$token"}""")
        val code = assertNotNull(sender.lastDeletionCode("alice@example.com"))
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.json("/commerce/account/delete/confirm", """{"sessionToken":"$token","code":"$code"}""").status,
        )
        assertNull(store.findAccountByEmail("alice@example.com")!!.erasedAt, "nothing erased over the cap")
    }

    @Test
    fun wrongDeletionCodesCountTowardSignIn() = appTest { client ->
        val token = client.registerAndVerify(sender, "alice@example.com", ownerId = "owner-alice")
        repeat(cap) {
            if (it % 3 == 0) client.json("/commerce/account/delete/request", """{"sessionToken":"$token"}""")
            client.json("/commerce/account/delete/confirm", """{"sessionToken":"$token","code":"000000"}""")
        }
        client.register("alice@example.com")
        assertEquals(HttpStatusCode.TooManyRequests, client.verify("alice@example.com", sender.lastCode("alice@example.com")!!).status)
    }

    @Test
    fun anAddressWithNoPendingCodeLocksTooSoThe429IsNoOracle() = appTest { client ->
        // No /register: nothing was ever issued. It must lock exactly like an address that had codes.
        repeat(cap) { client.verify("stranger@example.com", "000000") }
        assertEquals(HttpStatusCode.TooManyRequests, client.verify("stranger@example.com", "000000").status)
        // ...and its row does not name the stranger in the clear.
        assertNull(store.getEmailVerification(PersistedFailureLimit.OTP_FAILURES_PREFIX + "stranger@example.com"))
        assertNotNull(store.getEmailVerification(ErasedAccounts.otpFailuresKey("stranger@example.com")))
    }

    @Test
    fun erasureDeletesTheFailureRow() = appTest { client ->
        val token = client.registerAndVerify(sender, "alice@example.com", ownerId = "owner-alice")
        client.failTimes("alice@example.com", 2)
        assertNotNull(store.getEmailVerification(ErasedAccounts.otpFailuresKey("alice@example.com")))
        client.json("/commerce/account/delete/request", """{"sessionToken":"$token"}""")
        val code = sender.lastDeletionCode("alice@example.com")!!
        assertEquals(HttpStatusCode.OK, client.json("/commerce/account/delete/confirm", """{"sessionToken":"$token","code":"$code"}""").status)
        assertNull(store.getEmailVerification(ErasedAccounts.otpFailuresKey("alice@example.com")), "erasure must not leave the address behind")
    }

    @Test
    fun aQuietAddressLeavesNoRowBehind() = appTest { client ->
        client.failTimes("alice@example.com", 1)
        clock.addAndGet(PersistedFailureLimit.DAY_MS)
        client.register("alice@example.com")
        client.verify("alice@example.com", sender.lastCode("alice@example.com")!!)
        assertFalse(store.getEmailVerification(ErasedAccounts.otpFailuresKey("alice@example.com")) != null)
    }

    private suspend fun HttpResponse.bodyAsTextCompact(): String = bodyAsText().replace(" ", "")
}
