package org.tenshu.sync.relay

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.security.EntitlementTokenClaims
import org.tenshu.core.common.security.encodeEntitlementToken
import org.tenshu.core.common.util.HlcStamp
import org.tenshu.core.common.util.sha256
import org.tenshu.sync.relay.auth.RelayEntitlementVerifier.Companion.ENTITLEMENT_HEADER
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry.EdgeWrite
import org.tenshu.sync.relay.mailbox.ContentEdgeStore
import org.tenshu.sync.relay.mailbox.ContentFreedStore
import org.tenshu.sync.relay.mailbox.ContentRefStore
import org.tenshu.sync.relay.routing.OPERATOR_TOKEN_HEADER
import org.tenshu.sync.relay.routing.configureOperatorRouting
import org.tenshu.sync.relay.routing.quotaWithHeld
import org.tenshu.sync.relay.storage.ContentSweepJob
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CR-2 B5r through the routes (`content-plane-cr2-b5-spec.md` §1.3–§1.5, §3 "D3" and "D5"): the legacy
 * FREE routes answer 410 only when armed; `/sync/usage` and the quota leave held bytes out only when
 * armed, and a ref shared with live content stays charged; the status route is behind the operator brakes.
 */
@OptIn(ExperimentalEncodingApi::class)
class ContentSweepRoutingTest {

    private val owner = "alice-b5"

    private fun config(mode: ContentSweepMode, enforce: Boolean = false) = RelayConfig(
        devMode = true,
        bindHost = "127.0.0.1",
        port = 8080,
        contentSweepMode = mode,
        entitlementIssuerPublicKeyB64 = if (enforce) issuerPubB64 else null,
        entitlementEnforce = enforce,
    )

    private class Plane {
        val edges = ContentEdgeStore()
        val refs = ContentRefStore()
        val blobs = ContentBlobStore()
        val freeing = ContentFreedStore(edges, refs, blobs)
    }

    private fun relay(mode: ContentSweepMode, enforce: Boolean = false, test: suspend ApplicationTestBuilder.(Plane) -> Unit) {
        val plane = Plane()
        testApplication {
            application {
                testModule(
                    config = config(mode, enforce),
                    contentEdgeStore = plane.edges,
                    contentRefStore = plane.refs,
                    contentBlobStore = plane.blobs,
                    contentFreeing = plane.freeing,
                    ed25519 = ed,
                )
            }
            test(plane)
        }
    }

    // ---- D3, retired by CR-2 B6 ------------------------------------------------------------------------

    private suspend fun ApplicationTestBuilder.legacyDelete(): HttpResponse = client.post("/sync/content/delete") {
        header("X-Dev-Owner", owner)
        contentType(ContentType.Application.Json)
        setBody("""{"refs":["${"blob-" + "a".repeat(64)}"]}""")
    }

    private suspend fun ApplicationTestBuilder.legacyReclaim(): HttpResponse = client.post("/sync/content/reclaim") {
        header("X-Dev-Owner", owner)
        contentType(ContentType.Application.Json)
        setBody("""{"contentIds":["c1"]}""")
    }

    /**
     * ⚠⚠ B5r's D3 answered these 410 when armed and obeyed them otherwise. B6 removed both routes, so in
     * EVERY mode a client can no longer free content — the relay sweep is the only freeing path, and a
     * relay deployed `off` leaks rather than letting a device free (`content-plane-cr2-b6-spec.md` §2).
     */
    @Test
    fun inEveryMode_theLegacyFreeRoutesAreGone() {
        for (mode in ContentSweepMode.entries) {
            relay(mode) {
                for (response in listOf(legacyDelete(), legacyReclaim())) {
                    assertTrue(
                        response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.MethodNotAllowed,
                        "$mode: ${response.status}",
                    )
                }
            }
        }
    }

    // ---- D5 -------------------------------------------------------------------------------------------

    /** c-dead (100 B alone + 10 B shared) lost its last edge; c-live holds the shared ref and 1 B more. */
    private fun Plane.heldScene() {
        blobs.put(owner, REF_A, ByteArray(100))
        blobs.put(owner, REF_B, ByteArray(10))
        blobs.put(owner, REF_C, ByteArray(1))
        refs.declareRefs(owner, "c-dead", listOf(REF_A, REF_B))
        refs.declareRefs(owner, "c-live", listOf(REF_B, REF_C))
        edges.writeEdges(owner, "h1", HlcStamp(1, 0, "d"), listOf(EdgeWrite("p", "c-dead")))
        edges.writeEdges(owner, "h2", HlcStamp(1, 0, "d"), listOf(EdgeWrite("p", "c-live")))
        edges.writeEdges(owner, "h1", HlcStamp(2, 0, "d"), listOf(EdgeWrite("p", null)))
    }

    private suspend fun ApplicationTestBuilder.usage(): Pair<Long, Long> {
        val body = Json.parseToJsonElement(client.get("/sync/usage") { header("X-Dev-Owner", owner) }.bodyAsText()).jsonObject
        return body.getValue("usedBytes").jsonPrimitive.long to (body["heldBytes"]?.jsonPrimitive?.long ?: -1)
    }

    @Test
    fun armed_usageLeavesHeldBytesOut_andReportsThem_butASharedRefStaysCharged() = relay(ContentSweepMode.ARMED) { plane ->
        plane.heldScene()
        assertEquals(11L to 100L, usage())
    }

    @Test
    fun offAndDryRun_usageChargesEverything() {
        for (mode in listOf(ContentSweepMode.OFF, ContentSweepMode.DRY_RUN)) {
            relay(mode) { plane ->
                plane.heldScene()
                assertEquals(111L to 0L, usage(), "$mode: bytes nothing will free must count")
            }
        }
    }

    @Test
    fun armed_theQuotaCheckLeavesHeldBytesOut_offItDoesNot() {
        val chunk = ByteArray(64) { 7 }
        val chunkRef = "blob-" + sha256(chunk).joinToString("") { ((it.toInt() and 0xFF).toString(16)).padStart(2, '0') }
        // 111 B stored, 100 B held: a 64-byte PUT under a 100-byte quota fits only if the held bytes are not charged.
        suspend fun ApplicationTestBuilder.put() = client.put("/sync/content/$chunkRef") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", owner)
            header(ENTITLEMENT_HEADER, mintToken(quotaBytes = 100))
            setBody(chunk)
        }
        relay(ContentSweepMode.ARMED, enforce = true) { plane ->
            plane.heldScene()
            assertEquals(HttpStatusCode.NoContent, put().status)
        }
        relay(ContentSweepMode.OFF, enforce = true) { plane ->
            plane.heldScene()
            assertEquals(HttpStatusCode.InsufficientStorage, put().status)
        }
    }

    @Test
    fun quotaWithHeld_saturates() {
        assertEquals(100L, quotaWithHeld(100, 0))
        assertEquals(150L, quotaWithHeld(100, 50))
        assertEquals(Long.MAX_VALUE, quotaWithHeld(Long.MAX_VALUE - 1, 50))
    }

    // ---- status (§1.5) ----------------------------------------------------------------------------------

    @Test
    fun theStatusRoute_isBehindTheOperatorBrakes_andReportsTheSweep() {
        val plane = Plane()
        val sweep = ContentSweepJob(
            ContentSweepMode.DRY_RUN, graceMs = 900_000, intervalMs = 300_000,
            edges = plane.edges, freeing = plane.freeing, refs = plane.refs, blobs = plane.blobs, drainer = null,
        )
        for ((token, loopback, expected) in listOf(
            Triple(null, true, HttpStatusCode.NotFound),
            Triple("secret", false, HttpStatusCode.NotFound),
            Triple("secret", true, HttpStatusCode.OK),
        )) {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { configureOperatorRouting(token, job = null, isLoopbackPeer = { loopback }, sweep = sweep) }
                }
                val response = client.get("/ops/content-sweep/status") { header(OPERATOR_TOKEN_HEADER, "secret") }
                assertEquals(expected, response.status, "token=$token loopback=$loopback")
                if (expected == HttpStatusCode.OK) {
                    val body = response.bodyAsText()
                    assertTrue("\"mode\":\"dry_run\"" in body && "\"graceMs\":900000" in body && "\"intervalMs\":300000" in body, body)
                }
            }
        }
    }

    // ---- entitlement fixture (EntitlementEnforcementTest's dev issuer) ---------------------------------

    private val ed = BouncyCastleEd25519Backend()
    private val issuerSeed = ByteArray(32) { (it + 65).toByte() }
    private val issuerPubB64 = Base64.Default.encode(ed.derivePublicKey(issuerSeed))

    private fun mintToken(quotaBytes: Long): String {
        val now = System.currentTimeMillis() / 1000
        return encodeEntitlementToken(
            EntitlementTokenClaims(
                ownerId = owner,
                tier = "tenshu.drive.pro",
                quotaBytes = quotaBytes,
                issuedAtSec = now - 5,
                expiresAtSec = now + 3600,
            ),
        ) { msg -> ed.sign(issuerSeed, msg) }
    }

    private companion object {
        val REF_A = "blob-" + "a".repeat(64)
        val REF_B = "blob-" + "b".repeat(64)
        val REF_C = "blob-" + "c".repeat(64)
    }
}
