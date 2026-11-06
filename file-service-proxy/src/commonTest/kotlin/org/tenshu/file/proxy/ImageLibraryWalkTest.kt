package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which files an index pass is offered (`inference-service.md` IN-D6).
 *
 * ⚠⚠ This walk decides what a model is *run over*, so every exclusion here is a decision about the
 * user's battery and their privacy — a video handed to an image model is wasted work, and a trashed
 * photograph coming back as a search result is a deletion that did not hold.
 */
class ImageLibraryWalkTest {

    private fun serviceOf(proxy: TestProxy): FileService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        )
        return env.getService(FileService.ID)
    }

    private suspend fun FileService.upload(parent: String, name: String) {
        assertTrue(
            defaultFileSystem.uploadFile(parent, name, byteArrayOf(1, 2, 3)) is TResult.Success,
            "seeding $parent/$name should succeed",
        )
    }

    @Test
    fun everyImageInTheTreeIsFoundIncludingNestedOnes() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val service = serviceOf(proxy)
        service.defaultFileSystem.createFolder("/Holiday")
        service.upload("/", "top.jpg")
        service.upload("/Holiday", "nested.png")

        val paths = proxy.executionContext.collectImagePaths()

        assertEquals(listOf("/Holiday/nested.png", "/top.jpg"), paths.sorted())
    }

    /**
     * ⚠⚠ A deleted photograph must not be indexed. Otherwise it comes back as a semantic search
     * result — a deletion that looks like it held and did not.
     */
    @Test
    fun trashedImagesAreSkipped() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val service = serviceOf(proxy)
        service.defaultFileSystem.createFolder("/Trash")
        service.upload("/", "kept.jpg")
        service.upload("/Trash", "deleted.jpg")

        assertEquals(listOf("/kept.jpg"), proxy.executionContext.collectImagePaths())
    }

    /**
     * ⚠ The image test is the **name**'s answer, the same derivation Drive uses for row glyphs. A
     * video or a document handed to an image model is bytes read and battery spent for nothing.
     */
    @Test
    fun nonImagesAreSkipped() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val service = serviceOf(proxy)
        service.upload("/", "photo.jpg")
        service.upload("/", "clip.mp4")
        service.upload("/", "notes.pdf")

        assertEquals(listOf("/photo.jpg"), proxy.executionContext.collectImagePaths())
    }

    /** ⚠ An empty library is an empty list, not a failure — the state of most devices on day one. */
    @Test
    fun anEmptyLibraryYieldsNothing() = runTest {
        val proxy = createFileProxy(backgroundScope)

        assertEquals(emptyList(), proxy.executionContext.collectImagePaths())
    }

    /**
     * ⚠⚠ A walk that read every row says so — empty folders, trashed files and non-images are
     * decisions, not gaps — or the reconcile that depends on it would never run.
     */
    @Test
    fun aFullyReadTreeIsComplete() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val service = serviceOf(proxy)
        service.defaultFileSystem.createFolder("/Empty")
        service.defaultFileSystem.createFolder("/Trash")
        service.upload("/", "photo.jpg")
        service.upload("/", "notes.pdf")
        service.upload("/Trash", "deleted.jpg")

        val walk = proxy.executionContext.walkImageLibrary()

        assertEquals(listOf("/photo.jpg"), walk.paths)
        assertTrue(walk.complete)
    }

    /**
     * ⚠⚠ A tree the walk could not read is a PARTIAL walk — here the root row itself is gone, the
     * same `getData`-is-null branch a missing folder or file row takes. It must not be taken for an
     * empty library: a reconcile trusting it would erase the derived data of every photograph the
     * user still has.
     */
    @Test
    fun aRowTheWalkCannotReadMakesItIncomplete() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val service = serviceOf(proxy)
        service.upload("/", "kept.jpg")
        assertTrue(proxy.executionContext.walkImageLibrary().complete, "precondition")

        proxy.executionContext.database.deleteData(
            DataId.StringDataId.create(context = proxy.tContext, type = File.ID, keyValue = "/"),
        )
        val walk = proxy.executionContext.walkImageLibrary()

        assertEquals(emptyList(), walk.paths)
        assertFalse(walk.complete)
    }

    /**
     * ⚠⚠ A folder whose list names a child with no row — a device mid-sync, or a row lost — is a
     * PARTIAL walk, though the store's read silently skips the member. The rest is still listed,
     * but it must not be taken for the whole library.
     */
    @Test
    fun aDanglingChildMakesTheWalkIncompleteButKeepsTheRest() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val service = serviceOf(proxy)
        service.defaultFileSystem.createFolder("/Holiday")
        service.upload("/", "kept.jpg")
        service.upload("/Holiday", "not-arrived.jpg")
        // The row goes; the folder's list still references it (`deleteData` does not cascade).
        proxy.executionContext.database.deleteData(
            DataId.StringDataId.create(context = proxy.tContext, type = File.ID, keyValue = "/Holiday/not-arrived.jpg"),
        )

        val walk = proxy.executionContext.walkImageLibrary()

        assertEquals(listOf("/kept.jpg"), walk.paths)
        assertFalse(walk.complete)
    }

    /**
     * ⚠⚠ **`complete` is not a snapshot.** `MoveFile` unlinks its source from the
     * old parent before it saves the destination, so a walk landing in that window reads nothing
     * unreadable and still misses a photograph the user has. This pins both halves of the answer:
     * the walk really does report such a list as complete — so nothing may delete on it alone — and
     * the point read every reconcile owes ([livePathsAmong]) still finds the row.
     */
    @Test
    fun aFileUnlinkedByAMoveInFlightIsMissedByACompleteWalkAndFoundByThePointRead() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val service = serviceOf(proxy)
        service.defaultFileSystem.createFolder("/Holiday")
        service.upload("/Holiday", "moving.jpg")
        service.upload("/", "still.jpg")

        // `MoveFileImplementation`'s first write, and nothing after it: the state a walk can meet.
        val oldParent = DataId.StringDataId.create(type = File.ID, keyValue = "/Holiday")
        proxy.executionContext.database.removeFromArray(
            reference = ArrayDatabaseReference(
                arrayId = DataId.EntityFieldDataId.create(
                    type = TList.ID,
                    parentEntity = oldParent as DataId.EntityDataId,
                    fieldRef = Folder.Fields.FILES,
                ),
                isEntity = false,
                elementSchemaId = File.ID,
            ),
            dataId = DataId.StringDataId.create(type = File.ID, keyValue = "/Holiday/moving.jpg"),
        )

        val walk = proxy.executionContext.walkImageLibrary()

        assertEquals(listOf("/still.jpg"), walk.paths)
        assertTrue(walk.complete, "the walk read no gap — which is why it cannot be the only proof")
        assertEquals(
            setOf("/Holiday/moving.jpg"),
            proxy.executionContext.livePathsAmong(listOf("/Holiday/moving.jpg")),
            "the row still exists, so a reconcile must spare it",
        )
    }
}
