package org.tenshu.core.common.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.InvalidCallerError
import org.tenshu.core.Operation
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.get
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.of
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.schema.typeOf
import org.tenshu.core.schema.typeVar
import org.tenshu.core.toTString
import org.tenshu.core.trait.TerminalOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 5.6 of `docs/adr/0009-operation-schema-typing.md` + Phase 3.1 of
 * `docs/adr/0005-composite-schema-typing.md`: validates the recursive receiver-compatibility
 * walker. The `matchesReceiver` helper is exercised directly with constructed [Type]
 * pairs for focused coverage of the param-walking logic; one end-to-end case via
 * [evaluateCallstack] confirms the integration path still produces an
 * [InvalidCallerError] on shape mismatch.
 */
class ReceiverCompatibilityTest {

    @Test
    fun matchesReceiver_paramShapeMatches_returnsTrue() {
        // TList<SyntheticTestData> vs TList<SyntheticTestData> — full-info match.
        val expected = typeOf(TList.Schema).of(SyntheticTestData.Schema)
        val actual = typeOf(TList.Schema).of(SyntheticTestData.Schema)
        assertTrue(matchesReceiver(expected, actual))
    }

    @Test
    fun matchesReceiver_paramShapeDiffers_returnsFalse() {
        // Phase 3.1 improvement: today's baseSchema-only check accepted this; recursive
        // walk now catches the param-level mismatch.
        val expected = typeOf(TList.Schema).of(SyntheticTestData.Schema)
        val actual = typeOf(TList.Schema).of(SyntheticTestData.DataIdTraitSchema)
        assertFalse(matchesReceiver(expected, actual))
    }

    @Test
    fun matchesReceiver_typeVariableInExpected_acceptsAnyActual() {
        // `TList<E>` accepts any TList payload. Variance / bound enforcement deferred.
        val expected = typeOf(TList.Schema).of(typeVar("E"))
        val actualA = typeOf(TList.Schema).of(SyntheticTestData.Schema)
        val actualB = typeOf(TList.Schema).of(SyntheticTestData.DataIdTraitSchema)
        assertTrue(matchesReceiver(expected, actualA))
        assertTrue(matchesReceiver(expected, actualB))
    }

    @Test
    fun matchesReceiver_nestedGeneric_walksRecursively() {
        // TList<TList<File>> vs TList<TList<File>> — nested match.
        val expected = typeOf(TList.Schema).of(typeOf(TList.Schema).of(SyntheticTestData.Schema))
        val actualMatch = typeOf(TList.Schema).of(typeOf(TList.Schema).of(SyntheticTestData.Schema))
        assertTrue(matchesReceiver(expected, actualMatch))

        // Inner element schema mismatch — walker descends and catches it.
        val actualMismatch =
            typeOf(TList.Schema).of(typeOf(TList.Schema).of(SyntheticTestData.DataIdTraitSchema))
        assertFalse(matchesReceiver(expected, actualMismatch))
    }

    @Test
    fun matchesReceiver_baseSchemaMismatch_returnsFalse() {
        val expected = SyntheticTestData.Schema.toType()
        val actual = TList.Schema.toType()
        assertFalse(matchesReceiver(expected, actual))
    }

    @Test
    fun matchesReceiver_actualLacksParams_constrainsBaseOnly() {
        // Conservative info-gap path: actual surfaces a TList shape but no element info
        // (e.g. a runtime composite that hasn't surfaced its parameterisation). Walker
        // checks only the base schema, preserving today's behaviour.
        val expected = typeOf(TList.Schema).of(SyntheticTestData.Schema)
        val actual = TList.Schema.toType()
        assertTrue(matchesReceiver(expected, actual))
    }

    @Test
    fun matchesReceiver_unparameterisedExpected_skipsParamCheck() {
        // Expected only constrains the base; any matching base shape passes regardless
        // of actual params.
        val expected = TList.Schema.toType()
        val actual = typeOf(TList.Schema).of(SyntheticTestData.Schema)
        assertTrue(matchesReceiver(expected, actual))
    }

    @Test
    fun evaluateCallstack_baseSchemaMismatch_failsWithInvalidCaller() = runTest {
        // End-to-end integration check: an operation expecting [SyntheticTestData] as
        // the receiver, called on a plain TList, fails with [InvalidCallerError]. This
        // is the realistic path through [checkReceiverCompatibility]; the unit tests
        // above cover the param-walking surface that the full chain walk seldom reaches
        // because non-stream materialisation drops [ArrayDatabaseReference] info.
        val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val context = testProxy.tContext

        val op = operation(
            id = "test/receiver/syntheticOnly",
            receiver = SyntheticTestData.Schema.toType(),
            traits = listOf(TerminalOperation.Schema),
        )
        testProxy.additionalImplementations[op.id.get()] = object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?,
            ): T {
                @Suppress("UNCHECKED_CAST")
                return "ok".toTString(executionContext.context) as T
            }
        }
        testProxy.schemaRegistry.registerConfig(tModule {
            operation(op)
            operation(interfaceClass = Operation::class, protocolSchema = op)
        })

        val list = listOf<Data>().toTList(context)

        val ex = assertFailsWith<TenshuException> {
            evaluateCallstack(
                executionContext = testProxy.executionContext,
                contextData = list,
                callstack = listOf(OperationDto(params = emptyMap(), schema = op)),
            )
        }

        assertNotNull(ex.error as? InvalidCallerError, "Expected InvalidCallerError, got ${ex.error}")
    }
}
