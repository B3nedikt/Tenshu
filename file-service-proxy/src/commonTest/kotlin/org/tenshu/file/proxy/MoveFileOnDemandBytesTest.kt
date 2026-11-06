package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.OnDemandResolution
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.get
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.equalTo
import org.tenshu.core.file.ContentHash
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.Size
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.MoveFile
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.core.getOrNull
import org.tenshu.core.id
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Content addressing (`file-content-addressing.md` §8.5) makes a move
 * **pure metadata**: a file's ciphertext + manifest are bound to its stable random
 * `contentId`, not its path, so relocating it writes a `File` at the new path
 * carrying the SAME `contentId` — no byte fetch, no re-encryption, no content
 * re-emit. In particular it never materialises an un-downloaded file's bytes.
 *
 * The local plaintext rename still leaves NO 0-byte ghost at the source: a 0-byte
 * file is not an absent one (`fileSize` → `0L` not `null`), which would read as
 * "downloaded" to `HasLocalData` and wedge the OnDemand fetch off for good.
 */
class MoveFileOnDemandBytesTest {

    @Test
    fun move_isPureMetadata_preservingContentId_withoutFetchingBytes() = runTest {
        val emitter = RecordingContentEmitter()
        val holder = SyncBlockEmitterHolder().apply { fileContentEmitter = emitter }
        val resolver = FakeOnDemandResolution()
        val testProxy = createFileProxy(
            backgroundScope,
            deviceId = "device-1",
            syncBlockEmitterHolder = holder,
            onDemandResolution = resolver,
        )

        val bytes = ByteArray(1000) { (it % 251).toByte() }
        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to "photo.jpg".toTString(testProxy.tContext),
                UploadFile.Params.DATA to bytes.toTByteArray(testProxy.tContext),
            )
        )
        val originalContentId = testProxy.contentIdOf("/photo.jpg")
        assertNotNull(originalContentId, "upload must stamp a contentId")

        // The state of any file synced from another device, or un-pinned here:
        // the entity is local but its bytes are not.
        testProxy.fileDatabase.deleteFile("/photo.jpg")
        emitter.calls.clear()
        resolver.calls.clear()

        testProxy.executionContext.executeAsync<Data>(
            operationId = MoveFile.ID,
            caller = null,
            params = mapOf(
                MoveFile.Params.SOURCE_PATH to "/photo.jpg".toTString(testProxy.tContext),
                MoveFile.Params.TARGET_PATH to "/moved.jpg".toTString(testProxy.tContext),
            )
        )

        // The moved file carries the SAME contentId — its bytes stay addressable
        // without moving a single byte.
        assertEquals(
            originalContentId,
            testProxy.contentIdOf("/moved.jpg"),
            "the moved file must keep its contentId so the same bytes resolve",
        )
        // No bytes were fetched and no content was re-emitted — the whole point.
        assertEquals(0, resolver.calls.size, "a move must not trigger an OnDemand byte fetch")
        assertTrue(emitter.calls.isEmpty(), "a move must not re-emit content; got ${emitter.calls}")
        // No 0-byte ghost at the source (nor a fabricated empty file at the target).
        assertNull(
            testProxy.fileDatabase.fileSize("/photo.jpg"),
            "the source path must be gone, not zeroed (a 0-byte file reads as 'downloaded')",
        )
    }

    @Test
    fun move_doesNotReEmitContent() = runTest {
        val emitter = RecordingContentEmitter()
        val holder = SyncBlockEmitterHolder().apply { fileContentEmitter = emitter }
        val testProxy = createFileProxy(
            backgroundScope,
            deviceId = "device-1",
            syncBlockEmitterHolder = holder,
        )

        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to "photo.jpg".toTString(testProxy.tContext),
                UploadFile.Params.DATA to ByteArray(10).toTByteArray(testProxy.tContext),
            )
        )
        val originalContentId = testProxy.contentIdOf("/photo.jpg")
        emitter.calls.clear()

        testProxy.executionContext.executeAsync<Data>(
            operationId = MoveFile.ID,
            caller = null,
            params = mapOf(
                MoveFile.Params.SOURCE_PATH to "/photo.jpg".toTString(testProxy.tContext),
                MoveFile.Params.TARGET_PATH to "/renamed.jpg".toTString(testProxy.tContext),
            )
        )

        // The content is bound to the contentId, which the move preserves — so there
        // is nothing to re-emit.
        assertTrue(
            emitter.calls.isEmpty(),
            "a move must not re-emit content; got ${emitter.calls}",
        )
        assertEquals(
            originalContentId,
            testProxy.contentIdOf("/renamed.jpg"),
            "the moved file must carry the same contentId",
        )
    }

    @Test
    fun move_ofUnDownloadedFile_succeeds() = runTest {
        val resolver = FakeOnDemandResolution() // would fetch nothing (offline) — must not be called
        val testProxy = createFileProxy(
            backgroundScope,
            deviceId = "device-1",
            onDemandResolution = resolver,
        )

        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to "photo.jpg".toTString(testProxy.tContext),
                UploadFile.Params.DATA to ByteArray(1000).toTByteArray(testProxy.tContext),
            )
        )
        val originalContentId = testProxy.contentIdOf("/photo.jpg")
        testProxy.fileDatabase.deleteFile("/photo.jpg")

        // A move is pure metadata, so it succeeds offline — no fetch, no failure.
        testProxy.executionContext.executeAsync<Data>(
            operationId = MoveFile.ID,
            caller = null,
            params = mapOf(
                MoveFile.Params.SOURCE_PATH to "/photo.jpg".toTString(testProxy.tContext),
                MoveFile.Params.TARGET_PATH to "/moved.jpg".toTString(testProxy.tContext),
            )
        )

        assertEquals(0, resolver.calls.size, "a move must not fetch bytes")
        assertEquals(
            originalContentId,
            testProxy.contentIdOf("/moved.jpg"),
            "the un-downloaded file moved by metadata alone, keeping its contentId",
        )
        // No fabricated empty file at either path.
        assertNull(
            testProxy.fileDatabase.fileSize("/moved.jpg"),
            "a metadata-only move of un-downloaded bytes must not fabricate an empty file",
        )
    }

    /**
     * §3.3.2 step 1 (generic value-prop preservation): a move must carry *every* stored
     * value property to the new path, not a hand-listed subset. `MoveFile` re-stamps no
     * explicit list — it `include(...)`s the source entity's whole
     * `.properties` map — so a value property survives a move with no per-property code.
     * This guards the whole set (contentId, contentHash, createdAt, size) against a future
     * refactor silently dropping one, and proves the generic path preserves properties the
     * OnDemand tests above don't cover (contentHash/createdAt/size).
     */
    @Test
    fun move_preservesAllStoredValueProperties() = runTest {
        val testProxy = createFileProxy(backgroundScope, deviceId = "device-1")

        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to "photo.jpg".toTString(testProxy.tContext),
                UploadFile.Params.DATA to ByteArray(1234) { (it % 251).toByte() }
                    .toTByteArray(testProxy.tContext),
            )
        )
        val before = testProxy.valuePropsOf("/photo.jpg")
        assertNotNull(before, "upload must stamp value properties")
        assertNotNull(before.contentId, "upload stamps contentId")
        assertNotNull(before.contentHash, "upload stamps contentHash")
        assertNotNull(before.createdAt, "upload stamps createdAt")
        assertEquals(1234L, before.size, "upload stamps size")

        testProxy.executionContext.executeAsync<Data>(
            operationId = MoveFile.ID,
            caller = null,
            params = mapOf(
                MoveFile.Params.SOURCE_PATH to "/photo.jpg".toTString(testProxy.tContext),
                MoveFile.Params.TARGET_PATH to "/moved.jpg".toTString(testProxy.tContext),
            )
        )

        val after = testProxy.valuePropsOf("/moved.jpg")
        assertNotNull(after, "the moved file must resolve at the new path")
        assertEquals(before.contentId, after.contentId, "contentId survives the move")
        assertEquals(before.contentHash, after.contentHash, "contentHash survives the move")
        assertEquals(before.createdAt, after.createdAt, "createdAt survives the move")
        assertEquals(before.size, after.size, "size survives the move")
    }

    @Test
    fun move_emitsOldPathTombstone_andRemovesOldEntityLocally() = runTest {
        val tombstones = RecordingTombstoneEmitter()
        val testProxy = createFileProxy(
            backgroundScope,
            deviceId = "device-1",
            tombstoneEmitter = tombstones,
        )

        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to "photo.jpg".toTString(testProxy.tContext),
                UploadFile.Params.DATA to ByteArray(10).toTByteArray(testProxy.tContext),
            )
        )
        val oldHash = DataId.StringDataId.create(type = File.ID, keyValue = "/photo.jpg")
            .getDataIdHash().toString()

        testProxy.executionContext.executeAsync<Data>(
            operationId = MoveFile.ID,
            caller = null,
            params = mapOf(
                MoveFile.Params.SOURCE_PATH to "/photo.jpg".toTString(testProxy.tContext),
                MoveFile.Params.TARGET_PATH to "/moved.jpg".toTString(testProxy.tContext),
            )
        )

        // §3.3.3.A: a move must tell peers the old path is gone — else a device still
        // holding /photo.jpg shows a duplicate at both paths. Exactly one tombstone, on
        // the old path.
        assertEquals(
            listOf(oldHash to File.ID),
            tombstones.calls,
            "move must emit exactly one tombstone, on the old path",
        )
        // The old-path entity is gone from the local store too (not merely detached from
        // the parent listing).
        assertNull(
            testProxy.executionContext.database.getData(
                DataId.StringDataId.create(type = File.ID, keyValue = "/photo.jpg")
            ),
            "the old-path entity must be removed from the local store",
        )
    }

    @Test
    fun moveFolder_emitsAPerDescendantTombstone() = runTest {
        val tombstones = RecordingTombstoneEmitter()
        val testProxy = createFileProxy(
            backgroundScope,
            deviceId = "device-1",
            tombstoneEmitter = tombstones,
        )

        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to "/myfolder".toTString(testProxy.tContext))
        )
        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/myfolder".toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to "child.txt".toTString(testProxy.tContext),
                UploadFile.Params.DATA to byteArrayOf(1).toTByteArray(testProxy.tContext),
            )
        )
        fun hashOf(path: String) =
            DataId.StringDataId.create(type = File.ID, keyValue = path).getDataIdHash().toString()

        testProxy.executionContext.executeAsync<Data>(
            operationId = MoveFile.ID,
            caller = null,
            params = mapOf(
                MoveFile.Params.SOURCE_PATH to "/myfolder".toTString(testProxy.tContext),
                MoveFile.Params.TARGET_PATH to "/renamed".toTString(testProxy.tContext),
            )
        )

        // A folder move tombstones the folder AND each descendant's old path individually
        // (unlike a folder *delete*, whose single root tombstone doesn't cascade on
        // receivers) — the recursive child moves each emit their own (§3.3.3.B/C).
        assertEquals(
            setOf(hashOf("/myfolder") to File.ID, hashOf("/myfolder/child.txt") to File.ID),
            tombstones.calls.toSet(),
            "a folder move must tombstone the folder and each descendant's old path",
        )
    }

    @Test
    fun move_reKeysHistory_soItSurvivesAtTheNewPath() = runTest {
        // A FakeClock advanced between writes gives each modification a distinct timestamp — a
        // FileModification's DataId embeds a wall-clock ms, so same-ms rows collide by key; real
        // upload/move are seconds apart, which this models.
        val clock = org.tenshu.core.common.FakeClock(initial = 1_000L)
        val testProxy = createFileProxy(backgroundScope, deviceId = "device-1", clock = clock)
        val ctx = testProxy.tContext

        // Upload stamps a CreatedFileModification (the pinned history floor) at /photo.jpg.
        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(ctx),
                UploadFile.Params.FILE_NAME to "photo.jpg".toTString(ctx),
                UploadFile.Params.DATA to ByteArray(10).toTByteArray(ctx),
            )
        )
        assertTrue(
            testProxy.modificationFileIds().contains("/photo.jpg"),
            "upload must stamp history at the source path",
        )
        clock.advance(1_000L)

        testProxy.executionContext.executeAsync<Data>(
            operationId = MoveFile.ID,
            caller = null,
            params = mapOf(
                MoveFile.Params.SOURCE_PATH to "/photo.jpg".toTString(ctx),
                MoveFile.Params.TARGET_PATH to "/moved.jpg".toTString(ctx),
            )
        )

        // History followed the file: prior rows re-keyed to the new path, none left dangling at
        // the old path (the cascade reaped only the sourcePath rows). §3.3.4.
        val ids = testProxy.modificationFileIds()
        assertTrue(!ids.contains("/photo.jpg"), "no history may dangle at the old path")
        assertTrue(ids.contains("/moved.jpg"), "history must survive at the new path")
        val typesAtNew = testProxy.modificationTypeIdsFor("/moved.jpg")
        assertTrue(
            typesAtNew.contains(org.tenshu.core.file.CreatedFileModification.ID),
            "the re-keyed Created floor row must survive; got $typesAtNew",
        )
        assertTrue(
            typesAtNew.contains(org.tenshu.core.file.RenamedFileModification.ID),
            "the move's own Rename row must be present; got $typesAtNew",
        )
    }

    private fun org.tenshu.core.common.TestProxy.modifications(): List<Data> {
        val ref = org.tenshu.core.common.proxy.ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = tContext,
                type = TList.ID,
                relationId = org.tenshu.core.file.FileService.Relations.MODIFICATIONS,
            ),
            isEntity = false,
            elementSchemaId = org.tenshu.core.file.FileModification.ID,
        )
        return executionContext.database.getArrayData(ref)?.toList() ?: emptyList()
    }

    private fun modFileId(row: Data): String? = when (row) {
        is org.tenshu.core.entity.Entity<*> -> runCatching {
            row.getFieldValue<org.tenshu.core.TString>(org.tenshu.core.file.FileModification.Field.FILE_ID).get()
        }.getOrNull()
        is org.tenshu.core.Composite -> runCatching {
            row.getField<org.tenshu.core.TString>(org.tenshu.core.file.FileModification.Field.FILE_ID).get()
        }.getOrNull()
        else -> null
    }

    private fun org.tenshu.core.common.TestProxy.modificationFileIds(): List<String> =
        modifications().mapNotNull { modFileId(it) }

    private fun org.tenshu.core.common.TestProxy.modificationTypeIdsFor(path: String): List<String> =
        modifications().filter { modFileId(it) == path }
            .map { (it as? org.tenshu.core.entity.Entity<*>)?.type?.get() ?: it.id }

    @Test
    fun move_sweepsStaleSyncMetadata_atOldAndNewHash() = runTest {
        val testProxy = createFileProxy(backgroundScope, deviceId = "device-1")
        val ctx = testProxy.tContext

        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(ctx),
                UploadFile.Params.FILE_NAME to "photo.jpg".toTString(ctx),
                UploadFile.Params.DATA to ByteArray(10).toTByteArray(ctx),
            )
        )

        fun fileHash(path: String) =
            DataId.StringDataId.create(type = File.ID, keyValue = path).getDataIdHash().toString()
        fun metaId(hash: String) =
            DataId.StringDataId.create(type = org.tenshu.sync.common.SyncMetadata.ID, keyValue = hash)
        val oldHash = fileHash("/photo.jpg")
        val newHash = fileHash("/moved.jpg")

        // Seed stale SyncMetadata at both hashes, as if the file had been synced/ingested (a
        // USER upload never writes SyncMetadata — only the ingest path does).
        fun seedMeta(hash: String) {
            val hlc = org.tenshu.core.time.HybridLogicalClock.create(
                context = ctx, physicalTime = 1L, logicalCounter = 0L, deviceId = "seed",
            )
            testProxy.executionContext.database.saveData(
                org.tenshu.sync.common.SyncMetadata.create(ctx, hlc, targetDataIdHash = hash)
            )
        }
        seedMeta(oldHash)
        seedMeta(newHash)
        assertNotNull(testProxy.executionContext.database.getData(metaId(oldHash)))
        assertNotNull(testProxy.executionContext.database.getData(metaId(newHash)))

        testProxy.executionContext.executeAsync<Data>(
            operationId = MoveFile.ID,
            caller = null,
            params = mapOf(
                MoveFile.Params.SOURCE_PATH to "/photo.jpg".toTString(ctx),
                MoveFile.Params.TARGET_PATH to "/moved.jpg".toTString(ctx),
            )
        )

        // The DataIdMigrator swept the stale local LWW tip at both hashes (§3.3.3.E) — else a
        // leftover high-HLC row at the new hash could silently drop the re-emitted block.
        assertNull(
            testProxy.executionContext.database.getData(metaId(oldHash)),
            "stale SyncMetadata at the old hash must be swept",
        )
        assertNull(
            testProxy.executionContext.database.getData(metaId(newHash)),
            "stale SyncMetadata at the new hash must be swept",
        )
    }

    private class RecordingTombstoneEmitter : org.tenshu.sync.common.TombstoneEmitter {
        /** ⚠ A fake with no realm: it names locally and therefore does NOT cover the wire (`DataIdMigratorWireNameTest` does). */
        override fun emit(
            targetEntityId: org.tenshu.core.entity.DataId,
            targetEntityType: String,
            revokedDeviceId: String?,
        ): String? = emit(
            targetEntityIdHash = targetEntityId.getDataIdHash().toString(),
            targetEntityType = targetEntityType,
            revokedDeviceId = revokedDeviceId,
        )

        val calls = mutableListOf<Pair<String, String>>()
        override fun emit(
            targetEntityIdHash: String,
            targetEntityType: String,
            revokedDeviceId: String?,
        ): String {
            calls += targetEntityIdHash to targetEntityType
            return "block-${++emitCount}"
        }

        private var emitCount = 0
    }

    private data class FileValueProps(
        val contentId: String?,
        val contentHash: String?,
        val createdAt: Long?,
        val size: Long?,
    )

    /**
     * Read a personal file's stamped [File.ContentId] value property through the
     * client → proxy select chain (the pattern `FileCreatedAtTest` uses for value
     * properties — `getData` returns the composite without value props), or null if the
     * file doesn't resolve.
     */
    private suspend fun org.tenshu.core.common.TestProxy.contentIdOf(filePath: String): String? {
        val executor = LocalTaskExecutor(executionContext)
        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = tContext,
        )
        val fileService = env.getService<FileService>(FileService.ID)
        return fileService.defaultFileSystem.root.files
            .search<File> { this.path equalTo filePath }
            .select { relations(File.ContentId) }
            .resolve()
            .getOrNull()
            ?.get(File.ContentId)?.get()
    }

    /** Read several stored value properties of a file through the select chain (see [contentIdOf]). */
    private suspend fun org.tenshu.core.common.TestProxy.valuePropsOf(filePath: String): FileValueProps? {
        val executor = LocalTaskExecutor(executionContext)
        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = tContext,
        )
        val fileService = env.getService<FileService>(FileService.ID)
        val file = fileService.defaultFileSystem.root.files
            .search<File> { this.path equalTo filePath }
            .select { relations(File.ContentId, File.ContentHash, File.CreatedAt, File.Size) }
            .resolve()
            .getOrNull()
            ?: return null
        return FileValueProps(
            contentId = file.get(File.ContentId)?.get(),
            contentHash = file.get(File.ContentHash)?.get(),
            createdAt = file.get(File.CreatedAt)?.get(),
            size = file.get(File.Size)?.get(),
        )
    }

    private class FakeOnDemandResolution : OnDemandResolution {
        val calls = mutableListOf<Pair<DataId, String>>()
        var onResolve: (() -> Unit)? = null

        override suspend fun resolve(
            executionContext: ExecutionContext,
            entityId: DataId,
            propertyId: String,
            params: TList<Data>?,
        ): TResult<Data?> {
            calls += entityId to propertyId
            onResolve?.invoke()
            return TResult.Success.create(executionContext.context, null)
        }
    }

    /** Records each emitFileContent call as (targetKey, propertyId, filePath). */
    private class RecordingContentEmitter : FileContentSyncEmitter {
        val calls = mutableListOf<Triple<String, String, String>>()
        override suspend fun emitFileContent(
            targetEntityId: DataId,
            propertyId: String,
            filePath: String,
        ): Boolean {
            calls += Triple(
                (targetEntityId as DataId.StringDataId).keyValue.get(),
                propertyId,
                filePath,
            )
            return true
        }

        override suspend fun shareContentToRealm(
            contentId: String,
            targetRealmOwnerId: String,
            targetPropertyId: String,
        ): Boolean = true
    }
}
