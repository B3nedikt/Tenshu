package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.FakeClock
import org.tenshu.core.common.TestProxy
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.ThumbnailXLarge
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.sync.FileSyncPreferences
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.system.OfflineKeeper
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.file.impl.OfflineKeeperImplementation
import org.tenshu.file.test.createFileProxy
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The keeper's **thumbnail** sweep end to end (`drive-xxlarge-thumbnail-rung.md` D-20):
 * the device's stored ceiling, the derived blob addresses, the bytes leaving the store, and the
 * sentinel that stops the backfill minting them straight back.
 *
 * `ThumbnailCacheEvictionTest` covers the decision; this covers what a pure function cannot —
 * that the preference is read, that only the large classes are offered, and that a sweep leaves the
 * right trace.
 */
class OfflineKeeperThumbnailEvictionTest {

    private val deviceId = "test-device"
    private val clock = FakeClock(initial = 1_000_000L)

    @Test
    fun `evicts the coldest large thumbnail once the ceiling is exceeded`() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId, clock = clock)
        // ⚠⚠ The warm blob is written FIRST, so the in-memory store's write counter makes it look
        // colder. Only the `.read` stamps say otherwise — which is the thing under test. Written the
        // other way round, this passes with the stamp ignored entirely.
        val warm = proxy.uploadWithThumbnails("warm.jpg", readStampMs = 900_000L)
        val cold = proxy.uploadWithThumbnails("cold.jpg", readStampMs = 10L)
        proxy.writeSetting(FileSyncPreferences.MaxThumbnailBytes, 400L)

        proxy.runKeeper()

        assertNull(
            proxy.fileDatabase.fileSize(FileBytesPropertyWriter.thumbnailPath(cold, GetStoredThumbnail.CLASS_XLARGE)),
            "600 bytes of hero rungs against a 400-byte ceiling ⇒ the coldest blob goes",
        )
        assertNotNull(
            proxy.fileDatabase.fileSize(FileBytesPropertyWriter.thumbnailPath(warm, GetStoredThumbnail.CLASS_XLARGE)),
            "the sweep stops once under the ceiling rather than flushing the cache",
        )
        assertNotNull(
            proxy.fileDatabase.fileSize(ThumbnailCacheSidecars.evictedPath(cold, GetStoredThumbnail.CLASS_XLARGE)),
            "a deliberate eviction leaves its sentinel, or the backfill mints it straight back",
        )
        assertNull(
            proxy.fileDatabase.fileSize(ThumbnailCacheSidecars.readStampPath(cold, GetStoredThumbnail.CLASS_XLARGE)),
            "the blob's read stamp goes with it",
        )
    }

    /** ⚠⚠ A row re-blanks without its small classes, and at 64/48 KiB they are not the problem. */
    @Test
    fun `never evicts the list or large classes`() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId, clock = clock)
        val contentId = proxy.uploadWithThumbnails("photo.jpg", readStampMs = 10L)
        proxy.writeSetting(FileSyncPreferences.MaxThumbnailBytes, 0L)

        proxy.runKeeper()

        assertNull(
            proxy.fileDatabase.fileSize(FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_XLARGE)),
            "a ceiling of 0 keeps no hero rung",
        )
        for (small in listOf(GetStoredThumbnail.CLASS_LIST, GetStoredThumbnail.CLASS_LARGE)) {
            assertNotNull(
                proxy.fileDatabase.fileSize(FileBytesPropertyWriter.thumbnailPath(contentId, small)),
                "$small is outside the ceiling entirely",
            )
        }
    }

    @Test
    fun `evicts nothing under the shipped default`() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId, clock = clock)
        val contentId = proxy.uploadWithThumbnails("photo.jpg", readStampMs = 10L)

        proxy.runKeeper()

        assertNotNull(
            proxy.fileDatabase.fileSize(FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_XLARGE)),
            "300 bytes against the 1 GiB default ⇒ nothing moves",
        )
    }

    /**
     * ⚠⚠ "Keep this offline" cannot mean "and blank its picture". The hub pins `/` and runs this pass
     * every backup, so without the exemption it would trim the one archive that holds everything.
     */
    @Test
    fun `never evicts a pinned file's thumbnails`() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId, clock = clock)
        val contentId = proxy.uploadWithThumbnails("kept.jpg", readStampMs = 10L)
        proxy.writeSetting(FileSyncPreferences.DefaultPolicy, SyncPolicy.KEEP_OFFLINE)
        proxy.writeSetting(FileSyncPreferences.MaxThumbnailBytes, 0L)

        proxy.runKeeper()

        assertNotNull(
            proxy.fileDatabase.fileSize(FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_XLARGE)),
            "a pinned file keeps its classes whatever the ceiling says",
        )
    }

    /**
     * ⚠⚠ Bytes the relay has not taken yet leave no sentinel: nothing published them, so a byte
     * holder must mint the class again rather than believe it is safe there.
     */
    @Test
    fun `a blob whose bytes are still queued leaves no sentinel`() = runTest {
        val queued = QueuedContentEmitter()
        val proxy = createFileProxy(
            backgroundScope,
            deviceId = deviceId,
            clock = clock,
            syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = queued },
        )
        val contentId = proxy.uploadWithThumbnails("photo.jpg", readStampMs = 10L)
        queued.queuedFor += "/photo.jpg" to File.ThumbnailXLarge.id
        proxy.writeSetting(FileSyncPreferences.MaxThumbnailBytes, 0L)

        proxy.runKeeper()

        assertNull(
            proxy.fileDatabase.fileSize(FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_XLARGE)),
            "it is still evicted — the journal has no ceiling of its own",
        )
        assertNull(
            proxy.fileDatabase.fileSize(ThumbnailCacheSidecars.evictedPath(contentId, GetStoredThumbnail.CLASS_XLARGE)),
            "but nothing published it, so it must not be marked as safely on the relay",
        )
    }

    /**
     * ⚠⚠ The only self-heal that fires for a pulled class: inbound content is promoted by
     * `FileContentManifestApplier` with a `moveFile`, which never reaches an `ExternalPropertyWriter`.
     */
    @Test
    fun `reading a class that is present again clears its stale sentinel`() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId, clock = clock)
        val contentId = proxy.uploadWithThumbnails("photo.jpg", readStampMs = 10L)
        proxy.writeSetting(FileSyncPreferences.MaxThumbnailBytes, 0L)
        proxy.runKeeper()
        assertNotNull(proxy.fileDatabase.fileSize(ThumbnailCacheSidecars.evictedPath(contentId, GetStoredThumbnail.CLASS_XLARGE)))

        // The class comes back the way a pull lands it: bytes at the blob path, nothing else touched.
        proxy.fileDatabase.writeRawByteArray(
            FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_XLARGE),
            ByteArray(300),
        )
        proxy.executionContext.execute<org.tenshu.core.TByteArray?>(
            operationId = GetStoredThumbnail.ID,
            caller = File.create(proxy.tContext, "/photo.jpg"),
            params = mapOf(GetStoredThumbnail.Params.THUMBNAIL_CLASS to GetStoredThumbnail.CLASS_XLARGE.toTString()),
        )

        assertNull(
            proxy.fileDatabase.fileSize(ThumbnailCacheSidecars.evictedPath(contentId, GetStoredThumbnail.CLASS_XLARGE)),
            "a blob that is here is not 'deliberately gone'",
        )
    }

    /**
     * ⚠⚠ `FileDatabase.deleteFile` returns `Unit` and its interface default is a no-op, so a blob that
     * survives the delete must not be marked evicted — that would tell the backfill it is safely on
     * the relay while this device freed nothing.
     */
    @Test
    fun `a blob that could not be deleted is not marked evicted`() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId, clock = clock)
        val contentId = proxy.uploadWithThumbnails("photo.jpg", readStampMs = 10L)
        proxy.writeSetting(FileSyncPreferences.MaxThumbnailBytes, 0L)
        proxy.additionalImplementations[OfflineKeeper.ID] = OfflineKeeperImplementation(
            fileDatabase = DeleteRefusing(
                delegate = proxy.fileDatabase,
                refused = FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_XLARGE),
            ),
            deviceId = deviceId,
        )

        proxy.runKeeper()

        assertNotNull(
            proxy.fileDatabase.fileSize(FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_XLARGE)),
            "precondition: the delete did not take",
        )
        assertNull(
            proxy.fileDatabase.fileSize(ThumbnailCacheSidecars.evictedPath(contentId, GetStoredThumbnail.CLASS_XLARGE)),
            "and nothing claims it was evicted",
        )
    }

    /** Uploads a file and seeds its stored classes: 300 bytes of hero rung, 50 each of the small ones. */
    private suspend fun TestProxy.uploadWithThumbnails(name: String, readStampMs: Long): String {
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
        fileDatabase.writeRawByteArray(
            FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_XLARGE),
            ByteArray(300),
        )
        for (small in listOf(GetStoredThumbnail.CLASS_LIST, GetStoredThumbnail.CLASS_LARGE)) {
            fileDatabase.writeRawByteArray(FileBytesPropertyWriter.thumbnailPath(contentId, small), ByteArray(50))
        }
        fileDatabase.writeRawByteArray(
            ThumbnailCacheSidecars.readStampPath(contentId, GetStoredThumbnail.CLASS_XLARGE),
            readStampMs.toString().encodeToByteArray(),
        )
        return contentId
    }

    private fun TestProxy.contentIdOf(path: String): String {
        val dataId = DataId.StringDataId.create(context = tContext, type = File.ID, keyValue = path)
        val row = assertNotNull(executionContext.database.getData(dataId), "the row for $path")
        val contentId = (executionContext.resolveProperty(row, propertyRef(File.ContentId.id)) as? TString)?.get()
        return assertNotNull(contentId?.takeIf { it.isNotEmpty() }, "the content id of $path")
    }

    private suspend fun TestProxy.runKeeper() {
        executionContext.executeAsync<Data>(operationId = OfflineKeeper.ID, caller = null, params = null)
    }

    private fun <T> TestProxy.writeSetting(preference: Preference<T>, value: T) {
        executionContext.saveData(
            Entity.create<DeviceSetting>(
                context = tContext,
                composite = DeviceSetting.create(
                    context = tContext,
                    deviceId = deviceId,
                    preferenceId = preference.id,
                    value = preference.adapter.toData(value, tContext),
                ),
            ),
        )
    }

    /** An emitter that reports the given `(file path, property)` pairs as still queued for the relay. */
    private class QueuedContentEmitter : FileContentSyncEmitter {
        val queuedFor = mutableSetOf<Pair<String, String>>()

        override suspend fun emitFileContent(targetEntityId: DataId, propertyId: String, filePath: String): Boolean = true

        override suspend fun shareContentToRealm(contentId: String, targetRealmOwnerId: String, targetPropertyId: String): Boolean = true

        override suspend fun queuedContentBytesFor(targetEntityId: DataId, propertyId: String): Long =
            if (hasQueuedContentFor(targetEntityId, propertyId)) 1_024L else 0L

        override suspend fun hasQueuedContentFor(targetEntityId: DataId, propertyId: String): Boolean =
            (targetEntityId as DataId.StringDataId).keyValue.get() to propertyId in queuedFor
    }

    /** A [FileDatabase] whose delete of one path silently does nothing — the interface's own default. */
    private class DeleteRefusing(private val delegate: FileDatabase, private val refused: String) : FileDatabase by delegate {
        override fun deleteFile(filePath: String) {
            if (filePath != refused) delegate.deleteFile(filePath)
        }
    }

}
