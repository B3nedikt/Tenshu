package org.tenshu.mapregion.proxy

import org.tenshu.core.TByteArray
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.proxy.FileDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trips [DefaultRegionStore] over in-memory fakes: bytes land in the [FileDatabase], the
 * index persists to [SecureStorageDelegate] (survives a fresh store instance over the same
 * backing stores), and remove evicts both.
 */
class DefaultRegionStoreTest {

    private val delegate = MapSecureStorageDelegate()
    private val files = MapFileDatabase()

    private fun store() = DefaultRegionStore(delegate, files, regionsDir = "/regions")

    @Test
    fun addPersistsBytesAndIndexThenRemoveEvicts() {
        val bytes = "PMTILES".encodeToByteArray()
        val entry = RegionEntryData(
            regionId = "de-bayern", name = "Bayern", coverage = "poly",
            byteSize = bytes.size.toLong(), sha256 = "abc", tileSchemaVersion = 4,
            path = "regions/2026-07/de-bayern.pmtiles",
        )

        val landed = store().add(entry, buildDate = "2026-07", bytes = bytes)
        assertEquals("/regions/de-bayern.pmtiles", landed.localPath)
        assertEquals(bytes.size.toLong(), landed.byteSize)
        assertContentPresent(landed.localPath, bytes)

        // A fresh store over the same backing stores sees the persisted index (survives restart).
        assertEquals(listOf("de-bayern"), store().installed().map { it.regionId })

        assertTrue(store().remove("de-bayern"))
        assertTrue(store().installed().isEmpty())
        assertNull(files.readBytes("/regions/de-bayern.pmtiles"))
        assertFalse(store().remove("de-bayern")) // idempotent: already gone
    }

    @Test
    fun addReplacesAPriorCopyOfTheSameRegion() {
        val entry = RegionEntryData(
            regionId = "at", name = "Austria", coverage = "",
            byteSize = 3, sha256 = "v1", tileSchemaVersion = 4, path = "regions/2026-07/at.pmtiles",
        )
        store().add(entry, "2026-04", "old".encodeToByteArray())
        store().add(entry.copy(sha256 = "v2"), "2026-07", "newer".encodeToByteArray())

        val installed = store().installed()
        assertEquals(1, installed.size)
        assertEquals("v2", installed.first().sha256)
        assertEquals("2026-07", installed.first().buildDate)
    }

    @Test
    fun anUpdateNeverWritesOverTheLiveBundle_itStagesThenRenames() {
        // The refresh case (track 3b): a resident region is re-downloaded while it is being served.
        // Writing the new bytes over the live file would leave, for the duration of the write, a file
        // that is neither the old bundle nor the new one — and a crash mid-write would strand a
        // truncated file the index still vouches for. So bytes must land on a staging path and be
        // RENAMED in (rename is atomic; a reader sees only the old file or the whole new one).
        val entry = RegionEntryData(
            regionId = "at", name = "Austria", coverage = "",
            byteSize = 3, sha256 = "v1", tileSchemaVersion = 4, path = "regions/2026-07/at.pmtiles",
        )
        store().add(entry, "2026-04", "old".encodeToByteArray())
        files.clearLog()

        store().add(entry.copy(sha256 = "v2"), "2026-07", "newer".encodeToByteArray())

        // The live path was never a write target — only the staging path was, and then a rename.
        assertTrue(files.writes.none { it == "/regions/at.pmtiles" }, "wrote straight over the live bundle")
        assertTrue(files.writes.contains("/regions/at.pmtiles.part"), "bytes should stage first")
        assertEquals(listOf("/regions/at.pmtiles.part" to "/regions/at.pmtiles"), files.moves)
        // …and the staging file does not leak.
        assertNull(files.readBytes("/regions/at.pmtiles.part"))
        assertContentPresent("/regions/at.pmtiles", "newer".encodeToByteArray())
    }

    @Test
    fun streamedLanding_stagesChunksInvisibly_thenCommitsWithARename() {
        // The streamed download path (§7.1 track 3e): chunks land on the staging path, which nothing
        // serves, and only the commit promotes them — by rename, onto the canonical path.
        val entry = RegionEntryData(
            regionId = "at", name = "Austria", coverage = "",
            byteSize = 6, sha256 = "v2", tileSchemaVersion = 4, path = "regions/2026-07/at.pmtiles",
        )
        val store = store().apply { add(entry.copy(sha256 = "v1"), "2026-04", "older!".encodeToByteArray()) }
        files.clearLog()

        store.beginStaging("at", "v2")
        store.writeStaged("at", 0, "new".encodeToByteArray())
        store.writeStaged("at", 3, "er!".encodeToByteArray())

        // Mid-stream: the OLD bundle is still what the store serves. A half-written download must never
        // be visible — this is what lets a resident region keep serving through a refresh.
        assertEquals("v1", store.installed().single().sha256)
        assertEquals("older!", store.readRange("at", 0, 6)?.decodeToString())
        assertTrue(files.writes.none { it == "/regions/at.pmtiles" }, "never write over the live bundle")

        val landed = store.commitStaged(entry, "2026-07", byteSize = 6)

        assertEquals(6L, landed.byteSize)
        assertEquals("v2", store().installed().single().sha256)
        assertEquals("newer!", store().readRange("at", 0, 6)?.decodeToString())
        assertEquals(listOf("/regions/at.pmtiles.part" to "/regions/at.pmtiles"), files.moves)
        assertNull(files.readBytes("/regions/at.pmtiles.part"), "the staging file must not linger")
    }

    @Test
    fun abortStagingRemovesAHalfWrittenDownload_leavingTheResidentCopyAlone() {
        val entry = RegionEntryData(
            regionId = "ch", name = "Schweiz", coverage = "",
            byteSize = 3, sha256 = "v1", tileSchemaVersion = 4, path = "regions/2026-07/ch.pmtiles",
        )
        val store = store().apply { add(entry, "2026-07", "abc".encodeToByteArray()) }

        store.beginStaging("ch", "v1")
        store.writeStaged("ch", 0, "XX".encodeToByteArray()) // a download that then fails its sha gate
        store.abortStaging("ch")

        assertNull(files.readBytes("/regions/ch.pmtiles.part"))
        assertEquals("abc", store.readRange("ch", 0, 3)?.decodeToString(), "resident copy untouched")
        assertEquals(listOf("ch"), store.installed().map { it.regionId })
    }

    @Test
    fun removeClearsTheIndexBeforeTheBytes_soNoEntryEverVouchesForAMissingFile() {
        val entry = RegionEntryData(
            regionId = "ch", name = "Schweiz", coverage = "",
            byteSize = 3, sha256 = "x", tileSchemaVersion = 4, path = "regions/2026-07/ch.pmtiles",
        )
        store().add(entry, "2026-07", "abc".encodeToByteArray())
        files.clearLog()

        assertTrue(store().remove("ch"))

        // Deleting the bytes first would mean a crash in between leaves an index entry pointing at a
        // file that is gone — a region that reports itself installed and then serves nothing. Leaking
        // an unreferenced file is the strictly better failure (the next add overwrites it).
        assertEquals(listOf("/regions/ch.pmtiles"), files.deletes)
        assertTrue(store().installed().isEmpty())
    }

    @Test
    fun readRangeSeeksTheLandedBundleAndNullsForUnknown() {
        val bytes = "PMTILES-payload".encodeToByteArray()
        val entry = RegionEntryData(
            regionId = "de-bayern", name = "Bayern", coverage = "poly",
            byteSize = bytes.size.toLong(), sha256 = "abc", tileSchemaVersion = 4,
            path = "regions/2026-07/de-bayern.pmtiles",
        )
        val store = store().apply { add(entry, "2026-07", bytes) }

        assertEquals("PMTILES", store.readRange("de-bayern", 0, 7)?.decodeToString())
        assertEquals("payload", store.readRange("de-bayern", 8, 999)?.decodeToString())
        assertEquals(0, store.readRange("de-bayern", 100, 8)?.size) // clamped at EOF
        assertNull(store.readRange("unknown", 0, 8)) // not installed
    }

    private fun assertContentPresent(path: String, expected: ByteArray) {
        val stored = files.readBytes(path)?.get() ?: error("no bytes at $path")
        assertEquals(expected.decodeToString(), stored.decodeToString())
    }

    private class MapSecureStorageDelegate : SecureStorageDelegate {
        private val map = mutableMapOf<String, String>()
        override fun storeSecret(key: String, value: String) { map[key] = value }
        override fun getSecret(key: String): String? = map[key]
        override fun storeString(key: String, value: String) { map[key] = value }
        override fun getString(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
    }

    /**
     * Records every write / move / delete, so a test can assert *how* bytes landed, not just where they
     * ended up. [moveFile] is overridden as a true key-move — mirroring `AndroidFileDatabaseImpl`
     * (`File.renameTo`) and `InMemoryFileDatabase`; the interface's copy+delete default would defeat the
     * very property under test.
     */
    private class MapFileDatabase : FileDatabase {
        private val files = mutableMapOf<String, ByteArray>()
        val writes = mutableListOf<String>()
        val moves = mutableListOf<Pair<String, String>>()
        val deletes = mutableListOf<String>()

        fun clearLog() { writes.clear(); moves.clear(); deletes.clear() }

        override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
            writes += filePath
            files[filePath] = bytes
        }
        override fun writeByteArray(filePath: String, bytes: TByteArray) {
            writes += filePath
            files[filePath] = bytes.get()
        }
        override fun readBytes(filePath: String): TByteArray? = files[filePath]?.let { TByteArrayDto(it) }
        override fun moveFile(fromPath: String, toPath: String) {
            moves += fromPath to toPath
            files.remove(fromPath)?.let { files[toPath] = it }
        }
        override fun deleteFile(filePath: String) {
            deletes += filePath
            files.remove(filePath)
        }
    }
}
