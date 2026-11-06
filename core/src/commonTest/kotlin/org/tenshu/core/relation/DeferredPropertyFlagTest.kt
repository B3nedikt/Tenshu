package org.tenshu.core.relation

import org.tenshu.core.Composite
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.composite
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the schema-level `deferred` flag (`docs/proposals/core-deferred-properties.md`
 * §3.1): it rides the handle's `DynamicPropertyRelationSchema` as **code-side
 * metadata** (the `callstack` precedent — never a wire field), defaults to eager,
 * and survives `withParams` because the param-bound copy shares the same schema
 * instance.
 */
class DeferredPropertyFlagTest {

    private val receiver = composite(id = "test/deferred/receiver")

    @Test
    fun dynamicPropertyDefaultsToEager() {
        val handle = dynamicProperty<Composite, TInteger>(
            id = "test/deferred/default",
            receiver = receiver,
        ) { error("chain must not materialise during flag reads") }
        assertFalse(handle.relationSchema.deferredByDefault)
    }

    @Test
    fun dynamicPropertyDeferredFlagRidesRelationSchema() {
        val handle = dynamicProperty<Composite, TInteger>(
            id = "test/deferred/flagged",
            receiver = receiver,
            deferred = true,
        ) { error("chain must not materialise during flag reads") }
        assertTrue(handle.relationSchema.deferredByDefault)
    }

    @Test
    fun withParamsPreservesDeferredFlag() {
        val handle = dynamicProperty<Composite, TInteger>(
            id = "test/deferred/params",
            receiver = receiver,
            deferred = true,
        ) { error("chain must not materialise during flag reads") }
        val bound = handle.withParams(100.toTInteger(), 100.toTInteger())
        assertSame(handle.relationSchema, bound.relationSchema, "withParams shares the schema instance")
        assertTrue(bound.relationSchema.deferredByDefault)
    }
}
