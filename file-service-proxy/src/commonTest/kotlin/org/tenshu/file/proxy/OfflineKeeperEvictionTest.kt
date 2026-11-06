package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.sync.FileSyncPreferences
import org.tenshu.core.file.sync.PathSyncOverride
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.system.OfflineKeeper
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.core.TString
import org.tenshu.core.file.ContentId as FileContentIdProperty
import org.tenshu.core.schema.propertyRef
import org.tenshu.file.test.createFileProxy
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The offline keeper's **evict half** end to end (`retention-quota-model.md`
 * Face B): the wiring from the device's stored ceiling, through the tree walk, to
 * bytes actually leaving the `FileDatabase`.
 *
 * `LocalCacheEvictionTest` covers the *decision*; this covers the parts a pure
 * function cannot — that the preference is read at all, and that eviction drops
 * bytes without touching the entity. A silently-misread preference would disable
 * the whole feature while every unit test stayed green.
 */
class OfflineKeeperEvictionTest {

    private val deviceId = "test-device"

    @Test
    fun `evicts the coldest file once the ceiling is exceeded`() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)

        // 4 bytes each; written oldest-first, and InMemoryFileDatabase stamps
        // write order, so /cold.bin is the coldest.
        proxy.upload("cold.bin")
        proxy.upload("warm.bin")
        proxy.writeSetting(FileSyncPreferences.MaxLocalBytes, 6L)

        proxy.runKeeper()

        assertNull(
            proxy.fileDatabase.fileSize("/cold.bin"),
            "8 bytes held against a 6-byte ceiling ⇒ the coldest file's bytes go",
        )
        assertNotNull(
            proxy.fileDatabase.fileSize("/warm.bin"),
            "the sweep must stop once under the ceiling, not flush the cache",
        )
        assertNotNull(
            proxy.database.getData(proxy.fileId("/cold.bin")),
            "eviction is not a delete — the File entity and its metadata survive",
        )
    }

    @Test
    fun `evicts nothing under the shipped defaults`() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)
        proxy.upload("a.bin")
        proxy.upload("b.bin")

        // No settings written at all: the ceiling defaults to 0 (unlimited) and
        // InMemoryFileDatabase reports no free-space figure, so the floor is
        // skipped. ⚠ This guards against a default that starts
        // deleting people's cached content the first time the keeper runs.
        proxy.runKeeper()

        assertNotNull(proxy.fileDatabase.fileSize("/a.bin"))
        assertNotNull(proxy.fileDatabase.fileSize("/b.bin"))
    }

    /**
     * ⚠⚠ Bytes still owed to the relay are not cache (`retention-quota-model.md` §2), and evicting
     * them loses the only copy — the journalled emit would name a `filePath` that no longer exists, leaving the
     * `File` row pointing at content that is nowhere.
     *
     * The unsent file is uploaded **first**, so it is also the coldest and coldest-first ordering
     * alone would take it. Only the journal probe can save it — the same construction the pinned
     * test uses, and what makes this fail rather than pass vacuously when the guard is deleted.
     */
    @Test
    fun `spares a file whose bytes have not reached the relay`() = runTest {
        val emitter = QueuedContentEmitter()
        val proxy = createFileProxy(
            backgroundScope,
            deviceId = deviceId,
            syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = emitter },
        )
        proxy.upload("unsent.bin")
        proxy.upload("sent.bin")
        emitter.queuedFor += "/unsent.bin" to File.Data.id
        // ⚠ 2 bytes, not 6: the unsent file is excluded from the candidate set, so the cache this
        // ceiling measures is /sent.bin alone. A 6-byte ceiling would already be satisfied.
        proxy.writeSetting(FileSyncPreferences.MaxLocalBytes, 2L)

        proxy.runKeeper()

        assertNotNull(
            proxy.fileDatabase.fileSize("/unsent.bin"),
            "bytes the relay has never seen are not cache, even as the coldest thing on disk",
        )
        assertNull(
            proxy.fileDatabase.fileSize("/sent.bin"),
            "the ceiling is still enforced — it is spent on content the relay already holds",
        )
    }

    /**
     * ⚠⚠ The guard asks whether a journal **entry exists**, never whether it weighs anything.
     * `queuedContentBytesFor` is a quota figure and answers `0` for a live intent whose file is
     * empty or whose path no longer sizes — so a `> 0L` guard would read "still owed" as "safely
     * sent" and delete exactly the row it exists to spare.
     */
    @Test
    fun `spares an unsent file the queued-bytes figure reports as weightless`() = runTest {
        val emitter = QueuedContentEmitter()
        val proxy = createFileProxy(
            backgroundScope,
            deviceId = deviceId,
            syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = emitter },
        )
        proxy.upload("weightless.bin")
        proxy.upload("sent.bin")
        emitter.queuedFor += "/weightless.bin" to File.Data.id
        emitter.weighNothing = true
        proxy.writeSetting(FileSyncPreferences.MaxLocalBytes, 2L)

        proxy.runKeeper()

        assertNotNull(
            proxy.fileDatabase.fileSize("/weightless.bin"),
            "a journalled intent that measures 0 is still an intent",
        )
        assertNull(proxy.fileDatabase.fileSize("/sent.bin"), "and the sweep still ran")
    }

    @Test
    fun `spends the unpinned file and spares the pinned one`() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)

        // The PINNED file is written first, so it is also the COLDEST — coldest-first
        // ordering alone would take it. Only the pin can save it, which is what makes
        // this a real assertion rather than one a no-op sweep would also satisfy.
        proxy.upload("pinned.bin")
        proxy.upload("loose.bin")
        proxy.writeSetting(
            FileSyncPreferences.Overrides,
            listOf(PathSyncOverride("/pinned.bin", SyncPolicy.KEEP_OFFLINE)),
        )
        // ⚠ 2 bytes, not 6: the ceiling measures the *cache* (the 4-byte
        // /loose.bin) and ignores the pin entirely, so a 6-byte ceiling would be
        // satisfied already and nothing would happen.
        proxy.writeSetting(FileSyncPreferences.MaxLocalBytes, 2L)

        proxy.runKeeper()

        assertNotNull(
            proxy.fileDatabase.fileSize("/pinned.bin"),
            "KEEP_OFFLINE is exempt even when it is the coldest thing on disk",
        )
        assertNull(
            proxy.fileDatabase.fileSize("/loose.bin"),
            "the ceiling is still enforced — it is spent on evictable content",
        )
    }

    private suspend fun TestProxy.upload(name: String) {
        executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(),
                UploadFile.Params.FILE_NAME to name.toTString(),
                UploadFile.Params.DATA to byteArrayOf(1, 2, 3, 4).toTByteArray(tContext),
            ),
        )
    }

    private suspend fun TestProxy.runKeeper() {
        executionContext.executeAsync<Data>(
            operationId = OfflineKeeper.ID,
            caller = null,
            params = null,
        )
    }

    private fun <T> TestProxy.writeSetting(
        preference: Preference<T>,
        value: T,
    ) {
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

    private fun TestProxy.fileId(path: String): DataId =
        DataId.StringDataId.create(context = tContext, type = File.ID, keyValue = path)

    /**
     * Reports the given `(file path, property)` pairs as still journalled for the relay.
     *
     * [weighNothing] models the two real cases where [queuedContentBytesFor] answers `0` for a live
     * entry — an empty file, and a `filePath` the database can no longer size — while
     * [hasQueuedContentFor] still answers `true`. The keeper must follow the second.
     */
    private class QueuedContentEmitter : FileContentSyncEmitter {
        val queuedFor = mutableSetOf<Pair<String, String>>()
        var weighNothing = false

        private fun isQueued(targetEntityId: DataId, propertyId: String): Boolean =
            (targetEntityId as DataId.StringDataId).keyValue.get() to propertyId in queuedFor

        override suspend fun emitFileContent(targetEntityId: DataId, propertyId: String, filePath: String): Boolean = true

        override suspend fun shareContentToRealm(contentId: String, targetRealmOwnerId: String, targetPropertyId: String): Boolean = true

        override suspend fun queuedContentBytesFor(targetEntityId: DataId, propertyId: String): Long =
            if (!weighNothing && isQueued(targetEntityId, propertyId)) 1_024L else 0L

        override suspend fun hasQueuedContentFor(targetEntityId: DataId, propertyId: String): Boolean =
            isQueued(targetEntityId, propertyId)
    }

    private fun TestProxy.contentIdOf(path: String): String {
        val row = executionContext.database.getData(
            DataId.StringDataId.create(context = tContext, type = File.ID, keyValue = path)
        )!!
        return (executionContext.resolveProperty(
            row,
            propertyRef(File.FileContentIdProperty.id),
        ) as TString).get()
    }
}
