package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.server.testing.testApplication
import org.tenshu.commerce.routing.AccountRateLimits
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.commerce.store.Rail
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cloudflare Turnstile on the mail-sending routes (`commerce-accounts-and-sessions.md` D16).
 *
 * ⚠ Every refusal here also asserts that **nothing was spent**: no mail, no account row, and no
 * send budget. A challenge that ran after the limiter would still let a bot burn the per-inbox and
 * global budgets real sign-ins share — which is the whole reason it exists.
 */
class TurnstileRoutingTest {

    private val store = InMemoryCommerceStore()
    private val sender = RecordingEmailSender()

    /** Every form Cloudflare's siteverify was sent, decoded. */
    private val verifyCalls = CopyOnWriteArrayList<Map<String, String>>()

    /** What the stub siteverify answers next: `true`/`false` for `success`, or null for a 500. */
    @Volatile
    private var siteverifyAnswers: Boolean? = true

    private val siteverify = MockEngine { request ->
        val form = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        verifyCalls += parseQueryString(form).entries().associate { (k, v) -> k to v.single() }
        when (val answer = siteverifyAnswers) {
            null -> respond("upstream", HttpStatusCode.InternalServerError)
            else -> respond(
                """{"success":$answer,"error-codes":[]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
    }

    private fun appTest(
        turnstileOn: Boolean = true,
        sendPerIpPerHour: Int = 0,
        sendGlobalPerDay: Int = 0,
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        application {
            module(
                store = store,
                emailSender = sender,
                otpTtlMs = 60_000L,
                otpMaxAttempts = 3,
                sessionTtlMs = 3_600_000L,
                webAppOrigin = WEB_ORIGIN,
                limits = AccountRateLimits.hourly(
                    sendPerIpPerHour = sendPerIpPerHour,
                    sendPerEmailPerHour = 0,
                    verifyPerEmailPerHour = 0,
                    sendGlobalPerDay = sendGlobalPerDay,
                ),
                turnstile = if (turnstileOn) TurnstileVerifier.create(SECRET, HttpClient(siteverify)) else null,
            )
        }
        block(client)
    }

    private suspend fun HttpClient.register(email: String, token: String? = null, browser: Boolean = true) =
        post("/commerce/account/register") {
            contentType(ContentType.Application.Json)
            if (browser) header(HttpHeaders.Origin, WEB_ORIGIN)
            val tokenField = token?.let { ""","turnstileToken":"$it"""" } ?: ""
            setBody("""{"email":"$email","tosVersion":"v1","privacyVersion":"v1"$tokenField}""")
        }

    private suspend fun HttpClient.deletionRequest(body: String, browser: Boolean = true) =
        post("/commerce/account/delete/request") {
            contentType(ContentType.Application.Json)
            if (browser) header(HttpHeaders.Origin, WEB_ORIGIN)
            setBody(body)
        }

    @Test
    fun browserRegisterWithoutATokenIsRefusedBeforeAnythingIsSpent() = appTest(sendPerIpPerHour = 1, sendGlobalPerDay = 1) { client ->
        val refused = client.register("a@example.com")
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertTrue("challenge_failed" in refused.bodyAsText())
        assertNull(sender.lastCode("a@example.com"), "a refused challenge must send no mail")
        assertNull(store.findAccountByEmail("a@example.com"), "a refused challenge must create no account")
        assertTrue(verifyCalls.isEmpty(), "a missing token is not worth a siteverify round trip")

        // The per-IP and global budgets are ONE — so had the refusal spent either, this would be a 429.
        assertEquals(HttpStatusCode.Accepted, client.register("a@example.com", token = "good").status)
        assertNotNull(sender.lastCode("a@example.com"))
    }

    @Test
    fun aPassedChallengeSendsTheSecretTokenAndIpToSiteverify() = appTest { client ->
        assertEquals(HttpStatusCode.Accepted, client.register("b@example.com", token = "tok-123").status)
        val call = verifyCalls.single()
        assertEquals(SECRET, call["secret"])
        assertEquals("tok-123", call["response"])
        assertTrue(!call["remoteip"].isNullOrBlank(), "the client IP rides along: $call")
    }

    @Test
    fun aRejectedTokenIsRefusedAndSpendsNothing() = appTest(sendPerIpPerHour = 1) { client ->
        siteverifyAnswers = false
        assertEquals(HttpStatusCode.Forbidden, client.register("c@example.com", token = "replayed").status)
        assertNull(sender.lastCode("c@example.com"))
        assertNull(store.findAccountByEmail("c@example.com"))

        siteverifyAnswers = true
        assertEquals(HttpStatusCode.Accepted, client.register("c@example.com", token = "fresh").status)
    }

    @Test
    fun noAnswerFromCloudflareFailsClosed() = appTest { client ->
        siteverifyAnswers = null
        val response = client.register("d@example.com", token = "tok")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue("challenge_unavailable" in response.bodyAsText())
        assertNull(sender.lastCode("d@example.com"))
    }

    @Test
    fun anOversizedTokenIsRefusedWithoutARoundTrip() = appTest { client ->
        val huge = "x".repeat(TurnstileVerifier.MAX_TOKEN_LENGTH + 1)
        assertEquals(HttpStatusCode.Forbidden, client.register("e@example.com", token = huge).status)
        assertTrue(verifyCalls.isEmpty())
    }

    @Test
    fun aNativeRegisterWithNoOriginIsNotChallenged() = appTest { client ->
        // ⚠ The D16 exemption: the apps send no Origin and cannot run the widget.
        assertEquals(HttpStatusCode.Accepted, client.register("f@example.com", browser = false).status)
        assertNotNull(sender.lastCode("f@example.com"))
        assertTrue(verifyCalls.isEmpty())
    }

    @Test
    fun deletionByEmailIsChallengedEvenWithoutAnOrigin() = appTest(sendGlobalPerDay = 1) { client ->
        store.createAccount("g@example.com", rail = Rail.Free, createdAt = 1L)
        // Only the web page sends the {email} variant, so nothing exempts it — dropping Origin buys a
        // script nothing here.
        assertEquals(HttpStatusCode.Forbidden, client.deletionRequest("""{"email":"g@example.com"}""", browser = false).status)
        assertEquals(HttpStatusCode.Forbidden, client.deletionRequest("""{"email":"g@example.com"}""").status)
        assertNull(sender.lastDeletionCode("g@example.com"), "a refused challenge must send no mail")

        // The global budget is ONE: the refusals above spent none of it.
        assertEquals(
            HttpStatusCode.Accepted,
            client.deletionRequest("""{"email":"g@example.com","turnstileToken":"tok"}""").status,
        )
        assertNotNull(sender.lastDeletionCode("g@example.com"))
    }

    @Test
    fun deletionBySessionIsNotChallenged() = appTest { client ->
        // A session already proves the inbox; the apps cannot run the widget. An unknown session is
        // the route's own 401, reached without a siteverify call.
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.deletionRequest("""{"sessionToken":"not-a-session"}""", browser = false).status,
        )
        assertTrue(verifyCalls.isEmpty())
    }

    @Test
    fun offLeavesBothRoutesAsTheyWere() = appTest(turnstileOn = false) { client ->
        assertEquals(HttpStatusCode.Accepted, client.register("h@example.com").status)
        assertEquals(HttpStatusCode.Accepted, client.deletionRequest("""{"email":"h@example.com"}""").status)
        assertTrue(verifyCalls.isEmpty())
    }

    @Test
    fun configReadsTheSecretAndNeverPrintsIt() {
        val config = CommerceConfig.fromEnv { if (it == "TURNSTILE_SECRET_KEY") " 0x4AAA-secret " else null }
        assertEquals("0x4AAA-secret", config.turnstileSecretKey)
        assertTrue("0x4AAA-secret" !in config.toString())
        assertTrue("turnstileSecretKey=set" in config.toString())
        assertNull(CommerceConfig.fromEnv { if (it == "TURNSTILE_SECRET_KEY") "  " else null }.turnstileSecretKey)
    }

    private companion object {
        const val WEB_ORIGIN = "https://tenshu.app"
        const val SECRET = "test-turnstile-secret"
    }
}
