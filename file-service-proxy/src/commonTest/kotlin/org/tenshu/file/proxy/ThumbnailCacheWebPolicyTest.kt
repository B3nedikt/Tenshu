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
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.ThumbnailXLarge
import org.tenshu.core.file.sync.FileSyncPreferences
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.impl.ThumbnailCachePolicy
import org.tenshu.file.impl.dropPulledThumbnail
import org.tenshu.file.impl.evictThumbnailCache
import org.tenshu.file.test.createFileProxy
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The thumbnail-cache pass run ALONE, as the web runtime runs it (`drive-xxlarge-thumbnail-rung.md`
 * §4.7, web carve-out): the policy's default ceiling, a stored row over it, the rungs a browser still
 * owes the relay, and unknown recency read as cold. Plus the grid's pull-draw-drop.
 * `OfflineKeeperThumbnailEvictionTest` pins the same pass inside the keeper, with native's policy.
 */
class ThumbnailCacheWebPolicyTest {

    private val deviceId = "test-device"
    private val clock = FakeClock(initial = 1_000_000L)

    // The web's shape at a size a test can fill: 400 bytes instead of 64 MiB.
    private val small = ThumbnailCachePolicy(defaultMaxBytes = 400L, keepPendingPublish = true, unknownRecencyIsCold = true)

    @Test
    fun `the web default is 64 MiB - RAM, since the JS store hydrates every byte`() {
        assertEquals(64L * 1024 * 1024, ThumbnailCachePolicy.WEB.defaultMaxBytes)
        assertEquals(FileSyncPreferences.DEFAULT_MAX_THUMBNAIL_BYTES, ThumbnailCachePolicy.NATIVE.defaultMaxBytes)
    }

    @Test
    fun `with no stored row the policy default is the ceiling`() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId, clock = clock)
        val warm = proxy.uploadWithThumbnails("warm.jpg", readStampMs = 900_000L)
        val cold = proxy.uploadWithThumbnails("cold.jpg", readStampMs = 10L)

        proxy.sweep(small)

        assertNull(proxy.xlarge(cold), "600 bytes against the policy's 400 ⇒ the coldest goes")
        assertNotNull(proxy.xlarge(warm))
    }

    @Test
    fun `a stored MaxThumbnailBytes row wins over the policy default`() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId, clock = clock)
        val a = proxy.uploadWithThumbnails("a.jpg", readStampMs = 10L)
        val b = proxy.uploadWithThumbnails("b.jpg", readStampMs = 20L)
        proxy.writeSetting(FileSyncPreferences.MaxThumbnailBytes, 10_000L)

        proxy.sweep(small)

        assertNotNull(proxy.xlarge(a))
        assertNotNull(proxy.xlarge(b))
    }

    /** ⚠⚠ No backfill runs in a browser: a rung it never sent, evicted, is gone in a web-only realm. */
    @Test
    fun `a rung still owed to the relay is kept, however cold`() = runTest {
        val queued = QueuedContentEmitter()
        val proxy = createFileProxy(
            backgroundScope,
            deviceId = deviceId,
            clock = clock,
            syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = queued },
        )
        val owed = proxy.uploadWithThumbnails("owed.jpg", readStampMs = 10L)
        queued.queuedFor += "/owed.jpg" to File.ThumbnailXLarge.id
        proxy.writeSetting(FileSyncPreferences.MaxThumbnailBytes, 0L)

        proxy.sweep(small, queued)

        assertNotNull(proxy.xlarge(owed), "unsent ⇒ not a candidate on the web")
        // Native's policy evicts it (a byte holder mints it again) — the difference is the policy's.
        proxy.sweep(ThumbnailCachePolicy.NATIVE, queued)
        assertNull(proxy.xlarge(owed))
    }

    /** `IndexedDbFileDatabase` reports no write time: an unstamped rung was minted here and never viewed. */
    @Test
    fun `on the web a rung with no recency is the coldest`() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId, clock = clock)
        val viewed = proxy.uploadWithThumbnails("viewed.jpg", readStampMs = 10L)
        val neverViewed = proxy.uploadWithThumbnails("never.jpg", readStampMs = null)
        proxy.unknownWriteTimes = true

        proxy.sweep(small)

        assertNull(proxy.xlarge(neverViewed), "no stamp, no write time ⇒ cold on the web")
        assertNotNull(proxy.xlarge(viewed))
    }

    @Test
    fun `a pulled rung dropped after drawing leaves no blob, no stamp and no sentinel`() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId, clock = clock)
        val contentId = proxy.uploadWithThumbnails("tile.jpg", readStampMs = 10L)

        dropPulledThumbnail(proxy.fileDatabase, contentId, GetStoredThumbnail.CLASS_XLARGE)

        assertNull(proxy.xlarge(contentId))
        assertNull(proxy.fileDatabase.fileSize(ThumbnailCacheSidecars.readStampPath(contentId, GetStoredThumbnail.CLASS_XLARGE)))
        assertNull(
            proxy.fileDatabase.fileSize(ThumbnailCacheSidecars.evictedPath(contentId, GetStoredThumbnail.CLASS_XLARGE)),
            "the relay has it — it was just pulled from there",
        )
        for (keep in listOf(GetStoredThumbnail.CLASS_LIST, GetStoredThumbnail.CLASS_LARGE)) {
            assertNotNull(proxy.fileDatabase.fileSize(FileBytesPropertyWriter.thumbnailPath(contentId, keep)))
        }
    }

    // ── harness (the same seeding as OfflineKeeperThumbnailEvictionTest) ─────────────────────────

    private var TestProxy.unknownWriteTimes: Boolean
        get() = unknownTimes.contains(this)
        set(value) {
            if (value) unknownTimes += this else unknownTimes -= this
        }
    private val unknownTimes = mutableSetOf<TestProxy>()

    private suspend fun TestProxy.sweep(policy: ThumbnailCachePolicy, emitter: FileContentSyncEmitter? = null) {
        val db = if (unknownWriteTimes) NoWriteTimes(fileDatabase) else fileDatabase
        executionContext.evictThumbnailCache(
            fileDatabase = db,
            deviceId = deviceId,
            syncBlockEmitterHolder = emitter?.let { SyncBlockEmitterHolder().apply { fileContentEmitter = it } },
            policy = policy,
        )
    }

    private fun TestProxy.xlarge(contentId: String): Long? =
        fileDatabase.fileSize(FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_XLARGE))

    private suspend fun TestProxy.uploadWithThumbnails(name: String, readStampMs: Long?): String {
        executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf<String, Data>(
                UploadFile.Params.PARENT_PATH to "/".toTString(),
                UploadFile.Params.FILE_NAME to name.toTString(),
                UploadFile.Params.DATA to byteArrayOf(1, 2, 3, 4).toTByteArray(tContext),
            ),
        )
        val dataId = DataId.StringDataId.create(context = tContext, type = File.ID, keyValue = "/$name")
        val row = assertNotNull(executionContext.database.getData(dataId))
        val contentId = assertNotNull((executionContext.resolveProperty(row, propertyRef(File.ContentId.id)) as? TString)?.get())
        fileDatabase.writeRawByteArray(FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_XLARGE), ByteArray(300))
        for (small in listOf(GetStoredThumbnail.CLASS_LIST, GetStoredThumbnail.CLASS_LARGE)) {
            fileDatabase.writeRawByteArray(FileBytesPropertyWriter.thumbnailPath(contentId, small), ByteArray(50))
        }
        if (readStampMs != null) {
            fileDatabase.writeRawByteArray(
                ThumbnailCacheSidecars.readStampPath(contentId, GetStoredThumbnail.CLASS_XLARGE),
                readStampMs.toString().encodeToByteArray(),
            )
        }
        return contentId
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

    /** The JS store's shape: no write times (`IndexedDbFileDatabase` does not override `lastModifiedMs`). */
    private class NoWriteTimes(private val delegate: org.tenshu.core.common.proxy.FileDatabase) :
        org.tenshu.core.common.proxy.FileDatabase by delegate {
        override fun lastModifiedMs(filePath: String): Long? = null
    }

    private class QueuedContentEmitter : FileContentSyncEmitter {
        val queuedFor = mutableSetOf<Pair<String, String>>()
        override suspend fun emitFileContent(targetEntityId: DataId, propertyId: String, filePath: String): Boolean = true
        override suspend fun shareContentToRealm(contentId: String, targetRealmOwnerId: String, targetPropertyId: String): Boolean = true
        override suspend fun queuedContentBytesFor(targetEntityId: DataId, propertyId: String): Long =
            if (hasQueuedContentFor(targetEntityId, propertyId)) 1_024L else 0L
        override suspend fun hasQueuedContentFor(targetEntityId: DataId, propertyId: String): Boolean =
            (targetEntityId as DataId.StringDataId).keyValue.get() to propertyId in queuedFor
    }
}
