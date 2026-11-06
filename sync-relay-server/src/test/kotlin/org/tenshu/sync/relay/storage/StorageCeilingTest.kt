package org.tenshu.sync.relay.storage

import org.tenshu.sync.relay.mailbox.ContentBlobRegistry
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import org.tenshu.sync.relay.mailbox.ShareRegistry
import org.tenshu.sync.relay.mailbox.ShareStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [StorageCeiling] — the relay-wide cost brake — and the two store totals it measures.
 */
class StorageCeilingTest {

    private val cleanup = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() = cleanup.forEach { it() }

    private class Clock(var nowMs: Long = 1_000L)

    @Test
    fun admitsUpToTheCeiling_andRefusesPastIt() {
        val ceiling = StorageCeiling(ceilingBytes = 100, measure = { 60L })
        assertTrue(ceiling.admits(40), "exactly at the ceiling still fits")
        assertFalse(ceiling.admits(41))
    }

    @Test
    fun acceptedWrites_countBeforeTheNextMeasurement() {
        val clock = Clock()
        val ceiling = StorageCeiling(ceilingBytes = 100, measure = { 0L }, refreshMs = 60_000, nowMs = { clock.nowMs })
        assertEquals(0, ceiling.estimatedBytes())
        ceiling.recordStored(90)
        assertEquals(90, ceiling.estimatedBytes())
        assertFalse(ceiling.admits(20), "a write recorded since the last measurement must count")
    }

    @Test
    fun measuresAtMostOncePerRefresh_andAMeasurementReplacesTheRecordedWrites() {
        val clock = Clock()
        var stored = 90L
        var measurements = 0
        val ceiling = StorageCeiling(
            ceilingBytes = 100,
            measure = { measurements++; stored },
            refreshMs = 60_000,
            nowMs = { clock.nowMs },
        )
        assertFalse(ceiling.admits(20))
        assertFalse(ceiling.admits(20))
        assertEquals(1, measurements, "the full sum runs once per refresh window, never per request")

        // The owner frees bytes; the brake lets go only once the next measurement sees it.
        stored = 10L
        clock.nowMs += 59_999
        assertFalse(ceiling.admits(20))
        clock.nowMs += 1
        assertTrue(ceiling.admits(20))
        assertEquals(2, measurements)
    }

    @Test
    fun rejectsANonPositiveCeiling() {
        assertFailsWith<IllegalArgumentException> { StorageCeiling(ceilingBytes = 0, measure = { 0L }) }
    }

    @Test
    fun contentTotal_sumsEveryOwner_inBothBackends() {
        for (store in listOf(ContentBlobStore(), sqliteContent())) {
            store.put("alice", ref(1), ByteArray(100))
            store.put("alice", ref(2), ByteArray(50))
            store.put("bob", ref(3), ByteArray(7))
            assertEquals(157, store.totalBytes(), store::class.simpleName)
            store.dropRefs("alice", listOf(ref(1)))
            assertEquals(57, store.totalBytes(), "freed bytes leave the total (${store::class.simpleName})")
        }
    }

    @Test
    fun shareTotal_sumsDeclaredChunks_inBothBackends() {
        for (store in listOf(ShareStore(), sqliteShares())) {
            store.create(share("share-a", "alice", chunkCount = 3))
            store.create(share("share-b", "bob", chunkCount = 2))
            assertEquals(5, store.declaredChunkTotal(), store::class.simpleName)
        }
    }

    private fun ref(n: Int) = "blob-" + n.toString().padStart(64, '0')

    private fun share(id: String, owner: String, chunkCount: Int) = ShareStore.ShareRecord(
        shareId = id,
        ownerId = owner,
        sealedMeta = byteArrayOf(1),
        chunkCount = chunkCount,
        chunkHashes = emptyList(),
        expiresAtMillis = null,
        maxOpens = null,
        createdAtMs = 0L,
    )

    private fun tempDb(): Pair<RelayDatabase, File> {
        val dbFile = File.createTempFile("relay-ceiling-test", ".db")
        val blobDir = File.createTempFile("relay-ceiling-blobs", "").also { it.delete(); it.mkdirs() }
        val db = RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}")
        cleanup += {
            db.close()
            listOf("", "-wal", "-shm").forEach { File("${dbFile.absolutePath}$it").delete() }
            blobDir.deleteRecursively()
        }
        return db to blobDir
    }

    private fun sqliteContent(): ContentBlobRegistry =
        tempDb().let { (db, dir) -> SqliteContentBlobStore(db, FileBlobStore(dir)) }

    private fun sqliteShares(): ShareRegistry =
        tempDb().let { (db, dir) -> SqliteShareStore(db, FileBlobStore(dir)) }
}
