package org.tenshu.core.common.parser

import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.schema.GenericType
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.TypeParameterDecl
import org.tenshu.core.schema.TypeVariable
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.schema.type
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 1.6 round-trip: an `operation(...)` declaring `receiver` and `typeParams`
 * survives materialization through the registered DTO factories, and the resulting
 * `OperationSchemaDto` exposes the same fields back.
 */
class OperationSchemaTypingRoundTripTest {

    private val registry = createCoreTestRegistry()

    @Test
    fun operation_withReceiverAndTypeParams_roundTripsThroughDto() {
        val schema = operation(
            id = "test/op/drop-shape-rt",
            receiver = listType("E"),
            typeParams = {
                type("E", Data.Schema)
            },
            returnType = listType("E"),
        )

        // Lazy form should already expose the fields; round-trip through the registry's
        // materializer turns the LazyComposite into an OperationSchemaDto-flavored DTO.
        val materialized =
            registry.getOrCreate(schema as org.tenshu.core.schema.LazyDataCreator<OperationSchema>)

        // Receiver is GenericType<TList, [TypeVariable("E")]>.
        val receiver = materialized.receiver
        assertNotNull(receiver, "receiver should round-trip non-null")
        val genericReceiver = receiver as? GenericType
        assertNotNull(genericReceiver, "receiver should be a GenericType after round-trip")
        val recvParam = genericReceiver.params.toList().single().type
        assertTrue(
            recvParam is TypeVariable,
            "receiver's element should be a TypeVariable after round-trip, got ${recvParam::class.simpleName}"
        )
        assertEquals("E", recvParam.name.get())

        // typeParams contains one decl with name "E", bound Data.
        val tps = materialized.typeParams.toList()
        assertEquals(1, tps.size)
        assertEquals("E", tps[0].name.get())
        assertEquals(Data.ID, tps[0].bound.id.get())

        // returnType also references the variable.
        val rt = materialized.returnType
        val genericRt = rt as? GenericType
        assertNotNull(genericRt)
        val rtElement = genericRt.params.toList().single().type
        assertTrue(rtElement is TypeVariable)
        assertEquals("E", rtElement.name.get())
    }

    @Test
    fun operation_withoutReceiver_roundTripsToNullReceiver() {
        val schema = operation(
            id = "test/op/no-receiver-rt",
            typeParams = { type("R") },
        )

        val materialized =
            registry.getOrCreate(schema as org.tenshu.core.schema.LazyDataCreator<OperationSchema>)
        assertNull(materialized.receiver, "absent receiver should round-trip as null")

        // typeParams is still present (as an empty-or-populated TList, never null).
        val tps = materialized.typeParams.toList()
        assertEquals(1, tps.size)
        assertEquals("R", tps[0].name.get())
    }

    @Test
    fun typeVariableSchema_andTypeParameterDeclSchema_areRegistered() {
        // Bootstrap: the new schemas resolve via the registry.
        assertNotNull(
            registry.getSchema(TypeVariable.ID),
            "TypeVariable.Schema must be registered in the core registry"
        )
        assertNotNull(
            registry.getSchema(TypeParameterDecl.ID),
            "TypeParameterDecl.Schema must be registered in the core registry"
        )
    }

    @Test
    fun operationSchemaSchema_isMaterializableOnFreshRegistry() {
        // The meta-schema OperationSchema.Schema is itself an OperationSchema. With the
        // two new fields added in Phase 1, it must still materialize cleanly.
        val materialized = registry.getOrCreate(
            OperationSchema.Schema as org.tenshu.core.schema.LazyDataCreator<OperationSchema>
        )
        assertEquals(OperationSchema.ID, materialized.id.get())

        // Meta-instance has receiver = null and typeParams = empty.
        assertNull(materialized.receiver)
        assertEquals(0, materialized.typeParams.toList().size)
    }

    @Test
    fun operation_concreteReceiver_roundTripsBaseSchemaId() {
        val schema = operation(
            id = "test/op/concrete-receiver-rt",
            receiver = Data.Schema.toType(),
        )

        val materialized =
            registry.getOrCreate(schema as org.tenshu.core.schema.LazyDataCreator<OperationSchema>)
        val receiver: Type? = materialized.receiver
        assertNotNull(receiver)
        val generic = receiver as? GenericType
        assertNotNull(generic)
        assertEquals(Data.ID, generic.baseSchema.id.get())
    }

    @Test
    fun typeVariableDto_roundTripsThroughDataFactory() {
        // Demonstrates that the TypeVariable composite factory is wired: building one via
        // dataFactory and reading the name back works end-to-end.
        val name = "T".toTString()
        val dto = registry.createComposite<TypeVariable>(
            type = TypeVariable.ID,
            fields = listOf(name)
        )
        assertEquals("T", dto.name.get())
        assertEquals(TypeVariable.ID, dto.schema.id.get())
    }
}
