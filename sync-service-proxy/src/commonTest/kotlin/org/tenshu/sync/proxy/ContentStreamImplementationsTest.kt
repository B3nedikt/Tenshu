package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.ContentStreamOpening
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OnDemandResolution
import org.tenshu.core.entity.DataId
import org.tenshu.core.get
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTString
import org.tenshu.sync.CloseContentStream
import org.tenshu.sync.ContentStreamHandle
import org.tenshu.sync.OpenContentStream
import org.tenshu.sync.ReadContentRange
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.CloseContentStreamImplementation
import org.tenshu.sync.proxy.impl.OpenContentStreamImplementation
import org.tenshu.sync.proxy.impl.ReadContentRangeImplementation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D2b S2b (`drive-streaming-preview.md` §3.5) — unit coverage for each pull-op
 * [org.tenshu.core.common.proxy.executor.OperationImplementation]: that it reads its params,
 * delegates to the [OnDemandResolution] component with the right arguments, maps the result,
 * and surfaces a [TenshuException] on the unavailable/failed case. The resolver internals
 * (fetch → manifest → session → range) are covered separately by `ContentStreamOpsTest`; here
 * the resolver is a recording fake so each impl is tested in isolation.
 */
class ContentStreamImplementationsTest {

    private lateinit var fake: FakeOnDemandResolution
    private lateinit var testProxy: TestProxy

    @BeforeTest
    fun setup() {
        fake = FakeOnDemandResolution()
        testProxy = TestProxy(
            schemaRegistry = createCoreTestRegistry(),
            additionalComponents = mapOf(OnDemandResolution.ID to fake),
        ).also { it.schemaRegistry.registerConfig(syncServiceCommonModule) }
    }

    private val ctx get() = testProxy.executionContext

    // ---- OpenContentStream -------------------------------------------------

    @Test
    fun open_readsTriple_delegates_andMapsOpeningToHandle() = runTest {
        fake.openResult = ContentStreamOpening(handle = "cs-7", totalSize = 9_001L)

        val handle = OpenContentStreamImplementation().execute<ContentStreamHandle>(
            executionContext = ctx,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(
                OpenContentStream.Params.ENTITY_TYPE to "tenshu/file/file".toTString(testProxy.tContext),
                OpenContentStream.Params.ENTITY_KEY to "/movie.mp4".toTString(testProxy.tContext),
                OpenContentStream.Params.PROPERTY_ID to "tenshu/file/file/data".toTString(testProxy.tContext),
            ),
        )

        // Mapped the opening onto the returned composite.
        assertEquals("cs-7", handle.handle.get())
        assertEquals(9_001L, handle.totalSize.get())
        // Delegated with the entity DataId built from (type, key) + the propertyId.
        val entity = fake.lastOpenEntityId as DataId.StringDataId
        assertEquals("tenshu/file/file", entity.type.get())
        assertEquals("/movie.mp4", entity.keyValue.get())
        assertEquals("tenshu/file/file/data", fake.lastOpenPropertyId)
    }

    @Test
    fun open_unavailable_throws() = runTest {
        fake.openResult = null
        assertFailsWith<TenshuException> {
            OpenContentStreamImplementation().execute<ContentStreamHandle>(
                executionContext = ctx,
                operationContext = null,
                implementationFactory = { null },
                caller = null,
                params = mapOf(
                    OpenContentStream.Params.ENTITY_TYPE to "t".toTString(testProxy.tContext),
                    OpenContentStream.Params.ENTITY_KEY to "k".toTString(testProxy.tContext),
                    OpenContentStream.Params.PROPERTY_ID to "p".toTString(testProxy.tContext),
                ),
            )
        }
    }

    // ---- ReadContentRange --------------------------------------------------

    @Test
    fun read_readsParams_delegates_andReturnsBytes() = runTest {
        fake.readResult = byteArrayOf(9, 8, 7, 6)

        val bytes = ReadContentRangeImplementation().execute<TByteArray>(
            executionContext = ctx,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(
                ReadContentRange.Params.HANDLE to "cs-7".toTString(testProxy.tContext),
                ReadContentRange.Params.OFFSET to 4_096L.toTInteger(testProxy.tContext),
                ReadContentRange.Params.LENGTH to 256.toTInteger(testProxy.tContext),
            ),
        )

        assertContentEquals(byteArrayOf(9, 8, 7, 6), bytes.get())
        assertEquals(Triple("cs-7", 4_096L, 256), fake.lastRead)
    }

    @Test
    fun read_failure_throws() = runTest {
        fake.readResult = null
        assertFailsWith<TenshuException> {
            ReadContentRangeImplementation().execute<TByteArray>(
                executionContext = ctx,
                operationContext = null,
                implementationFactory = { null },
                caller = null,
                params = mapOf(
                    ReadContentRange.Params.HANDLE to "gone".toTString(testProxy.tContext),
                    ReadContentRange.Params.OFFSET to 0L.toTInteger(testProxy.tContext),
                    ReadContentRange.Params.LENGTH to 16.toTInteger(testProxy.tContext),
                ),
            )
        }
    }

    // ---- CloseContentStream ------------------------------------------------

    @Test
    fun close_readsHandle_andDelegates() {
        val result = CloseContentStreamImplementation().execute<Data?>(
            executionContext = ctx,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(
                CloseContentStream.Params.HANDLE to "cs-7".toTString(testProxy.tContext),
            ),
        )
        assertNull(result, "close returns null")
        assertEquals(listOf("cs-7"), fake.closed)
    }

    @Test
    fun close_unknownHandle_isCleanNoOp_whenResolverMissing() {
        // No resolver component ⇒ close is a silent no-op (idempotent contract), no throw.
        val noResolver = TestProxy(schemaRegistry = createCoreTestRegistry())
            .also { it.schemaRegistry.registerConfig(syncServiceCommonModule) }
        val result = CloseContentStreamImplementation().execute<Data?>(
            executionContext = noResolver.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(
                CloseContentStream.Params.HANDLE to "x".toTString(noResolver.tContext),
            ),
        )
        assertNull(result)
    }
}

/** Recording fake of the [OnDemandResolution] component for the impl unit tests. */
private class FakeOnDemandResolution : OnDemandResolution {

    var openResult: ContentStreamOpening? = ContentStreamOpening("cs-0", 0L)
    var lastOpenEntityId: DataId? = null
    var lastOpenPropertyId: String? = null

    var readResult: ByteArray? = ByteArray(0)
    var lastRead: Triple<String, Long, Int>? = null

    val closed = mutableListOf<String>()

    override suspend fun resolve(
        executionContext: ExecutionContext,
        entityId: DataId,
        propertyId: String,
        params: TList<Data>?,
    ): TResult<Data?> = TResult.Success.create(executionContext.context, null)

    override suspend fun openContentStream(
        executionContext: ExecutionContext,
        entityId: DataId,
        propertyId: String,
    ): ContentStreamOpening? {
        lastOpenEntityId = entityId
        lastOpenPropertyId = propertyId
        return openResult
    }

    override suspend fun readContentRange(handle: String, offset: Long, length: Int): ByteArray? {
        lastRead = Triple(handle, offset, length)
        return readResult
    }

    override fun closeContentStream(handle: String): Boolean {
        closed += handle
        return true
    }
}
