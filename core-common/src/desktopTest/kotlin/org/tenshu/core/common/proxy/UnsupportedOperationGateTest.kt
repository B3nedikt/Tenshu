package org.tenshu.core.common.proxy

import kotlinx.coroutines.runBlocking
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.UnsupportedOperationError
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.id
import org.tenshu.core.core.create
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.toTString
import org.tenshu.core.trait.TerminalOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E3 (`core-schema-evolution.md` §9.3): a caller naming an operation this build
 * does not have gets a **typed** [UnsupportedOperationError], not the cryptic
 * `error/internal` the parser used to raise from an unresolvable type tag.
 *
 * That is what makes **deregistration the cut-off mechanism**: the phase's whole
 * claim is that removing a registration is a supported deprecation step, and it
 * only is if the removal produces an answer a client can act on.
 *
 * The setup is deliberately two proxies — one that registered the op (so it can
 * *produce* the bytes) and one that did not (so it must *interpret* them). ⚠ A
 * single-proxy test could not reach this at all: the type tag is derived from the
 * id, so only a peer that knows the id can emit a tag the reader does not.
 */
private val PARAM_ID = "tenshu/test/e3/paramOnlyTheCallerKnows"

/** A parameter type, to prove the check is positional and not "anything unknown". */
private val PARAM_SCHEMA = composite(
    id = PARAM_ID,
    fields = { string("$PARAM_ID/value") }
)

private class ParamDto(
    private val fieldValues: List<Data?>
) : org.tenshu.core.Composite, org.tenshu.core.common.impl.CompositeDto(fieldValues) {
    override val schema get() = PARAM_SCHEMA
}

class UnsupportedOperationGateTest {

    private val opSchema = operation(
        id = "tenshu/test/e3/retiredOperation",
        traits = listOf(TerminalOperation.Schema)
    )

    /**
     * Same op id, but declaring a parameter typed as [PARAM_SCHEMA] — used only by
     * the nested-unknown case. Both sides register this shape, so the OPERATION is
     * known to the reader and only the parameter's type is not.
     */
    private val opWithParamSchema = operation(
        id = "tenshu/test/e3/retiredOperation",
        params = { composite(id = "$PARAM_ID/value", type = PARAM_SCHEMA) },
        traits = listOf(TerminalOperation.Schema)
    )

    private fun TestProxy.registerOp(schema: org.tenshu.core.schema.OperationSchema = opSchema) = apply {
        schemaRegistry.registerConfig(
            tModule { operation(interfaceClass = Operation::class, protocolSchema = schema) }
        )
    }

    private fun TestProxy.registerParam() = apply {
        schemaRegistry.registerConfig(
            tModule {
                composite(
                    interfaceClass = org.tenshu.core.Composite::class,
                    dataSchema = PARAM_SCHEMA,
                    dtoFactory = ::ParamDto,
                )
            }
        )
    }

    private fun TestProxy.bytesOf(vararg elements: Data): ByteArray {
        val task: TList<Data> = elements.toList().toTList(tContext)
        return serializer.serialize(
            task,
            isTopLevel = true,
            expectedType = Type.create(baseSchema = TList.Schema)
        )
    }

    private fun TestProxy.parseResult(bytes: ByteArray): TResult<Data> =
        parser.parse(bytes, expectedType = Type.create(baseSchema = TResult.Schema))

    @Test
    fun anOperationThisBuildDoesNotHaveFailsTyped() = runBlocking {
        val caller = TestProxy().registerOp()
        val reader = TestProxy()
        reader.admitSchemaSession("c1")

        val bytes = caller.bytesOf(OperationDto(params = emptyMap(), schema = opSchema))
        val result = reader.parseResult(reader.gateway.call("c1", bytes))

        assertTrue(result is TResult.Failure, "an unknown operation must fail, not execute: $result")
        assertEquals(
            UnsupportedOperationError.ID,
            result.error.id,
            "the cut-off answer is typed — this is the whole point of E3 (§9.3)",
        )
    }

    @Test
    fun theTypedErrorNamesTheTagTheCallerCanResolve() = runBlocking {
        val caller = TestProxy().registerOp()
        val reader = TestProxy()
        reader.admitSchemaSession("c1")

        val bytes = caller.bytesOf(OperationDto(params = emptyMap(), schema = opSchema))
        // ⚠ Parsed with the CALLER's registry — the error type must itself cross
        // the wire, or the case it names collapses back into error/internal
        // (the wire-registration gap: in-process tests never catch that).
        val result = caller.parseResult(reader.gateway.call("c1", bytes))

        assertTrue(result is TResult.Failure, "expected a failure, got: $result")
        val error = result.error as UnsupportedOperationError
        assertTrue(error.typeTag.get().isNotEmpty(), "the wire tag is the one durable handle")
        // ⚠ The reader CANNOT name the id: the tag→id map is built from registered
        // schemas, so a deregistered op is unnameable by the side that dropped it.
        // The caller resolves it — it produced the tag from the id it holds.
        assertEquals("", error.operationId.get(), "an unregistered op is unnameable by the reader")
        assertEquals(
            caller.tContext.let { org.tenshu.core.common.util.idToUuid(opSchema.id.get()) }
                .toHexString(),
            error.typeTag.get(),
            "the tag must be the caller's own id hash, so the caller can map it back",
        )
    }

    @Test
    fun anUnknownTypeInsideAKnownOperationIsNotReportedAsAnUnsupportedOperation() = runBlocking {
        // Both sides know the op; only the caller knows a type it passes as a param.
        val caller = TestProxy().registerOp(opWithParamSchema).registerParam()
        val reader = TestProxy().registerOp(opWithParamSchema)
        reader.admitSchemaSession("c1")

        val param: Data = ParamDto(listOf("v".toTString(caller.tContext)))
        val bytes = caller.bytesOf(
            OperationDto(
                params = mapOf("$PARAM_ID/value" to param),
                schema = opWithParamSchema,
            )
        )
        val result = reader.parseResult(reader.gateway.call("c1", bytes))

        assertTrue(result is TResult.Failure, "still a failure — the reader cannot decode it")
        // The point: it must NOT claim the *operation* is unsupported. The op is
        // registered; something nested is not, and mislabelling that would send a
        // client chasing an op-version problem it does not have.
        assertTrue(
            result.error.id != UnsupportedOperationError.ID,
            "a nested unknown type must not be reported as an unsupported operation, was: " +
                result.error.id,
        )
    }
}
