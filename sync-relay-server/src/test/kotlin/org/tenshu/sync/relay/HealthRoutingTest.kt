package org.tenshu.sync.relay

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.tenshu.sync.relay.storage.BlobStore
import org.tenshu.sync.relay.storage.RelayDatabase
import org.tenshu.sync.relay.storage.SqliteContentEdgeStore
import org.tenshu.sync.relay.storage.SqliteContentFreedStore
import org.tenshu.sync.relay.storage.SqliteContentRefStore
import org.tenshu.sync.relay.storage.FileBlobStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GET /health` must catch the state `GET /` cannot see: the blob volume refusing a write. A
 * 100 %-full volume fails every `POST /sync/push` while `GET /` stays a cheerful 200 and reads
 * serve perfectly.
 *
 * The unwritable case is built from a **real** filesystem that rejects writes, not a throwing fake:
 * a fake would prove only that `runCatching` catches, while this exercises `writeBytes` itself —
 * the call that actually threw.
 */
class HealthRoutingTest {

    private fun appTest(
        blobStore: BlobStore?,
        databaseUrl: String? = null,
        block: suspend (client: HttpClient) -> Unit,
    ) = testApplication {
        application {
            testModule(
                config = RelayConfig(
                    devMode = true,
                    bindHost = "127.0.0.1",
                    port = 8080,
                    databaseUrl = databaseUrl,
                ),
                blobStore = blobStore,
                // ⚠ Durable stores on a **working** file, while `databaseUrl` may deliberately name
                // an unwritable one: what these tests probe is the VOLUME, not the stores, and a
                // database-configured relay may not run in-memory content stores (see `module`'s
                // require). Letting the harness open the broken URL instead would fail at
                // construction, before the 503 this test exists to observe.
                contentEdgeStore = databaseUrl?.let { SqliteContentEdgeStore(RelayDatabase(workingDatabaseUrl())) },
                contentRefStore = databaseUrl?.let { SqliteContentRefStore(RelayDatabase(workingDatabaseUrl())) },
                contentFreeing = databaseUrl?.let {
                    RelayDatabase(workingDatabaseUrl()).let { db -> SqliteContentFreedStore(db, SqliteContentRefStore(db)) }
                },
            )
        }
        block(client)
    }

    /** A `jdbc:sqlite:` URL whose parent directory is writable, as production has. */
    private fun workingDatabaseUrl(): String {
        val dir = File.createTempFile("relay-health-db", "").let { it.delete(); it.mkdirs(); it }
        return "jdbc:sqlite:${File(dir, "relay.db").absolutePath}"
    }

    /**
     * A `jdbc:sqlite:` URL whose parent cannot accept a write, by the same construction the blob
     * fixture uses: the directory the database would live in is really a regular **file**.
     */
    private fun brokenDatabaseUrl(): String {
        val notADirectory = File.createTempFile("relay-health-db-broken", "")
        notADirectory.deleteOnExit()
        return "jdbc:sqlite:${File(notADirectory, "relay.db").absolutePath}"
    }

    /**
     * ⚠⚠ Pins the database-volume probe (`hosting-provider-economics.md` D10).
     *
     * Probing only the blob store, on the reasoning that a full volume breaks the SQLite write too,
     * holds only while they share a volume. With `S3BlobStore` the blob probe writes to a bucket
     * that is perfectly healthy while the droplet's disk is full, so such a route would answer a
     * cheerful 200 through the exact outage it is built for. A working blob store here stands in
     * for that bucket.
     */
    @Test
    fun aHealthyBlobStoreDoesNotExcuseAnUnwritableDatabaseVolume() =
        appTest(workingStore(), databaseUrl = brokenDatabaseUrl()) { client ->
            val response = client.get("/health")
            assertEquals(
                HttpStatusCode.ServiceUnavailable,
                response.status,
                "the bucket was fine and the database's volume was not — that is degraded",
            )
            assertTrue(response.bodyAsText().contains("degraded"), response.bodyAsText())
        }

    /** And the added probe must not invent a red: both volumes writable is still 200. */
    @Test
    fun bothVolumesWritableIs200() =
        appTest(workingStore(), databaseUrl = workingDatabaseUrl()) { client ->
            val response = client.get("/health")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("\"ok\""), response.bodyAsText())
        }

    /** An unwritable blob store is still 503 when the database's own volume is fine. */
    @Test
    fun anUnwritableBlobStoreIs503EvenWhenTheDatabaseVolumeIsFine() =
        appTest(brokenStore(), databaseUrl = workingDatabaseUrl()) { client ->
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health").status)
        }

    /** A writable blob root, as production has. */
    private fun workingStore(): FileBlobStore =
        FileBlobStore(File.createTempFile("relay-health", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        })

    /**
     * A blob root that cannot accept a write, by construction: its parent is a regular **file**, so
     * creating anything beneath it fails at the filesystem level.
     *
     * ⚠ Deliberately not `setWritable(false)` — permission bits are ignored when the tests run as
     * root, so that version would quietly pass by writing successfully. This one fails for every
     * user.
     */
    private fun brokenStore(): FileBlobStore {
        val notADirectory = File.createTempFile("relay-health-broken", "")
        notADirectory.deleteOnExit()
        return FileBlobStore(File(notADirectory, "blobs"))
    }

    @Test
    fun aWritableVolumeIs200() = appTest(workingStore()) { client ->
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"ok\""), response.bodyAsText())
    }

    @Test
    fun aVolumeThatRefusesAWriteIs503() = appTest(brokenStore()) { client ->
        val response = client.get("/health")
        assertEquals(
            HttpStatusCode.ServiceUnavailable,
            response.status,
            "a blob volume that cannot be written must read as 503 — this is the outage shape",
        )
        assertTrue(response.bodyAsText().contains("degraded"), response.bodyAsText())
    }

    /** A probe must not make a full disk fuller, once per poll. */
    @Test
    fun aProbeLeavesNothingBehind() {
        val root = File.createTempFile("relay-health-clean", "").let { file ->
            file.delete(); file.mkdirs(); file
        }
        val store = FileBlobStore(root)
        repeat(5) { store.probe() }
        assertEquals(
            emptyList(),
            root.listFiles()?.map { it.name }?.sorted() ?: emptyList(),
            "the probe leaked a file",
        )
    }

    /**
     * No `DATABASE_URL` means no durable volume to probe. Answering `durable:false` beats both a
     * false green and a false red — and in production it is itself the alarm (every restart wipes
     * the registrations).
     */
    @Test
    fun anInMemoryRelaySaysSoRatherThanGuessing() = appTest(null) { client ->
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"durable\":false"), response.bodyAsText())
    }

    /** `/health` is unauthenticated, so a prober must not learn the shape of the failure. */
    @Test
    fun theFailureBodyLeaksNothingAboutTheCause() = appTest(brokenStore()) { client ->
        val body = client.get("/health").bodyAsText()
        assertFalse(body.contains("relay-health-broken"), body)
        assertFalse(body.contains("Exception"), body)
    }

    /**
     * ⚠ Guards the split: `GET /` must stay a store-free static string, or a full disk starts
     * failing the root route too and the two signals collapse back into one.
     */
    @Test
    fun rootStaysUpWhenTheVolumeIsBroken() = appTest(brokenStore()) { client ->
        assertEquals(HttpStatusCode.OK, client.get("/").status)
    }
}
