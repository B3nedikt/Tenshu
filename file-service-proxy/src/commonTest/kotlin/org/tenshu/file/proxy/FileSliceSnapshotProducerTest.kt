package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.common.slice.FileSliceEntry
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the producer through a **real** file proxy rather than a fake tree — the point is
 * that it reads what `CreateFolder`/`UploadFile` actually wrote, so the slice cannot silently
 * disagree with the listing it is supposed to stand in for.
 */
class FileSliceSnapshotProducerTest {

    private val producer = FileSliceSnapshotProducer()

    @Test
    fun sliceKeys_walksTheWholeFolderTreeAndListsOnlyFolders() = runTest {
        val proxy = createFileProxy(backgroundScope)
        proxy.executionContext.createFolder("/photos")
        proxy.executionContext.createFolder("/photos/2026")
        proxy.executionContext.createFolder("/docs")
        proxy.executionContext.upload("/photos", "a.jpg")

        val keys = producer.sliceKeys(proxy.executionContext, Folder.Fields.FILES)

        assertTrue("/" in keys, "root is a slice")
        assertTrue("/photos" in keys)
        assertTrue("/photos/2026" in keys, "the walk must recurse, not stop at depth 1")
        assertTrue("/docs" in keys)
        assertTrue(
            "/photos/a.jpg" !in keys,
            "a plain file is an ENTRY in its parent's slice, never a slice of its own",
        )
    }

    @Test
    fun sliceKeys_ignoresARelationItDoesNotOwn() = runTest {
        val proxy = createFileProxy(backgroundScope)
        assertEquals(
            emptyList(),
            producer.sliceKeys(proxy.executionContext, "tenshu/timeline/moments"),
            "a producer must never answer for a relation it does not declare",
        )
    }

    @Test
    fun encodeSlice_carriesEveryChildWithTheMetadataARowNeeds() = runTest {
        val proxy = createFileProxy(backgroundScope)
        proxy.executionContext.createFolder("/photos")
        proxy.executionContext.createFolder("/photos/2026")
        proxy.executionContext.upload("/photos", "a.jpg", bytes = ByteArray(1234) { 7 })

        val slice = producer.encodeSlice(proxy.executionContext, Folder.Fields.FILES, "/photos")
        val entries = FileSliceEntry.decode(assertNotNull(slice).entries)

        assertEquals(2, slice.entryCount, "entryCount must agree with what entries encodes")
        assertEquals(2, entries?.size)

        val file = entries!!.single { !it.isFolder }
        assertEquals("/photos/a.jpg", file.entityId)
        assertEquals("a.jpg", file.name, "name is denormalised so a render needs no path parsing")
        assertEquals(1234, file.size)
        assertTrue(
            file.contentId.isNotEmpty(),
            "contentId is what lets a joiner reach the content plane for bytes and — since C7 — " +
                "for either thumbnail class, so an empty one makes the entry unusable",
        )

        val child = entries.single { it.isFolder }
        assertEquals("/photos/2026", child.entityId)
        assertEquals(
            "2026", child.name,
            "a child folder is an entry too — that is how a joiner knows to offer navigation " +
                "into a slice it has not fetched",
        )
    }

    @Test
    fun encodeSlice_ofAnEmptyFolderIsAnEmptySlice_notAMissingOne() = runTest {
        // ⚠ The distinction the reader depends on: an empty slice renders "this folder is
        // empty", a null one falls back to a relation read. Conflating them would make every
        // empty folder pay the fallback.
        val proxy = createFileProxy(backgroundScope)
        proxy.executionContext.createFolder("/empty")

        val slice = assertNotNull(producer.encodeSlice(proxy.executionContext, Folder.Fields.FILES, "/empty"))

        assertEquals(0, slice.entryCount)
        assertContentEquals(emptyList(), FileSliceEntry.decode(slice.entries))
    }

    @Test
    fun encodeSlice_returnsNullForAFolderThatDoesNotExist() = runTest {
        // ⚠ Null, not an empty slice: "I cannot produce this" must be distinguishable from
        // "this folder is empty", or a joiner would render a deleted folder as an empty one.
        val proxy = createFileProxy(backgroundScope)
        assertNull(producer.encodeSlice(proxy.executionContext, Folder.Fields.FILES, "/nope"))
    }

    @Test
    fun encodeSlice_matchesTheFolderItStandsInFor() = runTest {
        // The property that actually matters: a slice must agree with the listing it replaces.
        // If these ever diverge, a joiner renders a folder that silently omits files — worse
        // than no snapshot at all, and invisible without this assertion.
        val proxy = createFileProxy(backgroundScope)
        proxy.executionContext.createFolder("/mixed")
        repeat(5) { proxy.executionContext.upload("/mixed", "f$it.txt") }
        proxy.executionContext.createFolder("/mixed/sub")

        val slice = assertNotNull(producer.encodeSlice(proxy.executionContext, Folder.Fields.FILES, "/mixed"))
        val fromSlice = FileSliceEntry.decode(slice.entries)!!.map { it.entityId }.sorted()

        val expected = (0 until 5).map { "/mixed/f$it.txt" } + "/mixed/sub"
        assertContentEquals(expected.sorted(), fromSlice)
    }

    @Test
    fun bootstrapSliceKeys_isRootAlone() = runTest {
        // ⚠ The seed cannot be `sliceKeys()`: that enumerates the LOCAL store, which on the
        // device this whole feature exists for is empty. Root is the one coordinate nameable
        // before any state arrives; everything else is reached by opening a folder.
        val proxy = createFileProxy(backgroundScope)
        assertEquals(
            listOf("/"),
            producer.bootstrapSliceKeys(proxy.executionContext, Folder.Fields.FILES),
        )
        assertEquals(
            emptyList(),
            producer.bootstrapSliceKeys(proxy.executionContext, "tenshu/timeline/moments"),
        )
    }

    @Test
    fun applySlice_rebuildsTheFolderOnADeviceThatHasNoneOfIt() = runTest {
        // The round trip that is the point of C8: produce on a device that has the tree, apply
        // on one that has nothing, and the second device's own slice must agree with the first's.
        // ⚠ That comparison also proves the parent's FILES membership was maintained — the
        // producer reads it through `resolveListField`, so an entry that failed to land in the
        // relation would simply be missing here.
        val source = createFileProxy(backgroundScope)
        source.executionContext.createFolder("/photos")
        source.executionContext.createFolder("/photos/2026")
        source.executionContext.upload("/photos", "a.jpg", bytes = ByteArray(1234) { 7 })
        val slice = assertNotNull(
            producer.encodeSlice(source.executionContext, Folder.Fields.FILES, "/photos")
        )

        val joiner = createFileProxy(backgroundScope)
        val created = producer.applySlice(
            joiner.executionContext, Folder.Fields.FILES, "/photos", slice.entries, isDeleted = { false }
        )

        assertEquals(3, created, "the folder itself, plus its two children")
        val rebuilt = assertNotNull(
            producer.encodeSlice(joiner.executionContext, Folder.Fields.FILES, "/photos")
        )
        assertContentEquals(
            FileSliceEntry.decode(slice.entries)!!.map { it.entityId }.sorted(),
            FileSliceEntry.decode(rebuilt.entries)!!.map { it.entityId }.sorted(),
        )
        val file = FileSliceEntry.decode(rebuilt.entries)!!.single { !it.isFolder }
        assertEquals(1234, file.size, "the projection's scalars must survive the round trip")
        assertTrue(file.contentId.isNotEmpty(), "contentId is what makes the bytes reachable")
    }

    @Test
    fun applySlice_neverOverwritesARowTheDeviceAlreadyHas() = runTest {
        // ⚠⚠ Create-only is a correctness rule, not an optimisation. An entry is a PROJECTION —
        // it carries no thumbnails, no sync rules, none of the properties a real row has — so
        // applying it over an existing entity would silently discard them. A stale local row is
        // the log's problem to fix; a truncated one is nobody's.
        val joiner = createFileProxy(backgroundScope)
        joiner.executionContext.createFolder("/photos")
        joiner.executionContext.upload("/photos", "a.jpg", bytes = ByteArray(16))

        val entries = FileSliceEntry.encode(
            listOf(
                FileSliceEntry(
                    entityId = "/photos/a.jpg",
                    name = "a.jpg",
                    isFolder = false,
                    size = 999_999,
                    contentId = "from-the-snapshot",
                    contentHash = "from-the-snapshot",
                    createdAtMillis = 1,
                    lastModifiedAtMillis = 1,
                ),
            ),
        )

        assertEquals(
            0,
            producer.applySlice(joiner.executionContext, Folder.Fields.FILES, "/photos", entries, isDeleted = { false }),
            "every row already existed, so nothing may be written",
        )
        val after = assertNotNull(
            producer.encodeSlice(joiner.executionContext, Folder.Fields.FILES, "/photos")
        )
        val file = FileSliceEntry.decode(after.entries)!!.single()
        assertEquals(16, file.size, "the real row's size, not the snapshot's claim")
        assertTrue(file.contentId != "from-the-snapshot", "the real row's content id survives")
    }

    @Test
    fun applySlice_ofBytesItCannotParseAppliesNothing() = runTest {
        // Degrade, never fail (§5.7.5): an unreadable slice must leave the device to bootstrap
        // by replaying the log, not half-populate a folder.
        val joiner = createFileProxy(backgroundScope)
        assertEquals(
            0,
            producer.applySlice(
                joiner.executionContext, Folder.Fields.FILES, "/photos", byteArrayOf(9, 9, 9), isDeleted = { false }
            ),
        )
        assertNull(
            producer.encodeSlice(joiner.executionContext, Folder.Fields.FILES, "/photos"),
            "not even the slice's own folder may be created from bytes that did not parse",
        )
    }

    @Test
    fun applySlice_ignoresARelationItDoesNotOwn() = runTest {
        val joiner = createFileProxy(backgroundScope)
        assertEquals(
            0,
            producer.applySlice(
                joiner.executionContext,
                "tenshu/timeline/moments",
                "2026-08",
                FileSliceEntry.encode(emptyList()), isDeleted = { false },
            ),
        )
    }

    private suspend fun ExecutionContext.createFolder(path: String) {
        executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to path.toTString()),
        )
    }

    private suspend fun ExecutionContext.upload(
        parent: String,
        name: String,
        bytes: ByteArray = ByteArray(16),
    ) {
        executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to parent.toTString(),
                UploadFile.Params.FILE_NAME to name.toTString(),
                UploadFile.Params.DATA to bytes.toTByteArray(),
            ),
        )
    }
}
