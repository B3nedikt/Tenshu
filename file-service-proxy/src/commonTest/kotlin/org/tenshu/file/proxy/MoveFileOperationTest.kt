package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.TString
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.file.File
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.AlreadyExistsError
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.MoveFile
import org.tenshu.core.file.system.MoveNeedsLocalBytesError
import org.tenshu.core.file.system.ReplaceFile
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.id
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.sync.common.PendingContentEmit
import org.tenshu.sync.SyncService
import kotlin.test.assertNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class MoveFileOperationTest {

    @Test
    fun moveFile_retargetsADeferredContentIntent_withoutChangingItsKey() = runTest {
        // ⚠⚠ A personal `File`'s identity IS its path, so a move retires the row every pending intent
        // names as its holder, and renames `File.Data`'s bytes. The intent is keyed on
        // its CONTENT, which a move does not change — so the key survives, and only where the bytes
        // are and which row holds them are rewritten (`content-plane-refcount.md` §6d D3).
        val testProxy = createFileProxy(backgroundScope)
        val sourcePath = "/parked.bin"
        val targetPath = "/moved_parked.bin"
        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to "parked.bin".toTString(testProxy.tContext),
                UploadFile.Params.DATA to byteArrayOf(1, 2, 3).toTByteArray(testProxy.tContext),
            ),
        )

        // The intents a closed metered gate (or a 507, or being offline) parks for this file: the
        // content-keyed one, and an entity-keyed one journalled before intents were keyed on content.
        val sourceDataId = DataId.StringDataId.create(type = File.ID, keyValue = sourcePath)
        val sourceHash = sourceDataId.getDataIdHash().toString()
        val propertyId = "${File.ID}/data"
        val legacyPropertyId = "${File.ID}/legacy"
        val contentKey = PendingContentEmit.contentIdKeyOf("cid-parked", propertyId)
        val journalRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = PendingContentEmit.ID,
                relationId = SyncService.Collections.PENDING_CONTENT_EMITS,
            ),
            isEntity = false,
            elementSchemaId = PendingContentEmit.ID,
        )
        testProxy.executionContext.database.addToArray(
            journalRef,
            PendingContentEmit.create(
                context = testProxy.tContext,
                emitKey = contentKey,
                targetEntityId = sourceDataId,
                propertyId = propertyId,
                filePath = sourcePath,
                contentHash = "hash-of-parked",
                wrappedCek = "wrapped-cek",
                landedChunkHashes = "chunk-a,chunk-b",
                contentId = "cid-parked",
            ),
        )
        testProxy.executionContext.database.addToArray(
            journalRef,
            PendingContentEmit.create(
                context = testProxy.tContext,
                emitKey = PendingContentEmit.keyOf(sourceHash, legacyPropertyId),
                targetEntityId = sourceDataId,
                propertyId = legacyPropertyId,
                filePath = sourcePath,
            ),
        )

        testProxy.executionContext.executeAsync<Data>(
            operationId = MoveFile.ID,
            caller = null,
            params = mapOf(
                MoveFile.Params.SOURCE_PATH to sourcePath.toTString(testProxy.tContext),
                MoveFile.Params.TARGET_PATH to targetPath.toTString(testProxy.tContext),
            ),
        )

        val entries = testProxy.executionContext.database.getArrayData(journalRef)
            ?.filterIsInstance<PendingContentEmit>()
            ?.toList()
            .orEmpty()
        val targetDataId = DataId.StringDataId.create(type = File.ID, keyValue = targetPath)
        val targetHash = targetDataId.getDataIdHash().toString()
        assertEquals(2, entries.size, "both intents survive the move")

        val moved = entries.single { it.contentId.get() == "cid-parked" }
        assertEquals(contentKey, moved.emitKey.get(), "the key is the content's, and a move does not change content")
        assertEquals(targetHash, moved.targetEntityId.getDataIdHash().toString(), "the holder is the row that exists now")
        assertEquals(targetPath, moved.filePath.get(), "and it names the path the bytes are at now")
        assertEquals(
            "chunk-a,chunk-b",
            moved.landedChunkHashes.get(),
            "resume state rides along, or the retry re-uploads chunks the relay already has",
        )

        // ⚠ An entity-keyed row (journalled before content keying) carries the entity key, which a
        // move does change.
        val legacy = entries.single { it.propertyId.get() == legacyPropertyId }
        assertEquals(PendingContentEmit.keyOf(targetHash, legacyPropertyId), legacy.emitKey.get())
        // ⚠⚠ The membership move is not the whole job: leaving the old ROW behind is a leak every
        // counter is blind to, because the drain and the figures both read the ARRAY. An orphan
        // then reads exactly like a stuck upload that is not stuck.
        assertNull(
            testProxy.executionContext.database.getData(
                DataId.StringDataId.create(
                    context = testProxy.tContext,
                    type = PendingContentEmit.ID,
                    keyValue = PendingContentEmit.keyOf(sourceHash, legacyPropertyId),
                )
            ),
            "the retired entity key leaves NO PendingContentEmit row behind",
        )
    }

    @Test
    fun moveFile_preservesTypeAndData() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val fileName = "test.txt"
        val sourcePath = "/$fileName"
        val targetPath = "/moved_$fileName"
        val fileData = byteArrayOf(1, 2, 3)

        // 1. Upload
        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to fileName.toTString(testProxy.tContext),
                UploadFile.Params.DATA to fileData.toTByteArray(testProxy.tContext)
            )
        )

        // 2. Move
        testProxy.executionContext.executeAsync<Data>(
            operationId = MoveFile.ID,
            caller = null,
            params = mapOf(
                MoveFile.Params.SOURCE_PATH to sourcePath.toTString(testProxy.tContext),
                MoveFile.Params.TARGET_PATH to targetPath.toTString(testProxy.tContext)
            )
        )

        // 3. Verify
        val rootId = DataId.StringDataId.create(type = File.ID, keyValue = "/")
        val ref = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                type = TList.ID,
                parentEntity = rootId,
                fieldRef = Folder.Fields.FILES
            ),
            isEntity = false
        )
        val filesList = testProxy.executionContext.getArrayData(ref)!!.toList()

        assertEquals(1, filesList.size)
        val movedData = filesList[0]
        assertEquals(File.ID, movedData.id)

        val movedPath = when (movedData) {
            is Entity<*> -> movedData.getFieldValue<FilePath>(File.Field.PATH).get()
            is File -> movedData.path.get()
            else -> ""
        }
        assertEquals(targetPath, movedPath)

        val bytes = testProxy.fileDatabase.readBytes(targetPath)?.get()
        assertTrue(fileData.contentEquals(bytes), "File data should be preserved")
    }

    @Test
    fun moveFolder_recursive_updatesChildrenPaths() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val sourcePath = "/myfolder"
        val targetPath = "/renamed_folder"
        val expectedChildTargetPath = "$targetPath/child.txt"

        // 1. Create structure
        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to sourcePath.toTString(testProxy.tContext))
        )
        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to sourcePath.toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to "child.txt".toTString(testProxy.tContext),
                UploadFile.Params.DATA to byteArrayOf(1).toTByteArray(testProxy.tContext)
            )
        )

        // 2. Move
        testProxy.executionContext.executeAsync<Data>(
            operationId = MoveFile.ID,
            caller = null,
            params = mapOf(
                MoveFile.Params.SOURCE_PATH to sourcePath.toTString(testProxy.tContext),
                MoveFile.Params.TARGET_PATH to targetPath.toTString(testProxy.tContext)
            )
        )

        // 3. Verify folder moved in parent array
        val rootFiles = testProxy.executionContext.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.EntityFieldDataId.create(
                    type = TList.ID,
                    parentEntity = DataId.StringDataId.create(type = File.ID, keyValue = "/"),
                    fieldRef = Folder.Fields.FILES
                ),
                isEntity = false
            )
        )!!.toList()

        val foundTarget = rootFiles.any {
            val path = when (it) {
                is Entity<*> -> it.getFieldValue<FilePath>(File.Field.PATH).get()
                is File -> it.path.get()
                else -> ""
            }
            path == targetPath
        }
        assertTrue(foundTarget, "Folder should be at target path. Files: $rootFiles")

        val foundSource = rootFiles.any {
            val path = when (it) {
                is Entity<*> -> it.getFieldValue<FilePath>(File.Field.PATH).get()
                is File -> it.path.get()
                else -> ""
            }
            path == sourcePath
        }
        assertTrue(!foundSource, "Folder should NOT be at source path")

        // 4. Verify child path updated recursively
        val children = testProxy.executionContext.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.EntityFieldDataId.create(
                    type = TList.ID,
                    parentEntity = DataId.StringDataId.create(
                        type = File.ID,
                        keyValue = targetPath
                    ),
                    fieldRef = Folder.Fields.FILES
                ),
                isEntity = false
            )
        )!!.toList()

        assertEquals(1, children.size, "Child should be in the new folder. Children: $children")
        val movedChildPath = when (val childData = children[0]) {
            is Entity<*> -> childData.getFieldValue<FilePath>(File.Field.PATH).get()
            is File -> childData.path.get()
            else -> ""
        }
        assertEquals(
            expectedChildTargetPath,
            movedChildPath,
            "Child path should be updated recursively"
        )
    }

    @Test
    fun moveFile_failsIfTargetAlreadyExists() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val file1 = "file1.txt"
        val file2 = "file2.txt"

        // 1. Upload two files
        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to file1.toTString(testProxy.tContext),
                UploadFile.Params.DATA to byteArrayOf(1).toTByteArray(testProxy.tContext)
            )
        )
        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to file2.toTString(testProxy.tContext),
                UploadFile.Params.DATA to byteArrayOf(2).toTByteArray(testProxy.tContext)
            )
        )

        // 2. Attempt to move file1 to file2's path — per
        // `docs/proposals/core-error-channel.md`, impls throw `TenshuException`;
        // in-process consumers propagate the throw (LocalTaskExecutor's
        // catch-all is on the wire path).
        val ex = kotlin.test.assertFailsWith<org.tenshu.core.TenshuException> {
            testProxy.executionContext.executeAsync<Data>(
                operationId = MoveFile.ID,
                caller = null,
                params = mapOf(
                    MoveFile.Params.SOURCE_PATH to "/$file1".toTString(testProxy.tContext),
                    MoveFile.Params.TARGET_PATH to "/$file2".toTString(testProxy.tContext)
                )
            )
        }
        assertTrue(ex.error is AlreadyExistsError)
    }
    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // No reference root is written — not by an upload, a move or an overwrite
    // (`content-plane-cr2-b6-spec.md` §1).
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /** The stored property ids of the row at [path] (empty when there is no row). */
    private fun storedPropertyIds(executionContext: ExecutionContext, path: String): Set<String> {
        val dataId = DataId.StringDataId.create(type = File.ID, keyValue = path)
        val row = executionContext.database.getData(dataId, asEntity = true) as? Entity<*> ?: return emptySet()
        return row.properties.keys.map { it.get() }.toSet()
    }

    private suspend fun upload(testProxy: org.tenshu.core.common.TestProxy, name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) {
        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to name.toTString(testProxy.tContext),
                UploadFile.Params.DATA to bytes.toTByteArray(testProxy.tContext),
            )
        )
    }

    private suspend fun move(testProxy: org.tenshu.core.common.TestProxy, source: String, target: String) =
        testProxy.executionContext.executeAsync<Data>(
            operationId = MoveFile.ID,
            caller = null,
            params = mapOf(
                MoveFile.Params.SOURCE_PATH to source.toTString(testProxy.tContext),
                MoveFile.Params.TARGET_PATH to target.toTString(testProxy.tContext),
            )
        )

    @Test
    fun uploadMoveAndOverwrite_writeNoReferenceRoot() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        upload(testProxy, "rootless.txt")
        val afterUpload = storedPropertyIds(testProxy.executionContext, "/rootless.txt")
        // Control: the read sees the row's value properties at all, or the assertions below are vacuous.
        assertTrue(File.ContentId.id in afterUpload, "control: the stored properties must be readable: $afterUpload")
        assertTrue(afterUpload.none { it.endsWith("/referenceRoot") }, "an upload mints no root: $afterUpload")

        move(testProxy, "/rootless.txt", "/moved.txt")
        val afterMove = storedPropertyIds(testProxy.executionContext, "/moved.txt")
        assertTrue(File.ContentId.id in afterMove, "control: the move carried the content id")
        assertTrue(afterMove.none { it.endsWith("/referenceRoot") }, "a move writes no root: $afterMove")

        // ⚠ Different bytes: an unchanged save short-circuits before the entity is rewritten.
        val contentBefore = readStringProperty(testProxy.executionContext, "/moved.txt", File.ContentId.id)
        testProxy.executionContext.executeAsync<Data>(
            operationId = ReplaceFile.ID,
            caller = null,
            params = mapOf(
                ReplaceFile.Params.PARENT_PATH to "/".toTString(testProxy.tContext),
                ReplaceFile.Params.FILE_NAME to "moved.txt".toTString(testProxy.tContext),
                ReplaceFile.Params.DATA to byteArrayOf(9, 9, 9, 9).toTByteArray(testProxy.tContext),
            )
        )
        val afterOverwrite = storedPropertyIds(testProxy.executionContext, "/moved.txt")
        assertNotEquals(
            contentBefore,
            readStringProperty(testProxy.executionContext, "/moved.txt", File.ContentId.id),
            "control: the overwrite minted a new content id, so it rewrote the row",
        )
        assertTrue(afterOverwrite.none { it.endsWith("/referenceRoot") }, "an overwrite writes no root: $afterOverwrite")
    }

    /** A stored string value property of the row at [path], read the way the emit reads it. */
    private fun readStringProperty(
        executionContext: ExecutionContext,
        path: String,
        propertyId: String,
    ): String? {
        val dataId = DataId.StringDataId.create(type = File.ID, keyValue = path)
        val row = executionContext.database.getData(dataId) ?: return null
        return runCatching {
            (executionContext.resolveProperty(row, propertyRef(propertyId)) as? TString)?.get()
        }.getOrNull()
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // An OFFLINE move of content this device does not hold is refused with
    // `MoveNeedsLocalBytesError`; with the bytes, or online, it goes through
    // (`content-plane-cr2-b6-spec.md` D3).
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private fun assertRefusedForLocalBytes(result: Result<Data?>, what: String) {
        assertTrue(result.isFailure, "$what: the move must be refused")
        assertTrue(
            result.exceptionOrNull()?.message?.contains(MoveNeedsLocalBytesError.ID) == true,
            "$what: refused with the typed MoveNeedsLocalBytesError: ${result.exceptionOrNull()?.message}",
        )
    }

    @Test
    fun anOfflineMoveWithoutLocalBytes_isRefused_andChangesNothing() = runTest {
        val testProxy = createFileProxy(backgroundScope, isOffline = { true })
        upload(testProxy, "remote.bin")
        // The shape of a row synced from a peer and never downloaded here: content id, no bytes.
        testProxy.fileDatabase.deleteFile("/remote.bin")

        assertRefusedForLocalBytes(runCatching { move(testProxy, "/remote.bin", "/elsewhere.bin") }, "a file")

        assertTrue(storedPropertyIds(testProxy.executionContext, "/remote.bin").isNotEmpty(), "the source row stays")
        assertTrue(storedPropertyIds(testProxy.executionContext, "/elsewhere.bin").isEmpty(), "and no target was written")
    }

    @Test
    fun anOfflineMoveWithLocalBytes_goesThrough() = runTest {
        val testProxy = createFileProxy(backgroundScope, isOffline = { true })
        upload(testProxy, "here.bin")

        move(testProxy, "/here.bin", "/moved-offline.bin")

        assertTrue(storedPropertyIds(testProxy.executionContext, "/moved-offline.bin").isNotEmpty(), "the move applied")
        assertNull(testProxy.executionContext.database.getData(DataId.StringDataId.create(type = File.ID, keyValue = "/here.bin")))
    }

    @Test
    fun anOfflineFolderMove_isRefused_whenAnyFileInsideHasNoLocalBytes() = runTest {
        val testProxy = createFileProxy(backgroundScope, isOffline = { true })
        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to "/album".toTString(testProxy.tContext))
        )
        for (name in listOf("a.bin", "b.bin")) {
            testProxy.executionContext.executeAsync<Data>(
                operationId = UploadFile.ID,
                caller = null,
                params = mapOf(
                    UploadFile.Params.PARENT_PATH to "/album".toTString(testProxy.tContext),
                    UploadFile.Params.FILE_NAME to name.toTString(testProxy.tContext),
                    UploadFile.Params.DATA to byteArrayOf(4, 5, 6).toTByteArray(testProxy.tContext),
                )
            )
        }
        testProxy.fileDatabase.deleteFile("/album/b.bin")

        assertRefusedForLocalBytes(runCatching { move(testProxy, "/album", "/album2") }, "a folder holding one")
        assertTrue(storedPropertyIds(testProxy.executionContext, "/album2").isEmpty(), "nothing of the tree moved")
    }

    @Test
    fun anOnlineMoveWithoutLocalBytes_goesThrough_unlessTheProbeSaysTheRelayIsUnreachable() = runTest {
        // Online by the cached verdict, and the probe confirms: a move is pure metadata and reaches
        // the relay promptly, so it needs no bytes here.
        var probes = 0
        val online = createFileProxy(backgroundScope, isOffline = { false }, confirmWritable = { probes++; true })
        upload(online, "remote.bin")
        online.fileDatabase.deleteFile("/remote.bin")
        move(online, "/remote.bin", "/moved.bin")
        assertTrue(storedPropertyIds(online.executionContext, "/moved.bin").isNotEmpty(), "the online move applied")
        assertEquals(1, probes, "the probe is asked once, for the move that lacks bytes")

        // The cached verdict says online, the acknowledged round trip says otherwise.
        val stale = createFileProxy(backgroundScope, isOffline = { false }, confirmWritable = { false })
        upload(stale, "remote.bin")
        stale.fileDatabase.deleteFile("/remote.bin")
        assertRefusedForLocalBytes(runCatching { move(stale, "/remote.bin", "/moved.bin") }, "a stale online verdict")
    }

    @Test
    fun aMoveWithLocalBytes_neverProbesTheRelay() = runTest {
        var probes = 0
        val testProxy = createFileProxy(backgroundScope, isOffline = { false }, confirmWritable = { probes++; false })
        upload(testProxy, "here.bin")

        move(testProxy, "/here.bin", "/moved.bin")

        assertTrue(storedPropertyIds(testProxy.executionContext, "/moved.bin").isNotEmpty())
        assertEquals(0, probes, "content held here needs no round trip — the walk answers first")
    }
}
