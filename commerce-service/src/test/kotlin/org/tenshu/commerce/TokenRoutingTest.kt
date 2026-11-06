package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.tenshu.commerce.routing.TokenResponse
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.security.EntitlementTokenVerifier
import org.tenshu.identity.EntitlementSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * HTTP-surface coverage for `POST /commerce/entitlement/token` after the Phase-2 re-gate: a valid
 * session issues a token that verifies under the pinned commerce key, with the tier + quota taken
 * from the account's entitlement (Free → 5 GiB); no session → 401; malformed → 400.
 */
class TokenRoutingTest {

    private val ed = BouncyCastleEd25519Backend()
    private val json = Json { ignoreUnknownKeys = true }
    private val store = InMemoryCommerceStore()
    private val sender = RecordingEmailSender()
    private val config = CommerceConfig.fromEnv { null }

    private fun appTest(block: suspend (client: HttpClient) -> Unit) = testApplication {
        application {
            module(
                minter = EntitlementMinter(config, IssuerKeypair(config.issuerSeed, ed)),
                tokenMinter = EntitlementTokenMinter(config, IssuerKeypair(config.issuerSeed, ed)),
                store = store,
                emailSender = sender,
            )
        }
        block(client)
    }

    @Test
    fun validSession_issuesVerifiableToken() = appTest { client ->
        val sessionToken = client.registerAndVerify(sender, "alice@example.com", ownerId = "owner-1")

        val response = client.post("/commerce/entitlement/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionToken":"$sessionToken"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<TokenResponse>(response.bodyAsText())
        assertEquals(ProductCatalog.FREE, body.tier)
        assertEquals(ProductCatalog.quotaBytes(ProductCatalog.FREE, fallback = 0L), body.quotaBytes)

        val verifier = EntitlementTokenVerifier(
            publicKeysByKeyId = mapOf(null to EntitlementSignature.COMMERCE_PUBLIC_KEY),
            ed25519 = ed,
            nowSec = { System.currentTimeMillis() / 1000 },
        )
        val valid = assertIs<EntitlementTokenVerifier.Result.Valid>(verifier.verify(body.token))
        assertEquals("owner-1", valid.claims.ownerId)
        assertEquals(ProductCatalog.FREE, valid.claims.tier)
    }

    @Test
    fun noSession_isUnauthorized() = appTest { client ->
        val response = client.post("/commerce/entitlement/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionToken":"bogus"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun malformedBody_isBadRequest() = appTest { client ->
        val response = client.post("/commerce/entitlement/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"no":"token"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
