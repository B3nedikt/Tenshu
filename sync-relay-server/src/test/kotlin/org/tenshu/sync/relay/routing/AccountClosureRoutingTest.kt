package org.tenshu.sync.relay.routing

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.tenshu.core.common.security.AccountClosureClaims
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.security.encodeAccountClosureToken
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import org.tenshu.sync.relay.testModule
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `POST /sync/account/closure` — the only inbound route that can destroy user content
 * (`retention-quota-model.md` Face A / D4).
 *
 * ⚠ Almost every case here asserts a **refusal**, and every one of them also asserts the blobs are
 * still there: a route that answered correctly while deleting anyway would pass a status-only test.
 *
 * Records are minted with the real [BouncyCastleEd25519Backend] and the committed dev issuer seed
 * (self-contained, no `:commerce-service` dependency — the `EntitlementEnforcementTest` idiom).
 */
@OptIn(ExperimentalEncodingApi::class)
class AccountClosureRoutingTest {

    private val day = 24L * 60 * 60 * 1000

    private val ed = BouncyCastleEd25519Backend()
    private val issuerSeed = ByteArray(32) { (it + 65).toByte() }
    private val issuerPubB64 = Base64.Default.encode(ed.derivePublicKey(issuerSeed))

    private fun config(
        reclaim: Boolean,
        graceMs: Long = 30 * day,
        withIssuer: Boolean = true,
        keyId: String? = null,
    ) = RelayConfig(
        devMode = true,
        bindHost = "127.0.0.1",
        port = 8080,
        entitlementIssuerPublicKeyB64 = if (withIssuer) issuerPubB64 else null,
        entitlementIssuerKeyId = keyId,
        accountClosureReclaim = reclaim,
        accountClosureGraceMs = graceMs,
    )

    /**
     * Mint against the real wall clock — the module's verifier reads `System.currentTimeMillis`,
     * so [closedDaysAgo] is what positions a record relative to the grace window.
     */
    private fun record(
        ownerId: String = "alice",
        closedDaysAgo: Long = 0,
        ttlSec: Long = 3_600,
        keyId: String? = null,
    ): String {
        val nowSec = System.currentTimeMillis() / 1000
        return encodeAccountClosureToken(
            AccountClosureClaims(
                ownerId = ownerId,
                closedAtSec = nowSec - closedDaysAgo * 24 * 60 * 60,
                issuedAtSec = nowSec - 5,
                expiresAtSec = nowSec + ttlSec,
                keyId = keyId,
            ),
        ) { message -> ed.sign(issuerSeed, message) }
    }

    private fun storeWith(owner: String = "alice") = ContentBlobStore().apply {
        put(owner, "blob-1", ByteArray(10), nowMs = 0)
    }

    @Test
    fun `the route does not exist while the flag is off`() = testApplication {
        val store = storeWith()
        application { testModule(config(reclaim = false), contentBlobStore = store, ed25519 = ed) }

        val response = client.post("/sync/account/closure") { setBody(record(closedDaysAgo = 365)) }

        // ⚠ 404, not 403: a probe learns nothing about whether this deployment has reclamation at
        // all. The route is registered only when the flag is deliberately opened (as production
        // did, 2026-10-24).
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(1, store.blobCount("alice"), "nothing was touched")
    }

    @Test
    fun `the route does not exist without an issuer key to verify against`() = testApplication {
        val store = storeWith()
        application {
            testModule(config(reclaim = true, withIssuer = false), contentBlobStore = store, ed25519 = ed)
        }

        // The flag alone is not enough — with no key every record would be unverifiable, and a
        // route that can only ever reject is worse than no route.
        val response = client.post("/sync/account/closure") { setBody(record(closedDaysAgo = 365)) }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(1, store.blobCount("alice"))
    }

    @Test
    fun `an unsigned record is rejected`() = testApplication {
        val store = storeWith()
        application { testModule(config(reclaim = true), contentBlobStore = store, ed25519 = ed) }

        val response = client.post("/sync/account/closure") { setBody("not-a-record") }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(1, store.blobCount("alice"))
    }

    @Test
    fun `a tampered record is rejected`() = testApplication {
        val store = storeWith()
        application { testModule(config(reclaim = true), contentBlobStore = store, ed25519 = ed) }

        // Swap the owner in the payload segment: the signature no longer covers it.
        val parts = record(closedDaysAgo = 365).split(".")
        val tampered = parts[0] + "." + parts[1].dropLast(2) + "AA" + "." + parts[2]
        val response = client.post("/sync/account/closure") { setBody(tampered) }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(1, store.blobCount("alice"))
    }

    @Test
    fun `an expired record is rejected`() = testApplication {
        val store = storeWith()
        application { testModule(config(reclaim = true), contentBlobStore = store, ed25519 = ed) }

        // ⚠⚠ This is what makes reopening safe. A record that commerce has stopped re-issuing must
        // stop being honoured, or a leaked one would be a permanent delete-this-owner capability.
        val response = client.post("/sync/account/closure") {
            setBody(record(closedDaysAgo = 365, ttlSec = -10))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(1, store.blobCount("alice"))
    }

    @Test
    fun `a record signed under an unexpected key id is rejected`() = testApplication {
        val store = storeWith()
        application {
            testModule(config(reclaim = true, keyId = "kid-2026"), contentBlobStore = store, ed25519 = ed)
        }

        // Correct signature, wrong `kid` — pinning must be a hard no, never a fall-through to the
        // default key (A29 rotation).
        val response = client.post("/sync/account/closure") {
            setBody(record(closedDaysAgo = 365, keyId = "kid-2025"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(1, store.blobCount("alice"))
    }

    @Test
    fun `a valid record inside the grace window is accepted but not acted on`() = testApplication {
        val store = storeWith()
        application {
            testModule(config(reclaim = true, graceMs = 30 * day), contentBlobStore = store, ed25519 = ed)
        }

        val response = client.post("/sync/account/closure") { setBody(record(closedDaysAgo = 1)) }

        // 202: understood, deliberately not acted on. Commerce re-posts, so there is nothing for it
        // to retry differently.
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals(1, store.blobCount("alice"), "the grace window is the point")
    }

    @Test
    fun `a valid record past the grace window reclaims that owner and only that owner`() = testApplication {
        val store = ContentBlobStore().apply {
            put("alice", "blob-1", ByteArray(10), nowMs = 0)
            put("bob", "blob-1", ByteArray(10), nowMs = 0)
        }
        application {
            testModule(config(reclaim = true, graceMs = 30 * day), contentBlobStore = store, ed25519 = ed)
        }

        val response = client.post("/sync/account/closure") { setBody(record(closedDaysAgo = 31)) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, store.blobCount("alice"))
        // ⚠ Bob shares the same `ref`. Reclaim is owner-scoped, never ref-scoped.
        assertEquals(1, store.blobCount("bob"), "a co-owner of the same ref is untouched")
    }

    @Test
    fun `re-posting the same record after reclamation is harmless`() = testApplication {
        val store = storeWith()
        application {
            testModule(config(reclaim = true, graceMs = 30 * day), contentBlobStore = store, ed25519 = ed)
        }
        val token = record(closedDaysAgo = 31)

        // Delivery is a repeating sweep with no server-side idempotency state — the second post
        // must be an ordinary success over an already-empty keyspace, not an error to handle.
        assertEquals(HttpStatusCode.OK, client.post("/sync/account/closure") { setBody(token) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/sync/account/closure") { setBody(token) }.status)
        assertEquals(0, store.blobCount("alice"))
    }
}
