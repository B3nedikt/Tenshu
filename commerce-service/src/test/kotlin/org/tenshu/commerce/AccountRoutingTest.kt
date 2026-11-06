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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HTTP coverage for the §4.2 account endpoints: the register → verify → session happy path (token
 * issued, owner bound, Free entitlement granted); and the OTP failure modes (wrong / expired /
 * over-attempts → 401). Re-gate coverage (`unverified → /mint 401`) lives here too.
 */
class AccountRoutingTest {

    private val store = InMemoryCommerceStore()
    private val sender = RecordingEmailSender()
    private val clock = AtomicLong(1_000_000L)
    private val otpTtlMs = 60_000L
    private val maxAttempts = 3

    private fun appTest(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            module(
                store = store,
                emailSender = sender,
                otpTtlMs = otpTtlMs,
                otpMaxAttempts = maxAttempts,
                sessionTtlMs = 3_600_000L,
                now = { clock.get() },
            )
        }
        block(client)
    }

    private suspend fun HttpClient.register(email: String) = post("/commerce/account/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"email":"$email","tosVersion":"v1","privacyVersion":"v1"}""")
    }

    private suspend fun HttpClient.verify(email: String, code: String, ownerId: String) =
        post("/commerce/account/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","code":"$code","ownerId":"$ownerId"}""")
        }

    @Test
    fun registerVerifySession_happyPath() = appTest { client ->
        val registerResponse = client.register("alice@example.com")
        assertEquals(HttpStatusCode.Accepted, registerResponse.status)

        val token = client.registerAndVerify(sender, "alice@example.com", ownerId = "owner-alice")
        assertTrue(token.isNotBlank())

        val account = store.findAccountByEmail("alice@example.com")
        assertNotNull(account)
        assertNotNull(account.emailVerifiedAt, "verify marks the account verified")
        assertEquals("owner-alice", store.liveOwner(account.id))
        assertEquals(ProductCatalog.FREE, store.currentEntitlement(account.id, clock.get())?.productId)

        // The session token is now usable.
        val sessionResponse = client.post("/commerce/account/session") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionToken":"$token"}""")
        }
        assertEquals(HttpStatusCode.OK, sessionResponse.status)
    }

    @Test
    fun wrongCode_isUnauthorized() = appTest { client ->
        client.register("bob@example.com")
        val response = client.verify("bob@example.com", code = wrongCodeFor("bob@example.com"), ownerId = "owner-bob")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    /** A 6-digit code guaranteed not to equal the captured real one (deterministic, no flake). */
    private fun wrongCodeFor(email: String): String =
        if (sender.lastCode(email) == "000000") "111111" else "000000"

    @Test
    fun expiredCode_isUnauthorized() = appTest { client ->
        client.register("carol@example.com")
        val code = sender.lastCode("carol@example.com")!!
        clock.addAndGet(otpTtlMs + 1) // walk past the OTP TTL
        val response = client.verify("carol@example.com", code = code, ownerId = "owner-carol")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun tooManyAttempts_burnsTheCode() = appTest { client ->
        client.register("dave@example.com")
        val realCode = sender.lastCode("dave@example.com")!!
        val wrong = wrongCodeFor("dave@example.com")
        repeat(maxAttempts) {
            assertEquals(HttpStatusCode.Unauthorized, client.verify("dave@example.com", wrong, "owner-dave").status)
        }
        // Even the correct code now fails — the attempts ceiling burned it.
        assertEquals(HttpStatusCode.Unauthorized, client.verify("dave@example.com", realCode, "owner-dave").status)
    }

    @Test
    fun verifyUnknownEmail_isUnauthorized() = appTest { client ->
        val response = client.verify("nobody@example.com", code = "123456", ownerId = "owner-x")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun mintWithoutSession_isUnauthorized() = appTest { client ->
        val response = client.post("/commerce/entitlement/mint") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionToken":"not-a-real-token"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue("publisherSignature" !in response.bodyAsText())
    }

    @Test
    fun mintWithSession_mints() = appTest { client ->
        val token = client.registerAndVerify(sender, "erin@example.com", ownerId = "owner-erin")
        val response = client.post("/commerce/entitlement/mint") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionToken":"$token"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("publisherSignature" in response.bodyAsText())
        assertTrue(ProductCatalog.FREE in response.bodyAsText(), "free account mints the free product")
    }
}
