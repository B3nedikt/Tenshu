package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.file.File
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.DeleteFile
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileTestRegistry
import org.tenshu.sync.common.TombstoneEmitter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeleteFileTombstoneTest {

    private class RecordingEmitter : TombstoneEmitter {
        /** ⚠ A fake with no realm: it names locally and therefore does NOT cover the wire (EI-1). */
        override fun emit(
            targetEntityId: org.tenshu.core.entity.DataId,
            targetEntityType: String,
            revokedDeviceId: String?,
        ): String? = emit(
            targetEntityIdHash = targetEntityId.getDataIdHash().toString(),
            targetEntityType = targetEntityType,
            revokedDeviceId = revokedDeviceId,
        )

        data class Emission(
            val targetEntityIdHash: String,
            val targetEntityType: String,
            val revokedDeviceId: String?
        )

        val emissions = mutableListOf<Emission>()

        override fun emit(
            targetEntityIdHash: String,
            targetEntityType: String,
            revokedDeviceId: String?
        ): String {
            emissions += Emission(targetEntityIdHash, targetEntityType, revokedDeviceId)
            return "block-${++emitCount}"
        }

        private var emitCount = 0

        val cascades = mutableMapOf<String, List<DataId>>()

        override fun emitWithCascade(
            targetEntityId: DataId,
            targetEntityType: String,
            cascadedEntityIds: List<DataId>,
        ): String? = emit(targetEntityId, targetEntityType).also { blockId ->
            if (blockId != null && cascadedEntityIds.isNotEmpty()) cascades[blockId] = cascadedEntityIds
        }
    }

    @Test
    fun deleteFile_emitsTombstoneForTargetEntity() = runTest {
        val emitter = RecordingEmitter()
        val testProxy = TestProxy(
            schemaRegistry = createFileTestRegistry(),
            coroutineScope = backgroundScope,
            implementationFactoryOverride = { id, proxy ->
                fileServiceProxyImplementationFactory(id, proxy.fileDatabase, emitter)
            }
        ).apply { executionContext.initFileService() }

        val path = "/doomed.txt"

        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to path.toTString())
        )

        testProxy.executionContext.executeAsync<Data>(
            operationId = DeleteFile.ID,
            caller = null,
            params = mapOf(DeleteFile.Params.PATH to path.toTString())
        )

        assertEquals(
            1, emitter.emissions.size,
            "DeleteFile should emit exactly one tombstone for the target entity (no per-child emit)"
        )

        val expectedHash = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = path
        ).getDataIdHash().toString()
        assertEquals(expectedHash, emitter.emissions.single().targetEntityIdHash)
        assertEquals(File.ID, emitter.emissions.single().targetEntityType)
        assertTrue(emitter.cascades.isEmpty(), "a delete that removed nothing else records no cascade")
    }

    @Test
    fun deleteFile_recursiveDelete_namesEveryRemovedDescendantAgainstItsOneTombstone() = runTest {
        // ⚠⚠ One tombstone (ADR 0006 #7), but the content plane undeclares by tombstone, so the
        // descendants must be named against it or their edges stand for ever (§9.14).
        val emitter = RecordingEmitter()
        val testProxy = TestProxy(
            schemaRegistry = createFileTestRegistry(),
            coroutineScope = backgroundScope,
            implementationFactoryOverride = { id, proxy ->
                fileServiceProxyImplementationFactory(id, proxy.fileDatabase, emitter)
            }
        ).apply { executionContext.initFileService() }
        listOf("/top", "/top/a", "/top/a/b").forEach { path ->
            testProxy.executionContext.executeAsync<Data>(
                operationId = CreateFolder.ID,
                caller = null,
                params = mapOf(CreateFolder.Params.PATH to path.toTString())
            )
        }

        testProxy.executionContext.executeAsync<Data>(
            operationId = DeleteFile.ID,
            caller = null,
            params = mapOf(DeleteFile.Params.PATH to "/top".toTString())
        )

        fun fileAt(path: String) = DataId.StringDataId.create(context = testProxy.tContext, type = File.ID, keyValue = path)
        assertEquals(1, emitter.emissions.size)
        assertEquals(
            mapOf("block-1" to setOf(fileAt("/top/a"), fileAt("/top/a/b"))),
            emitter.cascades.mapValues { it.value.toSet() },
            "every descendant, keyed by the tombstone's blockId, and never the target itself",
        )
    }

    @Test
    fun deleteFile_recursiveDelete_emitsExactlyOneTombstone() = runTest {
        // Cascade-delete of children must NOT produce a tombstone per child — only the
        // operation's target gets one. Receivers will tombstone their own children when
        // the recursive delete runs locally on their side.
        val emitter = RecordingEmitter()
        val testProxy = TestProxy(
            schemaRegistry = createFileTestRegistry(),
            coroutineScope = backgroundScope,
            implementationFactoryOverride = { id, proxy ->
                fileServiceProxyImplementationFactory(id, proxy.fileDatabase, emitter)
            }
        ).apply { executionContext.initFileService() }

        val parentPath = "/parent"
        val childPath = "/parent/child.txt"

        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to parentPath.toTString())
        )
        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to childPath.toTString())
        )

        testProxy.executionContext.executeAsync<Data>(
            operationId = DeleteFile.ID,
            caller = null,
            params = mapOf(DeleteFile.Params.PATH to parentPath.toTString())
        )

        assertEquals(1, emitter.emissions.size)
        val expectedHash = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = parentPath
        ).getDataIdHash().toString()
        assertEquals(expectedHash, emitter.emissions.single().targetEntityIdHash)
    }

    @Test
    fun deleteFile_withoutEmitter_doesNotCrash() = runTest {
        // The factory accepts a nullable emitter for back-compat with test harnesses that
        // pre-date Phase 3. Local-deletion semantics must be preserved.
        val testProxy = TestProxy(
            schemaRegistry = createFileTestRegistry(),
            coroutineScope = backgroundScope,
            implementationFactoryOverride = { id, proxy ->
                fileServiceProxyImplementationFactory(id, proxy.fileDatabase) // no emitter
            }
        ).apply { executionContext.initFileService() }

        val path = "/no-sync.txt"
        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to path.toTString())
        )
        testProxy.executionContext.executeAsync<Data>(
            operationId = DeleteFile.ID,
            caller = null,
            params = mapOf(DeleteFile.Params.PATH to path.toTString())
        )

        val dataId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = path
        )
        assertNotNull(testProxy.database, "Sanity")
        // Entity should still be deleted locally even with no emitter.
        kotlin.test.assertNull(testProxy.database.getData(dataId))
    }
}
