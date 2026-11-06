package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.engine.mock.toByteArray
import kotlinx.coroutines.runBlocking
import org.tenshu.commerce.play.PlayLookup
import org.tenshu.commerce.play.RealGooglePlayClient
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [RealGooglePlayClient] over a `MockEngine`: the service-account sign-in and the v2 purchase shape. */
class GooglePlayClientTest {

    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val pem = "-----BEGIN PRIVATE KEY-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) +
        "\n-----END PRIVATE KEY-----\n"
    private val serviceAccount = """{"type":"service_account","client_email":"play@tenshu.iam.gserviceaccount.com",
        "token_uri":"https://oauth2.test/token","private_key":${kotlinx.serialization.json.JsonPrimitive(pem)}}"""

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun signsInWithAVerifiableJwt_andReadsTheSubscription() = runBlocking {
        val seen = mutableListOf<String>()
        var tokenRequests = 0
        val engine = MockEngine { request ->
            seen += request.url.toString()
            when (request.url.host) {
                "oauth2.test" -> {
                    tokenRequests++
                    val assertion = (request.body as FormDataContent).formData["assertion"]!!
                    val (h, c, s) = assertion.split(".")
                    val valid = Signature.getInstance("SHA256withRSA").run {
                        initVerify(keys.public); update("$h.$c".toByteArray()); verify(Base64.getUrlDecoder().decode(s))
                    }
                    assertTrue(valid, "the JWT is signed with the service account's key")
                    assertTrue("androidpublisher" in String(Base64.getUrlDecoder().decode(c)))
                    respond("""{"access_token":"ya29.test","expires_in":3600}""", HttpStatusCode.OK, json)
                }
                else -> {
                    assertEquals("Bearer ya29.test", request.headers[HttpHeaders.Authorization])
                    respond(
                        """{"subscriptionState":"SUBSCRIPTION_STATE_ACTIVE",
                            "acknowledgementState":"ACKNOWLEDGEMENT_STATE_PENDING",
                            "externalAccountIdentifiers":{"obfuscatedExternalAccountId":"acc-1"},
                            "lineItems":[{"productId":"tenshu.drive.basic","expiryTime":"2026-11-23T10:00:00.5Z"}]}""",
                        HttpStatusCode.OK, json,
                    )
                }
            }
        }
        val client = RealGooglePlayClient(HttpClient(engine), serviceAccount, "app.tenshu.control.android", "https://play.test")
        val found = assertIs<PlayLookup.Found>(client.subscription("tok-1")).subscription
        assertEquals("tenshu.drive.basic", found.productId)
        assertEquals("acc-1", found.obfuscatedAccountId)
        assertEquals(1_795_428_000_500L, found.expiryMs)
        assertEquals(false, found.acknowledged)
        assertTrue(seen.any { it == "https://play.test/androidpublisher/v3/applications/app.tenshu.control.android/purchases/subscriptionsv2/tokens/tok-1" })

        client.subscription("tok-1")
        assertEquals(1, tokenRequests, "the access token is cached")
    }

    @Test
    fun anUnknownToken_isNotFound_andAServerError_isFailed() = runBlocking {
        var status = HttpStatusCode.BadRequest
        val engine = MockEngine { request ->
            if (request.url.host == "oauth2.test") respond("""{"access_token":"t","expires_in":3600}""", HttpStatusCode.OK, json)
            else respond("""{"error":{"code":400}}""", status, json)
        }
        val client = RealGooglePlayClient(HttpClient(engine), serviceAccount, "p", "https://play.test")
        assertEquals(PlayLookup.NotFound, client.subscription("bad"))
        status = HttpStatusCode.InternalServerError
        assertEquals(PlayLookup.Failed, client.subscription("bad"))
    }

    @Test
    fun pathSegmentsAreEncoded_theLinkedTokenIsRead_andCancelStopsRenewalsForGood() = runBlocking {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var state = "SUBSCRIPTION_STATE_ACTIVE"
        var context = ""
        val engine = MockEngine { request ->
            if (request.url.host == "oauth2.test") return@MockEngine respond("""{"access_token":"t","expires_in":3600}""", HttpStatusCode.OK, json)
            paths += request.url.encodedPath
            if (request.method == io.ktor.http.HttpMethod.Post) {
                bodies += String(request.body.toByteArray())
                state = "SUBSCRIPTION_STATE_CANCELED"
                context = ""","canceledStateContext":{"developerInitiatedCancellation":{}}"""
                respond("{}", HttpStatusCode.OK, json)
            } else {
                respond(
                    """{"subscriptionState":"$state","linkedPurchaseToken":"old-tok"$context,
                        "lineItems":[{"productId":"tenshu.drive.basic","expiryTime":"2026-11-23T10:00:00Z"}]}""",
                    HttpStatusCode.OK, json,
                )
            }
        }
        val client = RealGooglePlayClient(HttpClient(engine), serviceAccount, "p", "https://play.test")
        val found = assertIs<PlayLookup.Found>(client.subscription("a/b?c")).subscription
        assertEquals("old-tok", found.linkedPurchaseToken)
        assertTrue(paths.last().endsWith("/tokens/a%2Fb%3Fc"), paths.last())

        assertTrue(client.cancelRenewals("tok"))
        assertTrue(paths.any { it.endsWith("/purchases/subscriptionsv2/tokens/tok:cancel") }, paths.toString())
        assertTrue("DEVELOPER_REQUESTED_STOP_PAYMENTS" in bodies.single())
        // Already cancelled by us: nothing more is sent.
        assertTrue(client.cancelRenewals("tok"))
        assertEquals(1, bodies.size)

        // Cancelled by the USER (restorable in the Play Store): cancelled again, for good.
        context = ""","canceledStateContext":{"userInitiatedCancellation":{}}"""
        assertTrue(client.cancelRenewals("tok"))
        assertEquals(2, bodies.size)
    }

    @Test
    fun aRefusedCancel_isAcceptedOnlyWhenNothingCanRenewOnItsOwn() = runBlocking {
        var state = "SUBSCRIPTION_STATE_PENDING"
        var posts = 0
        val engine = MockEngine { request ->
            if (request.url.host == "oauth2.test") return@MockEngine respond("""{"access_token":"t","expires_in":3600}""", HttpStatusCode.OK, json)
            if (request.method == io.ktor.http.HttpMethod.Post) {
                posts++
                respond("""{"error":{"code":400}}""", HttpStatusCode.BadRequest, json)
            } else {
                respond("""{"subscriptionState":"$state"}""", HttpStatusCode.OK, json)
            }
        }
        val client = RealGooglePlayClient(HttpClient(engine), serviceAccount, "p", "https://play.test")
        // An unpaid signup that Play will not cancel must not fail every sweep forever.
        assertTrue(client.cancelRenewals("tok"))
        state = "SUBSCRIPTION_STATE_ACTIVE"
        assertEquals(false, client.cancelRenewals("tok"), "an active subscription that would renew is owed a retry")
        assertEquals(2, posts)
    }

}
