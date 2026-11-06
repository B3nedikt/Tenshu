package org.tenshu.sync.relay.routing

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.tenshu.sync.relay.storage.ContentReclaimJob
import org.tenshu.sync.relay.storage.FileBlobStore
import org.tenshu.sync.relay.storage.RelayDatabase
import org.tenshu.sync.relay.storage.SqliteContentBlobStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The operator routes can delete ciphertext, so every brake is pinned: absent without a token,
 * refused off loopback, refused with a wrong token, and an enqueue needs the plan's own count.
 */
class OperatorRoutingTest {

    private lateinit var dbFile: File
    private lateinit var blobDir: File

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-ops-test", ".db")
        blobDir = File.createTempFile("relay-ops-blobs", "").also { it.delete(); it.mkdirs() }
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
        blobDir.deleteRecursively()
    }

    private val garbageRef = "blob-" + "6".repeat(64)

    private fun withRoutes(token: String?, loopback: Boolean = true, test: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
        RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}").use { db ->
            val files = FileBlobStore(blobDir)
            SqliteContentBlobStore(db, files).put("alice", garbageRef, byteArrayOf(1, 2), nowMs = 0)
            val job = ContentReclaimJob(db, files)
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        configureOperatorRouting(token, job, isLoopbackPeer = { loopback }, now = { 100L * 24 * 3_600_000 })
                    }
                }
                test()
            }
        }
    }

    @Test
    fun withoutAToken_theRoutesDoNotExist() = withRoutes(token = null) {
        assertEquals(HttpStatusCode.NotFound, client.get("/ops/content-reclaim/plan?owner=alice").status)
    }

    @Test
    fun aWrongToken_isRefused() = withRoutes(token = "secret") {
        val response = client.get("/ops/content-reclaim/plan?owner=alice") { header(OPERATOR_TOKEN_HEADER, "guess") }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun theRightToken_offLoopback_isRefused() = withRoutes(token = "secret", loopback = false) {
        val response = client.get("/ops/content-reclaim/plan?owner=alice") { header(OPERATOR_TOKEN_HEADER, "secret") }
        assertEquals(HttpStatusCode.NotFound, response.status, "the token alone never suffices")
    }

    @Test
    fun onLoopbackWithTheToken_thePlanReportsTheCandidates() = withRoutes(token = "secret") {
        val response = client.get("/ops/content-reclaim/plan?owner=alice") { header(OPERATOR_TOKEN_HEADER, "secret") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"candidateBlobs\":1"), response.bodyAsText())
    }

    @Test
    fun anEnqueueWithoutThePlansDigest_isRejected_andAWrongDigestConflicts() = withRoutes(token = "secret") {
        val missing = client.post("/ops/content-reclaim/enqueue?owner=alice") { header(OPERATOR_TOKEN_HEADER, "secret") }
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        val wrong = client.post("/ops/content-reclaim/enqueue?owner=alice&expectDigest=deadbeef") {
            header(OPERATOR_TOKEN_HEADER, "secret")
        }
        assertEquals(HttpStatusCode.Conflict, wrong.status)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.planDigest(): String {
        val plan = client.get("/ops/content-reclaim/plan?owner=alice") { header(OPERATOR_TOKEN_HEADER, "secret") }.bodyAsText()
        return Regex("\"candidateDigest\":\"([0-9a-f]+)\"").find(plan)!!.groupValues[1]
    }

    @Test
    fun planEnqueueDrain_endToEnd() = withRoutes(token = "secret") {
        client.post("/ops/content-reclaim/enqueue?owner=alice&expectDigest=${planDigest()}") { header(OPERATOR_TOKEN_HEADER, "secret") }
        val drained = client.post("/ops/content-reclaim/drain?owner=alice") { header(OPERATOR_TOKEN_HEADER, "secret") }
        assertEquals(HttpStatusCode.OK, drained.status)
        assertTrue(drained.bodyAsText().contains("\"deletedBlobs\":1"), drained.bodyAsText())
    }

    @Test
    fun anInvalidIdleWindow_isRejected_neverClamped() = withRoutes(token = "secret") {
        for (bad in listOf("0", "-5", "abc", "9999999999999")) {
            val plan = client.get("/ops/content-reclaim/plan?owner=alice&minIdleHours=$bad") { header(OPERATOR_TOKEN_HEADER, "secret") }
            assertEquals(HttpStatusCode.BadRequest, plan.status, "minIdleHours=$bad")
            val enqueue = client.post("/ops/content-reclaim/enqueue?owner=alice&expectDigest=x&minIdleHours=$bad") {
                header(OPERATOR_TOKEN_HEADER, "secret")
            }
            assertEquals(HttpStatusCode.BadRequest, enqueue.status, "minIdleHours=$bad")
        }
    }
}
