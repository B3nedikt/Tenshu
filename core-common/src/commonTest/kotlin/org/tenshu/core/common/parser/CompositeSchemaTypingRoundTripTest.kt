package org.tenshu.core.common.parser

import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.LazyDataCreator
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Phase 1 round-trip tests for `docs/adr/0005-composite-schema-typing.md`. The new
 * [CompositeSchema.typeParams] slot is reachable through the lazy form of the
 * `composite(...)` builder and survives a registry materialisation through
 * `CompositeSchemaDto`.
 */
class CompositeSchemaTypingRoundTripTest {

    private val registry = createCoreTestRegistry()

    @Test
    fun composite_withoutTypeParams_defaultsToEmpty() {
        val schema = composite(id = "test/composite/plain")
        assertEquals(0, schema.typeParams.toList().size)
    }

    @Test
    fun composite_withTypeParams_lazyExposesIt() {
        val schema = composite(
            id = "test/composite/with-type-params",
            typeParams = {
                type("E", Data.Schema)
                type("R")
            },
        )
        val tps = schema.typeParams.toList()
        assertEquals(2, tps.size)
        assertEquals("E", tps[0].name.get())
        assertEquals(Data.ID, tps[0].bound.id.get())
        assertEquals("R", tps[1].name.get())
        assertEquals(Data.ID, tps[1].bound.id.get())
    }

    @Test
    fun composite_withTypeParams_roundTripsThroughDto() {
        val schema = composite(
            id = "test/composite/round-trip",
            typeParams = {
                type("K", Data.Schema)
                type("V", Data.Schema)
            },
        )

        @Suppress("UNCHECKED_CAST")
        val materialised = registry.getOrCreate(schema as LazyDataCreator<CompositeSchema>)
        val tps = materialised.typeParams.toList()
        assertEquals(2, tps.size)
        assertEquals("K", tps[0].name.get())
        assertEquals("V", tps[1].name.get())
    }

    @Test
    fun compositeSchemaSchema_isMaterializableOnFreshRegistry() {
        // Bootstrap: the meta-schema CompositeSchema.Schema is itself a CompositeSchema.
        // After Phase 1 it gains a typeParams field; the meta-instance has it empty.
        // Materialisation on a fresh registry must not loop or fail.
        @Suppress("UNCHECKED_CAST")
        val materialised = registry.getOrCreate(
            CompositeSchema.Schema as LazyDataCreator<CompositeSchema>
        )
        assertEquals(CompositeSchema.ID, materialised.id.get())
        assertEquals(0, materialised.typeParams.toList().size)
    }

    @Test
    fun composite_meta_includesTypeParamsInValues() {
        // The meta-schema's `values` list now declares the new TYPE_PARAMS field
        // alongside VALUES and TRAITS.
        val fieldIds = CompositeSchema.Schema.values.toList().map { it.id.get() }
        assertNotNull(
            fieldIds.find { it == CompositeSchema.Fields.TYPE_PARAMS },
            "CompositeSchema.Schema.values should declare TYPE_PARAMS, got: $fieldIds"
        )
    }

    @Test
    fun phase2_parameterisedComposites_declareTypeParams() {
        // Phase 2 of `docs/adr/0005-composite-schema-typing.md`: every parameterised composite
        // declares its type variables. Single source of truth; if a future composite
        // gets generic parameters in code but forgets the schema declaration, this
        // test surfaces the mismatch.
        data class Case(val name: String, val schema: CompositeSchema, val expected: List<String>)

        val cases = listOf(
            Case("TList<E>", org.tenshu.core.collection.list.TList.Schema, listOf("E")),
            Case("TPair<A,B>", org.tenshu.core.collection.TPair.Schema, listOf("A", "B")),
            Case("TMap<K,V>", org.tenshu.core.collection.TMap.Schema, listOf("K", "V")),
            Case("Maybe.Some<E>", org.tenshu.core.Maybe.Some.Schema, listOf("E")),
            Case("Maybe.Empty<E>", org.tenshu.core.Maybe.Empty.Schema, listOf("E")),
            Case("TResult.Success<E>", org.tenshu.core.TResult.Success.Schema, listOf("E")),
            Case("TResult.Failure<E>", org.tenshu.core.TResult.Failure.Schema, listOf("E")),
            Case("StreamElement.Value<E>", org.tenshu.core.StreamElement.Value.Schema, listOf("E")),
            Case("StreamElement.Removed<E>", org.tenshu.core.StreamElement.Removed.Schema, listOf("E")),
            Case("StreamElement.End<E>", org.tenshu.core.StreamElement.End.Schema, listOf("E")),
            Case("Entity<E>", org.tenshu.core.entity.Entity.Schema, listOf("E")),
        )

        cases.forEach { (name, schema, expected) ->
            val actualNames = schema.typeParams.toList().map { it.name.get() }
            assertEquals(
                expected,
                actualNames,
                "$name typeParams mismatch: expected $expected, got $actualNames"
            )
        }
    }
}
