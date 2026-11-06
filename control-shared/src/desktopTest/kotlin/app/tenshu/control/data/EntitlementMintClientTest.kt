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
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.identity.EntitlementSignature
import org.tenshu.storage.proxy.DevSeedEntitlementMintClient
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The off-device mint seam (`commerce-accounts-and-sessions.md` §4.3): the dev-key minter reproduces
 * the prior in-process behaviour, and the **session-gated** commerce HTTP minter sends only its
 * `sessionToken` (no email/owner — the service derives those from the verified account), maps a
 * `200` into a verifiable [EntitlementSignature.SignedEntitlement], short-circuits when there is no
 * session, and treats any non-200 as a no-op `null`.
 */
class EntitlementMintClientTest {

    private val ed = BouncyCastleEd25519Backend()

    @Test
    fun devSeedMinter_signsWithDevKey_andWindowFromNow() = runTest {
        val client = DevSeedEntitlementMintClient(
            sign = { msg -> ed.sign(EntitlementSignature.DEV_SEED, msg) },
            now = { 1_000L },
            ttlMs = 5_000L,
        )
        val signed = client.mint(ownerId = "owner-1", email = null, productId = "tenshu.drive.free")

        assertEquals(EntitlementSignature.DEV_PUBLISHER_ID, signed.publisherId)
        assertEquals(1_000L, signed.validFrom)
        assertEquals(6_000L, signed.validUntil)
        val keys = EntitlementSignature.publicKeysFor(signed.publisherId)
        assertTrue(keys.any { ed.verify(it, signed.message(), signed.signature) })
    }

    @Test
    fun commerceMinter_sendsSessionToken_andParses200() = runTest {
        // The mock service signs with the committed commerce dev seed; its public half is the
        // pinned COMMERCE_PUBLIC_KEY, so the parsed record must verify under one of publicKeysFor.
        val commerceSeed = ByteArray(32) { (it + 65).toByte() }
        var seenBody: String? = null
        val engine = MockEngine { request ->
            seenBody = (request.body as TextContent).text
            val now = 2_000L
            val until = now + 60_000L
            val sig = ed.sign(
                commerceSeed,
                EntitlementSignature.message("owner-1", EntitlementSignature.COMMERCE_PUBLISHER_ID, "tenshu.drive.premium", now, until),
            )
            respond(
                content = """
                    {"ownerId":"owner-1","publisherId":"${EntitlementSignature.COMMERCE_PUBLISHER_ID}",
                     "productId":"tenshu.drive.premium","validFrom":$now,"validUntil":$until,
                     "publisherSignature":"${Base64.Default.encode(sig)}"}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = CommerceEntitlementMintClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(engine),
            sessionTokenProvider = { "sess-token-123" },
        )

        // email/productId args are ignored — the account behind the session decides them.
        val signed = client.mint(ownerId = "ignored", email = "ignored@example.com", productId = "ignored")

        assertTrue(signed != null)
        assertEquals(EntitlementSignature.COMMERCE_PUBLISHER_ID, signed.publisherId)
        val keys = EntitlementSignature.publicKeysFor(signed.publisherId)
        assertTrue(
            keys.any { ed.verify(it, signed.message(), signed.signature) },
            "parsed record must verify under one of the publisher's trusted keys",
        )
        assertTrue("sess-token-123" in seenBody!!, "the request carries the session token")
        assertTrue("ignored@example.com" !in seenBody!!, "no email/PII is sent")
    }

    @Test
    fun commerceMinter_noSession_shortCircuits_withoutRequest() = runTest {
        var called = false
        val engine = MockEngine { called = true; respond("{}", HttpStatusCode.OK) }
        val client = CommerceEntitlementMintClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(engine),
            sessionTokenProvider = { null },
        )

        assertNull(client.mint(ownerId = "owner-1", email = "alice@example.com", productId = "tenshu.drive.premium"))
        assertEquals(false, called, "no session → no request to the gated endpoint")
    }

    @Test
    fun commerceMinter_401_isNoOpNull() = runTest {
        val engine = MockEngine { respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized) }
        val client = CommerceEntitlementMintClient(
            baseUrl = "http://commerce.test",
            httpClient = httpClientFor(engine),
            sessionTokenProvider = { "stale-session" },
        )

        assertNull(client.mint(ownerId = "owner-1", email = null, productId = "tenshu.drive.premium"))
    }

    private fun httpClientFor(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }
}
