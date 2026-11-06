package org.tenshu.sync.relay.routing

import io.ktor.client.request.delete
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.mailbox.ShareStore
import org.tenshu.sync.relay.mailbox.TakedownOutcome
import org.tenshu.sync.relay.mailbox.TakedownReason
import org.tenshu.sync.relay.storage.FileBlobStore
import org.tenshu.sync.relay.storage.RelayDatabase
import org.tenshu.sync.relay.storage.SqliteContentBlobStore
import org.tenshu.sync.relay.storage.SqliteShareStore
import org.tenshu.sync.relay.testModule
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST /ops/share/takedown` — notice-and-action on one share link (acceptable-use §3,
 * `docs/how-to/take-down-a-share-link.md`). It deletes ciphertext, so every brake is pinned like the
 * reclaim routes', and the effect is pinned end to end: the link stops serving, its bytes go, one
 * audit row per call, and nothing else is touched.
 */
class ShareTakedownRoutingTest {

    private lateinit var dbFile: File
    private lateinit var blobDir: File

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-takedown-test", ".db")
        blobDir = File.createTempFile("relay-takedown-blobs", "").also { it.delete(); it.mkdirs() }
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
        blobDir.deleteRecursively()
    }

    private fun record(shareId: String, owner: String = "alice") = ShareStore.ShareRecord(
        shareId = shareId, ownerId = owner, sealedMeta = byteArrayOf(4),
        chunkCount = 2, chunkHashes = listOf(byteArrayOf(1), byteArrayOf(2)),
        expiresAtMillis = null, maxOpens = null, createdAtMs = 1_000,
    )

    /** The operator routes over the durable store, as production runs them. */
    private fun withOps(
        token: String? = TOKEN,
        loopback: Boolean = true,
        test: suspend ApplicationTestBuilder.(SqliteShareStore, FileBlobStore, SqliteContentBlobStore) -> Unit,
    ) {
        RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}").use { db ->
            val files = FileBlobStore(blobDir)
            val shares = SqliteShareStore(db, files)
            val content = SqliteContentBlobStore(db, files)
            shares.create(record(SHARE))
            shares.putChunk(SHARE, 0, byteArrayOf(1, 2, 3))
            shares.putChunk(SHARE, 1, byteArrayOf(4, 5))
            shares.create(record(OTHER_SHARE))
            shares.putChunk(OTHER_SHARE, 0, byteArrayOf(9))
            content.put("alice", CONTENT_REF, byteArrayOf(7, 7), nowMs = 0)
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        configureOperatorRouting(token, job = null, isLoopbackPeer = { loopback }, now = { NOW }, shares = shares)
                    }
                }
                test(shares, files, content)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.takeDown(
        body: String = """{"shareId":"$SHARE","reason":"illegal_content","note":"ticket 42"}""",
        token: String? = TOKEN,
    ): HttpResponse = client.post("/ops/share/takedown") {
        contentType(ContentType.Application.Json)
        token?.let { header(OPERATOR_TOKEN_HEADER, it) }
        setBody(body)
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    // ---- the brakes ---------------------------------------------------------------------------

    @Test
    fun withoutAConfiguredToken_theRouteDoesNotExist() = withOps(token = null) { shares, _, _ ->
        for (presented in listOf("anything", "", null)) {
            assertEquals(HttpStatusCode.NotFound, takeDown(token = presented).status, "token=$presented")
        }
        assertTrue(shares.takedowns(SHARE).isEmpty())
        assertTrue(shares.open(SHARE, NOW) != null, "the link still serves")
    }

    @Test
    fun noToken_orAWrongOne_isRefused_andChangesNothing() = withOps { shares, files, _ ->
        for (presented in listOf(null, "guess", "", TOKEN + "x", TOKEN.dropLast(1))) {
            val response = takeDown(token = presented)
            assertEquals(HttpStatusCode.NotFound, response.status, "token=$presented")
            assertEquals("", response.bodyAsText(), "a refusal says nothing about which brake held")
        }
        assertTrue(shares.takedowns(SHARE).isEmpty(), "no audit row for a refused caller")
        assertTrue(files.exists("share/$SHARE/0"), "no bytes deleted for a refused caller")
        assertTrue(shares.open(SHARE, NOW) != null, "the link still serves")
    }

    @Test
    fun theRightToken_offLoopback_isRefused() = withOps(loopback = false) { shares, files, _ ->
        assertEquals(HttpStatusCode.NotFound, takeDown().status, "the token alone never suffices")
        assertTrue(shares.takedowns(SHARE).isEmpty())
        assertTrue(files.exists("share/$SHARE/0"))
    }

    // ---- the effect ---------------------------------------------------------------------------

    @Test
    fun aTakedown_stopsServing_deletesTheSharesBytes_andWritesOneAuditRow() = withOps { shares, files, content ->
        val response = takeDown()
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.json()
        assertEquals("taken_down", body.getValue("outcome").jsonPrimitive.content)
        assertEquals("alice", body.getValue("ownerId").jsonPrimitive.content)
        assertEquals("5", body.getValue("bytesDeleted").jsonPrimitive.content)
        assertEquals("true", body.getValue("bytesDeleteCompleted").jsonPrimitive.content)

        // Stops serving: the descriptor and every chunk are gone for a recipient.
        assertNull(shares.open(SHARE, NOW), "the descriptor GET is a 410 now")
        assertNull(shares.getChunk(SHARE, 0, NOW))
        assertFalse(files.exists("share/$SHARE/0") || files.exists("share/$SHARE/1"), "its ciphertext is deleted")

        // Audit: exactly one row, no plaintext beyond what the operator typed.
        val rows = shares.takedowns(SHARE)
        assertEquals(1, rows.size)
        with(rows.single()) {
            assertEquals(TakedownOutcome.TAKEN_DOWN, outcome)
            assertEquals(TakedownReason.ILLEGAL_CONTENT, reason)
            assertEquals("alice", ownerId)
            assertEquals(NOW, takenDownAtMs)
            assertEquals("ticket 42", operatorNote)
        }

        // Nothing else: another share of the same owner, and the owner's durable content, are untouched.
        assertTrue(shares.open(OTHER_SHARE, NOW) != null)
        assertContentEquals(byteArrayOf(9), shares.getChunk(OTHER_SHARE, 0, NOW))
        assertContentEquals(byteArrayOf(7, 7), content.get("alice", CONTENT_REF, NOW))

        // The owner's own revoke afterwards is an ordinary success, not an error to retry.
        assertTrue(shares.revoke(SHARE, "alice", NOW + 1))
    }

    @Test
    fun aRepeat_isIdempotent_andAudited() = withOps { shares, _, _ ->
        takeDown()
        val again = takeDown(body = """{"shareId":"$SHARE","reason":"illegal_content"}""")
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals("already_taken_down", again.json().getValue("outcome").jsonPrimitive.content)
        assertEquals("0", again.json().getValue("bytesDeleted").jsonPrimitive.content)
        assertEquals(
            listOf(TakedownOutcome.TAKEN_DOWN, TakedownOutcome.ALREADY_TAKEN_DOWN),
            shares.takedowns(SHARE).map { it.outcome },
        )
        assertNull(shares.open(SHARE, NOW))
    }

    @Test
    fun anUnknownShareId_isRecordedAsNotFound_withNoOwner() = withOps { shares, files, _ ->
        val response = takeDown(body = """{"shareId":"nope","reason":"csam"}""")
        assertEquals(HttpStatusCode.OK, response.status, "a bodyless 404 means a refused caller; this is an answer")
        assertEquals("not_found", response.json().getValue("outcome").jsonPrimitive.content)
        val row = shares.takedowns("nope").single()
        assertEquals(TakedownOutcome.NOT_FOUND, row.outcome)
        assertNull(row.ownerId)
        assertTrue(files.exists("share/$SHARE/0"), "an unknown id deletes nothing")
    }

    @Test
    fun aMalformedRequest_isRejected_andNotAudited() = withOps { shares, _, _ ->
        val bad = listOf(
            """{"shareId":"$SHARE","reason":"because"}""",
            """{"shareId":"https://tenshu.app/s/$SHARE#k=secret","reason":"csam"}""",
            """{"shareId":"$SHARE#k=secret","reason":"csam"}""",
            """{"shareId":"","reason":"csam"}""",
            """{"shareId":"$SHARE","reason":"csam","note":"two\nlines"}""",
            """{"shareId":"$SHARE","reason":"csam","note":"${"x".repeat(501)}"}""",
            """{"reason":"csam"}""",
            """not json""",
        )
        for (body in bad) assertEquals(HttpStatusCode.BadRequest, takeDown(body = body).status, body)
        assertTrue(shares.takedowns(SHARE).isEmpty())
        assertTrue(shares.open(SHARE, NOW) != null)
    }

    // ---- through the whole module (in-memory relay) -------------------------------------------

    @Test
    fun endToEnd_theRecipientGets410_andTheOwnerCanStillRevoke_butNotReupload() = testApplication {
        val shares = ShareStore()
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080, operatorToken = TOKEN),
                shareStore = shares,
            )
        }
        client.post("/share") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", "alice")
            setBody("""{"shareId":"$SHARE","sealedMeta":"AAEC","chunkCount":1,"chunkHashes":[]}""")
        }
        client.put("/share/$SHARE/chunk/0") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            setBody(byteArrayOf(1, 2, 3))
        }
        assertEquals(HttpStatusCode.OK, client.get("/share/$SHARE/chunk/0").status)

        // The test engine's peer is loopback, so the real `rawPeerIsLoopback` lets this through.
        val response = takeDown(body = """{"shareId":"$SHARE","reason":"ip_infringement"}""")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("taken_down", response.json().getValue("outcome").jsonPrimitive.content)

        // Neutral to the recipient: the same 410 a revoke or an expiry gives.
        val gone = client.get("/share/$SHARE")
        assertEquals(HttpStatusCode.Gone, gone.status)
        assertEquals("Share is no longer available", gone.bodyAsText())
        assertEquals(HttpStatusCode.Gone, client.get("/share/$SHARE/chunk/0").status)

        // The owner cannot put the content back under the dead id …
        val reupload = client.put("/share/$SHARE/chunk/0") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            setBody(byteArrayOf(1, 2, 3))
        }
        assertEquals(HttpStatusCode.Gone, reupload.status)
        // … and the owner's device revoking it is an ordinary 204, so it has nothing to retry.
        assertEquals(HttpStatusCode.NoContent, client.delete("/share/$SHARE") { header("X-Dev-Owner", "alice") }.status)
        assertEquals(1, shares.takedowns(SHARE).size)
    }

    private companion object {
        const val TOKEN = "operator-secret"
        const val SHARE = "AAAAAAAAAAAAAAAAAAAAAA"
        const val OTHER_SHARE = "BBBBBBBBBBBBBBBBBBBBBB"
        const val CONTENT_REF = "blob-0000"
        const val NOW = 50_000L
    }
}
