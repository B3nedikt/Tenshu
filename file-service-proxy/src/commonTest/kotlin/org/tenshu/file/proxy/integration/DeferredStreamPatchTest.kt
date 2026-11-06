package org.tenshu.file.proxy.integration

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.tenshu.core.ChainTemplate
import org.tenshu.core.Data
import org.tenshu.core.StreamElement
import org.tenshu.core.TByteArray
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.common.impl.reference.DynamicPropertyTypeReferenceDto
import org.tenshu.core.common.module.dynamicPropertyRelation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.PendingDeferredProperty
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.getPreview
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.schema.dynamicPropertyRef
import org.tenshu.core.schema.fieldRef
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.core.schema.dynamicPropertyRelation as schemaDynamicPropertyRelation

/**
 * Deferred-property **stream patches** (`core-deferred-properties.md` §4/§5,
 * Phase 3): the initial emission carries rows with [PendingDeferredProperty]
 * markers; `StreamRegistry`'s per-stream resolution queue resolves each marker
 * in the background and re-emits that row as an ordinary per-item
 * [StreamElementValueDto]; failures settle the row with the property dropped
 * (Q1); signal-only streams schedule nothing; `removeDatabaseListener`
 * cancels queued resolutions.
 */
class DeferredStreamPatchTest {

    private companion object {
        const val RELATION_ID = "test/deferredStream/preview"
        const val FOLDER = "/folder"
        val BYTES = byteArrayOf(9, 9)
    }

    private class ScriptedResolver(
        private val behaviour: () -> TByteArray,
    ) : SyncOperationImplementation {
        var invocations = 0
            private set

        // UploadFile's thumbnail pipeline also dispatches GetPreview during
        // harness setup — tests assert against the count SINCE the stream
        // was registered, not since proxy construction.
        var baseline = 0
        val sinceBaseline: Int get() = invocations - baseline

        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?
        ): T {
            invocations++
            @Suppress("UNCHECKED_CAST")
            return behaviour() as T
        }
    }

    private suspend fun waitFor(timeoutMs: Long = 2000, predicate: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) delay(10)
        }
    }

    private class Harness(
        val testProxy: org.tenshu.core.common.TestProxy,
        val resolver: ScriptedResolver,
    ) {
        val context = testProxy.tContext
        val emissions = mutableListOf<Data>()

        suspend fun uploadFile(name: String) {
            testProxy.executionContext.executeAsync<Data>(
                UploadFile.ID, null, mapOf(
                    UploadFile.Params.PARENT_PATH to FOLDER.toTString(context),
                    UploadFile.Params.FILE_NAME to name.toTString(context),
                    UploadFile.Params.DATA to "hello".encodeToByteArray().toTByteArray(context),
                )
            )
        }

        fun folderFilesRef(): ArrayDatabaseReference {
            val parentDataId = DataId.StringDataId.create(
                context = context,
                type = File.ID,
                keyValue = FOLDER
            )
            return ArrayDatabaseReference(
                arrayId = DataId.EntityFieldDataId.create(
                    context = context,
                    type = TList.ID,
                    parentEntity = parentDataId as DataId.EntityDataId,
                    fieldRef = Folder.Fields.FILES
                ),
                isEntity = false
            )
        }

        fun selectStack(): List<Data> = listOf(
            OperationDto(
                params = mapOf(
                    Select.Params.SELECTORS to TListDto(
                        listOf(
                            // The key field is load-bearing: the patch path matches
                            // rows by DataId, which for File derives from the path.
                            Selector.FieldSelector.create<File>(
                                context = context,
                                fieldReference = fieldRef(File.Field.PATH)
                            ),
                            Selector.DynamicPropertySelector.create<File>(
                                context = context,
                                dynamicPropertyReference = dynamicPropertyRef(RELATION_ID),
                            )
                        )
                    )
                ),
                schema = Select.Schema
            )
        )

        /** Entities carried by a [StreamElementValueDto] (initial list or single-row patch). */
        fun entitiesOf(emission: Data): List<Entity<*>> {
            val value = (emission as? StreamElement.Value<*>)?.payload ?: return emptyList()
            return when (value) {
                is TList<*> -> value.toList().filterIsInstance<Entity<*>>()
                is Entity<*> -> listOf(value)
                else -> emptyList()
            }
        }

        fun propertyOf(entity: Entity<*>): Data? =
            entity.properties[DynamicPropertyTypeReferenceDto(RELATION_ID)]
    }

    private suspend fun harness(
        scope: kotlinx.coroutines.CoroutineScope,
        behaviour: () -> TByteArray = { TByteArrayDto(BYTES) },
    ): Harness {
        val testProxy = createFileProxy(scope)
        val resolver = ScriptedResolver(behaviour)
        testProxy.additionalImplementations[GetPreview.ID] = resolver
        testProxy.schemaRegistry.registerConfig(
            tModule {
                dynamicPropertyRelation(
                    fromId = File.ID,
                    schema = schemaDynamicPropertyRelation(
                        id = RELATION_ID,
                        deferred = true,
                        callstack = ChainTemplate<File, TByteArray> {
                            getPreview(width = 1, height = 1)
                        }
                    )
                )
            }
        )
        val h = Harness(testProxy, resolver)
        testProxy.executionContext.executeAsync<Data>(
            CreateFolder.ID,
            null,
            mapOf(CreateFolder.Params.PATH to FOLDER.toTString(h.context))
        )
        h.uploadFile("a.txt")
        testProxy.testClientConnection.addListener { h.emissions.add(it) }
        resolver.baseline = resolver.invocations
        return h
    }

    @Test
    fun deferredPropertyPatchesRowAfterInitialEmission() = runTest {
        val h = harness(backgroundScope)

        h.testProxy.executionContext.setDatabaseListener(1L, h.folderFilesRef(), h.selectStack())

        // Initial emission: the row carries the marker — the resolver was NOT paid inline.
        assertTrue(h.emissions.isNotEmpty(), "initial emission expected")
        val initialRow = h.entitiesOf(h.emissions.first()).single()
        assertIs<PendingDeferredProperty>(
            h.propertyOf(initialRow),
            "initial emission must carry the deferred marker, not the value"
        )

        // The patch: same row re-emitted with the resolved value.
        waitFor {
            h.emissions.size >= 2 && h.entitiesOf(h.emissions.last()).any {
                h.propertyOf(it) is TByteArray
            }
        }
        assertEquals(1, h.resolver.sinceBaseline, "one resolution for one marker")

        // The patch merged back into the stream state — no further emissions
        // without new DB activity.
        delay(200)
        assertEquals(2, h.emissions.size, "patched row must not re-emit on its own")
    }

    @Test
    fun failedResolutionSettlesRowWithoutProperty() = runTest {
        val h = harness(backgroundScope) { error("resolver exploded") }

        h.testProxy.executionContext.setDatabaseListener(2L, h.folderFilesRef(), h.selectStack())

        // Q1: the patch drops the property key — row settles, stream stays alive.
        waitFor {
            h.emissions.size >= 2 && h.entitiesOf(h.emissions.last()).any {
                h.propertyOf(it) == null
            }
        }
        val settledRow = h.entitiesOf(h.emissions.last()).single()
        assertNull(h.propertyOf(settledRow), "failed resolution settles the row with the property dropped")
        assertTrue(
            h.emissions.none { it is StreamElement.End<*> },
            "a failed deferred resolution must not kill the stream"
        )
    }

    @Test
    fun signalOnlyStreamSchedulesNoDeferredResolutions() = runTest {
        val h = harness(backgroundScope)

        h.testProxy.executionContext.setDatabaseListener(
            3L, h.folderFilesRef(), h.selectStack(), signalOnly = true
        )

        delay(200)
        assertEquals(0, h.resolver.sinceBaseline, "signal-only subscribers discard payloads — no patches")
        assertTrue(h.emissions.isEmpty(), "signal-only streams skip the initial emission entirely")
    }

    @Test
    fun removeDatabaseListenerCancelsQueuedResolutions() = runTest {
        val h = harness(backgroundScope)

        h.testProxy.executionContext.setDatabaseListener(4L, h.folderFilesRef(), h.selectStack())
        // Cancel before the queued patch coroutine gets scheduled.
        h.testProxy.executionContext.removeDatabaseListener(4L)

        delay(200)
        assertEquals(0, h.resolver.sinceBaseline, "queued resolutions must be cancelled with the stream")
        assertEquals(1, h.emissions.size, "only the initial emission — no patch after removal")
    }

    @Test
    fun arrayChangeReprojectsAndRepatches() = runTest {
        val h = harness(backgroundScope)

        h.testProxy.executionContext.setDatabaseListener(5L, h.folderFilesRef(), h.selectStack())
        waitFor {
            h.emissions.size >= 2 && h.entitiesOf(h.emissions.last()).any {
                h.propertyOf(it) is TByteArray
            }
        }

        // A new array member re-runs the projection (fresh markers — accepted
        // churn) and the queue re-patches every marker back to a value.
        h.uploadFile("b.txt")
        waitFor(timeoutMs = 4000) {
            val patched = h.emissions.flatMap { h.entitiesOf(it) }
                .filter { h.propertyOf(it) is TByteArray }
                .mapNotNull { it.fields.values.firstOrNull()?.toString() }
            h.emissions.size >= 4 && patched.size >= 2
        }

        // Terminal state: every row the stream knows settles to a real value.
        delay(200)
        val lastPerRow = h.emissions.flatMap { h.entitiesOf(it) }
        assertTrue(
            lastPerRow.any { h.propertyOf(it) is TByteArray },
            "rows re-patch after a re-projection"
        )
        assertTrue(h.resolver.sinceBaseline >= 2, "each marker generation resolves")
    }
}
