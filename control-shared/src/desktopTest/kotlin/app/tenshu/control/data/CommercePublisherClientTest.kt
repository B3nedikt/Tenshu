package app.tenshu.control.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real device-side commerce account client (`commerce-accounts-and-sessions.md` §3–§4, Phase
 * 3b) against a MockEngine: register emails an OTP; verify trades the OTP + ownerId for a session
 * token (surfaced synchronously for the entitlement clients and persisted across restart); redeem
 * mints a signed grant onto [PublisherClient.redeemedEntitlements]. Email/PII never leaves this
 * client uninvited, and non-success maps to a typed result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommercePublisherClientTest {

    private fun httpClientFor(engine: MockEngine): HttpClient =
        HttpClient(engine) { install(ContentNegotiation) { json() } }

    private fun jsonOk(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }

    // ── register ────────────────────────────────────────────────────────────

    @Test
    fun register_202_sendsEmailAndAcceptanceVersions() = runTest {
        var seenBody: String? = null
        val engine = MockEngine { request ->
            seenBody = (request.body as TextContent).text
            respond("""{"status":"otp_sent"}""", HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(engine),
            ownerIdProvider = { "owner-1" },
            storageDelegate = InMemorySecureStorageDelegate(),
        )

        val result = client.register("alice@example.com", displayName = "Alice")
        assertTrue(result is PublisherClient.RegisterResult.OtpSent)
        assertTrue("alice@example.com" in seenBody!!, "register carries the email")
        assertTrue("tosVersion" in seenBody!! && "privacyVersion" in seenBody!!)
        assertNull(client.account.value, "no account until the OTP is verified")
    }

    /**
     * `commerce-otp-delivery-feedback.md` §2. The service tells its failures apart; this used to
     * collapse all of them into "Something went wrong. Please try again." — which for a spent
     * budget is advice that cannot work for an hour, and for a refused send is a shrug.
     *
     * ⚠ Asserts the messages **differ from each other and from the generic**, not their exact
     * wording: pinning the copy would make every future rewording a test edit for no safety.
     */
    @Test
    fun register_distinguishesRateLimitedFromSendFailedFromGeneric() = runTest {
        suspend fun messageFor(status: HttpStatusCode): String {
            val client = CommercePublisherClient(
                baseUrl = "http://commerce.test",
                httpClient = httpClientFor(jsonOk("""{"error":"x"}""", status)),
                ownerIdProvider = { "owner-1" },
                storageDelegate = InMemorySecureStorageDelegate(),
            )
            val result = client.register("alice@example.com", displayName = null)
            return (result as PublisherClient.RegisterResult.Error).message
        }

        val rateLimited = messageFor(HttpStatusCode.TooManyRequests)
        val sendFailed = messageFor(HttpStatusCode.ServiceUnavailable)
        val generic = messageFor(HttpStatusCode.BadRequest)

        assertEquals(3, setOf(rateLimited, sendFailed, generic).size, "each failure gets its own message")
        assertTrue(
            "wait" in rateLimited.lowercase(),
            "a spent budget must tell the user to wait — retrying cannot work for an hour: $rateLimited",
        )
    }

    @Test
    fun register_rejectsMalformedEmail_withoutRequest() = runTest {
        var called = false
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(MockEngine { called = true; respond("{}", HttpStatusCode.Accepted) }),
            ownerIdProvider = { "owner-1" },
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        assertTrue(client.register("nope", displayName = null) is PublisherClient.RegisterResult.Error)
        assertEquals(false, called)
    }

    // ── verify ──────────────────────────────────────────────────────────────

    @Test
    fun verify_200_bindsSessionAndAccount_sendsOwnerAndCode() = runTest {
        var verifyBody: String? = null
        val engine = MockEngine { request ->
            val text = (request.body as TextContent).text
            if (request.url.encodedPath.endsWith("/verify")) verifyBody = text
            val body = if (request.url.encodedPath.endsWith("/verify")) {
                """{"sessionToken":"sess-xyz"}"""
            } else {
                """{"status":"otp_sent"}"""
            }
            val status = if (request.url.encodedPath.endsWith("/verify")) HttpStatusCode.OK else HttpStatusCode.Accepted
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(engine),
            ownerIdProvider = { "owner-1" },
            storageDelegate = InMemorySecureStorageDelegate(),
        )

        client.register("alice@example.com", displayName = "Alice")
        val result = client.verifyOtp("123456")

        assertTrue(result is PublisherClient.VerifyResult.Verified)
        assertEquals("sess-xyz", client.currentSessionToken())
        val account = client.account.value
        assertNotNull(account)
        assertEquals(PublisherAccount.Plan.Free, account.plan)
        assertEquals(
            "alice@example.com",
            (account.identityMethod as? PublisherAccount.IdentityMethod.Email)?.address,
        )
        assertTrue("123456" in verifyBody!! && "owner-1" in verifyBody!!, "verify carries the code + ownerId")
    }

    @Test
    fun verify_401_isInvalidCode_noSession() = runTest {
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/verify")) {
                        respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized)
                    } else {
                        respond("""{"status":"otp_sent"}""", HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                },
            ),
            ownerIdProvider = { "owner-1" },
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        client.register("alice@example.com", displayName = null)
        assertTrue(client.verifyOtp("000000") is PublisherClient.VerifyResult.InvalidCode)
        assertNull(client.currentSessionToken())
        assertNull(client.account.value)
    }

    @Test
    fun verify_withoutOwner_errors_withoutRequest() = runTest {
        var verifyCalled = false
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/verify")) verifyCalled = true
                    respond("""{"status":"otp_sent"}""", HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
            ownerIdProvider = { null }, // no device identity yet
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        client.register("alice@example.com", displayName = null)
        assertTrue(client.verifyOtp("123456") is PublisherClient.VerifyResult.Error)
        assertEquals(false, verifyCalled, "verify never hits the network without an ownerId")
    }

    // ── session persistence ───────────────────────────────────────────────────

    @Test
    fun verifiedSession_rehydratesOnFreshClient() = runTest {
        val delegate = InMemorySecureStorageDelegate()
        val engine = jsonOkRouter()
        val first = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(engine),
            ownerIdProvider = { "owner-1" },
            storageDelegate = delegate,
        )
        first.register("alice@example.com", displayName = null)
        first.verifyOtp("123456")
        assertEquals("sess-xyz", first.currentSessionToken())

        // A fresh client over the same secure storage starts already signed in.
        val second = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(jsonOkRouter()),
            ownerIdProvider = { "owner-1" },
            storageDelegate = delegate,
        )
        assertEquals("sess-xyz", second.currentSessionToken())
        assertNotNull(second.account.value)

        second.disconnect()
        assertNull(second.currentSessionToken())
        assertNull(CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(jsonOkRouter()),
            ownerIdProvider = { "owner-1" },
            storageDelegate = delegate,
        ).account.value, "disconnect persists — a fresh client starts clean")
    }

    // ── redeem ────────────────────────────────────────────────────────────────

    @Test
    fun redeem_200_emitsSignedGrant_sendsCodeAndOwner() = runTest {
        var redeemBody: String? = null
        // base64("AQID") == bytes [1,2,3]
        val engine = MockEngine { request ->
            redeemBody = (request.body as TextContent).text
            respond(
                """{"ownerId":"owner-1","publisherId":"tenshu.commerce","productId":"tenshu.drive.premium","validFrom":0,"validUntil":99,"publisherSignature":"AQID"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(engine),
            ownerIdProvider = { "owner-1" },
            storageDelegate = InMemorySecureStorageDelegate(),
        )

        val result = client.redeem("FOUNDER-1")
        assertTrue(result is PublisherClient.RedeemResult.Redeemed)
        assertTrue("FOUNDER-1" in redeemBody!! && "owner-1" in redeemBody!!)

        val granted = client.redeemedEntitlements.first()
        assertEquals("tenshu.drive.premium", granted.productId)
        assertEquals(byteArrayOf(1, 2, 3).toList(), granted.signature.toList())
    }

    @Test
    fun redeem_403_isInvalid() = runTest {
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(jsonOk("""{"error":"forbidden"}""", HttpStatusCode.Forbidden)),
            ownerIdProvider = { "owner-1" },
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        assertTrue(client.redeem("BOGUS") is PublisherClient.RedeemResult.Invalid)
    }

    @Test
    fun redeem_blankCode_isInvalid_withoutRequest() = runTest {
        var called = false
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(MockEngine { called = true; respond("{}", HttpStatusCode.OK) }),
            ownerIdProvider = { "owner-1" },
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        assertTrue(client.redeem("   ") is PublisherClient.RedeemResult.Invalid)
        assertEquals(false, called)
    }

    // ── checkout ──────────────────────────────────────────────────────────────

    private val bodies = mutableListOf<Pair<String, String>>()

    /**
     * A signed-in client. `/register` + `/verify` always succeed; every other path is answered by
     * [answer] (`path -> (status, body)`), and each request body is recorded in [bodies].
     */
    private suspend fun signedIn(
        storage: InMemorySecureStorageDelegate = InMemorySecureStorageDelegate(),
        answer: (String) -> Pair<HttpStatusCode, String>,
    ): CommercePublisherClient {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            bodies += path to (request.body as TextContent).text
            when {
                path.endsWith("/register") -> respond("""{"status":"otp_sent"}""", HttpStatusCode.Accepted, json)
                path.endsWith("/verify") -> respond("""{"sessionToken":"sess-xyz"}""", HttpStatusCode.OK, json)
                else -> answer(path).let { (status, body) -> respond(body, status, json) }
            }
        }
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(engine),
            ownerIdProvider = { "owner-1" },
            storageDelegate = storage,
        )
        client.register("alice@example.com", displayName = null)
        client.verifyOtp("123456")
        return client
    }

    private fun bodyOf(pathSuffix: String): String? = bodies.lastOrNull { it.first.endsWith(pathSuffix) }?.second

    @Test
    fun checkout_200_returnsHostedUrl_sendsOwnerProductAndSession() = runTest {
        val client = signedIn { HttpStatusCode.OK to """{"checkoutUrl":"https://stripe.test/session/abc"}""" }

        val result = client.startCheckout(CommerceProducts.PREMIUM, withdrawalAcknowledged = true)
        assertTrue(result is PublisherClient.CheckoutResult.Ready)
        assertEquals("https://stripe.test/session/abc", result.url)
        val body = bodyOf("/checkout")!!
        assertTrue("owner-1" in body && CommerceProducts.PREMIUM in body, "checkout carries ownerId + productId")
        assertTrue("\"withdrawalAcknowledged\":true" in body, "checkout carries the acknowledgement: $body")
        assertTrue(CommercePublisherClient.TOS_VERSION in body, "checkout names the acknowledged terms: $body")
        // ⚠⚠ The session is what pins the email Stripe charges to this account. Without it the
        // purchase lands on whatever address was typed into Stripe, which this device may not be
        // signed in to — and the service cannot see what the customer is already paying for.
        assertTrue("\"sessionToken\":\"sess-xyz\"" in body, "checkout carries the session: $body")
    }

    @Test
    fun checkout_withoutASession_asksToSignIn_withoutRequest() = runTest {
        var called = false
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(MockEngine { called = true; respond("{}", HttpStatusCode.OK) }),
            ownerIdProvider = { "owner-1" },
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        assertEquals(
            PublisherClient.CheckoutResult.SignInRequired,
            client.startCheckout(CommerceProducts.BASIC, withdrawalAcknowledged = true),
        )
        assertEquals(false, called, "checkout never hits the network without a session")
    }

    @Test
    fun checkout_401_dropsTheDeadSession_andAsksToSignIn() = runTest {
        val storage = InMemorySecureStorageDelegate()
        val client = signedIn(storage) { HttpStatusCode.Unauthorized to """{"error":"unauthorized"}""" }

        assertEquals(
            PublisherClient.CheckoutResult.SignInRequired,
            client.startCheckout(CommerceProducts.BASIC, withdrawalAcknowledged = true),
        )
        assertNull(client.currentSessionToken())
        assertNull(client.account.value)
        assertNull(storage.getSecret(CommercePublisherClient.STORAGE_KEY))
    }

    /** A hosted checkout always starts a NEW subscription, so the service refuses a second one. */
    @Test
    fun checkout_409_tellsAPlanAlreadyBillingFromAPlanChange() = runTest {
        var error = "already_subscribed"
        val client = signedIn { HttpStatusCode.Conflict to """{"error":"$error"}""" }

        assertEquals(
            PublisherClient.CheckoutResult.AlreadySubscribed,
            client.startCheckout(CommerceProducts.BASIC, withdrawalAcknowledged = true),
        )
        error = "plan_change_required"
        assertEquals(
            PublisherClient.CheckoutResult.PlanChangeRequired,
            client.startCheckout(CommerceProducts.PREMIUM, withdrawalAcknowledged = true),
        )
        // The realm pays under another email: not a plan change this session could make.
        error = "subscribed_under_another_account"
        assertEquals(
            PublisherClient.CheckoutResult.SubscribedUnderAnotherAccount,
            client.startCheckout(CommerceProducts.BASIC, withdrawalAcknowledged = true),
        )
    }

    @Test
    fun checkout_503_isUnavailable() = runTest {
        val client = signedIn { HttpStatusCode.ServiceUnavailable to """{"error":"stripe_unavailable"}""" }
        assertTrue(client.startCheckout(CommerceProducts.BASIC, withdrawalAcknowledged = true) is PublisherClient.CheckoutResult.Unavailable)
    }

    @Test
    fun checkout_400_isError() = runTest {
        val client = signedIn { HttpStatusCode.BadRequest to """{"error":"unknown_product"}""" }
        assertTrue(client.startCheckout(CommerceProducts.BASIC, withdrawalAcknowledged = true) is PublisherClient.CheckoutResult.Error)
    }

    @Test
    fun checkout_withoutOwner_errors_withoutRequest() = runTest {
        var called = false
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(MockEngine { called = true; respond("{}", HttpStatusCode.OK) }),
            ownerIdProvider = { null }, // no device identity yet
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        assertTrue(client.startCheckout(CommerceProducts.BASIC, withdrawalAcknowledged = true) is PublisherClient.CheckoutResult.Error)
        assertEquals(false, called, "checkout never hits the network without an ownerId")
    }

    // ── syncPlan ──────────────────────────────────────────────────────────────

    /**
     * ⚠⚠ The only thing that brings a purchase to the device promptly: the webhook writes commerce's
     * ledger, and the account-driven writer asks for Free and skips a record that already says so.
     */
    @Test
    fun syncPlan_200_handsTheSignedPlanToTheProxy() = runTest {
        val client = signedIn {
            HttpStatusCode.OK to
                """{"ownerId":"owner-1","publisherId":"tenshu.commerce","productId":"${CommerceProducts.BASIC}",""" +
                """"validFrom":10,"validUntil":20,"publisherSignature":"c2ln"}"""
        }

        assertEquals(PublisherClient.PlanSyncResult.Synced(CommerceProducts.BASIC), client.syncPlan())
        assertTrue("\"sessionToken\":\"sess-xyz\"" in bodyOf("/entitlement/mint")!!)
        // It rides the seam the proxy already persists commerce-signed grants from.
        val signed = client.redeemedEntitlements.first()
        assertEquals(CommerceProducts.BASIC, signed.productId)
        assertEquals("owner-1", signed.ownerId)
        assertEquals(20L, signed.validUntil)
    }

    /**
     * ⚠⚠ Every `/mint` answer is a new record with a slightly later `validUntil`, which the proxy
     * saves as a renewal. Handing each one on made the checkout screen's poll a stored-row write
     * and a sync block every five seconds.
     */
    @Test
    fun syncPlan_handsOnAChangedPlan_notEveryFreshlyStampedCopyOfTheSameOne() = runTest {
        var product = "tenshu.drive.free"
        var validUntil = 1_000_000L
        val client = signedIn {
            HttpStatusCode.OK to
                """{"ownerId":"owner-1","publisherId":"tenshu.commerce","productId":"$product",""" +
                """"validFrom":10,"validUntil":$validUntil,"publisherSignature":"c2ln"}"""
        }
        val handedOn = mutableListOf<Pair<String, Long>>()
        val collecting = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.redeemedEntitlements.collect { handedOn += it.productId to it.validUntil }
        }

        client.syncPlan()
        validUntil += 5_000 // the next poll, five seconds on
        client.syncPlan()
        validUntil += 5_000
        // Still an answer the caller can read — it is only not SAVED again.
        assertEquals(PublisherClient.PlanSyncResult.Synced("tenshu.drive.free"), client.syncPlan())
        assertEquals(listOf("tenshu.drive.free" to 1_000_000L), handedOn)

        product = CommerceProducts.BASIC // the webhook landed
        client.syncPlan()
        assertEquals(CommerceProducts.BASIC, handedOn.last().first)
        assertEquals(2, handedOn.size)

        validUntil += 13L * 60 * 60 * 1000 // a window that has genuinely moved on is a renewal
        client.syncPlan()
        assertEquals(3, handedOn.size)
        collecting.cancel()
    }

    @Test
    fun syncPlan_withoutASession_asksNothing() = runTest {
        var called = false
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(MockEngine { called = true; respond("{}", HttpStatusCode.OK) }),
            ownerIdProvider = { "owner-1" },
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        assertEquals(PublisherClient.PlanSyncResult.SignedOut, client.syncPlan())
        assertEquals(false, called)
    }

    @Test
    fun syncPlan_401_dropsTheDeadSession() = runTest {
        val storage = InMemorySecureStorageDelegate()
        val client = signedIn(storage) { HttpStatusCode.Unauthorized to """{"error":"unauthorized"}""" }

        assertEquals(PublisherClient.PlanSyncResult.SignedOut, client.syncPlan())
        assertNull(client.account.value)
        assertNull(storage.getSecret(CommercePublisherClient.STORAGE_KEY))
    }

    @Test
    fun syncPlan_403_isUnavailable_andKeepsTheSession() = runTest {
        val client = signedIn { HttpStatusCode.Forbidden to """{"error":"no_entitlement"}""" }

        assertEquals(PublisherClient.PlanSyncResult.Unavailable, client.syncPlan())
        assertEquals("sess-xyz", client.currentSessionToken())
    }

    @Test
    fun syncPlan_aMalformedGrant_isNotHandedOn() = runTest {
        val client = signedIn {
            HttpStatusCode.OK to
                """{"ownerId":"owner-1","publisherId":"tenshu.commerce","productId":"${CommerceProducts.BASIC}",""" +
                """"validFrom":10,"validUntil":20,"publisherSignature":"not base64 !!"}"""
        }
        assertTrue(client.syncPlan() is PublisherClient.PlanSyncResult.Error)
    }

    // ── portal ────────────────────────────────────────────────────────────────

    /** A signed-in client whose `/portal` answers with [portalStatus] + [portalBody]. */
    private suspend fun signedInClient(portalStatus: HttpStatusCode, portalBody: String): CommercePublisherClient {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/verify") ->
                    respond("""{"sessionToken":"sess-xyz"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.encodedPath.endsWith("/portal") ->
                    respond(portalBody, portalStatus, headersOf(HttpHeaders.ContentType, "application/json"))
                else ->
                    respond("""{"status":"otp_sent"}""", HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(engine),
            ownerIdProvider = { "owner-1" },
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        client.register("alice@example.com", displayName = null)
        client.verifyOtp("123456")
        return client
    }

    @Test
    fun portal_200_returnsHostedUrl() = runTest {
        val result = signedInClient(HttpStatusCode.OK, """{"portalUrl":"https://stripe.test/portal/abc"}""")
            .openBillingPortal()
        assertTrue(result is PublisherClient.PortalResult.Ready)
        assertEquals("https://stripe.test/portal/abc", result.url)
    }

    @Test
    fun portal_409_isNoSubscription() = runTest {
        val result = signedInClient(HttpStatusCode.Conflict, """{"error":"no_subscription"}""").openBillingPortal()
        assertTrue(result is PublisherClient.PortalResult.NoSubscription)
    }

    @Test
    fun portal_401_isUnauthorized() = runTest {
        val result = signedInClient(HttpStatusCode.Unauthorized, """{"error":"unauthorized"}""").openBillingPortal()
        assertTrue(result is PublisherClient.PortalResult.Unauthorized)
    }

    @Test
    fun portal_503_isUnavailable() = runTest {
        val result = signedInClient(HttpStatusCode.ServiceUnavailable, """{"error":"stripe_unavailable"}""").openBillingPortal()
        assertTrue(result is PublisherClient.PortalResult.Unavailable)
    }

    @Test
    fun portal_withoutSession_isUnauthorized_withoutRequest() = runTest {
        var portalCalled = false
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/portal")) portalCalled = true
                    respond("{}", HttpStatusCode.OK)
                },
            ),
            ownerIdProvider = { "owner-1" },
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        // Never registered/verified → no session token.
        assertTrue(client.openBillingPortal() is PublisherClient.PortalResult.Unauthorized)
        assertEquals(false, portalCalled, "portal never hits the network without a session")
    }

    /** A MockEngine that answers `/register` with 202 and `/verify` with a `sess-xyz` session. */
    private fun jsonOkRouter() = MockEngine { request ->
        if (request.url.encodedPath.endsWith("/verify")) {
            respond("""{"sessionToken":"sess-xyz"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        } else {
            respond("""{"status":"otp_sent"}""", HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    // ── account deletion (§9.7) ─────────────────────────────────────────────

    private fun signedInStorage() = InMemorySecureStorageDelegate().apply {
        storeSecret(
            CommercePublisherClient.STORAGE_KEY,
            """{"email":"ada@example.com","sessionToken":"tok-1"}""",
        )
    }

    @Test
    fun deletion_requestSendsTheSessionToTheRequestRoute() = runTest {
        var seenPath: String? = null
        var seenBody: String? = null
        val engine = MockEngine { request ->
            seenPath = request.url.encodedPath
            seenBody = (request.body as TextContent).text
            respond("""{"status":"code_sent"}""", HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(engine),
            ownerIdProvider = { "owner-1" },
            storageDelegate = signedInStorage(),
        )

        assertEquals(PublisherClient.AccountDeletionResult.CodeSent, client.requestAccountDeletion())
        assertEquals("/commerce/account/delete/request", seenPath)
        assertTrue("tok-1" in seenBody!!)
        assertTrue("ada@example.com" !in seenBody!!, "the session names the account; the address stays home")
    }

    @Test
    fun deletion_confirmedDropsTheLocalSession() = runTest {
        var seenPath: String? = null
        val storage = signedInStorage()
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(MockEngine { request ->
                seenPath = request.url.encodedPath
                respond("""{"status":"deleted"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }),
            ownerIdProvider = { "owner-1" },
            storageDelegate = storage,
        )
        assertNotNull(client.account.value)

        assertEquals(PublisherClient.AccountDeletionResult.Deleted, client.confirmAccountDeletion(" 123456 "))

        assertEquals("/commerce/account/delete/confirm", seenPath)
        assertNull(client.currentSessionToken(), "the session no longer exists on commerce")
        assertNull(client.account.value)
        assertNull(storage.getSecret(CommercePublisherClient.STORAGE_KEY), "nor on disk")
    }

    /** Commerce answering confirm with 401, and the session probe with [sessionStatus]. */
    private fun confirm401(sessionStatus: HttpStatusCode) = MockEngine { request ->
        val status = if (request.url.encodedPath == "/commerce/account/session") sessionStatus else HttpStatusCode.Unauthorized
        respond("""{"error":"x"}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @Test
    fun deletion_aWrongCodeKeepsTheSession() = runTest {
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(confirm401(sessionStatus = HttpStatusCode.OK)),
            ownerIdProvider = { "owner-1" },
            storageDelegate = signedInStorage(),
        )

        assertEquals(PublisherClient.AccountDeletionResult.InvalidCode, client.confirmAccountDeletion("000000"))
        assertEquals("tok-1", client.currentSessionToken())
    }

    /**
     * ⚠⚠ A confirm whose response was lost: the retry finds no account and gets 401. Reading that as
     * "wrong code" left the device signed in with a dead session, inviting endless retries.
     */
    @Test
    fun deletion_aRetryAfterALostConfirmSignsOutAndSaysSo() = runTest {
        val storage = signedInStorage()
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(confirm401(sessionStatus = HttpStatusCode.Unauthorized)),
            ownerIdProvider = { "owner-1" },
            storageDelegate = storage,
        )

        assertEquals(PublisherClient.AccountDeletionResult.SignedOut, client.confirmAccountDeletion("123456"))
        assertNull(client.currentSessionToken())
        assertNull(client.account.value)
        assertNull(storage.getSecret(CommercePublisherClient.STORAGE_KEY))
    }

    @Test
    fun deletion_anUnreachableProbeIsNotADeadSession() = runTest {
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(MockEngine { request ->
                if (request.url.encodedPath == "/commerce/account/session") error("network down")
                respond("""{"error":"x"}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
            }),
            ownerIdProvider = { "owner-1" },
            storageDelegate = signedInStorage(),
        )

        assertEquals(PublisherClient.AccountDeletionResult.InvalidCode, client.confirmAccountDeletion("123456"))
        assertEquals("tok-1", client.currentSessionToken(), "uncertainty must not sign the user out")
    }

    @Test
    fun deletion_withoutASessionAsksToSignIn_withoutARequest() = runTest {
        var called = false
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(MockEngine { called = true; respond("{}", HttpStatusCode.Accepted) }),
            ownerIdProvider = { "owner-1" },
            storageDelegate = InMemorySecureStorageDelegate(),
        )

        assertEquals(PublisherClient.AccountDeletionResult.SignInRequired, client.requestAccountDeletion())
        assertEquals(PublisherClient.AccountDeletionResult.SignInRequired, client.confirmAccountDeletion("123456"))
        assertEquals(false, called)
    }

    @Test
    fun deletion_aDeadSessionOnRequestSignsOut() = runTest {
        val client = CommercePublisherClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(jsonOk("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized)),
            ownerIdProvider = { "owner-1" },
            storageDelegate = signedInStorage(),
        )

        assertEquals(PublisherClient.AccountDeletionResult.SignedOut, client.requestAccountDeletion())
        assertNull(client.currentSessionToken(), "a session commerce refuses is dropped")
    }
}
