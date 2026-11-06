package org.tenshu.sync.relay.storage

import org.tenshu.sync.relay.mailbox.ContentBlobRegistry
import org.tenshu.sync.relay.mailbox.ContentBlobRegistry.PutResult
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `putWithinQuota` on a ref the owner already holds, in both backends: a re-PUT at no growth always
 * lands (a retry or an in-place re-seal), and a re-PUT that grows the ref is charged on its growth.
 * Without the second half, many tiny refs overwritten with full-size bodies grow an owner past any
 * quota.
 */
class ContentQuotaGrowthTest {

    private val cleanup = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() = cleanup.forEach { it() }

    private fun backends(): List<ContentBlobRegistry> = listOf(ContentBlobStore(), sqlite())

    private fun sqlite(): ContentBlobRegistry {
        val dbFile = File.createTempFile("relay-growth-test", ".db")
        val blobDir = File.createTempFile("relay-growth-blobs", "").also { it.delete(); it.mkdirs() }
        val db = RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}")
        cleanup += {
            db.close()
            listOf("", "-wal", "-shm").forEach { File("${dbFile.absolutePath}$it").delete() }
            blobDir.deleteRecursively()
        }
        return SqliteContentBlobStore(db, FileBlobStore(blobDir))
    }

    private fun ref(n: Int) = "blob-" + n.toString().padStart(64, '0')

    @Test
    fun growingAHeldRef_isChargedOnItsGrowth() {
        for (store in backends()) {
            val name = store::class.simpleName
            assertEquals(PutResult.STORED, store.putWithinQuota("alice", ref(1), ByteArray(10), quotaBytes = 100), name)
            assertEquals(PutResult.STORED, store.putWithinQuota("alice", ref(2), ByteArray(10), quotaBytes = 100), name)

            // 20 - 10 + 90 = 100: exactly at quota, so it fits.
            assertEquals(PutResult.STORED, store.putWithinQuota("alice", ref(1), ByteArray(90), quotaBytes = 100), name)
            // 100 - 10 + 50 = 140: refused, and the old body stays.
            assertEquals(PutResult.OVER_QUOTA, store.putWithinQuota("alice", ref(2), ByteArray(50), quotaBytes = 100), name)
            assertEquals(10L, store.storedLength("alice", ref(2)), "a refused growth keeps the old body ($name)")
            assertEquals(100L, store.bytesUsed("alice"), name)

            // At quota, a re-PUT that does not grow its ref still lands.
            assertEquals(PutResult.STORED, store.putWithinQuota("alice", ref(1), ByteArray(90), quotaBytes = 100), name)
            assertEquals(PutResult.STORED, store.putWithinQuota("alice", ref(2), ByteArray(5), quotaBytes = 100), name)
        }
    }

    @Test
    fun storedLength_isNullForARefTheOwnerDoesNotHold() {
        for (store in backends()) {
            store.put("alice", ref(1), ByteArray(42))
            assertEquals(42L, store.storedLength("alice", ref(1)))
            assertEquals(null, store.storedLength("bob", ref(1)), "another owner's ref is not theirs")
            assertEquals(null, store.storedLength("alice", ref(2)))
        }
    }
}
