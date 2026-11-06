package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OnDemandResolution
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.Orientation
import org.tenshu.core.file.ThumbnailXLarge
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The index pass's fallback for a photograph stored only on the relay (2026-09-27).
 *
 * ⚠⚠ Asserted by what it refuses as much as by what it returns: a pull on a metered connection
 * would spend a user's data on a whole library of previews, and a `large` rung would hand the face
 * detector a group photo at 256 px.
 */
class IndexPreviewTest {

    private val photo = "/Backup/Phone/Camera/p1.jpg"
    private val contentId = "content-p1"
    private val xlarge = ByteArray(1_000) { 1 }
    private val xxlarge = ByteArray(2_000) { 2 }

    private class FakeResolver : OnDemandResolution {
        val calls = mutableListOf<String>()
        var onResolve: (String) -> Unit = {}
        override suspend fun resolve(
            executionContext: ExecutionContext,
            entityId: DataId,
            propertyId: String,
            params: TList<Data>?,
        ): TResult<Data?> {
            calls += propertyId
            onResolve(propertyId)
            return TResult.Success.create(executionContext.context, null)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.setUp(orientation: Long? = 6): Pair<TestProxy, FakeResolver> {
        val resolver = FakeResolver()
        val proxy = createFileProxy(backgroundScope, deviceId = "device-1", onDemandResolution = resolver)
        val entity = Entity.create<File>(context = proxy.tContext, composite = File.create(proxy.tContext, photo)) {
            set(File.ContentId, contentId)
            orientation?.let { set(File.Orientation, it) }
        }
        proxy.executionContext.saveData(entity)
        return proxy to resolver
    }

    private fun TestProxy.writeRung(thumbnailClass: String, bytes: ByteArray) =
        fileDatabase.writeRawByteArray(FileBytesPropertyWriter.thumbnailPath(contentId, thumbnailClass), bytes)

    @Test
    fun aStoredXLargeIsReturnedFirst() = runTest {
        val (proxy, resolver) = setUp(orientation = 6)
        proxy.writeRung(GetStoredThumbnail.CLASS_XLARGE, xlarge)
        proxy.writeRung(GetStoredThumbnail.CLASS_XXLARGE, xxlarge)

        val preview = assertNotNull(proxy.executionContext.previewForIndex(proxy.fileDatabase, photo, mayPull = true))

        assertContentEquals(xlarge, preview.bytes, "xlarge first: it is enough, and the smaller read")
        assertEquals(0, resolver.calls.size, "nothing is pulled when a preview is already here")
    }

    @Test
    fun xxLargeIsTheFallbackWhenOnlyItIsStored() = runTest {
        val (proxy, _) = setUp()
        proxy.writeRung(GetStoredThumbnail.CLASS_XXLARGE, xxlarge)

        assertContentEquals(xxlarge, proxy.executionContext.previewForIndex(proxy.fileDatabase, photo, mayPull = false)?.bytes)
    }

    /** ⚠ `large` is 256 px: a group photo's faces would be a handful of pixels. Never used. */
    @Test
    fun aLargeRungAloneIsNotEnough() = runTest {
        val (proxy, _) = setUp()
        proxy.writeRung(GetStoredThumbnail.CLASS_LARGE, ByteArray(300) { 3 })

        assertNull(proxy.executionContext.previewForIndex(proxy.fileDatabase, photo, mayPull = false))
    }

    /** ⚠⚠ A metered connection must never pull: that is a whole library of previews on mobile data. */
    @Test
    fun withoutPermissionToPullNothingIsFetched() = runTest {
        val (proxy, resolver) = setUp()

        assertNull(proxy.executionContext.previewForIndex(proxy.fileDatabase, photo, mayPull = false))
        assertEquals(0, resolver.calls.size)
    }

    @Test
    fun whenAllowedAMissingXLargeIsPulledAndServed() = runTest {
        val (proxy, resolver) = setUp()
        resolver.onResolve = { propertyId ->
            if (propertyId == File.ThumbnailXLarge.id) proxy.writeRung(GetStoredThumbnail.CLASS_XLARGE, xlarge)
        }

        val preview = proxy.executionContext.previewForIndex(proxy.fileDatabase, photo, mayPull = true)

        assertEquals(listOf(File.ThumbnailXLarge.id), resolver.calls)
        assertContentEquals(xlarge, preview?.bytes)
    }
}
