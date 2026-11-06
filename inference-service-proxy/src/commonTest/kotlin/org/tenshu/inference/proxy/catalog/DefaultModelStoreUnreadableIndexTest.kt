package org.tenshu.inference.proxy.catalog

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.tenshu.core.TByteArray
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.inference.InferenceCapability
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The index's read contract (`ios-on-device-inference.md` §13.7): nothing is answered, written or
 * deleted from an index assumed empty.
 *
 * ⚠ Each `fileStore()` / `delegateStore()` is a new process over the same storage, as in
 * `DefaultModelStoreTest`. The sweep runs at a process's first index read, so what a failed read
 * costs depends on which process meets it: the one right after an install still holds that
 * install's declaration.
 *
 * Two storages, because the two failures arrive differently:
 * - [fileStore] is the iOS owner's: the index beside the bytes, in a database that answers `null`
 *   for a file it cannot read ([Disk.noBytesFor]);
 * - [delegateStore] is Android's and desktop's: the index in the secure-storage delegate, whose
 *   read can only throw ([FailingReads]) or hand back a string.
 */
class DefaultModelStoreUnreadableIndexTest {

    private val disk = Disk()
    private val delegate = FailingReads(InMemorySecureStorageDelegate())

    private val indexKey = "inference.models.index"
    private val garbageKey = "$indexKey.garbage"
    private val indexFile = "/models/$indexKey.json"
    private val garbageFile = "/models/$garbageKey.json"
    private val role = ModelArtifactRole.VISION

    private fun digest(value: ByteArray) = value.decodeToString().hashCode().toString()

    private fun fileStore() = DefaultModelStore(
        FileModelIndexStorage(disk, "/models"),
        disk,
        modelsDir = "/models",
        digestOf = ::digest,
        ownership = ModelStoreOwnership.SoleProcess,
    )

    private fun delegateStore() = DefaultModelStore(
        delegate,
        disk,
        modelsDir = "/models",
        digestOf = ::digest,
        ownership = ModelStoreOwnership.SoleProcess,
    )

    private fun entry(modelId: String, content: ByteArray, modelVersion: Int = 1) = ModelEntryData(
        modelId = modelId,
        name = modelId,
        modelVersion = modelVersion,
        capabilities = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
        labelSetVersion = 1,
        runtime = "coreml",
        artifactVersion = 1,
        artifacts = listOf(ModelArtifactData(role, "models/$modelId", digest(content), content.size.toLong())),
        licence = "Apache-2.0",
    )

    private val siglip = "AAAA".encodeToByteArray()
    private val siglipV2 = "CCCC".encodeToByteArray()
    private val faces = "BBBB".encodeToByteArray()
    private val third = "DDDD".encodeToByteArray()

    private fun DefaultModelStore.install(modelId: String, content: ByteArray, modelVersion: Int = 1): String =
        assertNotNull(add(entry(modelId, content, modelVersion), mapOf(role to content))).pathOf(role)!!

    private fun DefaultModelStore.ids(): List<String> = installed().map { it.modelId }.sorted()

    private fun models(): Map<String, List<Byte>> = disk.snapshot().filterKeys { it.endsWith(".model") }

    // ─── the iOS owner: `null` for a file that is there ─────────────────────────────────────────

    /**
     * ⚠⚠ The defect, at its worst. The process that installed a model leaves that model's paths
     * declared; the next process's first read is the sweep. Read as empty, the index named
     * nothing, so the sweep deleted the model that had just been installed.
     */
    @Test
    fun anIndexFileThatIsThereAndGivesNoBytesIsUnreadableNotEmpty() {
        val path = fileStore().install("siglip", siglip)
        disk.noBytesFor += indexFile

        val next = fileStore()
        assertFalse(assertFailsWith<ModelIndexUnreadableException> { next.installed() }.undecodable)
        assertFailsWith<ModelIndexUnreadableException> { next.pathOf("siglip", role) }
        assertContentEquals(siglip, disk.bytesAt(path), "swept against an index that was not read")
        assertEquals(emptyList(), disk.deletes)

        // The file reads again, in the same process: nothing was remembered of the failure.
        disk.noBytesFor.clear()
        assertEquals(listOf("siglip"), next.ids())
        assertEquals(path, next.pathOf("siglip", role))
        assertContentEquals(siglip, disk.bytesAt(path))
    }

    /** ⚠⚠ The other half: a commit over an index read as empty wrote back an index of one. */
    @Test
    fun aCommitOrARemovalOnAnUnreadableIndexIsRefusedAndTheDiskIsLeftByteIdentical() {
        fileStore().also { it.install("siglip", siglip) }.install("faces", faces)
        val before = disk.snapshot()
        disk.noBytesFor += indexFile

        val next = fileStore()
        assertFailsWith<ModelIndexUnreadableException> { next.add(entry("third", third), mapOf(role to third)) }
        assertFailsWith<ModelIndexUnreadableException> { next.commitStaged(entry("third", third), mapOf(role to 4L)) }
        assertFailsWith<ModelIndexUnreadableException> { next.remove("siglip") }
        // ⚠ Before the fetch, not after it: a download begins here.
        assertFailsWith<ModelIndexUnreadableException> { next.beginStaging("third", role, digest(third)) }

        assertEquals(before, disk.snapshot(), "the index, the garbage list and every model, byte for byte")
        assertEquals(emptyList(), disk.deletes)

        disk.noBytesFor.clear()
        assertEquals(listOf("faces", "siglip"), next.ids())
        next.install("third", third)
        assertEquals(listOf("faces", "siglip", "third"), fileStore().ids())
    }

    /** The sweep is the first read that yields an index, not the first read. */
    @Test
    fun theSweepWaitsForAnIndexThatWasReadAndThenRunsAsBefore() {
        val old = fileStore().install("siglip", siglip)
        val current = fileStore().install("siglip", siglipV2, modelVersion = 2)
        disk.noBytesFor += indexFile

        val next = fileStore()
        assertFailsWith<ModelIndexUnreadableException> { next.installed() }
        assertNotNull(disk.bytesAt(old))
        assertNotNull(disk.bytesAt(current))

        disk.noBytesFor.clear()
        assertEquals(2, next.installed().single().modelVersion)
        assertNull(disk.bytesAt(old), "the replaced generation is reclaimed once the index is read")
        assertContentEquals(siglipV2, disk.bytesAt(current))
        assertNull(disk.bytesAt(garbageFile))
    }

    @Test
    fun aGarbageListThatCannotBeReadStopsTheSweepAndRefusesADeclaration() {
        val old = fileStore().install("siglip", siglip)
        val current = fileStore().install("siglip", siglipV2, modelVersion = 2)
        val index = disk.bytesAt(indexFile)
        val garbage = disk.bytesAt(garbageFile)
        disk.noBytesFor += garbageFile

        // The index is fine, so the store answers. It reclaims nothing.
        val next = fileStore()
        assertEquals(2, next.installed().single().modelVersion)
        assertNotNull(disk.bytesAt(old))

        // ⚠ A commit would write the list back from one assumed empty, and `old` would never go.
        val models = models()
        disk.moves.clear()
        assertFailsWith<ModelIndexUnreadableException> { next.add(entry("faces", faces), mapOf(role to faces)) }
        assertFailsWith<ModelIndexUnreadableException> { next.remove("siglip") }
        assertContentEquals(index, disk.bytesAt(indexFile))
        assertContentEquals(garbage, disk.bytesAt(garbageFile))
        assertEquals(models, models())
        assertEquals(emptyList(), disk.moves)

        disk.noBytesFor.clear()
        val added = next.install("faces", faces)
        assertTrue(old in declared(disk.bytesAt(garbageFile)), "the earlier declaration was kept")
        // ⚠ Not in this process: the list now holds this process's own declarations.
        assertNotNull(disk.bytesAt(old))

        assertEquals(listOf("faces", "siglip"), fileStore().ids())
        assertNull(disk.bytesAt(old))
        assertContentEquals(siglipV2, disk.bytesAt(current))
        assertContentEquals(faces, disk.bytesAt(added))
    }

    @Test
    fun anIndexFileThatIsNotThereIsAbsent() {
        val storage = FileModelIndexStorage(disk, "/models")
        assertNull(storage.getString(indexKey))
        assertEquals(emptyList(), fileStore().installed())

        // ⚠ And an empty file is a file that was read: nothing in it, so no index.
        disk.writeRawByteArray(indexFile, ByteArray(0))
        assertEquals("", storage.getString(indexKey))
        assertEquals(emptyList(), fileStore().installed())
        fileStore().install("siglip", siglip)
        assertEquals(listOf("siglip"), fileStore().ids())
    }

    // ─── Android and desktop: a read that throws ────────────────────────────────────────────────

    @Test
    fun aDelegateReadThatFailsOnceIsRefusedOnceAndThenForgotten() {
        delegateStore().also { it.install("siglip", siglip) }.install("faces", faces)
        val before = disk.snapshot()
        val stored = delegate.strings()
        delegate.failNextReadsOf[indexKey] = 1

        val next = delegateStore()
        val refused = assertFailsWith<ModelIndexUnreadableException> { next.add(entry("third", third), mapOf(role to third)) }
        assertFalse(refused.undecodable)
        assertNotNull(refused.cause, "the delegate's own failure is carried")
        assertEquals(before, disk.snapshot())
        assertEquals(stored, delegate.strings())

        assertEquals(listOf("faces", "siglip"), next.ids())
        next.install("third", third)
        assertEquals(listOf("faces", "siglip", "third"), delegateStore().ids())
    }

    // ─── every platform: bytes that were read and are not this build's index ────────────────────

    /**
     * ⚠ The stored string here is written by the test, because no build with another record shape
     * exists: it is what one would leave, or what this build's own records become to a build that
     * adds a field with no default.
     */
    @Test
    fun anIndexOfAnotherShapeIsNotEmptiedSweptOrWrittenOver() {
        val path = delegateStore().install("siglip", siglip)
        val foreign = """[{"modelId":"siglip","generation":7}]"""
        delegate.storeString(indexKey, foreign)
        val garbage = delegate.getString(garbageKey)
        val before = disk.snapshot()

        val next = delegateStore()
        assertTrue(assertFailsWith<ModelIndexUnreadableException> { next.installed() }.undecodable)
        assertFailsWith<ModelIndexUnreadableException> { next.add(entry("faces", faces), mapOf(role to faces)) }
        assertFailsWith<ModelIndexUnreadableException> { next.remove("siglip") }
        assertFailsWith<ModelIndexUnreadableException> { next.beginStaging("faces", role, digest(faces)) }

        assertEquals(foreign, delegate.getString(indexKey))
        assertEquals(garbage, delegate.getString(garbageKey))
        assertEquals(before, disk.snapshot())
        assertContentEquals(siglip, disk.bytesAt(path))
    }

    /** ⚠ `pathOf` is on the per-image path: bytes that will not change are not parsed again per call. */
    @Test
    fun anIndexOfAnotherShapeIsReadOnce() {
        delegate.storeString(indexKey, """{"models":[]}""")
        val store = delegateStore()
        delegate.reads.clear()

        repeat(20) { assertFailsWith<ModelIndexUnreadableException> { store.pathOf("siglip", role) } }

        assertEquals(listOf(indexKey), delegate.reads)
    }

    /**
     * Bytes no build could read as records — here a record cut short, as a write that died
     * part-way leaves it — are no index, by decision. ⚠ But nothing is deleted against them: only
     * a commit, which is the user asking for a model, puts a record there again.
     */
    @Test
    fun bytesThatAreNotJsonAreNoIndexAndNothingIsSweptAgainstThem() {
        val orphan = delegateStore().install("siglip", siglip)
        val whole = assertNotNull(delegate.getString(indexKey))
        delegate.storeString(indexKey, whole.take(whole.length / 2))

        val next = delegateStore()
        assertEquals(emptyList(), next.installed())
        assertContentEquals(siglip, disk.bytesAt(orphan), "swept against bytes that were not an index")
        assertEquals(emptyList(), disk.deletes.filter { it.endsWith(".model") })

        val added = next.install("faces", faces)
        assertEquals(listOf("faces"), delegateStore().ids())
        assertContentEquals(faces, disk.bytesAt(added))
        // The next process read a record back, and the declaration from before is still there.
        assertNull(disk.bytesAt(orphan))
    }

    private fun declared(bytes: ByteArray?): List<String> =
        Json.decodeFromString(ListSerializer(String.serializer()), assertNotNull(bytes).decodeToString())

    /**
     * A delegate whose next [failNextReadsOf] reads of a key throw, then read again: the only way
     * a [SecureStorageDelegate] can say it could not read.
     */
    private class FailingReads(private val inner: InMemorySecureStorageDelegate) : SecureStorageDelegate by inner {
        val failNextReadsOf = mutableMapOf<String, Int>()
        val reads = mutableListOf<String>()
        private val written = mutableMapOf<String, String>()

        fun strings(): Map<String, String> = written.toMap()

        override fun getString(key: String): String? {
            reads += key
            val left = failNextReadsOf[key] ?: 0
            if (left > 0) {
                failNextReadsOf[key] = left - 1
                throw IllegalStateException("the store could not be read")
            }
            return inner.getString(key)
        }

        override fun storeString(key: String, value: String) {
            written[key] = value
            inner.storeString(key, value)
        }

        override fun remove(key: String) {
            written.remove(key)
            inner.remove(key)
        }
    }

    /**
     * A map of files. [noBytesFor] is `IosFileDatabase`'s answer for a file it cannot read: `null`
     * from the read, while a stat still finds the file (read from its code: `readBytes` returns
     * `NSData.dataWithContentsOfFile(...) ?: return null`, `fileSize` asks the file manager).
     */
    private class Disk : FileDatabase {
        private val files = mutableMapOf<String, ByteArray>()
        val noBytesFor = mutableSetOf<String>()
        val deletes = mutableListOf<String>()
        val moves = mutableListOf<Pair<String, String>>()

        fun bytesAt(path: String): ByteArray? = files[path]
        fun snapshot(): Map<String, List<Byte>> = files.mapValues { it.value.toList() }

        override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
            files[filePath] = bytes
        }

        override fun writeByteArray(filePath: String, bytes: TByteArray) = writeRawByteArray(filePath, bytes.get())

        override fun readBytes(filePath: String): TByteArray? =
            if (filePath in noBytesFor) null else files[filePath]?.let { TByteArrayDto(it) }

        override fun fileSize(filePath: String): Long? = files[filePath]?.size?.toLong()

        override fun moveFile(fromPath: String, toPath: String) {
            moves += fromPath to toPath
            files.remove(fromPath)?.let { files[toPath] = it }
        }

        override fun deleteFile(filePath: String) {
            if (filePath in files) deletes += filePath
            files.remove(filePath)
        }
    }
}
