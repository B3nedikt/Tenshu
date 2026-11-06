package org.tenshu.sync.relay

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.tenshu.core.common.security.JwtClaims
import org.tenshu.core.common.security.encodeJwt
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import org.tenshu.sync.relay.mailbox.ContentEdgeStore
import org.tenshu.sync.relay.mailbox.ContentRefStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CR-6e's route (`content-plane-refcount.md` §11.9, track P2): `GET /sync/content/inventory`.
 *
 * ⚠⚠ **The inventory must stay a read.** `ContentInventoryTest` pins the arithmetic; what these pin is the
 * route's posture — that it is owner-scoped like `/sync/usage`, that it needs a credential, and that
 * asking for the number never costs the caller a byte. A reporting endpoint that could free would be
 * the one thing §11.5 separates quarantine from.
 */
class ContentInventoryRoutingTest {

    private val devicePrivateKey = "content-inventory-device-key".encodeToByteArray()

    private fun freshToken(ownerId: String, deviceId: String): String {
        val nowSec = System.currentTimeMillis() / 1000
        return encodeJwt(JwtClaims(ownerId, deviceId, nowSec - 5, nowSec + 60)) { msg ->
            stubEd25519Signature(devicePrivateKey, msg)
        }
    }

    private fun devices(vararg pairs: Pair<String, String>) = KnownDevicesStore().apply {
        pairs.forEach { (owner, device) -> register(owner, device, devicePrivateKey) }
    }

    private fun ref(label: String): String =
        "blob-" + label.encodeToByteArray().joinToString("") { "%02x".format(it) }.padEnd(64, '0').take(64)

    private class Fixture {
        val edges = ContentEdgeStore()
        val refs = ContentRefStore()
        val blobs = ContentBlobStore()
    }

    @Test
    fun reportsTheUndescribedPopulation_andExcludesDescribedBytes() = testApplication {
        val f = Fixture()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices("alice" to "d1"),
                contentEdgeStore = f.edges,
                contentRefStore = f.refs,
                contentBlobStore = f.blobs,
            )
        }
        // Described and live — never the inventory's.
        f.blobs.put("alice", ref("live"), ByteArray(100))
        f.refs.declareRefs("alice", "content-live", listOf(ref("live")))
        f.edges.seedEdge("alice", "holder-1", "file/data", "content-live", 1L)
        // An aborted emit's residue: bytes on the relay that no declaration reaches.
        f.blobs.put("alice", ref("stranded"), ByteArray(250))

        val r = client.get("/sync/content/inventory") {
            header("Authorization", "Bearer ${freshToken("alice", "d1")}")
        }
        val body = r.bodyAsText()

        assertEquals(HttpStatusCode.OK, r.status, body)
        assertTrue(body.contains("\"blobCount\":2"), body)
        assertTrue(body.contains("\"undescribedBlobCount\":1"), body)
        assertTrue(body.contains("\"undescribedBlobBytes\":250"), body)
        assertTrue(body.contains("\"describedContentCount\":1"), body)
        // ⚠ Never a ref list: the inventory reports a size, so it cannot become a work list a client
        // could act on — the same reason the candidate route hands out contentIds and never refs.
        assertTrue(!body.contains(ref("stranded")), "the inventory names no blob: $body")
    }

    @Test
    fun isScopedToTheTokenOwner() = testApplication {
        val f = Fixture()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices("alice" to "d1", "mallory" to "d2"),
                contentEdgeStore = f.edges,
                contentRefStore = f.refs,
                contentBlobStore = f.blobs,
            )
        }
        f.blobs.put("alice", ref("a"), ByteArray(10))
        f.blobs.put("mallory", ref("m"), ByteArray(99_999))

        val body = client.get("/sync/content/inventory") {
            header("Authorization", "Bearer ${freshToken("alice", "d1")}")
        }.bodyAsText()

        assertTrue(body.contains("\"blobCount\":1"), body)
        assertTrue(body.contains("\"undescribedBlobBytes\":10"), "mallory's 99999 must not leak: $body")
    }

    /**
     * CR-5's candidate predicate (`candidateContentIds`): described and named by no live edge. ⚠ Its
     * other caller, `/sync/content/orphan-candidates`, went in CR-2 B6; the inventory still counts it.
     */
    @Test
    fun theCandidateCountIsDescribedMinusLiveEdges() = testApplication {
        val f = Fixture()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices("alice" to "d1"),
                contentEdgeStore = f.edges,
                contentRefStore = f.refs,
                contentBlobStore = f.blobs,
            )
        }
        // Two described; one still held by a live edge, one whose holder is gone.
        f.refs.declareRefs("alice", "held", listOf(ref("h")))
        f.edges.seedEdge("alice", "holder-1", "file/data", "held", 1L)
        f.refs.declareRefs("alice", "dropped", listOf(ref("d")))

        val token = freshToken("alice", "d1")
        val inventory = client.get("/sync/content/inventory") {
            header("Authorization", "Bearer $token")
        }.bodyAsText()

        assertTrue(inventory.contains("\"candidateContentCount\":1"), "only the dropped one: $inventory")
    }

    @Test
    fun requiresACredential() = testApplication {
        application {
            testModule(config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080))
        }

        val r = client.get("/sync/content/inventory")

        assertEquals(HttpStatusCode.Unauthorized, r.status, r.bodyAsText())
    }

    @Test
    fun askingForTheNumberFreesNothing() = testApplication {
        val f = Fixture()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices("alice" to "d1"),
                contentEdgeStore = f.edges,
                contentRefStore = f.refs,
                contentBlobStore = f.blobs,
            )
        }
        f.blobs.put("alice", ref("x"), ByteArray(64))
        f.blobs.put("alice", ref("y"), ByteArray(64))

        repeat(3) {
            client.get("/sync/content/inventory") {
                header("Authorization", "Bearer ${freshToken("alice", "d1")}")
            }
        }

        assertEquals(2, f.blobs.blobCount("alice"), "the inventory is a read, not a sweep")
        assertEquals(128L, f.blobs.bytesUsed("alice"))
    }
}
