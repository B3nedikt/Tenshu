package org.tenshu.core

import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for [isALogic] — the type test behind the [isA] chain op. The entity arm
 * (`Entity.type` + a `SchemaProvider` walk) is covered where a provider exists:
 * `OrderedWindowFusionTest` in `:core-common` runs it over stored rows in both shapes.
 */
class IsALogicTest {

    private val base: CompositeSchema = composite(id = "test/isA/base")
    private val derived: CompositeSchema = composite(id = "test/isA/derived", parent = base)
    private val unrelated: CompositeSchema = composite(id = "test/isA/unrelated")

    private fun rowOf(rowSchema: CompositeSchema): Composite = object : Composite {
        override val schema: CompositeSchema get() = rowSchema
        override fun <T : Data> getField(reference: String): T = error("fieldless fixture")
        override fun <T : Data> getOptionalField(reference: String): T? = null
    }

    @Test
    fun ownTypeMatches() {
        assertTrue(isALogic(rowOf(base), base.id.get()).get())
        assertTrue(isALogic(rowOf(derived), derived.id.get()).get())
    }

    @Test
    fun subtypeIsAnInstanceOfItsParent_butNotTheOtherWayRound() {
        // `Folder : File` — a folder is a file, a file is not a folder.
        assertTrue(isALogic(rowOf(derived), base.id.get()).get())
        assertFalse(isALogic(rowOf(base), derived.id.get()).get())
    }

    @Test
    fun unrelatedTypeIsFalse_notAnError() {
        // Mirrors Kotlin's `is` on an unrelated class, and an id the proxy does not know
        // (a peer-only type) must read as "no row is that" rather than fail the chain.
        assertFalse(isALogic(rowOf(derived), unrelated.id.get()).get())
        assertFalse(isALogic(rowOf(derived), "test/isA/never-registered").get())
    }

    @Test
    fun valueTypesWalkTheirOwnParentChain() {
        assertTrue("x".toTString().isA(TString.Schema).get())
        assertTrue("x".toTString().isA(TValue.Schema).get())
        assertFalse("x".toTString().isA(TBoolean.Schema).get())
    }

    @Test
    fun inMemorySpelling_takesTheSchema() {
        assertTrue(rowOf(derived).isA(base).get())
        assertFalse(rowOf(base).isA(derived).get())
    }
}
