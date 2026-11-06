package org.tenshu.sync.relay.routing

import io.ktor.client.HttpClient
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
import io.ktor.server.testing.testApplication
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The relay-wide storage ceiling on the two routes that add stored bytes: a content `PUT` and a
 * share create. Past the ceiling, new bytes get `507` + `storage_ceiling`; bytes already held, and
 * everything that only reads, keep working.
 */
class StorageCeilingRoutingTest {

    private fun config(ceilingBytes: Long) =
        RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080, storageCeilingBytes = ceilingBytes)

    private fun ref(n: Int) = "blob-" + n.toString().padStart(64, '0')

    private suspend fun HttpClient.putContent(owner: String, ref: String, size: Int = 48): HttpResponse =
        put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", owner)
            setBody(ByteArray(size) { it.toByte() })
        }

    private suspend fun HttpClient.createShare(owner: String, shareId: String, chunkCount: Int): HttpResponse =
        post("/share") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", owner)
            setBody(
                """{"shareId":"$shareId","sealedMeta":"c2VhbGVkLW1ldGE","chunkCount":$chunkCount,""" +
                    """"chunkHashes":[],"expiresAtMillis":null,"maxOpens":null}""",
            )
        }

    @Test
    fun contentPut_pastTheCeiling_isRefusedForNewBytesOnly() = testApplication {
        application { testModule(config = config(ceilingBytes = 100), knownDevices = KnownDevicesStore()) }

        assertEquals(HttpStatusCode.NoContent, client.putContent("alice", ref(1)).status)
        // A different owner fills the rest: the ceiling is relay-wide, not per owner.
        assertEquals(HttpStatusCode.NoContent, client.putContent("bob", ref(2)).status)

        val refused = client.putContent("alice", ref(3))
        assertEquals(HttpStatusCode.InsufficientStorage, refused.status)
        assertTrue(refused.bodyAsText().contains(STORAGE_CEILING_ERROR), "the 507 names the ceiling, not the owner's quota")

        // A ref the owner already holds is a retry or an in-place re-seal, never new storage.
        assertEquals(HttpStatusCode.NoContent, client.putContent("alice", ref(1)).status)
        // Reads are never braked.
        val read = client.get("/sync/content/${ref(1)}") { header("X-Dev-Owner", "alice") }
        assertEquals(HttpStatusCode.OK, read.status)
    }

    @Test
    fun contentPut_pastTheCeiling_refusesGrowingAHeldRef() = testApplication {
        application { testModule(config = config(ceilingBytes = 100), knownDevices = KnownDevicesStore()) }

        assertEquals(HttpStatusCode.NoContent, client.putContent("alice", ref(1), size = 48).status)
        assertEquals(HttpStatusCode.NoContent, client.putContent("alice", ref(2), size = 48).status)

        // ⚠⚠ Overwriting a small held ref with a bigger body is growth: exempting it would let many
        // tiny refs be swapped for full-size ones past the ceiling.
        val grown = client.putContent("alice", ref(1), size = 4096)
        assertEquals(HttpStatusCode.InsufficientStorage, grown.status)
        assertTrue(grown.bodyAsText().contains(STORAGE_CEILING_ERROR))
        // A same-size re-seal still lands.
        assertEquals(HttpStatusCode.NoContent, client.putContent("alice", ref(1), size = 48).status)
    }

    @Test
    fun shareCreate_isChargedAtTheChunkCap_andItsChunksAreNotRefusedAfterwards() = testApplication {
        application {
            testModule(config = config(ceilingBytes = MAX_SHARE_CHUNK_BYTES + 10L), knownDevices = KnownDevicesStore())
        }

        assertEquals(HttpStatusCode.Created, client.createShare("alice", "share-one", chunkCount = 1).status)

        val refused = client.createShare("alice", "share-two", chunkCount = 1)
        assertEquals(HttpStatusCode.InsufficientStorage, refused.status)
        assertTrue(refused.bodyAsText().contains(STORAGE_CEILING_ERROR))

        // A same-owner retry of an admitted create is not new storage.
        assertEquals(HttpStatusCode.Created, client.createShare("alice", "share-one", chunkCount = 1).status)
        // The admitted share's chunk was paid for at create, so it lands although the relay is full.
        val chunk = client.put("/share/share-one/chunk/0") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            setBody(ByteArray(64))
        }
        assertEquals(HttpStatusCode.NoContent, chunk.status)
    }

    @Test
    fun noCeilingConfigured_storesWithoutLimit() = testApplication {
        application { testModule(knownDevices = KnownDevicesStore()) }
        repeat(5) { i -> assertEquals(HttpStatusCode.NoContent, client.putContent("alice", ref(i), size = 4096).status) }
    }
}
