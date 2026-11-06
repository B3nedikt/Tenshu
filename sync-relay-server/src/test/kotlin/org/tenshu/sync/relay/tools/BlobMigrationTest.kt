package org.tenshu.sync.relay.tools

import org.tenshu.sync.relay.mailbox.ShareStore
import org.tenshu.sync.relay.storage.BlobStore
import org.tenshu.sync.relay.storage.FileBlobStore
import org.tenshu.sync.relay.storage.RelayDatabase
import org.tenshu.sync.relay.storage.SqliteContentBlobStore
import org.tenshu.sync.relay.storage.SqliteShareStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The volume→bucket copy (`hosting-provider-economics.md` D10 S4). The destination here is a second
 * [FileBlobStore] rather than a bucket: what these pin is the *migration's* semantics —
 * enumeration, idempotency, verification — not S3 itself, which `S3BlobStoreContractTest` covers.
 */
class BlobMigrationTest {

    private lateinit var dbFile: File
    private lateinit var sourceDir: File
    private lateinit var destDir: File
    private lateinit var db: RelayDatabase

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-migration", ".db")
        sourceDir = File.createTempFile("relay-migration-src", "").also { it.delete(); it.mkdirs() }
        destDir = File.createTempFile("relay-migration-dst", "").also { it.delete(); it.mkdirs() }
        db = RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}")
    }

    @AfterTest
    fun teardown() {
        db.close()
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
        sourceDir.deleteRecursively()
        destDir.deleteRecursively()
    }

    private fun source() = FileBlobStore(sourceDir)
    private fun destination() = FileBlobStore(destDir)

    /** Writes through the real store, so the index row exists exactly as it does in production. */
    private fun putContent(owner: String, ref: String, bytes: ByteArray) {
        SqliteContentBlobStore(db, source()).put(owner, ref, bytes, nowMs = 1_000)
    }

    private fun createShare(shareId: String, chunkCount: Int) {
        SqliteShareStore(db, source()).create(
            ShareStore.ShareRecord(
                shareId = shareId,
                ownerId = "alice",
                sealedMeta = byteArrayOf(1),
                chunkCount = chunkCount,
                chunkHashes = List(chunkCount) { ByteArray(32) },
                expiresAtMillis = null,
                maxOpens = null,
                createdAtMs = 1_000,
            )
        )
    }

    /** A destination that silently appends to what it is given — the aws-chunked framing shape. */
    private class CorruptingStore(private val inner: BlobStore) : BlobStore {
        override fun put(key: String, bytes: ByteArray) = inner.put(key, bytes + byteArrayOf(0, 0))
        override fun get(key: String): ByteArray? = inner.get(key)
        override fun exists(key: String): Boolean = inner.exists(key)
        override fun delete(key: String) = inner.delete(key)
        override fun probe() = inner.probe()
    }

    @Test
    fun enumerate_coversContentBlobsAndShareChunks_fromTheIndexNotTheStore() {
        putContent("alice", "blob-a", byteArrayOf(1))
        putContent("bob", "blob-b", byteArrayOf(2))
        createShare("s1", chunkCount = 2)

        val keys = BlobMigration.enumerateKeys(db).toList()

        assertTrue(keys.containsAll(listOf("alice/blob-a", "bob/blob-b")), keys.toString())
        assertTrue(keys.containsAll(listOf("share/s1/0", "share/s1/1")), keys.toString())
    }

    @Test
    fun enumerate_skipsBytesWithNoIndexRow_becauseTheRelayCannotReachThemEither() {
        source().put("orphan/not-in-index", byteArrayOf(9))
        assertFalse(BlobMigration.enumerateKeys(db).contains("orphan/not-in-index"))
    }

    @Test
    fun run_copiesEveryEnumeratedKey_byteExact() {
        val big = ByteArray(64 * 1024) { (it % 251).toByte() }
        putContent("alice", "blob-a", big)

        val report = BlobMigration(source(), destination()).run(BlobMigration.enumerateKeys(db))

        assertTrue(report.ok, report.toString())
        assertEquals(1, report.copied)
        assertContentEquals(big, destination().get("alice/blob-a"))
    }

    @Test
    fun run_isIdempotent_soAnInterruptedPassIsSimplyRerun() {
        putContent("alice", "blob-a", byteArrayOf(1, 2, 3))
        val first = BlobMigration(source(), destination()).run(BlobMigration.enumerateKeys(db))
        val second = BlobMigration(source(), destination()).run(BlobMigration.enumerateKeys(db))

        assertEquals(1, first.copied)
        assertEquals(0, second.copied, "a second pass re-copied bytes it already had")
        assertEquals(1, second.skipped)
        assertTrue(second.ok)
    }

    @Test
    fun run_copiesOnlyTheDelta_whenNewBlobsArriveBetweenPasses() {
        // The two-pass shape in miniature: bulk pass against a live relay, then the delta pass.
        putContent("alice", "blob-a", byteArrayOf(1))
        BlobMigration(source(), destination()).run(BlobMigration.enumerateKeys(db))

        putContent("alice", "blob-b", byteArrayOf(2))
        val delta = BlobMigration(source(), destination()).run(BlobMigration.enumerateKeys(db))

        assertEquals(1, delta.copied, "the delta pass should copy exactly the new blob")
        assertEquals(1, delta.skipped)
        assertContentEquals(byteArrayOf(2), destination().get("alice/blob-b"))
    }

    @Test
    fun run_reportsAnIndexedKeyWhoseBytesAreMissingAtSource_ratherThanCountingItCopied() {
        putContent("alice", "blob-a", byteArrayOf(1))
        source().delete("alice/blob-a")

        val report = BlobMigration(source(), destination()).run(BlobMigration.enumerateKeys(db))

        assertEquals(listOf("alice/blob-a"), report.missingAtSource)
        assertEquals(0, report.copied)
        // Not a failure: the blob is equally unreadable on the relay today, so the migration did not
        // make anything worse — but it must be visible, not silently counted as success.
        assertTrue(report.ok)
    }

    /**
     * ⚠⚠ The reason verification is default-on. A destination that alters the payload — the
     * `aws-chunked` framing case, where a store that does not decode the framing *stores* it — does
     * not fail the write. Without the read-back this run reports a cheerful success over corruption
     * that surfaces months later, on read, with the source already deleted.
     */
    @Test
    fun run_failsWhenTheDestinationAltersThePayload() {
        putContent("alice", "blob-a", ByteArray(2048) { 7 })

        val report = BlobMigration(source(), CorruptingStore(destination()))
            .run(BlobMigration.enumerateKeys(db))

        assertFalse(report.ok, "a destination that changed the bytes must fail the run")
        assertEquals(1, report.failed.size)
        assertTrue(report.failed.single().second.contains("read back"), report.failed.toString())
    }

    @Test
    fun run_withVerifyDisabled_doesNotReadBack() {
        // The escape hatch exists (a re-run over an already-verified plane), so pin that it really
        // skips the check rather than silently keeping it.
        putContent("alice", "blob-a", byteArrayOf(1))

        val report = BlobMigration(source(), CorruptingStore(destination()), verify = false)
            .run(BlobMigration.enumerateKeys(db))

        assertTrue(report.ok)
        assertEquals(1, report.copied)
    }
}
