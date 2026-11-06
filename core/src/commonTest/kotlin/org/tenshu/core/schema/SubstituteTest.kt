package org.tenshu.core.schema

import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [substitute] — Phase 3.4 of `docs/adr/0005-composite-schema-typing.md`.
 * Exercises the helper's three branches: TypeVariable resolution, recursive
 * GenericType walking, and concrete-type passthrough.
 */
class SubstituteTest {

    @Test
    fun substitute_typeVariable_resolvesViaBindings() {
        val v = typeVar("E")
        val bindings = mapOf("E" to Data.Schema.toType())

        val result = substitute(v, bindings)

        assertEquals(Data.ID, result.baseSchema?.id?.get())
    }

    @Test
    fun substitute_unboundTypeVariable_throws() {
        val v = typeVar("X")
        val ex = assertFailsWith<IllegalStateException> {
            substitute(v, emptyMap())
        }
        assertTrue(
            ex.message?.contains("Unbound type variable 'X'") == true,
            "Diagnostic should name the variable, got: ${ex.message}"
        )
    }

    @Test
    fun substitute_concreteType_baseSchemaIsPreserved() {
        // GenericType is always rebuilt (params are recursively substituted), but
        // the baseSchema and shape stay identical when no variables appear.
        val concrete = Data.Schema.toType()
        val result = substitute(concrete, mapOf("E" to TList.Schema.toType()))
        assertEquals(concrete.baseSchema?.id?.get(), result.baseSchema?.id?.get())
    }

    @Test
    fun substitute_walksNestedGenerics() {
        // TList<Maybe<E>> with bindings E → Data
        val inner = maybeType("E")           // Maybe<E>
        val outer = listType("E")            // TList<E> — wait, easier: TList<Maybe<E>>
        // Construct TList<Maybe<E>> manually
        val nested = typeOf(TList.Schema).of(maybeType("E"))
        val bindings = mapOf("E" to Data.Schema.toType())

        val result = substitute(nested, bindings)

        // Result should be TList<Maybe<Data>>.
        assertEquals(TList.ID, result.baseSchema?.id?.get())
        val innerType = result.params.toList().single().type
        // innerType is Maybe<Data>; its first type-param's base is Data.
        val innerElement = innerType.params.toList().single().type
        assertEquals(Data.ID, innerElement.baseSchema?.id?.get())
    }

    @Test
    fun substitute_walksTwoVariableGeneric_TMapShape() {
        // Synthetic TMap<K, V>-shaped generic with two type vars K, V.
        // Verifies that K and V resolve independently — the case the previous
        // `parentParams.first()` heuristic got silently wrong.
        val tmap = typeOf(TList.Schema).of(typeVar("K"), typeVar("V"))
        val bindings = mapOf(
            "K" to Data.Schema.toType(),
            "V" to TList.Schema.toType()
        )

        val result = substitute(tmap, bindings)

        val resolved = result.params.toList()
        assertEquals(Data.ID, resolved[0].type.baseSchema?.id?.get())
        assertEquals(TList.ID, resolved[1].type.baseSchema?.id?.get())
    }
}
