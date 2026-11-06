package app.tenshu.control.documents

import app.tenshu.control.integration.utils.startAppIntegrationKoin
import app.tenshu.control.integration.utils.stopAppIntegrationKoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.fold
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.client.fileService
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DriveDocumentsSource] over the real in-process proxy chain — the reads Android's
 * `DriveDocumentsProvider` serves to the system file picker (`cross-app-file-access.md` P3).
 *
 * ⚠ `runBlocking`, not `runTest`: the source bounds every request with `withTimeout`, and under
 * `runTest`'s virtual clock a request waiting on the proxy's own dispatchers times out at once.
 */
class DriveDocumentsSourceTest {

    private lateinit var client: TenshuClient
    private lateinit var source: DriveDocumentsSource

    @Before
    fun setup() {
        client = startAppIntegrationKoin(clientId = "app.tenshu.control.test")
        source = DriveDocumentsSource(client = { GlobalContext.get().get<TenshuClient>() })
        runBlocking {
            val fs = client.fileService.defaultFileSystem
            fs.createFolder("/Docs").orFail()
            fs.uploadFile("/Docs", "notes.txt", NOTES, sourceModifiedAt = 1_700_000_000_000L).orFail()
            fs.uploadFile("/", "cat.jpg", byteArrayOf(1, 2, 3)).orFail()
        }
    }

    @After
    fun tearDown() {
        stopAppIntegrationKoin()
    }

    @Test
    fun theRootListsItsDirectChildrenOnly() = runBlocking {
        val children = source.children(DriveDocumentIds.ROOT).sortedBy { it.name }
        assertEquals(listOf("/Docs", "/cat.jpg"), children.map { it.documentId })
        val (docs, cat) = children
        assertTrue(docs.isFolder)
        assertEquals(0L, docs.size)
        assertFalse(docs.hasStillThumbnail)
        assertFalse(cat.isFolder)
        assertEquals(3L, cat.size)
        assertTrue(cat.hasStillThumbnail, "an image is a file a still is minted for")
    }

    @Test
    fun aFolderListsItsFiles_withTheFilesOwnDate() = runBlocking {
        val notes = source.children("/Docs").single()
        assertEquals("/Docs/notes.txt", notes.documentId)
        assertEquals("notes.txt", notes.name)
        assertEquals(NOTES.size.toLong(), notes.size)
        assertEquals(1_700_000_000_000L, notes.modifiedAtMillis, "the source's date, not the upload's")
        assertFalse(notes.hasStillThumbnail)
    }

    @Test
    fun oneDocumentReadsBackAsItIsListed() = runBlocking {
        assertEquals(source.children("/Docs").single(), source.document("/Docs/notes.txt"))
        assertTrue(source.document(DriveDocumentIds.ROOT).isFolder)
    }

    @Test
    fun absenceIsNotFound() = runBlocking {
        assertKind(DriveDocumentsException.Kind.NOT_FOUND) { source.document("/Docs/missing.txt") }
        assertKind(DriveDocumentsException.Kind.NOT_FOUND) { source.children("/Missing") }
        assertKind(DriveDocumentsException.Kind.NOT_FOUND) { source.document("/Docs/../cat.jpg") }
    }

    @Test
    fun contentReadsBackByRange() = runBlocking {
        val content = source.openContent("/Docs/notes.txt")
        try {
            assertEquals(NOTES.size.toLong(), content.size)
            assertContentEquals(NOTES.copyOfRange(0, 5), content.read(0, 5))
            assertContentEquals(NOTES.copyOfRange(6, NOTES.size), content.read(6, 4096), "short at the end")
            assertContentEquals(ByteArray(0), content.read(NOTES.size.toLong(), 10), "empty past it")
        } finally {
            content.close()
        }
    }

    @Test
    fun aFileWithNoStreamIsReadWholeOnlyUnderTheCap() = runBlocking {
        // The harness has no content stream for a local upload, so this is the whole-file path.
        val capped = DriveDocumentsSource(
            client = { GlobalContext.get().get<TenshuClient>() },
            maxWholeFileBytes = NOTES.size.toLong() - 1,
        )
        // Over the cap: refused, never loaded — Control must not OOM on a large unsynced file.
        assertKind(DriveDocumentsException.Kind.UNAVAILABLE) { capped.openContent("/Docs/notes.txt") }
        // At the cap: served.
        val exact = DriveDocumentsSource(
            client = { GlobalContext.get().get<TenshuClient>() },
            maxWholeFileBytes = NOTES.size.toLong(),
        )
        val content = exact.openContent("/Docs/notes.txt")
        try {
            assertEquals(NOTES.size.toLong(), content.size)
        } finally {
            content.close()
        }
    }

    @Test
    fun aFileWithNoStoredThumbnailHasNone() = runBlocking {
        assertNull(source.thumbnail("/Docs/notes.txt", 256), "no still is minted for text")
        assertNull(source.thumbnail(DriveDocumentIds.ROOT, 256))
    }

    private suspend fun assertKind(kind: DriveDocumentsException.Kind, block: suspend () -> Unit) {
        val e = assertFailsWith<DriveDocumentsException> { block() }
        assertEquals(kind, e.kind, e.message)
    }

    private fun TResult<*>.orFail() {
        fold(onSuccess = {}, onFailure = { error("setup write failed: $it") })
    }

    private companion object {
        val NOTES = "hello documents provider".encodeToByteArray()
    }
}
