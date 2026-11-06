package org.tenshu.sync.relay

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.tenshu.core.common.security.JwtClaims
import org.tenshu.core.common.security.encodeJwt
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.sync.relay.auth.KnownDevicesStore
import io.ktor.server.testing.testApplication
import org.tenshu.sync.relay.mailbox.ContentEdgeStore
import org.tenshu.sync.relay.mailbox.ContentRefStore
import org.tenshu.sync.relay.storage.RelayDatabase
import org.tenshu.sync.relay.storage.SqliteContentEdgeStore
import org.tenshu.sync.relay.storage.SqliteContentRefStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ⚠⚠ **A durable deployment may not run an in-memory content store.**
 *
 * `main()` built `SqliteContentEdgeStore` and `SqliteContentRefStore` and then did not pass them to
 * `module(...)`, whose parameters carry in-memory defaults so tests and dev mode need no wiring. The
 * defaults won **silently**: every declaration lived in RAM until the next restart, and nothing was
 * ever written to `content_edge` / `content_ref` on production.
 *
 * It survived because the only thing that exercises those routes is `TestRelayModule`, which passes
 * the stores **explicitly** — so the tests were green precisely where production was broken. The
 * observable symptom was an impossible one: `POST /sync/content/declare` answering `200 OK` while
 * both tables stayed empty, with no undeclare and no tombstone to explain it.
 *
 * These pin the guard, not the wiring, because a test cannot call `main()`. What it can do is assert
 * the contract `module` now enforces on whoever calls it.
 */
class DurableContentStoreWiringTest {

    private lateinit var dbFile: File
    private var db: RelayDatabase? = null

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-wiring", ".db")
    }

    @AfterTest
    fun teardown() {
        db?.close()
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    private fun configWithDatabase() = RelayConfig(
        devMode = false,
        bindHost = "127.0.0.1",
        port = 8080,
        databaseUrl = "jdbc:sqlite:${dbFile.absolutePath}",
    )

    /** The exact production mistake: a database configured, the in-memory default left in place. */
    @Test
    fun aDatabaseBackedRelayRefusesAnInMemoryEdgeStore() {
        val error = assertFailsWith<IllegalArgumentException> {
            testApplication {
                application {
                    module(
                        config = configWithDatabase(),
                        contentEdgeStore = ContentEdgeStore(),
                        // ⚠ ONE RelayDatabase for the durable store. Opening a second on the same
                        // file would leave `db` naming only the last, so `tearDown` would close one
                        // connection and leak the other into every following test in this class.
                        contentRefStore = SqliteContentRefStore(
                            RelayDatabase(configWithDatabase().databaseUrl!!).also { db = it },
                        ),
                    )
                }
                client.get("/health")
            }
        }
        assertTrue(
            error.message!!.contains("lost on every restart"),
            "the message must say what the operator loses: ${error.message}",
        )
        assertTrue(error.message!!.contains("ContentEdgeStore"), error.message!!)
    }

    @Test
    fun aDatabaseBackedRelayRefusesAnInMemoryRefStore() {
        val database = RelayDatabase(configWithDatabase().databaseUrl!!).also { db = it }
        val error = assertFailsWith<IllegalArgumentException> {
            testApplication {
                application {
                    module(
                        config = configWithDatabase(),
                        contentEdgeStore = SqliteContentEdgeStore(database),
                        contentRefStore = ContentRefStore(),
                    )
                }
                client.get("/health")
            }
        }
        assertTrue(error.message!!.contains("ref sets would be lost"), error.message!!)
    }

    /**
     * ⭐⭐ **The test that would have caught the original bug**, and the reason it did not exist: every
     * other route test wires `TestRelayModule`'s in-memory stores, so the declare path was only ever
     * exercised against a map. Here the SQLite stores go in and the assertion is on the **table**.
     *
     * Production's symptom was exactly this assertion failing while the response stayed `200 OK`.
     */
    @Test
    fun aDeclareAgainstSqliteStoresLandsInTheTables() = testApplication {
        val database = RelayDatabase(configWithDatabase().databaseUrl!!).also { db = it }
        val edges = SqliteContentEdgeStore(database)
        val refs = SqliteContentRefStore(database)
        val key = "wiring-device-key".encodeToByteArray()
        application {
            module(
                config = configWithDatabase(),
                knownDevices = KnownDevicesStore().apply { register("alice", "d1", key) },
                contentEdgeStore = edges,
                contentRefStore = refs,
                contentFreeing = org.tenshu.sync.relay.storage.SqliteContentFreedStore(database, refs),
                // ⚠ Over the SAME database as the edges, as `main` wires it: the push's block and its
                // registers are one transaction only because they share the connection.
                syncMailbox = org.tenshu.sync.relay.storage.SqliteSyncMailbox(
                    database,
                    org.tenshu.sync.relay.storage.RelayBinaryCodec(),
                ),
                // ⚠ `module` defaults to the real backend; the token below is stub-signed.
                ed25519 = org.tenshu.sync.relay.auth.StubEd25519Backend(),
            )
        }
        val nowSec = System.currentTimeMillis() / 1000
        val token = encodeJwt(JwtClaims("alice", "d1", nowSec - 5, nowSec + 60)) { msg ->
            stubEd25519Signature(key, msg)
        }
        val blob = "blob-" + "a".repeat(64)

        // CR-2 B3: the edge travels on the push of the row that names it; the describe carries refs.
        val ctx = wireContext()
        val push = client.post("/sync/push") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer $token")
            setBody(
                wireSerializer(ctx).serialize(
                    edgedPush(
                        ctx,
                        org.tenshu.sync.SyncBlock.create(
                            context = ctx,
                            blockId = "b1",
                            ownerId = "alice",
                            hlc = org.tenshu.core.time.HybridLogicalClock.create(ctx, 1_000L, 0L, "d1"),
                            encryptedPayload = byteArrayOf(1),
                            targetEntityIdHash = "h1",
                        ),
                        edges = listOf(org.tenshu.sync.PushedEdge.create(ctx, "tenshu/file/data", "c1", regenerable = false)),
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, push.status, push.bodyAsText())
        val response = client.post("/sync/content/declare") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""{"contents":[{"contentId":"c1","refs":["$blob"]}]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        // ⚠⚠ The `200` above is what production also returned. Only these two lines tell the
        // difference between "stored" and "put in a map that the next restart throws away".
        assertEquals(setOf("c1"), edges.referencedContentIds("alice"), "the edge must be IN THE TABLE")
        assertEquals(setOf(blob), refs.refsOf("alice", "c1"), "and so must the ref set")
    }

    /**
     * ⚠ The guard is scoped to a configured database, so dev mode and every existing test that
     * passes in-memory stores deliberately keep working. Without this, the fix would have broken
     * the whole suite rather than the one thing it targets.
     */
    @Test
    fun anInMemoryRelayWithNoDatabaseIsStillAllowed() = testApplication {
        application {
            module(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                contentEdgeStore = ContentEdgeStore(),
                contentRefStore = ContentRefStore(),
            )
        }
        val health = client.get("/health")
        assertEquals(HttpStatusCode.OK, health.status)
    }

    private fun wireContext(): org.tenshu.core.core.TContext {
        val ctx = object : org.tenshu.core.core.TContext {
            override var components: Map<String, org.tenshu.core.core.TComponent> = emptyMap()
        }
        val registry = org.tenshu.core.common.proxy.createDefaultSchemaRegistry(
            tContextProvider = { ctx },
            extraConfigs = listOf(
                org.tenshu.core.common.module.coreCommonModule,
                org.tenshu.sync.common.syncServiceCommonModule,
            ),
        )
        ctx.components = mapOf(
            org.tenshu.core.core.SchemaProvider.COMPONENT_ID to registry,
            org.tenshu.core.core.DataFactory.COMPONENT_ID to registry,
        )
        return ctx
    }

    private fun wireSerializer(ctx: org.tenshu.core.core.TContext) =
        org.tenshu.core.common.parser.BinarySerializer(
            ctx.components[org.tenshu.core.core.SchemaProvider.COMPONENT_ID] as org.tenshu.core.core.SchemaProvider
        )
}
