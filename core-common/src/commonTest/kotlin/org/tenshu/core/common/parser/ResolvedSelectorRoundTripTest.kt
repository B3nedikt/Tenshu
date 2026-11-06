package org.tenshu.core.common.parser

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.module.dynamicProperty
import org.tenshu.core.common.module.tModule
import org.tenshu.core.core.buildList
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.relation.deferred
import org.tenshu.core.relation.dynamicProperty
import org.tenshu.core.relation.eager
import org.tenshu.core.relation.valueProperty
import org.tenshu.core.relation.withParams
import org.tenshu.core.schema.composite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the `eager(...)`/`deferred(...)` per-select override wire contract
 * (`docs/proposals/core-deferred-properties.md` §3.2 as-built):
 *
 *  - a **bare** handle mints the historical base [Selector.DynamicPropertySelector]
 *    (untouched composite id + fields ⇒ untouched bytes — old-peer interop);
 *  - a wrapper mints the separate [Selector.DynamicPropertySelector.Resolved]
 *    composite, which round-trips through the binary wire with `params` holding
 *    its positional slot when absent;
 *  - a wrapper on a **static** handle degrades to the plain
 *    [Selector.PropertySelector] (statics are always eager).
 */
class ResolvedSelectorRoundTripTest {

    private val receiver = composite(id = "test/resolvedSelector/receiver")

    private val dynamicHandle = dynamicProperty<Composite, TInteger>(
        id = "test/resolvedSelector/dynamic",
        receiver = receiver,
    ) { error("chain must not materialise during selector minting") }

    private val flaggedHandle = dynamicProperty<Composite, TInteger>(
        id = "test/resolvedSelector/flagged",
        receiver = receiver,
        deferred = true,
    ) { error("chain must not materialise during selector minting") }

    private val staticHandle = valueProperty<TInteger>(
        id = "test/resolvedSelector/static",
        schema = TInteger.Schema,
    )

    private val registry = createSyntheticTestRegistry().also { registry ->
        // The selectors' property references serialise as naked type ids — the
        // parser resolves them via the registry, so the properties must be
        // registered the way a real module registers them (else the reference
        // parses as a generic TypeReferenceDto, not DynamicPropertyTypeReference).
        registry.registerConfig(
            tModule {
                dynamicProperty(fromId = "test/resolvedSelector/receiver", property = dynamicHandle)
                dynamicProperty(fromId = "test/resolvedSelector/receiver", property = flaggedHandle)
            }
        )
    }
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer(registry)
    private val parser = BinaryParser(registry, registry)

    private fun buildSelectors(block: org.tenshu.core.core.ListBuilder<Selector<Data>>.() -> Unit): List<Selector<Data>> =
        context.buildList(block)

    private fun roundTrip(selector: Selector<*>): Composite {
        val bytes = serializer.serialize(selector, isTopLevel = true, expectedType = null)
        return parser.parse(bytes, expectedType = null) as Composite
    }

    // ---- interop pin: bare handles keep the historical wire shape ------------------------

    @Test
    fun bareHandleMintsBaseSelectorNotResolved() {
        val selector = buildSelectors { relations(dynamicHandle) }.single()
        assertIs<Selector.DynamicPropertySelector<*>>(selector)
        assertFalse(selector is Selector.DynamicPropertySelector.Resolved<*>,
            "a bare handle must emit the historical composite id — old peers parse it unchanged")
        assertNull(selector.deferred, "no override ⇒ follow the property's schema default")
    }

    @Test
    fun bareFlaggedHandleStillMintsBaseSelector() {
        // Schema-level deferral is proxy-side metadata; the CLIENT wire shape must
        // not change just because the property's schema says deferred-by-default.
        val selector = buildSelectors { relations(flaggedHandle) }.single()
        assertFalse(selector is Selector.DynamicPropertySelector.Resolved<*>)
    }

    // ---- Resolved wire shape ---------------------------------------------------------------

    @Test
    fun eagerWrapperMintsResolvedSelectorFalse() {
        val selector = buildSelectors { relations(eager(dynamicHandle)) }.single()
        val resolved = assertIs<Selector.DynamicPropertySelector.Resolved<*>>(selector)
        assertFalse(resolved.deferred.get())
    }

    @Test
    fun deferredWrapperMintsResolvedSelectorTrue() {
        val selector = buildSelectors { relations(deferred(dynamicHandle)) }.single()
        val resolved = assertIs<Selector.DynamicPropertySelector.Resolved<*>>(selector)
        assertTrue(resolved.deferred.get())
    }

    @Test
    fun eagerWrapperOnStaticHandleDegradesToPlainPropertySelector() {
        val selector = buildSelectors { relations(eager(staticHandle)) }.single()
        assertIs<Selector.PropertySelector<*>>(selector)
    }

    // ---- binary round-trips (incl. the positional params slot) -----------------------------

    @Test
    fun resolvedSelectorWithoutParamsRoundTrips() {
        // params ABSENT but deferred present — the null params slot must hold its
        // position so `deferred` maps to the third schema field.
        val selector = buildSelectors { relations(deferred(dynamicHandle)) }.single()
        val parsed = assertIs<Selector.DynamicPropertySelector.Resolved<*>>(roundTrip(selector))
        assertEquals("test/resolvedSelector/dynamic", parsed.dynamicPropertyReference.get())
        assertNull(parsed.params)
        assertTrue(parsed.deferred.get())
    }

    @Test
    fun resolvedSelectorWithParamsRoundTrips() {
        val bound = dynamicHandle.withParams(100.toTInteger(context), 50.toTInteger(context))
        val selector = buildSelectors { relations(eager(bound)) }.single()
        val parsed = assertIs<Selector.DynamicPropertySelector.Resolved<*>>(roundTrip(selector))
        assertEquals("test/resolvedSelector/dynamic", parsed.dynamicPropertyReference.get())
        assertEquals(
            listOf(100L, 50L),
            parsed.params?.toList()?.map { (it as TInteger).get() },
        )
        assertFalse(parsed.deferred.get())
    }

    @Test
    fun baseSelectorStillRoundTrips() {
        // Regression guard: the union gained a member; the historical shape must
        // parse exactly as before.
        val selector = buildSelectors { relations(dynamicHandle) }.single()
        val parsed = assertIs<Selector.DynamicPropertySelector<*>>(roundTrip(selector))
        assertFalse(parsed is Selector.DynamicPropertySelector.Resolved<*>)
        assertEquals("test/resolvedSelector/dynamic", parsed.dynamicPropertyReference.get())
        assertNull(parsed.params)
    }
}
