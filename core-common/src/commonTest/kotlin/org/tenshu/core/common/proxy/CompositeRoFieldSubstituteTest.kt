package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.Maybe
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.request.roProvider
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.TypeParameter
import org.tenshu.core.schema.Variance
import org.tenshu.core.schema.toType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 3.3 of `docs/adr/0005-composite-schema-typing.md`. Confirms that field-access ROs
 * substitute typeVar references using the receiver's parameterisation. After Phase 2.5
 * `Maybe.Some.value`'s declared type is `typeVar("E")`; without substitution, the RO
 * factory would receive the variable and reject it.
 */
class CompositeRoFieldSubstituteTest {

    @Test
    fun maybeSome_valueField_substitutesTypeVarToBoundElementType() {
        val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val context = testProxy.tContext
        val chain = LocalRequestChain(testProxy.localTaskExecutor, context)

        // Construct a Maybe.Some<SyntheticTestData> RO via the parameterised factory.
        val maybeType = Type.create(
            baseSchema = Maybe.Some.Schema,
            params = listOf(
                TypeParameter.create(
                    type = SyntheticTestData.Schema.toType(),
                    variance = Variance.Covariant
                )
            )
        )
        val maybeRo = context.roProvider().createRequestObject<Composite>(
            type = maybeType,
            requestChain = chain,
            context = context,
        )

        assertNotNull(maybeRo)
        assertTrue(maybeRo is CompositeRo, "MaybeRo should extend CompositeRo")

        // Trigger field-access RO construction. Pre-Phase-3.3 this would fail because
        // `field.type` for VALUE is `typeVar("E")` and the registry's factory rejects
        // non-base types. With Phase 3.3, the receiver's `params` (the SyntheticTestData
        // binding) drives substitute(...) → SyntheticTestData.Schema.toType() before the
        // factory call.
        val valueRo = maybeRo.getField<Data>(Maybe.Some.Fields.VALUE)

        // The returned RO should be a Composite for the SyntheticTestData schema, not
        // an erased Data fallback.
        assertTrue(
            valueRo is Composite,
            "VALUE field should resolve to a Composite RO; got ${valueRo::class}"
        )
        assertEquals(
            SyntheticTestData.ID,
            (valueRo as Composite).schema.id.get(),
            "VALUE RO's schema should reflect the bound element type"
        )

        // The valueRo should be a RequestObject participating in the chain.
        assertTrue(valueRo is RequestObject, "Value RO should be a RequestObject")
    }

    @Test
    fun maybeSome_valueField_unboundFallsBackToData() {
        // When the receiver has no parameterisation (default elementType=Data), substitute
        // falls back via `defaultForUnbound = Data.Schema.toType()` so the RO factory still
        // gets a base type. This preserves the historical erased-Data behavior for callers
        // that don't parameterise the receiver.
        val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val context = testProxy.tContext
        val chain = LocalRequestChain(testProxy.localTaskExecutor, context)

        val maybeRo = context.roProvider().createRequestObject<Composite>(
            type = Maybe.Some.Schema.toType(),
            requestChain = chain,
            context = context,
        )

        // Fetching VALUE on an unparameterised Maybe should still succeed and resolve to
        // Data — no NPE, no factory error from a leaked TypeVariable.
        val valueRo = maybeRo.getField<Data>(Maybe.Some.Fields.VALUE)
        assertNotNull(valueRo)
    }
}
