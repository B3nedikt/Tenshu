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
import org.tenshu.commerce.store.EntitlementHistory
import org.tenshu.commerce.store.EntitlementSource
import org.tenshu.commerce.store.Rail
import org.tenshu.commerce.SessionTokens
import org.tenshu.commerce.store.RedemptionCode
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.identity.EntitlementSignature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HTTP coverage for `POST /commerce/entitlement/redeem` (§2.3): a valid seeded code mints a record
 * that verifies under the pinned commerce key; an exhausted or expired code → 403; the path is
 * identity-light (no email, no session).
 */
class RedeemRoutingTest {

    private val ed = BouncyCastleEd25519Backend()
    private val json = Json { ignoreUnknownKeys = true }
    private val store = InMemoryCommerceStore()
    private val config = CommerceConfig.fromEnv { null }

    private fun appTest(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            module(
                minter = EntitlementMinter(config, IssuerKeypair(config.issuerSeed, ed)),
                store = store,
                now = { FIXED_NOW },
            )
        }
        block(client)
    }

    private suspend fun HttpClient.redeem(code: String, ownerId: String, sessionToken: String? = null) =
        post("/commerce/entitlement/redeem") {
            contentType(ContentType.Application.Json)
            val session = sessionToken?.let { ""","sessionToken":"$it"""" } ?: ""
            setBody("""{"code":"$code","ownerId":"$ownerId"$session}""")
        }

    @Test
    fun validCode_mintsVerifiableEntitlement() = appTest { client ->
        store.putRedemptionCode(RedemptionCode(OtpCodes.hash("FOUNDER-1"), ProductCatalog.PREMIUM, maxUses = 1, usedCount = 0))

        val response = client.redeem("FOUNDER-1", ownerId = "owner-1")
        assertEquals(HttpStatusCode.OK, response.status)

        val minted = json.decodeFromString<MintedEntitlement>(response.bodyAsText())
        assertEquals("owner-1", minted.ownerId)
        assertEquals(ProductCatalog.PREMIUM, minted.productId)
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
        assertTrue(publicKeys.any { ed.verify(it, signed.message(), signed.signature) })
    }

    @Test
    fun unknownCode_isForbidden() = appTest { client ->
        val response = client.redeem("NOPE", ownerId = "owner-1")
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue("publisherSignature" !in response.bodyAsText())
    }

    @Test
    fun exhaustedCode_isForbidden() = appTest { client ->
        store.putRedemptionCode(RedemptionCode(OtpCodes.hash("ONCE"), ProductCatalog.BASIC, maxUses = 1, usedCount = 0))
        assertEquals(HttpStatusCode.OK, client.redeem("ONCE", "owner-1").status)
        // Second redemption exceeds max_uses.
        assertEquals(HttpStatusCode.Forbidden, client.redeem("ONCE", "owner-2").status)
    }

    @Test
    fun expiredCode_isForbidden() = appTest { client ->
        store.putRedemptionCode(
            RedemptionCode(OtpCodes.hash("OLD"), ProductCatalog.BASIC, maxUses = 5, usedCount = 0, expiresAt = FIXED_NOW - 1),
        )
        assertEquals(HttpStatusCode.Forbidden, client.redeem("OLD", "owner-1").status)
    }

    private companion object {
        const val FIXED_NOW = 1_000_000L
    }

    /**
     * ⚠⚠ The binding this route was missing. `EntitlementSource.Redeem` has existed unused since
     * the ledger was written, and without a row there the redeemed tier is invisible to the money
     * path: `/token` mints from the ACCOUNT's entitlement, the relay treats that token as final,
     * and the user is shown — and metered at — their old tier while holding a valid signed record.
     * Observed live 2026-08-08: a Premium redemption that left the device on 5 GiB.
     *
     * ⚠ `currentEntitlement` picks the highest-quota valid row, so Premium simply outranks the Free
     * row a signup leaves behind — there is no precedence rule to invent here, and the tier falls
     * back on its own when the redemption's window ends.
     */
    @Test
    fun withASession_bindsTheTierToTheAccountLedger() = appTest { client ->
        val account = store.createAccount(
            emailNormalised = "redeemer@example.com",
            createdAt = FIXED_NOW,
            rail = Rail.Free,
        )
        val session = "session-token-under-test"
        store.createSession(
            tokenHash = SessionTokens.hash(session),
            accountId = account.id,
            createdAt = FIXED_NOW,
            expiresAt = FIXED_NOW + 1_000_000,
        )
        store.appendEntitlementHistory(
            EntitlementHistory(
                accountId = account.id,
                productId = ProductCatalog.FREE,
                validFrom = FIXED_NOW,
                validUntil = FIXED_NOW + 1_000_000,
                source = EntitlementSource.Free,
                issuedAt = FIXED_NOW,
            ),
        )
        store.putRedemptionCode(
            RedemptionCode(OtpCodes.hash("PREMIUM-1"), ProductCatalog.PREMIUM, maxUses = 1, usedCount = 0),
        )

        val response = client.redeem("PREMIUM-1", ownerId = "owner-1", sessionToken = session)
        assertEquals(HttpStatusCode.OK, response.status)

        val current = store.currentEntitlement(account.id, FIXED_NOW)
        assertEquals(
            ProductCatalog.PREMIUM,
            current?.productId,
            "the account's own tier must reflect the redemption, or /token keeps reporting the old one",
        )
        assertEquals(EntitlementSource.Redeem, current?.source)
    }

    /** Absent session = the historical identity-light path: a code with no account, still honoured. */
    @Test
    fun withoutASession_staysIdentityLight() = appTest { client ->
        store.putRedemptionCode(
            RedemptionCode(OtpCodes.hash("ANON-1"), ProductCatalog.PREMIUM, maxUses = 1, usedCount = 0),
        )

        val response = client.redeem("ANON-1", ownerId = "owner-anon")

        assertEquals(HttpStatusCode.OK, response.status)
        val minted = json.decodeFromString<MintedEntitlement>(response.bodyAsText())
        assertEquals(ProductCatalog.PREMIUM, minted.productId)
    }

    /**
     * ⚠⚠ Ordering rule: a supplied-but-invalid session must NOT spend the code. Otherwise the caller
     * gets a record the account ledger never learns about and the redemption is gone — precisely the
     * failure this binding exists to prevent, reachable by presenting a stale session.
     */
    @Test
    fun anInvalidSession_isRefusedAndDoesNotConsumeTheCode() = appTest { client ->
        store.putRedemptionCode(
            RedemptionCode(OtpCodes.hash("GUARD-1"), ProductCatalog.PREMIUM, maxUses = 1, usedCount = 0),
        )

        val response = client.redeem("GUARD-1", ownerId = "owner-x", sessionToken = "not-a-real-session")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(
            0,
            store.getRedemptionCode(OtpCodes.hash("GUARD-1"))?.usedCount,
            "the code must survive a refused redeem",
        )
    }
}
