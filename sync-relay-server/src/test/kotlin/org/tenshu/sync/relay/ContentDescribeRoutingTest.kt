package org.tenshu.sync.relay

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.tenshu.core.common.security.JwtClaims
import org.tenshu.core.common.security.encodeJwt
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.sync.relay.auth.JwtAuthenticator
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.auth.StubEd25519Backend
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import org.tenshu.sync.relay.mailbox.ContentEdgeStore
import org.tenshu.sync.relay.mailbox.ContentRefStore
import org.tenshu.sync.relay.routing.configureContentRouting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `/sync/content/declare` after CR-2 B3 (`content-plane-cr2-b3-wire-spec.md` §3.4): the `contents`
 * (describe) form only. The CR-3 edge form and `/sync/content/undeclare` are deleted — edges travel on
 * `/sync/push` ([PushEdgesRoutingTest]) — and CR-2 B6 retired the reference handle (`referenceId`),
 * which since the 2026-10 reset is an unknown key.
 *
 * ⚠ Owner scoping is still the property most worth pinning: the owner comes from the verified token,
 * never the body.
 */
class ContentDescribeRoutingTest {

    private val devicePrivateKey = "content-edge-device-key".encodeToByteArray()
    private val devicePublicKey = devicePrivateKey // stub-level: public == private

    private fun signToken(claims: JwtClaims): String =
        encodeJwt(claims) { msg -> stubEd25519Signature(devicePrivateKey, msg) }

    private fun freshToken(ownerId: String, deviceId: String): String {
        val nowSec = System.currentTimeMillis() / 1000
        return signToken(JwtClaims(ownerId, deviceId, nowSec - 5, nowSec + 60))
    }

    private fun devices(vararg pairs: Pair<String, String>) = KnownDevicesStore().apply {
        pairs.forEach { (owner, device) -> register(owner, device, devicePublicKey) }
    }

    private fun ref(label: String): String =
        "blob-" + label.encodeToByteArray().joinToString("") { "%02x".format(it) }.padEnd(64, '0').take(64)

    @Test
    fun withoutAuth_returns401() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices(),
            )
        }
        val r = client.post("/sync/content/declare") {
            contentType(ContentType.Application.Json)
            setBody("""{"contents":[{"contentId":"content-a","refs":["${ref("x")}"]}]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun theEdgeFormIsRefused_andWritesNothing() = testApplication {
        // ⚠⚠ Spec §3.4: deleted, and refused LOUDLY. A pre-B3 client that still declares edges would
        // otherwise read 200 and believe the relay holds a reference it never recorded. Since the
        // 2026-10 reset `edges` is simply an unknown key, so the refusal is the decoder's.
        val edges = ContentEdgeStore()
        val refs = ContentRefStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices("alice" to "device-1"),
                contentEdgeStore = edges,
                contentRefStore = refs,
            )
        }
        val response = client.post("/sync/content/declare") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${freshToken("alice", "device-1")}")
            setBody(
                """{"edges":[{"holderHash":"holder-1","propertyId":"file/data","contentId":"content-edged"}],""" +
                    """"contents":[{"contentId":"content-described","refs":["${ref("both")}"]}]}""",
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("edges"), "the refusal names the offending key")
        assertTrue(edges.referencedContentIds("alice").isEmpty(), "no edge may be written by this route")
        assertTrue(refs.refsOf("alice", "content-described").isEmpty(), "refused whole, not half-obeyed")
    }

    @Test
    fun undeclareIsGone() = testApplication {
        // Spec §3.4 / §8: the deleted route answers 404. Removal is by tombstone, cascade or reap.
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices("alice" to "device-1"),
                contentEdgeStore = ContentEdgeStore(),
            )
        }
        val response = client.post("/sync/content/undeclare") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${freshToken("alice", "device-1")}")
            setBody("""{"holderHashes":["holder-1"]}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    /**
     * CR-2 B6 retired `referenceId` (and its `hlc`); the relay accepted-and-ignored them for clients one
     * release behind until the 2026-10 reset. Now they are unknown keys: a body carrying either is a
     * 400 and writes nothing, and a body without them is the ordinary 200.
     */
    @Test
    fun aRetiredReferenceIdIsAnUnknownKey() = testApplication {
        val refs = ContentRefStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices("alice" to "device-1"),
                contentRefStore = refs,
            )
        }
        val bodies = mapOf(
            "content-a" to ("""{"contents":[{"contentId":"content-a","refs":["${ref("a")}"]}]}""" to HttpStatusCode.OK),
            "content-b" to ("""{"contents":[{"contentId":"content-b","refs":["${ref("b")}"],"referenceId":""}]}""" to HttpStatusCode.BadRequest),
            "content-c" to ("""{"contents":[{"contentId":"content-c","refs":["${ref("c")}"],"hlc":"1:0:d"}]}""" to HttpStatusCode.BadRequest),
        )
        for ((content, bodyAndStatus) in bodies) {
            val (body, expected) = bodyAndStatus
            val response = client.post("/sync/content/declare") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer ${freshToken("alice", "device-1")}")
                setBody(body)
            }
            assertEquals(expected, response.status, "$content: ${response.bodyAsText()}")
        }
        assertEquals(setOf(ref("a")), refs.refsOf("alice", "content-a"))
        assertTrue(refs.refsOf("alice", "content-b").isEmpty())
        assertTrue(refs.refsOf("alice", "content-c").isEmpty())
    }

    @Test
    fun aRequestMayNotCarryUnboundedRefs() = testApplication {
        // ⚠⚠ MAX_TOTAL_REFS_PER_REQUEST: the per-entry cap alone leaves 512 × 4 096 refs in one request.
        val refs = ContentRefStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices("alice" to "device-1"),
                contentRefStore = refs,
            )
        }
        val body = """{"contents":[""" + (1..4).joinToString(",") { e ->
            """{"contentId":"c-$e","refs":[""" +
                (1..4_000).joinToString(",") { i -> "\"" + ref("r-$e-$i") + "\"" } + "]}"
        } + "]}"
        val r = client.post("/sync/content/declare") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${freshToken("alice", "device-1")}")
            setBody(body)
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(refs.refsOf("alice", "c-1").isEmpty(), "a rejected request must write nothing")
    }

    @Test
    fun aHolderLessDescription_writesRefsAndNoEdgeAtAll() = testApplication {
        // ⭐⭐ N1's describe wire (`content-plane-refcount.md` §6e). The relay learns content → blobs
        // — what it must know to free anything — and nothing about which entity or slot holds it.
        //
        // ⚠⚠ The assertion that matters is the NEGATIVE one: no `content_edge` row. `propertyId` is
        // on the wire today only because an edge is keyed by it, and this form is what retires that
        // disclosure. A description that quietly minted an edge would keep it alive for ever.
        val edges = ContentEdgeStore()
        val refs = ContentRefStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices("alice" to "device-1"),
                contentEdgeStore = edges,
                contentRefStore = refs,
            )
        }
        val chunk = ref("holderless")

        val response = client.post("/sync/content/declare") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${freshToken("alice", "device-1")}")
            setBody("""{"contents":[{"contentId":"content-a","refs":["$chunk"]}]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("\"contentsDescribed\":1"), response.bodyAsText())
        assertEquals(setOf(chunk), refs.refsOf("alice", "content-a"))
        assertTrue(
            edges.referencedContentIds("alice").isEmpty(),
            "the describe wire must write NO edge — that is the disclosure it exists to retire",
        )
    }

    @Test
    fun aDescriptionWithNoRefs_is400_becauseItWouldEraseTheSetItNames() = testApplication {
        // ⚠⚠ A `contents` entry is ONLY a ref set, so an empty one makes `declareRefs` delete the rows it claims to be writing. There is no reading of it
        // that is not a client bug, and a silently obeyed one manufactures orphans out of live chunks.
        val refs = ContentRefStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices("alice" to "device-1"),
                contentEdgeStore = ContentEdgeStore(),
                contentRefStore = refs,
            )
        }
        refs.declareRefs("alice", "content-a", listOf(ref("live")))

        val response = client.post("/sync/content/declare") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${freshToken("alice", "device-1")}")
            setBody("""{"contents":[{"contentId":"content-a","refs":[]}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertEquals(
            setOf(ref("live")),
            refs.refsOf("alice", "content-a"),
            "the standing set must survive: rejecting is the point, not reporting after the damage",
        )
    }

    @Test
    fun aDescriptionWithAMalformedRef_is400() = testApplication {
        // A ref that does not match `blob-<64 hex>` can address
        // nothing stored, so it is rejected loudly rather than written as a row nothing will match.
        val refs = ContentRefStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices("alice" to "device-1"),
                contentEdgeStore = ContentEdgeStore(),
                contentRefStore = refs,
            )
        }

        val response = client.post("/sync/content/declare") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${freshToken("alice", "device-1")}")
            setBody("""{"contents":[{"contentId":"content-a","refs":["not-a-blob-ref"]}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertTrue(refs.refsOf("alice", "content-a").isEmpty())
    }

    @Test
    fun aDescriptionLandsWithNoEdgeStoreAtALL() = testApplication {
        // ⚠⚠ The configuration the describe wire EXISTS for: refs wired, edges gone (N6's end state).
        // Registering `declare` inside the edge guard made that the one configuration in which nothing
        // could be described — the route 404'd for both forms, while the relay quietly held bytes no
        // declaration reached.
        val known = devices("alice" to "device-1")
        val refs = ContentRefStore()
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureContentRouting(
                    config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                    jwtAuthenticator = JwtAuthenticator(known, StubEd25519Backend()),
                    contentBlobStore = ContentBlobStore(),
                    contentEdges = null,
                    contentRefs = refs,
                )
            }
        }
        val token = freshToken("alice", "device-1")
        val chunk = ref("edgeless")

        val described = client.post("/sync/content/declare") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""{"contents":[{"contentId":"content-a","refs":["$chunk"]}]}""")
        }

        assertEquals(HttpStatusCode.OK, described.status, described.bodyAsText())
        assertTrue(described.bodyAsText().contains("\"contentsDescribed\":1"), described.bodyAsText())
        assertEquals(setOf(chunk), refs.refsOf("alice", "content-a"))
    }

    @Test
    fun aDescriptionWithNoRefStoreToWriteTo_is404_notAQuiet200() = testApplication {
        // ⚠⚠ Loud, not silent. Answering 200 would tell a client its content is described when
        // nothing on this relay knows the bytes — which is exactly the belief under which a later
        // enumeration pass reads those bytes as undescribed.
        val known = devices("alice" to "device-1")
        application {
            // The one plugin this route needs that `testModule` would otherwise bring: without it
            // the body never decodes and the 400 branch answers before the guard under test.
            install(ContentNegotiation) { json() }
            routing {
                configureContentRouting(
                    config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                    jwtAuthenticator = JwtAuthenticator(known, StubEd25519Backend()),
                    contentBlobStore = ContentBlobStore(),
                    contentEdges = ContentEdgeStore(),
                    contentRefs = null,
                )
            }
        }

        val response = client.post("/sync/content/declare") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${freshToken("alice", "device-1")}")
            setBody("""{"contents":[{"contentId":"content-a","refs":["${ref("x")}"]}]}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
    }

    @Test
    fun anEmptyBodyIsStill400_whenNeitherFormCarriesAnything() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices("alice" to "device-1"),
                contentEdgeStore = ContentEdgeStore(),
            )
        }

        val response = client.post("/sync/content/declare") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${freshToken("alice", "device-1")}")
            setBody("""{"contents":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
    }
}
