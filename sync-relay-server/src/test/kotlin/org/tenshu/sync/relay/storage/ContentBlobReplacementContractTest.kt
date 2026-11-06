package org.tenshu.sync.relay.storage

import org.tenshu.sync.relay.mailbox.ContentBlobRegistry
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Both relay content stores **replace** a ref's bytes on a re-PUT — the contract a relation slice's
 * address depends on (`sync-device-catch-up.md` §11.4.4). A slice is a latest-state pointer: it is
 * re-sealed in place, withdrawn with a small marker when it grows past the budget, and sealed again
 * when it shrinks. Before this test the in-memory store kept the first upload, so on a relay without
 * `DATABASE_URL` a withdrawal never landed and a withdrawn slice never came back. Run over the real
 * implementations, through both PUT paths the content route uses (`put` when quota is off,
 * `putWithinQuota` when it is on). The bytes are made up; the relay sees only ciphertext.
 */
class ContentBlobReplacementContractTest {

    private val cleanup = mutableListOf<() -> Unit>()

    @AfterTest
    fun teardown() = cleanup.forEach { it() }

    private fun inMemory(): ContentBlobRegistry = ContentBlobStore()

    private fun sqlite(): ContentBlobRegistry {
        val dbFile = File.createTempFile("relay-replace-test", ".db")
        val blobDir = File.createTempFile("relay-replace-blobs", "").also { it.delete(); it.mkdirs() }
        val db = RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}")
        cleanup += {
            db.close()
            listOf("", "-wal", "-shm").forEach { File("${dbFile.absolutePath}$it").delete() }
            blobDir.deleteRecursively()
        }
        return SqliteContentBlobStore(db, FileBlobStore(blobDir))
    }

    private val slice = ByteArray(300) { 1 }
    private val withdrawn = ByteArray(45) { 2 }
    private val resealed = ByteArray(280) { 3 }

    /** Under budget → over (withdrawn) → under again → over again, each time asserting what is served. */
    private fun replacesThroughTheWholeCycle(store: ContentBlobRegistry, write: (ByteArray, Long) -> Unit) {
        val sequence = listOf(slice, withdrawn, resealed, withdrawn)
        sequence.forEachIndexed { step, bytes ->
            write(bytes, step * 1_000L)
            assertContentEquals(bytes, store.get("alice", REF, nowMs = step * 1_000L + 1), "step $step is what is served")
            assertEquals(bytes.size.toLong(), store.bytesUsed("alice"), "step $step: the stored size is the latest")
            assertEquals(1, store.blobCount("alice"))
        }
    }

    @Test
    fun theInMemoryStoreReplacesOnPut() {
        val store = inMemory()
        replacesThroughTheWholeCycle(store) { bytes, now -> store.put("alice", REF, bytes, nowMs = now) }
    }

    @Test
    fun theInMemoryStoreReplacesOnPutWithinQuota() {
        val store = inMemory()
        replacesThroughTheWholeCycle(store) { bytes, now ->
            assertEquals(ContentBlobRegistry.PutResult.STORED, store.putWithinQuota("alice", REF, bytes, quotaBytes = 1_000, nowMs = now))
        }
    }

    @Test
    fun theSqliteStoreReplacesOnPut() {
        val store = sqlite()
        replacesThroughTheWholeCycle(store) { bytes, now -> store.put("alice", REF, bytes, nowMs = now) }
    }

    @Test
    fun theSqliteStoreReplacesOnPutWithinQuota() {
        val store = sqlite()
        replacesThroughTheWholeCycle(store) { bytes, now ->
            assertEquals(ContentBlobRegistry.PutResult.STORED, store.putWithinQuota("alice", REF, bytes, quotaBytes = 1_000, nowMs = now))
        }
    }

    private companion object {
        const val REF = "blob-0000000000000000000000000000000000000000000000000000000000000001"
    }
}
