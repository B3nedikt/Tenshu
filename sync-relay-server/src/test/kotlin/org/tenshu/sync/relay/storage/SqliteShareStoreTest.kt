package org.tenshu.sync.relay.storage

import org.tenshu.sync.relay.mailbox.ShareStore
import org.tenshu.sync.relay.mailbox.TakedownOutcome
import org.tenshu.sync.relay.mailbox.TakedownReason
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3b: durable Mode-A share store. Descriptors in SQLite, chunk bytes in a
 * [FileBlobStore]. Pins create/open/revoke + cap/expiry semantics, the revoked/expired/
 * orphan GC (rows + chunk files), and persistence across restart.
 */
class SqliteShareStoreTest {

    private lateinit var dbFile: File
    private lateinit var blobDir: File
    private lateinit var url: String

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-share-test", ".db")
        blobDir = File.createTempFile("relay-share-blobs", "").also { it.delete(); it.mkdirs() }
        url = "jdbc:sqlite:${dbFile.absolutePath}"
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
        blobDir.deleteRecursively()
    }

    private fun record(
        shareId: String = "s1",
        ownerId: String = "alice",
        expiresAtMillis: Long? = null,
        maxOpens: Int? = null,
        createdAtMs: Long = 1_000,
    ) = ShareStore.ShareRecord(
        shareId = shareId,
        ownerId = ownerId,
        sealedMeta = SEALED,
        chunkCount = 2,
        chunkHashes = listOf(byteArrayOf(1, 2), byteArrayOf(3, 4, 5)),
        expiresAtMillis = expiresAtMillis,
        maxOpens = maxOpens,
        createdAtMs = createdAtMs,
    )

    @Test
    fun create_peek_roundTrip_idempotency_and_ownerClash() {
        RelayDatabase(url).use { db ->
            val store = SqliteShareStore(db, FileBlobStore(blobDir))
            assertEquals("s1", store.create(record())?.shareId)

            val peeked = store.peek("s1")!!
            assertEquals("alice", peeked.ownerId)
            assertEquals(2, peeked.chunkCount)
            assertContentEquals(byteArrayOf(3, 4, 5), peeked.chunkHashes[1]) // framed BLOB round-trips

            // Same owner re-PUT → idempotent; different owner → refused.
            assertEquals("alice", store.create(record())?.ownerId)
            assertNull(store.create(record(ownerId = "mallory")))
            assertEquals(1, store.recordCount())
        }
    }

    /**
     * F3: the row holds no clear file name, MIME type or size — only the opaque sealed blob,
     * which round-trips byte-for-byte.
     */
    @Test
    fun shareRecord_holdsNoClearMetadata_and_sealedMetaRoundTrips() {
        RelayDatabase(url).use { db ->
            val store = SqliteShareStore(db, FileBlobStore(blobDir))
            store.create(record())
            assertContentEquals(SEALED, store.peek("s1")!!.sealedMeta)
            assertEquals(SEALED_COLUMNS_ONLY, shareRecordColumns(db))
        }
    }

    /** F12 closure: since F3 the row holds no size, so `bytesFreed` is the stored ciphertext's length. */
    @Test
    fun eraseOwnerBytes_reportsStoredCiphertextBytes() {
        RelayDatabase(url).use { db ->
            val blobs = FileBlobStore(blobDir)
            val store = SqliteShareStore(db, blobs)
            store.create(record(shareId = "a1"))
            store.putChunk("a1", 0, ByteArray(5))
            store.putChunk("a1", 1, ByteArray(3))
            store.create(record(shareId = "b1", ownerId = "bob"))
            store.putChunk("b1", 0, ByteArray(11))

            store.eraseOwner("alice")
            val result = store.eraseOwnerBytes("alice")
            assertEquals(2, result.blobsDropped)
            assertEquals(8L, result.bytesFreed)
            assertFalse(blobs.exists("share/a1/0"))
            assertTrue(blobs.exists("share/b1/0"), "only the closed owner")
            assertEquals(0L, store.eraseOwnerBytes("alice").bytesFreed, "a repeat frees nothing")
        }
    }

    /** The in-memory twin reports the same measured bytes. */
    @Test
    fun inMemory_eraseOwnerBytes_reportsStoredCiphertextBytes() {
        val store = ShareStore()
        store.create(record(shareId = "a1"))
        store.putChunk("a1", 0, ByteArray(5))
        store.putChunk("a1", 1, ByteArray(3))
        store.eraseOwner("alice")
        assertEquals(8L, store.eraseOwnerBytes("alice").bytesFreed)
    }

    /** `sealedMeta` / `chunkHashes` are arrays: equality must be by content, not identity. */
    @Test
    fun shareRecord_equalsByContent() {
        val a = record()
        val b = record().copy(sealedMeta = SEALED.copyOf(), chunkHashes = a.chunkHashes.map { it.copyOf() })
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == record().copy(sealedMeta = byteArrayOf(0)))
    }

    private fun shareRecordColumns(db: RelayDatabase): Set<String> = db.withConnection { conn ->
        conn.prepareStatement("PRAGMA table_info(share_record)").use { ps ->
            ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }
        }
    }

    private companion object {
        val SEALED = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val SEALED_COLUMNS_ONLY = setOf(
            "share_id", "owner_id", "chunk_count", "chunk_hashes", "expires_at", "max_opens",
            "created_at", "opens_so_far", "revoked_at", "sealed_meta",
        )
    }

    @Test
    fun open_bumpsOpens_capExhaustion_revoked_expired() {
        RelayDatabase(url).use { db ->
            val store = SqliteShareStore(db, FileBlobStore(blobDir))

            store.create(record(shareId = "cap", maxOpens = 1))
            assertEquals(1, store.open("cap", nowMs = 10)?.opensSoFar) // bumps 0 -> 1
            assertNull(store.open("cap", nowMs = 10))                  // cap exhausted -> 410

            store.create(record(shareId = "exp", expiresAtMillis = 100))
            assertNull(store.open("exp", nowMs = 100))                 // expired (>=)
            assertEquals(1, store.open("exp", nowMs = 50)?.opensSoFar) // still live before expiry

            store.create(record(shareId = "rev"))
            assertTrue(store.revoke("rev", "alice", nowMs = 5))
            assertNull(store.open("rev", nowMs = 6))
            assertFalse(store.revoke("rev", "mallory"))               // ownership enforced
        }
    }

    @Test
    fun chunk_putGet_roundTrip_and_revokedHidesChunks() {
        RelayDatabase(url).use { db ->
            val store = SqliteShareStore(db, FileBlobStore(blobDir))
            store.create(record())
            store.putChunk("s1", 0, byteArrayOf(9, 9))
            store.putChunk("s1", 0, byteArrayOf(7)) // first-write-wins (immutable)
            assertContentEquals(byteArrayOf(9, 9), store.getChunk("s1", 0, nowMs = 10))
            assertNull(store.getChunk("s1", 5, nowMs = 10)) // absent index

            store.revoke("s1", "alice", nowMs = 10)
            assertNull(store.getChunk("s1", 0, nowMs = 11)) // revoked hides chunks
        }
    }

    @Test
    fun gcSweep_dropsRevokedExpiredOrphan_deletesChunkFiles_keepsLive() {
        RelayDatabase(url).use { db ->
            val blobs = FileBlobStore(blobDir)
            val store = SqliteShareStore(db, blobs, revokedRetentionWindowMs = 100)

            // revoked at 0, with chunks → collectible at now=200; chunk files must be deleted.
            store.create(record(shareId = "rev"))
            store.putChunk("rev", 0, byteArrayOf(1))
            store.putChunk("rev", 1, byteArrayOf(2))
            store.revoke("rev", "alice", nowMs = 0)
            // expired at 50 → collectible at 200.
            store.create(record(shareId = "exp", expiresAtMillis = 50))
            // orphan: created at 0, never got a chunk → collectible at 200.
            store.create(record(shareId = "orphan", createdAtMs = 0))
            // live: fresh + has a chunk → kept.
            store.create(record(shareId = "live", createdAtMs = 195))
            store.putChunk("live", 0, byteArrayOf(3))

            assertEquals(3, store.gcSweep(nowMs = 200))
            assertEquals(1, store.recordCount())
            assertNull(store.peek("rev"))
            assertFalse(blobs.exists("share/rev/0"))
            assertFalse(blobs.exists("share/rev/1"))
            assertEquals("live", store.peek("live")?.shareId)

            assertEquals(0, store.gcSweep(nowMs = 300)) // idempotent on the survivor
        }
    }

    @Test
    fun maxRetention_disablesGc() {
        RelayDatabase(url).use { db ->
            val store = SqliteShareStore(db, FileBlobStore(blobDir), revokedRetentionWindowMs = Long.MAX_VALUE)
            store.create(record(shareId = "rev"))
            store.revoke("rev", "alice", nowMs = 0)
            assertEquals(0, store.gcSweep(nowMs = Long.MAX_VALUE))
            assertEquals(1, store.recordCount())
        }
    }

    @Test
    fun record_and_chunk_surviveReopen() {
        RelayDatabase(url).use { db ->
            val store = SqliteShareStore(db, FileBlobStore(blobDir))
            store.create(record(shareId = "s1", maxOpens = 5))
            store.open("s1", nowMs = 10) // opensSoFar -> 1
            store.putChunk("s1", 0, byteArrayOf(4, 2))
        }
        RelayDatabase(url).use { db ->
            val store = SqliteShareStore(db, FileBlobStore(blobDir))
            val r = store.peek("s1")!!
            assertEquals(1, r.opensSoFar)            // bumped count persisted
            assertEquals(5, r.maxOpens)
            assertContentEquals(byteArrayOf(4, 2), store.getChunk("s1", 0, nowMs = 20))
        }
    }

    // ---- operator takedown (acceptable-use §3) ----------------------------------------------------

    @Test
    fun takedownAudit_isAppendOnly_andSurvivesReopen() {
        RelayDatabase(url).use { db ->
            val store = SqliteShareStore(db, FileBlobStore(blobDir))
            store.create(record(shareId = "s1"))
            store.takeDown("s1", TakedownReason.CSAM, "ticket 7", nowMs = 5)
            for (sql in listOf(
                "UPDATE share_takedown SET operator_note = 'edited'",
                "UPDATE share_takedown SET owner_id = NULL",
                "DELETE FROM share_takedown",
            )) {
                val refused = runCatching {
                    db.withConnection { c -> c.createStatement().use { it.executeUpdate(sql) } }
                }.exceptionOrNull()
                assertTrue(refused?.message?.contains("append-only") == true, "$sql was not refused: $refused")
            }
        }
        RelayDatabase(url).use { db ->
            val row = SqliteShareStore(db, FileBlobStore(blobDir)).takedowns("s1").single()
            assertEquals(TakedownOutcome.TAKEN_DOWN, row.outcome)
            assertEquals("ticket 7", row.operatorNote)
            assertEquals("alice", row.ownerId)
        }
    }

    @Test
    fun takedown_whoseByteDeleteFails_isStillDead_andARepeatFinishesTheDelete() {
        RelayDatabase(url).use { db ->
            val files = FileBlobStore(blobDir)
            var failDeletes = true
            val flaky = object : BlobStore by files {
                override fun deleteAll(keys: Collection<String>) {
                    if (failDeletes) error("bucket unreachable")
                    files.deleteAll(keys)
                }
            }
            val store = SqliteShareStore(db, flaky)
            store.create(record(shareId = "s1"))
            store.putChunk("s1", 0, byteArrayOf(1))
            store.putChunk("s1", 1, byteArrayOf(2))

            val first = store.takeDown("s1", TakedownReason.MALWARE_OR_PHISHING, null, nowMs = 5)
            assertFalse(first.bytesDeleteCompleted)
            assertNull(store.open("s1", nowMs = 6), "the revoke committed before the byte delete")
            assertNull(store.getChunk("s1", 0, nowMs = 6))
            assertTrue(files.exists("share/s1/0"))

            failDeletes = false
            val second = store.takeDown("s1", TakedownReason.MALWARE_OR_PHISHING, null, nowMs = 7)
            assertEquals(TakedownOutcome.ALREADY_TAKEN_DOWN, second.record.outcome)
            assertTrue(second.bytesDeleteCompleted)
            assertEquals(2L, second.bytesDeleted)
            assertFalse(files.exists("share/s1/0") || files.exists("share/s1/1"))
        }
    }

    @Test
    fun takedown_ofAnOwnerRevokedShare_keepsTheOwnersRevokeTime_andStillDeletesBytesNow() {
        RelayDatabase(url).use { db ->
            val files = FileBlobStore(blobDir)
            val store = SqliteShareStore(db, files)
            store.create(record(shareId = "s1"))
            store.putChunk("s1", 0, byteArrayOf(1))
            store.revoke("s1", "alice", nowMs = 3)
            val result = store.takeDown("s1", TakedownReason.TERMS_VIOLATION, null, nowMs = 9)
            assertEquals(TakedownOutcome.TAKEN_DOWN, result.record.outcome)
            assertEquals(3L, store.peek("s1")!!.revokedAtMs)
            assertFalse(files.exists("share/s1/0"))
        }
    }

    @Test
    fun aTakenDownId_isRetiredForGood_evenAfterTheGcDroppedItsRow() {
        RelayDatabase(url).use { db ->
            val store = SqliteShareStore(db, FileBlobStore(blobDir), revokedRetentionWindowMs = 10)
            store.create(record(shareId = "s1"))
            store.takeDown("s1", TakedownReason.ILLEGAL_CONTENT, null, nowMs = 5)
            store.takeDown("never-existed", TakedownReason.OTHER, null, nowMs = 5)
            assertEquals(1, store.gcSweep(nowMs = 1_000))
            assertNull(store.peek("s1"), "the GC collected the revoked row")
            assertNull(store.create(record(shareId = "s1")), "the reported URL must not come back, not even for its owner")
            assertNull(store.create(record(shareId = "s1", ownerId = "mallory")))
            assertNull(store.create(record(shareId = "never-existed")), "a not_found notice reserves the id too")
            assertTrue(store.create(record(shareId = "s3")) != null, "other ids are unaffected")
        }
    }

    @Test
    fun aChunkPut_thatLandsAfterTheTakedown_isDroppedAgain() {
        RelayDatabase(url).use { db ->
            val files = FileBlobStore(blobDir)
            val store = SqliteShareStore(db, files)
            store.create(record(shareId = "s1"))
            store.takeDown("s1", TakedownReason.CSAM, null, nowMs = 5)
            // The route's revocation check passed before the takedown; the store write lands after it.
            store.putChunk("s1", 1, byteArrayOf(6, 6))
            assertFalse(files.exists("share/s1/1"), "bytes of an in-flight PUT must not outlive the takedown")
        }
    }
}
