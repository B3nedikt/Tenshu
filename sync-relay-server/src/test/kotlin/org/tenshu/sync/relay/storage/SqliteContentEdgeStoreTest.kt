package org.tenshu.sync.relay.storage

import org.tenshu.core.common.util.HlcStamp
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry.EdgeWrite
import org.tenshu.sync.relay.mailbox.ContentEdgeStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CR-2 B3's edge registers (`content-plane-cr2-b3-wire-spec.md` §2), pinned against BOTH stores: the
 * SQLite one production runs and the in-memory twin dev mode and the route tests run. Two stores that
 * order differently would be two relays that disagree about what is referenced.
 *
 * Each test pins a rule, not the CRUD: rule 1 in both arrival orders, the watermark, none as a
 * register, rule 2 at T with a recreate above T surviving, and rule 3's NULL filter.
 */
abstract class ContentEdgeRegistryContract {

    protected abstract fun <T> withStore(body: (ContentEdgeRegistry) -> T): T

    private fun hlc(physical: Long, counter: Long = 0, device: String = "dev-a") = HlcStamp(physical, counter, device)

    private fun ContentEdgeRegistry.write(holder: String, stamp: HlcStamp, vararg edges: Pair<String, String?>) =
        writeEdges("owner", holder, stamp, edges.map { (p, c) -> EdgeWrite(p, c) })

    @Test
    fun rule1_theNewerWriteWins_inEitherArrivalOrder() {
        // ⚠⚠ The whole point of stamping: two devices' pushes arrive in any order, and the register
        // must land where the LOG puts it, not where the network did.
        withStore { s ->
            s.write("h1", hlc(1_000), "file/data" to "old")
            s.write("h1", hlc(2_000), "file/data" to "new")
            assertEquals(ContentEdgeRegistry.Register("new", hlc(2_000)), s.register("owner", "h1", "file/data"))
        }
        withStore { s ->
            s.write("h1", hlc(2_000), "file/data" to "new")
            s.write("h1", hlc(1_000), "file/data" to "old")
            assertEquals(
                ContentEdgeRegistry.Register("new", hlc(2_000)),
                s.register("owner", "h1", "file/data"),
                "a late, OLDER push must not overwrite a newer register",
            )
            assertEquals(setOf("new"), s.referencedContentIds("owner"))
        }
    }

    @Test
    fun rule1_comparesHlcs_neverTheirStrings() = withStore { s ->
        // "10000:…" sorts before "9000:…" as a string. A store that compared encodings would keep the
        // older write here.
        s.write("h1", hlc(9_000), "file/data" to "older")
        s.write("h1", hlc(10_000), "file/data" to "newer")
        assertEquals("newer", s.register("owner", "h1", "file/data")?.contentId)
        // The counter and then the device id break a physical-time tie (compareHlc's total order).
        s.write("h1", hlc(10_000, counter = 1), "file/data" to "by-counter")
        assertEquals("by-counter", s.register("owner", "h1", "file/data")?.contentId)
        s.write("h1", hlc(10_000, counter = 1, device = "dev-b"), "file/data" to "by-device")
        assertEquals("by-device", s.register("owner", "h1", "file/data")?.contentId)
    }

    @Test
    fun rule1_aReplayAtTheSameStampIsANoOp() = withStore { s ->
        assertEquals(1, s.write("h1", hlc(1_000), "file/data" to "c"))
        assertEquals(0, s.write("h1", hlc(1_000), "file/data" to "c"), "strictly greater, so a retry changes nothing")
    }

    @Test
    fun none_isARegister_neverADelete_soAStaleWriteStillLoses() = withStore { s ->
        s.write("h1", hlc(1_000), "file/data" to "c1")
        // The row's latest version cleared the slot.
        s.write("h1", hlc(2_000), "file/data" to null)

        assertEquals(ContentEdgeRegistry.Register(null, hlc(2_000)), s.register("owner", "h1", "file/data"))
        assertTrue(s.referencedContentIds("owner").isEmpty(), "rule 3: none is not an edge")
        assertTrue(s.holdersOf("owner", "c1").isEmpty())

        // ⚠⚠ The reason none is kept: a stale write delivered late must have a stamp to lose against.
        s.write("h1", hlc(1_500), "file/data" to "c1")
        assertNull(s.register("owner", "h1", "file/data")?.contentId, "the stale write must not resurrect the edge")
    }

    @Test
    fun theWatermarkBeatsALateOlderBlock() = withStore { s ->
        s.write("h1", hlc(1_000), "file/data" to "c1")
        s.removeHolders("owner", listOf("h1"), hlc(5_000))

        // A block authored BEFORE the delete, delivered after it (a queued push).
        s.write("h1", hlc(4_000), "file/data" to "c2")
        assertNull(s.register("owner", "h1", "file/data")?.contentId)
        // Even a property the holder never had before the delete: the watermark governs the holder.
        s.write("h1", hlc(4_000), "file/thumbnail" to "t1")
        assertNull(s.register("owner", "h1", "file/thumbnail"), "no register is created below the watermark")
        // At the watermark itself is still dead (≥).
        s.write("h1", hlc(5_000), "file/data" to "c3")
        assertNull(s.register("owner", "h1", "file/data")?.contentId)
        assertTrue(s.referencedContentIds("owner").isEmpty())
    }

    @Test
    fun rule2_removesAtT_andARecreateAboveTSurvives() = withStore { s ->
        // The recreate (ADR 0006) reached the relay BEFORE the delete it outlives.
        s.write("h1", hlc(1_000), "file/data" to "c-old")
        s.write("h1", hlc(9_000), "file/thumbnail" to "t-new")

        assertEquals(1, s.removeHolders("owner", listOf("h1"), hlc(5_000)))

        assertEquals(ContentEdgeRegistry.Register(null, hlc(5_000)), s.register("owner", "h1", "file/data"))
        assertEquals(
            ContentEdgeRegistry.Register("t-new", hlc(9_000)),
            s.register("owner", "h1", "file/thumbnail"),
            "a register stamped above T is a recreate the removal never saw",
        )
        assertEquals(hlc(5_000), s.watermark("owner", "h1"))
        assertEquals(setOf("t-new"), s.referencedContentIds("owner"))
    }

    @Test
    fun rule2_aCascadeRemovesEveryNamedHolder_andARecreateAfterwardsLands() = withStore { s ->
        s.write("folder", hlc(1_000), "file/data" to "cf")
        s.write("child-a", hlc(1_000), "file/data" to "ca")
        s.write("child-b", hlc(1_000), "file/data" to "cb")
        s.write("bystander", hlc(1_000), "file/data" to "cx")

        assertEquals(3, s.removeHolders("owner", listOf("folder", "child-a", "child-b"), hlc(3_000)))
        assertEquals(setOf("cx"), s.referencedContentIds("owner"))

        // A recreate of child-a pushed later at a higher HLC is a new life of the holder.
        s.write("child-a", hlc(4_000), "file/data" to "ca2")
        assertEquals(setOf("cx", "ca2"), s.referencedContentIds("owner"))
    }

    @Test
    fun rule2_isIdempotent_andTheWatermarkOnlyRises() = withStore { s ->
        // Every reaping device sends the same (holder, T); an older removal arriving later is a max.
        s.write("h1", hlc(1_000), "file/data" to "c1")
        assertEquals(1, s.removeHolders("owner", listOf("h1"), hlc(5_000)))
        assertEquals(0, s.removeHolders("owner", listOf("h1"), hlc(5_000)), "a repeated reap clears nothing")
        s.removeHolders("owner", listOf("h1"), hlc(3_000))
        assertEquals(hlc(5_000), s.watermark("owner", "h1"), "an older removal never lowers the watermark")
        assertEquals(ContentEdgeRegistry.Register(null, hlc(5_000)), s.register("owner", "h1", "file/data"))
    }

    @Test
    fun aRemovalOfAHolderWithNoRegistersStillSetsItsWatermark() = withStore { s ->
        // The reap that names a child the relay never saw a push for: its push may still be in flight.
        s.removeHolders("owner", listOf("ghost"), hlc(5_000))
        s.write("ghost", hlc(4_000), "file/data" to "c")
        assertTrue(s.referencedContentIds("owner").isEmpty())
    }

    @Test
    fun rule3_everyEnumerationIgnoresNone() = withStore { s ->
        s.write("h1", hlc(1_000), "file/data" to "live", "file/thumbnail" to null)
        s.write("h2", hlc(1_000), "file/data" to null)
        assertEquals(setOf("live"), s.referencedContentIds("owner"))
        assertEquals(setOf("h1"), s.holdersOf("owner", "live"))
    }

    @Test
    fun ownersAreIsolated() = withStore { s ->
        s.writeEdges("owner-a", "h1", hlc(1_000), listOf(EdgeWrite("file/data", "a")))
        s.writeEdges("owner-b", "h1", hlc(1_000), listOf(EdgeWrite("file/data", "b")))
        s.removeHolders("owner-a", listOf("h1"), hlc(2_000))
        assertTrue(s.referencedContentIds("owner-a").isEmpty())
        assertEquals(setOf("b"), s.referencedContentIds("owner-b"), "the same holder hash under another owner is untouched")
        assertNull(s.watermark("owner-b", "h1"))
    }

    @Test
    fun blankIdentifiersWriteNothing() = withStore { s ->
        s.writeEdges("", "h1", hlc(1_000), listOf(EdgeWrite("file/data", "c")))
        s.writeEdges("owner", "", hlc(1_000), listOf(EdgeWrite("file/data", "c")))
        s.writeEdges("owner", "h1", hlc(1_000), listOf(EdgeWrite("", "c")))
        assertTrue(s.referencedContentIds("owner").isEmpty())
    }
}

class InMemoryContentEdgeStoreTest : ContentEdgeRegistryContract() {
    override fun <T> withStore(body: (ContentEdgeRegistry) -> T): T = body(ContentEdgeStore())
}

class SqliteContentEdgeStoreTest : ContentEdgeRegistryContract() {

    private lateinit var dbFile: File
    private lateinit var url: String

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("content-edge-test", ".db")
        dbFile.delete() // a fresh database per test; the contract's `withStore` may be called twice
        url = "jdbc:sqlite:${dbFile.absolutePath}"
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    override fun <T> withStore(body: (ContentEdgeRegistry) -> T): T {
        // ⚠ The rule-1 test opens two stores in sequence and expects each to start empty.
        teardown()
        return RelayDatabase(url).use { body(SqliteContentEdgeStore(it)) }
    }

    private fun <T> withDurableStore(body: (SqliteContentEdgeStore) -> T): T =
        RelayDatabase(url).use { body(SqliteContentEdgeStore(it)) }

    @Test
    fun registersAndWatermarksSurviveARestart() {
        withDurableStore {
            it.writeEdges("owner", "h1", HlcStamp(1_000, 0, "d"), listOf(EdgeWrite("file/data", "c")))
            it.writeEdges("owner", "h2", HlcStamp(1_000, 0, "d"), listOf(EdgeWrite("file/data", "c2")))
            it.removeHolders("owner", listOf("h2"), HlcStamp(2_000, 0, "d"))
        }
        withDurableStore {
            assertEquals(setOf("c"), it.referencedContentIds("owner"))
            assertEquals(HlcStamp(2_000, 0, "d"), it.watermark("owner", "h2"))
            assertEquals(ContentEdgeRegistry.Register(null, HlcStamp(2_000, 0, "d")), it.register("owner", "h2", "file/data"))
        }
    }

    @Test
    fun atomically_rollsBackEveryWriteInside() {
        withDurableStore { s ->
            runCatching {
                s.atomically {
                    s.writeEdges("owner", "h1", HlcStamp(1_000, 0, "d"), listOf(EdgeWrite("file/data", "c")))
                    s.removeHolders("owner", listOf("h2"), HlcStamp(1_000, 0, "d"))
                    error("the mailbox append failed")
                }
            }
            assertTrue(s.referencedContentIds("owner").isEmpty(), "the register write must roll back with the block")
            assertNull(s.watermark("owner", "h2"))
        }
    }
}
