package org.tenshu.file.proxy

import org.tenshu.core.ChainTemplate
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.common.impl.reference.DynamicPropertyTypeReferenceDto
import org.tenshu.core.common.module.dynamicPropertyRelation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.PendingDeferredProperty
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.Preview
import org.tenshu.core.file.ViewerPreview
import org.tenshu.core.file.getPreview
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.dynamicPropertyRef
import org.tenshu.core.toTBoolean
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.tenshu.core.schema.dynamicPropertyRelation as schemaDynamicPropertyRelation

/**
 * Deferred-property semantics in `SelectImplementation`
 * (`docs/proposals/core-deferred-properties.md` §4/§5): a deferred selector
 * writes a [PendingDeferredProperty] marker **instead of invoking the
 * resolver**; the per-select `Resolved` override beats the schema's
 * `deferredByDefault`; rows resolve eagerly when nothing marks them deferred.
 */
class DeferredSelectOperationTest {

    private class CountingResolver(private val bytes: TByteArray) : SyncOperationImplementation {
        var invocations = 0
            private set

        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?
        ): T {
            invocations++
            @Suppress("UNCHECKED_CAST")
            return bytes as T
        }
    }

    private class Harness(deferredByDefault: Boolean) {
        val testProxy = createFileProxy()
        val context = testProxy.tContext
        val resolver = CountingResolver(TByteArrayDto(byteArrayOf(1, 2)))
        val relationId = "test/deferredSelect/preview"

        init {
            testProxy.additionalImplementations[GetPreview.ID] = resolver
            testProxy.schemaRegistry.registerConfig(
                tModule {
                    dynamicPropertyRelation(
                        fromId = File.ID,
                        schema = schemaDynamicPropertyRelation(
                            id = relationId,
                            params = listOf(TInteger.Schema, TInteger.Schema),
                            deferred = deferredByDefault,
                            callstack = ChainTemplate<File, TByteArray> { params ->
                                val width = params[0] as Long
                                val height = params[1] as Long
                                getPreview(width = width.toInt(), height = height.toInt())
                            }
                        )
                    )
                }
            )
        }

        fun select(selector: Selector<File>): Entity<*> {
            val testFile = File.create(context = context, path = "/test")
            testProxy.database.saveData(testFile)
            val result = testProxy.executionContext.execute<TList<Entity<File>>>(
                operationId = Select.ID,
                caller = TListDto(listOf(testFile)),
                params = mapOf(Select.Params.SELECTORS to TListDto(listOf(selector)))
            )
            return result.toList()[0]
        }

        fun params(): TList<Data> =
            TListDto(listOf(100.toTInteger(context), 100.toTInteger(context)))

        fun baseSelector(): Selector<File> = Selector.DynamicPropertySelector.create(
            context = context,
            dynamicPropertyReference = dynamicPropertyRef(relationId),
            params = params(),
        )

        fun resolvedSelector(deferred: Boolean): Selector<File> =
            Selector.DynamicPropertySelector.Resolved.create(
                context = context,
                dynamicPropertyReference = dynamicPropertyRef(relationId),
                params = params(),
                deferred = deferred.toTBoolean(context),
            )
    }

    @Test
    fun schemaDeferredWritesMarkerWithoutResolving() {
        val h = Harness(deferredByDefault = true)
        val item = h.select(h.baseSelector())

        val marker = assertIs<PendingDeferredProperty>(
            item.properties[DynamicPropertyTypeReferenceDto(h.relationId)],
            "schema-level deferred must write the marker into the property slot"
        )
        assertEquals(h.relationId, marker.propertyId.get())
        assertEquals(
            listOf(100L, 100L),
            marker.params?.toList()?.map { (it as TInteger).get() },
            "the marker must carry the projection params for later resolution"
        )
        assertEquals(0, h.resolver.invocations, "deferral must never pay the resolver inline")
    }

    @Test
    fun eagerOverrideResolvesInlineDespiteSchemaFlag() {
        val h = Harness(deferredByDefault = true)
        val item = h.select(h.resolvedSelector(deferred = false))

        assertEquals(
            TByteArrayDto(byteArrayOf(1, 2)),
            item.properties[DynamicPropertyTypeReferenceDto(h.relationId)],
            "eager(...) must force inline resolution"
        )
        assertEquals(1, h.resolver.invocations)
    }

    @Test
    fun deferredOverrideDefersUnflaggedRelation() {
        val h = Harness(deferredByDefault = false)
        val item = h.select(h.resolvedSelector(deferred = true))

        assertIs<PendingDeferredProperty>(
            item.properties[DynamicPropertyTypeReferenceDto(h.relationId)]
        )
        assertEquals(0, h.resolver.invocations)
    }

    @Test
    fun unflaggedRelationStillResolvesInline() {
        val h = Harness(deferredByDefault = false)
        val item = h.select(h.baseSelector())

        assertEquals(
            TByteArrayDto(byteArrayOf(1, 2)),
            item.properties[DynamicPropertyTypeReferenceDto(h.relationId)]
        )
        assertEquals(1, h.resolver.invocations)
    }

    // ---- the production File.Preview migration (Phase 4) --------------------------------

    @Test
    fun filePreviewIsDeferredByDefault() {
        // Pins `deferred = true` on the REAL File.Preview registration: a plain
        // `relations(File.Preview(w, h))` projection writes the marker and never
        // pays the resolver inline.
        val h = Harness(deferredByDefault = false)
        val selector = Selector.DynamicPropertySelector.create<File>(
            context = h.context,
            dynamicPropertyReference = dynamicPropertyRef(File.Preview.id),
            params = h.params(),
        )
        val item = h.select(selector)

        assertIs<PendingDeferredProperty>(
            item.properties[DynamicPropertyTypeReferenceDto(File.Preview.id)],
            "File.Preview must be deferred-by-default (core-deferred-properties.md P4)"
        )
        assertEquals(0, h.resolver.invocations)
    }

    /**
     * ⚠⚠ The same pin for `File.ViewerPreview`, and it matters **more** than [File.Preview]'s: this
     * is the read that may decline a stored rung and render from the original, so a projection that
     * paid it inline would read a whole file per row (`drive-xxlarge-thumbnail-rung.md` X-3). The
     * viewer is a one-shot caller and wraps it in `eager(...)`; nothing else may.
     */
    @Test
    fun fileViewerPreviewIsDeferredByDefault() {
        val h = Harness(deferredByDefault = false)
        val selector = Selector.DynamicPropertySelector.create<File>(
            context = h.context,
            dynamicPropertyReference = dynamicPropertyRef(File.ViewerPreview.id),
            params = h.params(),
        )
        val item = h.select(selector)

        assertIs<PendingDeferredProperty>(
            item.properties[DynamicPropertyTypeReferenceDto(File.ViewerPreview.id)],
            "File.ViewerPreview must be deferred-by-default — it can read a whole original",
        )
        assertEquals(0, h.resolver.invocations)
    }

    @Test
    fun eagerFilePreviewResolvesInline() {
        // Pins the TenshuFilesRepository.getPreview contract: the one-shot
        // thumbnail loader wraps with eager(...) and MUST get real bytes.
        val h = Harness(deferredByDefault = false)
        val selector = Selector.DynamicPropertySelector.Resolved.create<File>(
            context = h.context,
            dynamicPropertyReference = dynamicPropertyRef(File.Preview.id),
            params = h.params(),
            deferred = false.toTBoolean(h.context),
        )
        val item = h.select(selector)

        assertEquals(
            TByteArrayDto(byteArrayOf(1, 2)),
            item.properties[DynamicPropertyTypeReferenceDto(File.Preview.id)],
            "eager(File.Preview(w, h)) must resolve inline — the thumbnail loader depends on it"
        )
        assertEquals(1, h.resolver.invocations)
    }

    @Test
    fun markerCarriesTheRowsEntityId() {
        val h = Harness(deferredByDefault = true)
        val item = h.select(h.baseSelector())

        val marker = assertIs<PendingDeferredProperty>(
            item.properties[DynamicPropertyTypeReferenceDto(h.relationId)]
        )
        // File's DataId derives from its path — the patch path re-reads the row
        // through this id, so it must be the row's own identity.
        val stringId = assertIs<DataId.StringDataId>(marker.entityId)
        assertEquals("/test", stringId.keyValue.get())
    }
}
