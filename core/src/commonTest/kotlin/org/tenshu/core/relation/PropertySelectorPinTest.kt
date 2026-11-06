package org.tenshu.core.relation

import org.tenshu.core.Composite
import org.tenshu.core.collection.list.toList
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.composite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pins the `Property` → wire-selector mapping
 * (`docs/adr/0013-property-authoring-unification.md`): the unified
 * handle must mint exactly the `StaticPropertyRelation` / `DynamicPropertyRelation` call-site
 * composites the engine already consumes, so the wire value is provably
 * unchanged by the authoring-layer refactor. Context-free: `selector()` reads
 * only the handle id + bound params, never materialising the relation schema.
 */
class PropertySelectorPinTest {

    private val receiver = composite(id = "test/handle/receiver")

    @Test
    fun valueHandleMintsStaticPropertySelector() {
        val handle = valueProperty<TInteger>(id = "test/handle/value", schema = TInteger.Schema)
        val static = assertIs<StaticPropertyRelation>(handle.selector())
        assertEquals("test/handle/value", static.reference.get())
    }

    @Test
    fun dynamicHandleMintsDynamicPropertySelectorWithoutParams() {
        val handle = dynamicProperty<Composite, TInteger>(
            id = "test/handle/dynamic",
            receiver = receiver,
        ) { error("chain must not materialise during selector minting") }
        val dynamic = assertIs<DynamicPropertyRelation>(handle.selector())
        assertEquals("test/handle/dynamic", dynamic.reference.get())
        assertNull(dynamic.params, "no bound params -> null params on the wire")
    }

    @Test
    fun dynamicHandleSelectorCarriesBoundParams() {
        val handle = dynamicProperty<Composite, TInteger>(
            id = "test/handle/dynamic/params",
            receiver = receiver,
        ) { error("chain must not materialise during selector minting") }
        val dynamic = assertIs<DynamicPropertyRelation>(
            handle.withParams(7.toTInteger(), 9.toTInteger()).selector()
        )
        assertEquals("test/handle/dynamic/params", dynamic.reference.get())
        val params = dynamic.params?.toList()?.map { (it as TInteger).get() }
        assertEquals(listOf(7L, 9L), params, "withParams binds the projection params")
    }
}
