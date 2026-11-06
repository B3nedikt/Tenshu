package org.tenshu.core.schema

import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 1 of `docs/adr/0009-operation-schema-typing.md`: the new `receiver` and `typeParams` slots
 * on [OperationSchema] are reachable through the lazy form of the DSL builder. Round-trip
 * through DTO is covered in `:core-common`'s `OperationSchemaTypingRoundTripTest`.
 */
class OperationSchemaTypingTest {

    @Test
    fun operation_withoutReceiverOrTypeParams_defaultsToNullAndEmpty() {
        val schema = operation(id = "test/op/plain")

        assertNull(schema.receiver, "receiver should default to null")
        assertEquals(0, schema.typeParams.toList().size, "typeParams should default to empty")
    }

    @Test
    fun operation_withReceiver_exposesIt() {
        val schema = operation(
            id = "test/op/with-receiver",
            receiver = Data.Schema.toType(),
        )

        val receiver = schema.receiver
        assertNotNull(receiver, "receiver should be present")
        assertEquals(Data.ID, receiver.baseSchema?.id?.get())
    }

    @Test
    fun operation_withTypeParams_exposesDeclarations() {
        val schema = operation(
            id = "test/op/with-type-params",
            typeParams = {
                type("E", Data.Schema)
                type("R")
            },
        )

        val params = schema.typeParams.toList()
        assertEquals(2, params.size)
        assertEquals("E", params[0].name.get())
        assertEquals(Data.ID, params[0].bound.id.get())
        assertEquals("R", params[1].name.get())
        // Bound defaults to Data.Schema when omitted.
        assertEquals(Data.ID, params[1].bound.id.get())
    }

    @Test
    fun operation_typeParamsBlockReturnsBinder_usableInListType() {
        // Mirrors the §1.2 "want" example for Drop<E : Data>.
        val schema = operation(
            id = "test/op/drop-shape",
            receiver = listType("E"),
            typeParams = { type("E", Data.Schema) },
            returnType = listType("E"),
        )

        // Receiver is a generic TList parameterised by a TypeVariable("E").
        val receiver = schema.receiver
        assertNotNull(receiver, "receiver should be non-null")
        val recvParam = receiver.params.toList().single()
        val recvParamType = recvParam.type
        assertTrue(
            recvParamType is TypeVariable,
            "receiver's element type should be a TypeVariable, got ${recvParamType::class.simpleName}"
        )
        assertEquals("E", recvParamType.name.get())

        // Return type also references the same variable.
        val rtParam = (schema.returnType as GenericType).params.toList().single().type
        assertTrue(rtParam is TypeVariable)
        assertEquals("E", rtParam.name.get())
    }

    @Test
    fun typeVar_producesTypeVariable() {
        val v: Type = typeVar("X")
        assertTrue(v is TypeVariable, "typeVar() should produce a TypeVariable")
        assertEquals("X", v.name.get())
    }

    @Test
    fun typeParameterDecl_defaultBoundIsData() {
        val decl = TypeParameterDecl.create(name = "T")
        assertEquals("T", decl.name.get())
        assertEquals(Data.ID, decl.bound.id.get())
    }

    @Test
    fun operationSchemaSchema_meta_exposesNewFieldsInValues() {
        // Bootstrap: the meta-schema OperationSchema.Schema is built with the new
        // `params = { ... receiver/typeParams ... }` block. Its values list should
        // include the two new field IDs.
        val fieldIds = OperationSchema.Schema.values.toList().map { it.id.get() }
        assertTrue(
            OperationSchema.Fields.RECEIVER in fieldIds,
            "OperationSchema.Schema.values should include RECEIVER, got $fieldIds"
        )
        assertTrue(
            OperationSchema.Fields.TYPE_PARAMS in fieldIds,
            "OperationSchema.Schema.values should include TYPE_PARAMS, got $fieldIds"
        )
    }
}
