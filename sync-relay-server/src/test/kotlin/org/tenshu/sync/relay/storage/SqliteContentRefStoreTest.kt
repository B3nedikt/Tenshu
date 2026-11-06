package org.tenshu.sync.relay.storage

import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The `contentId -> refs` hop (`content-plane-refcount.md` CR-4b). Each test pins a property the
 * enumeration argument depends on, not the CRUD.
 */
class SqliteContentRefStoreTest {

    private lateinit var dbFile: File
    private lateinit var url: String

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("content-ref-test", ".db")
        url = "jdbc:sqlite:${dbFile.absolutePath}"
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    private fun <T> withStore(body: (SqliteContentRefStore) -> T): T =
        RelayDatabase(url).use { body(SqliteContentRefStore(it)) }

    @Test
    fun theKeepSetIsTheJoinOfContentIdsToRefs() = withStore { s ->
        // ⭐ The query enumeration is built on: given the contentIds an owner still holds an edge
        // to, which refs must survive. Unanswerable from the edge table alone.
        s.declareRefs("owner", "content-a", listOf("blob-a1", "blob-a2"))
        s.declareRefs("owner", "content-b", listOf("blob-b1"))
        s.declareRefs("owner", "content-dead", listOf("blob-d1"))

        assertEquals(
            setOf("blob-a1", "blob-a2", "blob-b1"),
            s.referencedRefs("owner", listOf("content-a", "content-b")),
            "content-dead's refs must NOT be in the keep set — that is what makes them reclaimable",
        )
    }

    @Test
    fun aReDeclarationReplacesTheWholeSet_ratherThanUnioning() = withStore { s ->
        // ⚠⚠ A union would be the quiet bug: a re-emit that dropped a chunk (a shorter file) would
        // leave the old ref in the keep set for ever, so the very blob this table exists to reclaim
        // becomes permanently protected.
        s.declareRefs("owner", "content-a", listOf("blob-1", "blob-2", "blob-3"))
        s.declareRefs("owner", "content-a", listOf("blob-1", "blob-9"))

        assertEquals(setOf("blob-1", "blob-9"), s.refsOf("owner", "content-a"))
    }

    @Test
    fun oneOwnersDeclarationIsInvisibleToAnother() = withStore { s ->
        // Same shape as the edge table's scoping, and load-bearing for the same reason: a keep set
        // that leaked across owners would protect — or worse, expose — another tenant's blobs.
        s.declareRefs("alice", "content-a", listOf("blob-1"))
        s.declareRefs("mallory", "content-a", listOf("blob-9"))

        assertEquals(setOf("blob-1"), s.refsOf("alice", "content-a"))
        assertEquals(setOf("blob-9"), s.refsOf("mallory", "content-a"))
    }

    @Test
    fun redeclaringTheSameSetIsANoOp_soAClientNeedNotTrackWhatItSaid() = withStore { s ->
        s.declareRefs("owner", "content-a", listOf("blob-1", "blob-2"))
        s.declareRefs("owner", "content-a", listOf("blob-2", "blob-1"))

        assertEquals(setOf("blob-1", "blob-2"), s.refsOf("owner", "content-a"))
    }

    @Test
    fun forgettingContentDropsItsRefs_andNoOneElses() = withStore { s ->
        s.declareRefs("owner", "content-a", listOf("blob-1", "blob-2"))
        s.declareRefs("owner", "content-b", listOf("blob-3"))

        assertEquals(2, s.forgetContent("owner", "content-a"))
        assertTrue(s.refsOf("owner", "content-a").isEmpty())
        assertEquals(setOf("blob-3"), s.refsOf("owner", "content-b"))
        assertEquals(0, s.forgetContent("owner", "content-a"), "idempotent: a retry drops zero")
    }

    @Test
    fun aBigSetSurvivesTheParameterCeiling() = withStore { s ->
        // ⚠ `referencedRefs` chunks its `IN (...)` because SQLite's default parameter limit is 999
        // and an owner's live content list is unbounded. 1,200 crosses it.
        val ids = (1..1_200).map { "content-$it" }
        ids.forEach { s.declareRefs("owner", it, listOf("blob-$it")) }

        assertEquals(1_200, s.referencedRefs("owner", ids).size)
    }

    @Test
    fun aDeclarationThatFailsPartWayLeavesThePreviousSetIntact() {
        // ⚠⚠ §7b.3 #6, and the reason `declareRefs` is a transaction rather than delete-then-insert.
        // Between the DELETE and the INSERT the content has NO refs; a crash there would leave a
        // content object described as made of nothing, and every chunk of it reads as an orphan.
        // The rollback is what makes a failed declaration a no-op instead of a data-loss primitive.
        RelayDatabase(url).use { db -> SqliteContentRefStore(db).declareRefs("owner", "content-a", listOf("blob-1", "blob-2")) }

        val real = DriverManager.getConnection(url)
        // Fails the INSERT only, so the DELETE has already run inside the open transaction.
        val failing = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
            InvocationHandler { _, method, args ->
                val sql = args?.firstOrNull() as? String
                if (method.name == "prepareStatement" && sql?.startsWith("INSERT OR IGNORE INTO content_ref") == true) {
                    throw java.sql.SQLException("injected failure mid-declaration")
                }
                try {
                    method.invoke(real, *(args ?: emptyArray()))
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.cause ?: e
                }
            },
        ) as Connection

        RelayDatabase(url, connection = failing).use { db ->
            val store = SqliteContentRefStore(db)
            try {
                store.declareRefs("owner", "content-a", listOf("blob-9"))
                fail("the injected failure must propagate, not be swallowed")
            } catch (expected: java.sql.SQLException) {
                // The rollback is the assertion below.
            }
        }
        real.close()

        withStore { s ->
            assertEquals(
                setOf("blob-1", "blob-2"),
                s.refsOf("owner", "content-a"),
                "a failed declaration must leave the PREVIOUS set standing, never a partial or empty one",
            )
        }
    }
}
