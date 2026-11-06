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
import org.tenshu.commerce.routing.MintedEntitlement
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.identity.EntitlementSignature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HTTP coverage for `POST /commerce/entitlement/mint` after the Phase-2 re-gate
 * (`commerce-accounts-and-sessions.md` §4.3): a **valid commerce session** mints a record that
 * verifies under the pinned commerce key; no/invalid session → 401; malformed body → 400. There is
 * no client-supplied email/owner any more — the verified account decides both.
 */
class MintRoutingTest {

    private val ed = BouncyCastleEd25519Backend()
    private val json = Json { ignoreUnknownKeys = true }
    private val store = InMemoryCommerceStore()
    private val sender = RecordingEmailSender()
    private val config = CommerceConfig.fromEnv { null }

    private fun appTest(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            module(
                minter = EntitlementMinter(config, IssuerKeypair(config.issuerSeed, ed)),
                store = store,
                emailSender = sender,
            )
        }
        block(client)
    }

    @Test
    fun validSession_mintsVerifiableEntitlement() = appTest { client ->
        val token = client.registerAndVerify(sender, "alice@example.com", ownerId = "owner-1")

        val response = client.post("/commerce/entitlement/mint") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionToken":"$token"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val minted = json.decodeFromString<MintedEntitlement>(response.bodyAsText())
        assertEquals(EntitlementSignature.COMMERCE_PUBLISHER_ID, minted.publisherId)
        assertEquals("owner-1", minted.ownerId)
        assertEquals(ProductCatalog.FREE, minted.productId)

        val signed = EntitlementSignature.SignedEntitlement(
            ownerId = minted.ownerId,
            publisherId = minted.publisherId,
            productId = minted.productId,
            validFrom = minted.validFrom,
            validUntil = minted.validUntil,
            signature = Base64.getDecoder().decode(minted.publisherSignature),
        )
        val publicKeys = EntitlementSignature.publicKeysFor(minted.publisherId)
        assertTrue(publicKeys.isNotEmpty())
        assertTrue(
            publicKeys.any { ed.verify(it, signed.message(), signed.signature) },
            "the minted DTO must verify under one of the pinned commerce keys",
        )
    }

    @Test
    fun noSession_isUnauthorized_andDoesNotMint() = appTest { client ->
        val response = client.post("/commerce/entitlement/mint") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionToken":"bogus"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue("publisherSignature" !in response.bodyAsText())
    }

    @Test
    fun malformedBody_isBadRequest() = appTest { client ->
        val response = client.post("/commerce/entitlement/mint") {
            contentType(ContentType.Application.Json)
            setBody("""{"not":"a token"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
