package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.PureOperationRunner
import org.tenshu.core.SimpleComposite
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.module.referenceProperty
import org.tenshu.core.common.module.tModule
import org.tenshu.core.entity.DataId
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.valueProperty
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.toTString
import org.tenshu.core.trait.traitResolver
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins the **reference value property** primitive
 * (`docs/proposals/core-reference-property.md`): a `Property.Value<T>` whose value is
 * resolved live from a foreign entity keyed by one of the receiver's own scalar fields.
 *
 * The deref reuses the existing `DataId` mechanism — `StringDataId(target, <key>)` +
 * `Database.getData` — and the wire selector stays the uniform `StaticPropertyRelation`,
 * so these tests exercise the new `ReferenceResolver` branch directly over a stub
 * `Database`.
 */
class ReferencePropertyTest {

    private val targetSchema = composite(id = "tenshu/test/refProperty/target", parent = Composite.Schema)
    private val sourceSchema = composite(id = "tenshu/test/refProperty/source", parent = Composite.Schema)
    private val keyField = "tenshu/test/refProperty/source/key"

    private val toTarget: Property.Value<Composite> =
        valueProperty(id = "tenshu/test/refProperty/source/target", schema = targetSchema)

    @Test
    fun resolvesForeignEntityByKey() {
        val target = SimpleComposite(targetSchema, emptyMap())
        val ctx = setup(
            getData = { dataId ->
                if (dataId is DataId.StringDataId &&
                    dataId.type.get() == targetSchema.id.get() &&
                    dataId.keyValue.get() == "k1"
                ) target else null
            }
        )
        val source = SimpleComposite(sourceSchema, mapOf(keyField to "k1".toTString()))

        val resolved = ctx.resolveProperty(source, propertyRef(toTarget.id))

        assertSame(target, resolved, "should dereference the live foreign entity by its key")
    }

    @Test
    fun returnsNullWhenTargetAbsent() {
        val ctx = setup(getData = { null }) // no stored target for the key
        val source = SimpleComposite(sourceSchema, mapOf(keyField to "missing".toTString()))

        assertNull(ctx.resolveProperty(source, propertyRef(toTarget.id)))
    }

    // --- Fixtures ---------------------------------------------------------------

    private fun setup(getData: (DataId) -> Data?): ExecutionContext {
        val registry = createCoreTestRegistry()
        registry.registerConfig(
            tModule {
                referenceProperty(
                    fromId = sourceSchema.id.get(),
                    property = toTarget,
                    keyField = keyField,
                )
            }
        )
        val tContext = createTestContext(registry)
        val database = stubDatabase(getData)
        val materializer = CallstackMaterializer(registry, tContext)
        val resolver = ReferenceResolverImpl(
            context = tContext,
            database = database,
            materializer = materializer,
        )
        return ExecutionContext(
            context = tContext,
            database = database,
            traitResolver = tContext.traitResolver(),
            implementationFactory = { null },
            clientConnection = object : ClientConnection {
                override fun init() {}
                override fun send(data: Data) {}
                override fun destroy() {}
            },
            pureOperationRunner = tContext.components[PureOperationRunner.ID] as PureOperationRunner,
            materializer = materializer,
            sessionScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            referenceResolver = resolver,
        )
    }

    private fun stubDatabase(getData: (DataId) -> Data?): Database = object : Database {
        override fun getData(dataId: DataId, asEntity: Boolean, projection: Projection?): Data? =
            getData(dataId)

        override fun saveData(data: Data) {}
        override fun deleteData(dataId: DataId) {}
        override fun getArrayData(reference: ArrayDatabaseReference, projection: Projection?): Sequence<Data>? = null
        override fun getComposite(reference: CompositeDatabaseReference): Data? = null
        override fun addToArray(reference: ArrayDatabaseReference, value: Data, origin: WriteOrigin) {}
        override fun removeFromArray(reference: ArrayDatabaseReference, dataId: DataId, origin: WriteOrigin) {}
        override fun setArrayListener(
            reference: ArrayDatabaseReference,
            onAdded: (Data, WriteOrigin) -> Unit,
            onRemoved: (DataId, WriteOrigin) -> Unit
        ): Long = 0L

        override fun removeArrayListener(handle: Long) {}
        override fun <R> transaction(block: () -> R): R = block()
    }
}
