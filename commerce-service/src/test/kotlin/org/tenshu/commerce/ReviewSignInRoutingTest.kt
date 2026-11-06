package org.tenshu.commerce

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.tenshu.commerce.routing.VerifyResponse
import org.tenshu.commerce.store.AccountStatus
import org.tenshu.commerce.store.ErasedAccounts
import org.tenshu.commerce.store.InMemoryCommerceStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store-review sign-in on the routes (`commerce-accounts-and-sessions.md` D13, threat model
 * T-CB-12): listed addresses sign in — and confirm a deletion — with the fixed code only, never a
 * mailed one, and get no mail; every other address never with the fixed code; a per-address lockout;
 * and nothing secret in the log.
 */
class ReviewSignInRoutingTest {

    private val store = InMemoryCommerceStore()
    private val sender = RecordingEmailSender()
    private val clock = AtomicLong(1_700_000_000_000L)
    private val json = Json { ignoreUnknownKeys = true }

    // Example addresses: the real ones are unpublished and live only in deploy.env + the store forms.
    private val google = "google-review-k3p9xq7m2a@example.com"
    private val apple = "apple-review-w8n4tz6c1b@example.com"
    private val code = "482916"
    private val appleCode = "731054"
    private val hmacKey = "test-hmac-key-0123456789abcdef-0123456789"
    private val codeHash = ReviewSignIn.hmacHex(hmacKey, code)
    private val appleHash = ReviewSignIn.hmacHex(hmacKey, appleCode)

    private fun review(accounts: Map<String, String> = mapOf(google to codeHash, apple to appleHash)) =
        ReviewSignIn(accounts, hmacKey, store)

    private fun appTest(
        reviewSignIn: ReviewSignIn? = review(),
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
                reviewSignIn = reviewSignIn,
            )
        }
        block(client)
    }

    private suspend fun HttpClient.json(path: String, body: String): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.register(email: String) =
        json("/commerce/account/register", """{"email":"$email","tosVersion":"v1","privacyVersion":"v1"}""")

    private suspend fun HttpClient.verify(email: String, code: String, ownerId: String = "owner-review") =
        json("/commerce/account/verify", """{"email":"$email","code":"$code","ownerId":"$ownerId"}""")

    private suspend fun HttpResponse.sessionToken(): String =
        json.decodeFromString<VerifyResponse>(bodyAsText()).sessionToken

    // --- Off ------------------------------------------------------------------------------------

    @Test
    fun offWhenUnconfiguredTheListedAddressIsAnOrdinaryOne() = appTest(reviewSignIn = null) { client ->
        // Production's own wiring of "unset": the config yields no review sign-in at all.
        assertNull(CommerceConfig.fromEnv { null }.reviewSignIn(store))

        assertEquals(HttpStatusCode.Accepted, client.register(google).status)
        assertNotNull(sender.lastCode(google), "with the feature off, a review address is mailed a code like anyone")
        assertEquals(HttpStatusCode.Unauthorized, client.verify(google, code).status, "and the fixed code means nothing")
    }

    // --- Sign-in --------------------------------------------------------------------------------

    @Test
    fun aListedAddressWithTheFixedCodeGetsASession() = appTest { client ->
        assertEquals(HttpStatusCode.Accepted, client.register(google).status)
        val response = client.verify(google, code)
        assertEquals(HttpStatusCode.OK, response.status)
        val token = response.sessionToken()

        // A real session: it refreshes, and the account is verified and bound like any sign-in.
        assertEquals(HttpStatusCode.OK, client.json("/commerce/account/session", """{"sessionToken":"$token"}""").status)
        val account = assertNotNull(store.findAccountByEmail(google))
        assertNotNull(account.emailVerifiedAt)

        // The address normalises before the exact match, as every address does: same mailbox.
        assertEquals(HttpStatusCode.OK, client.verify("  ${google.uppercase()} ", code).status)
    }

    @Test
    fun registerForAListedAddressSendsNoMailAndStoresNoCode() = appTest { client ->
        val listed = client.register(google)
        val ordinary = client.register("alice@example.com")

        // No new oracle: same status, same body as an ordinary registration.
        assertEquals(ordinary.status, listed.status)
        assertEquals(ordinary.bodyAsText(), listed.bodyAsText())

        assertNull(sender.lastCode(google), "no mail to a review address")
        assertNull(store.getEmailVerification(google), "no mailed code stored for a review address")
        assertNotNull(sender.lastCode("alice@example.com"), "control: an ordinary address is mailed")
        assertNotNull(store.findAccountByEmail(google), "the account is still created, as for anyone")
    }

    @Test
    fun aMailedCodeIsNeverAcceptedForAListedAddress() = appTest { client ->
        client.register(google)
        // A mailed code that exists anyway — e.g. issued before the address was listed.
        store.putEmailVerification(google, OtpCodes.hash("123456"), expiresAt = clock.get() + 60_000L)

        assertEquals(HttpStatusCode.Unauthorized, client.verify(google, "123456").status)
        assertEquals(HttpStatusCode.OK, client.verify(google, code).status, "control: the fixed code still works")
    }

    @Test
    fun theFixedCodeIsNeverAcceptedForAnyOtherAddress() = appTest { client ->
        val lookalikes = listOf(
            "google-review-k3p9xq7m2a+x@example.com", // plus-suffix
            "google-review-k3p9xq7m2a@example.com.evil", // suffix domain
            "google-review-k3p9xq7m2a@example.com.", // trailing dot
            "google-review-k3p9xq7m2a@example.co",
            "google-review-k3p9xq7m2@example.com", // one character short
            "google-review@example.com", // the prefix without its suffix
            "review@example.com",
            "google-review-k3p9xq7m2a@evil.example.com",
            "google-reviеw-k3p9xq7m2a@example.com", // Cyrillic "е" (U+0435)
            "alice@example.com",
        )
        for (address in lookalikes) {
            // Registered first, so a refusal is the code's, not a missing account's.
            client.register(address)
            val response = client.verify(address, code)
            assertNotEquals(HttpStatusCode.OK, response.status, "the fixed code must not sign in `$address`")
            assertTrue(response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.BadRequest)
        }
        // Not one session anywhere.
        for (address in lookalikes) {
            store.findAccountByEmail(address)?.let { assertNull(it.emailVerifiedAt, "`$address` must not be verified") }
        }
        // And a look-alike is an ordinary address: it was mailed a code.
        assertNotNull(sender.lastCode("google-review-k3p9xq7m2a+x@example.com"))
    }

    // --- Lockout --------------------------------------------------------------------------------

    @Test
    fun tenWrongCodesLockTheAddressAndTheEleventhIsRefusedEvenWhenRight() = appTest { client ->
        client.register(google)
        client.register(apple)
        assertEquals(10, ReviewSignIn.MAX_FAILURES)
        repeat(10) {
            clock.addAndGet(60_000L)
            assertEquals(HttpStatusCode.Unauthorized, client.verify(google, "%06d".format(it)).status)
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.verify(google, code).status, "11th, the right code: still locked")
        assertNull(store.findAccountByEmail(google)!!.emailVerifiedAt)

        // Per address: the other review account is untouched.
        assertEquals(HttpStatusCode.OK, client.verify(apple, appleCode).status)

        // The deletion confirm shares the lock — it is the same credential.
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.json("/commerce/account/delete/confirm", "{\"email\":\"$google\",\"code\":\"$code\"}").status,
        )
    }

    @Test
    fun theLockoutSurvivesARestart() {
        appTest { client ->
            client.register(google)
            repeat(ReviewSignIn.MAX_FAILURES) { client.verify(google, "000000") }
        }
        // A fresh process: a new ReviewSignIn over the same durable store.
        appTest(reviewSignIn = review()) { client ->
            assertEquals(HttpStatusCode.TooManyRequests, client.verify(google, code).status)
        }
    }

    @Test
    fun theWindowRollsSoOnlyTheOldestFailureFreesASlot() = appTest { client ->
        client.register(google)
        val start = clock.get()
        repeat(ReviewSignIn.MAX_FAILURES) {
            clock.set(start + it * 3_600_000L) // one wrong code an hour
            client.verify(google, "000000")
        }
        // Just before the first failure ages out: still locked.
        clock.set(start + ReviewSignIn.LOCKOUT_WINDOW_MS - 1)
        assertEquals(HttpStatusCode.TooManyRequests, client.verify(google, code).status)
        // Once it has, one slot is free — the right code gets in.
        clock.set(start + ReviewSignIn.LOCKOUT_WINDOW_MS)
        assertEquals(HttpStatusCode.OK, client.verify(google, code).status)
        // ...and one more wrong guess re-locks: the other nine are still in the window.
        assertEquals(HttpStatusCode.Unauthorized, client.verify(google, "000000").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.verify(google, code).status)
    }

    @Test
    fun fewerFailuresThanTheLimitDoNotLock() = appTest { client ->
        client.register(google)
        repeat(ReviewSignIn.MAX_FAILURES - 1) { client.verify(google, "00000$it") }
        assertEquals(HttpStatusCode.OK, client.verify(google, code).status)
    }

    @Test
    fun erasingTheAccountDoesNotResetTheLockout() = appTest { client ->
        client.register(google)
        val token = client.verify(google, code).sessionToken()
        repeat(ReviewSignIn.MAX_FAILURES - 1) { client.verify(google, "000000") }
        assertEquals(HttpStatusCode.OK, client.json("/commerce/account/delete/confirm", "{\"sessionToken\":\"$token\",\"code\":\"$code\"}").status)
        client.register(google)
        assertEquals(HttpStatusCode.Unauthorized, client.verify(google, "000000").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.verify(google, code).status)
    }

    // --- Deletion -------------------------------------------------------------------------------

    @Test
    fun aReviewerCanDeleteAndThenSignUpAgainWithTheSameCode() = appTest { client ->
        client.register(google)
        val token = client.verify(google, code).sessionToken()
        val firstId = assertNotNull(store.findAccountByEmail(google)).id

        assertEquals(HttpStatusCode.Accepted, client.json("/commerce/account/delete/request", """{"sessionToken":"$token"}""").status)
        assertNull(sender.lastDeletionCode(google), "no deletion mail to a review address")
        assertNull(store.getEmailVerification(ErasedAccounts.deletionOtpKey(google)), "and no deletion code stored")

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.json("/commerce/account/delete/confirm", """{"sessionToken":"$token","code":"wrong"}""").status,
        )
        assertEquals(AccountStatus.Active, store.findAccountById(firstId)!!.status, "a wrong code deletes nothing")

        assertEquals(
            HttpStatusCode.OK,
            client.json("/commerce/account/delete/confirm", """{"sessionToken":"$token","code":"$code"}""").status,
        )
        val erased = assertNotNull(store.findAccountById(firstId))
        assertNotNull(erased.erasedAt)
        assertNull(store.findAccountByEmail(google), "the address no longer finds the erased row")

        // Sign up again: a fresh account, and the fixed code still works.
        assertEquals(HttpStatusCode.Accepted, client.register(google).status)
        assertEquals(HttpStatusCode.OK, client.verify(google, code, ownerId = "owner-review-2").status)
        val second = assertNotNull(store.findAccountByEmail(google))
        assertNotEquals(firstId, second.id)
        assertNull(second.erasedAt)

        // The web page's `{email}` variant works with the code too.
        assertEquals(HttpStatusCode.Accepted, client.json("/commerce/account/delete/request", """{"email":"$google"}""").status)
        assertTrue(sender.noAccountNotices.isEmpty(), "no mail of any kind to a review address")
        assertEquals(HttpStatusCode.OK, client.json("/commerce/account/delete/confirm", """{"email":"$google","code":"$code"}""").status)
        assertNotNull(store.findAccountById(second.id)!!.erasedAt)
    }

    @Test
    fun aMailedDeletionCodeIsNeverAcceptedForAListedAddress() = appTest { client ->
        client.register(google)
        client.verify(google, code)
        store.putEmailVerification(ErasedAccounts.deletionOtpKey(google), OtpCodes.hash("654321"), expiresAt = clock.get() + 60_000L)

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.json("/commerce/account/delete/confirm", """{"email":"$google","code":"654321"}""").status,
        )
        assertNull(store.findAccountByEmail(google)!!.erasedAt)
    }

    @Test
    fun theFixedCodeNeverDeletesAnotherAccount() = appTest { client ->
        val token = client.registerAndVerify(sender, "alice@example.com", ownerId = "owner-alice")
        client.json("/commerce/account/delete/request", """{"sessionToken":"$token"}""")
        assertNotNull(sender.lastDeletionCode("alice@example.com"), "an ordinary account is still mailed its code")

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.json("/commerce/account/delete/confirm", """{"sessionToken":"$token","code":"$code"}""").status,
        )
        assertNull(store.findAccountByEmail("alice@example.com")!!.erasedAt)
    }

    // --- One code per store -------------------------------------------------------------------

    @Test
    fun oneStoresCodeNeverOpensTheOtherStoresAccount() = appTest { client ->
        client.register(google)
        client.register(apple)
        assertEquals(HttpStatusCode.Unauthorized, client.verify(apple, code).status, "google's code must not open apple's account")
        assertEquals(HttpStatusCode.Unauthorized, client.verify(google, appleCode).status, "nor the other way round")
        assertEquals(HttpStatusCode.OK, client.verify(google, code).status)
        assertEquals(HttpStatusCode.OK, client.verify(apple, appleCode).status)
    }

    // --- Rotation ends sessions -----------------------------------------------------------------

    private suspend fun HttpClient.sessionAlive(token: String) =
        json("/commerce/account/session", """{"sessionToken":"$token"}""").status == HttpStatusCode.OK

    @Test
    fun rotatingAReviewCodeEndsThatAccountsSessionsOnly() {
        lateinit var googleToken: String
        lateinit var appleToken: String
        lateinit var aliceToken: String
        appTest { client ->
            client.register(google)
            client.register(apple)
            googleToken = client.verify(google, code).sessionToken()
            appleToken = client.verify(apple, appleCode).sessionToken()
            aliceToken = client.registerAndVerify(sender, "alice@example.com", ownerId = "owner-alice")
        }
        // First boot with these codes: records the fingerprints (and ends sessions minted before).
        review().endSessionsOnRotation()
        googleToken = reSignIn(google, code)
        appleToken = reSignIn(apple, appleCode)

        // Same config again: nothing changes.
        assertEquals(0, review().endSessionsOnRotation())
        // Google's code rotates: only google's sessions end.
        val rotated = ReviewSignIn.hmacHex(hmacKey, "111222")
        assertEquals(1, review(mapOf(google to rotated, apple to appleHash)).endSessionsOnRotation())
        appTest(reviewSignIn = review(mapOf(google to rotated, apple to appleHash))) { client ->
            assertFalse(client.sessionAlive(googleToken), "a session minted under the old code must end")
            assertTrue(client.sessionAlive(appleToken), "the other store's session is untouched")
            assertTrue(client.sessionAlive(aliceToken), "an ordinary account's session is untouched")
            assertEquals(HttpStatusCode.Unauthorized, client.verify(google, code).status, "the old code is dead")
            assertEquals(HttpStatusCode.OK, client.verify(google, "111222").status)
        }
    }

    @Test
    fun withdrawingTheConfigEndsEveryReviewSession() {
        lateinit var appleToken: String
        appTest { client -> client.register(apple) }
        review().endSessionsOnRotation()
        appleToken = reSignIn(apple, appleCode)
        // Review sign-in switched off: the boot path runs with no accounts.
        assertEquals(1, ReviewSignIn.endReviewSessionsOnRotation(store, emptyMap()))
        appTest(reviewSignIn = null) { client ->
            assertFalse(client.sessionAlive(appleToken))
        }
        assertNull(store.getEmailVerification(ReviewSignIn.FINGERPRINTS_KEY))
    }

    /** Sign [email] in again with [code] in a throwaway app over the same store; returns the session. */
    private fun reSignIn(email: String, code: String): String {
        var token = ""
        appTest { client -> token = client.verify(email, code).sessionToken() }
        return token
    }

    // --- Logs -----------------------------------------------------------------------------------

    @Test
    fun nothingSecretReachesTheLog() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        val out = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        val tee = PrintStream(out, true)
        System.setOut(tee)
        System.setErr(tee)
        try {
            appTest { client ->
                client.register(google)
                client.verify(google, "wrong-guess")
                val token = client.verify(google, code).sessionToken()
                client.json("/commerce/account/delete/request", """{"sessionToken":"$token"}""")
                client.json("/commerce/account/delete/confirm", """{"sessionToken":"$token","code":"$code"}""")
                repeat(ReviewSignIn.MAX_FAILURES + 1) { client.verify(apple, "00000$it") }
            }
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            root.detachAppender(appender)
        }
        val logged = appender.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") } +
            "\n" + out.toString(Charsets.UTF_8)

        // The capture must be seeing the feature's own lines, or this passes by seeing nothing.
        assertTrue("Review sign-in accepted" in logged, "did not capture the review sign-in line:\n$logged")
        assertTrue("locked out" in logged, "did not capture the lockout line")
        assertFalse(code in logged, "the review code reached the log")
        assertFalse(codeHash in logged, "the review code's hash reached the log")
        assertFalse(codeHash.uppercase() in logged)
        assertFalse(hmacKey in logged, "the HMAC key reached the log")
        assertFalse(google.substringBefore('@') in logged, "a review address's local part reached the log")
        assertFalse(apple.substringBefore('@') in logged)
        assertFalse(appleHash in logged)
    }
}
