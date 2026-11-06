package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.entity.DataId
import org.tenshu.core.common.FakeClock
import org.tenshu.core.common.TestProxy
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * ⚠⚠ **Recency is recorded on read, or the ceiling evicts exactly the wrong blobs**
 * (`drive-xxlarge-thumbnail-rung.md` §4.7). `FileDatabase.lastModifiedMs` is a *write* time, so
 * without a stamp the hero rung a viewer opens every day stays the coldest thing on the device and
 * is evicted and pulled again forever.
 *
 * The stamp is deliberately coarse: a write per rendered tile would be the cost this whole cache
 * exists to avoid.
 */
class ThumbnailReadStampTest {

    private val clock = FakeClock(initial = 1_000_000L)

    @Test
    fun `reading a hero rung records when it was read`() = runTest {
        val proxy = createFileProxy(backgroundScope, clock = clock)
        val contentId = proxy.seed("photo.jpg", GetStoredThumbnail.CLASS_XLARGE)

        proxy.readClass("/photo.jpg", GetStoredThumbnail.CLASS_XLARGE)

        assertEquals(
            "1000000",
            proxy.stampText(contentId, GetStoredThumbnail.CLASS_XLARGE),
            "the read is stamped with the clock's now",
        )
    }

    @Test
    fun `a second read within the interval does not rewrite the stamp`() = runTest {
        val proxy = createFileProxy(backgroundScope, clock = clock)
        val contentId = proxy.seed("photo.jpg", GetStoredThumbnail.CLASS_XLARGE)
        proxy.readClass("/photo.jpg", GetStoredThumbnail.CLASS_XLARGE)

        clock.advance(ThumbnailCacheSidecars.READ_STAMP_INTERVAL_MS - 1)
        proxy.readClass("/photo.jpg", GetStoredThumbnail.CLASS_XLARGE)

        assertEquals(
            "1000000",
            proxy.stampText(contentId, GetStoredThumbnail.CLASS_XLARGE),
            "a grid re-render must not cost a write per tile",
        )

        clock.advance(2)
        proxy.readClass("/photo.jpg", GetStoredThumbnail.CLASS_XLARGE)

        assertEquals(
            (1_000_000L + ThumbnailCacheSidecars.READ_STAMP_INTERVAL_MS + 1).toString(),
            proxy.stampText(contentId, GetStoredThumbnail.CLASS_XLARGE),
            "past the interval the next read moves it",
        )
    }

    /** ⚠ `LIST` and `LARGE` are outside the ceiling, so stamping them would be pure write amplification. */
    @Test
    fun `reading a small class stamps nothing`() = runTest {
        val proxy = createFileProxy(backgroundScope, clock = clock)
        val contentId = proxy.seed("photo.jpg", GetStoredThumbnail.CLASS_LIST)

        proxy.readClass("/photo.jpg", GetStoredThumbnail.CLASS_LIST)

        assertNull(
            proxy.fileDatabase.fileSize(ThumbnailCacheSidecars.readStampPath(contentId, GetStoredThumbnail.CLASS_LIST)),
            "a list row's read is just a read",
        )
    }

    private suspend fun TestProxy.seed(name: String, thumbnailClass: String): String {
        executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf<String, Data>(
                UploadFile.Params.PARENT_PATH to "/".toTString(),
                UploadFile.Params.FILE_NAME to name.toTString(),
                UploadFile.Params.DATA to byteArrayOf(1, 2, 3, 4).toTByteArray(tContext),
            ),
        )
        val contentId = contentIdOf("/$name")
        fileDatabase.writeRawByteArray(FileBytesPropertyWriter.thumbnailPath(contentId, thumbnailClass), ByteArray(64))
        return contentId
    }

    private fun TestProxy.readClass(path: String, thumbnailClass: String) {
        val bytes = executionContext.execute<TByteArray?>(
            operationId = GetStoredThumbnail.ID,
            caller = File.create(tContext, path),
            params = mapOf(GetStoredThumbnail.Params.THUMBNAIL_CLASS to thumbnailClass.toTString()),
        )
        assertNotNull(bytes, "precondition: the stored class is readable")
    }

    private fun TestProxy.stampText(contentId: String, thumbnailClass: String): String? =
        fileDatabase.readBytes(ThumbnailCacheSidecars.readStampPath(contentId, thumbnailClass))?.get()?.decodeToString()

    private fun TestProxy.contentIdOf(path: String): String {
        val dataId = DataId.StringDataId.create(context = tContext, type = File.ID, keyValue = path)
        val row = assertNotNull(executionContext.database.getData(dataId), "the row for $path")
        val contentId = (executionContext.resolveProperty(row, propertyRef(File.ContentId.id)) as? TString)?.get()
        return assertNotNull(contentId?.takeIf { it.isNotEmpty() }, "the content id of $path")
    }
}
