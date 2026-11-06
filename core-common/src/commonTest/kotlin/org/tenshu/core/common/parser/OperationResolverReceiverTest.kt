package org.tenshu.core.common.parser

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TArray
import org.tenshu.core.TResult
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.module.tModule
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Phase 2 of `docs/adr/0009-operation-schema-typing.md`:
 *  - Step 2.1 — `findOperation(operationId, requestChain, context)` derives the
 *    receiver's type id from the operation schema instead of taking it as an argument.
 *  - Step 2.2 — `registerOperation(...)` (no-baseTypeId overload) auto-slots the factory
 *    into `operations[derivedTypeId]` when the schema declares a receiver.
 *
 * Some tests intentionally call the @Deprecated two-arg / baseTypeId-taking overloads to
 * verify they still agree with the receiver-derived path during the carve-out window
 * (Phase 4.1 deprecates; 4.2 deletion gated on resolving the Select polymorphic receiver).
 */
@Suppress("DEPRECATION")
class OperationResolverReceiverTest {

    private val registry = createCoreTestRegistry()

    private val stubChain = object : RequestChain {
        override fun addReference(id: String) = this
        override fun addArray(array: TArray) = this
        override fun addNestedArray(block: (RequestChain) -> RequestChain) = this
        override fun getCallstack(): List<Data> = emptyList()
        override suspend fun getResult(): TResult<Data> = error("stub")
    }

    @Test
    fun findOperation_singleArg_resolvesWhenSchemaHasReceiver() {
        // Synthetic op with receiver = TList<E>. We don't need an interface class —
        // tModule's registration paths still wire it under the derived type id.
        val opSchema = operation(
            id = "test/op/list-shape-op",
            receiver = listType("E"),
            typeParams = { type("E") },
            returnType = listType("E"),
        )

        val expectedOp = StubOperation(opSchema)
        registry.registerConfig(tModule {
            registerOperation(
                interfaceClass = StubOperation::class,
                protocolSchema = opSchema,
                roFactory = { _, _ -> expectedOp }
            )
        })

        val resolved = registry.findOperation<StubOperation>(
            operationId = opSchema.id.get(),
            requestChain = stubChain,
            context = registry.tContext,
        )
        assertNotNull(resolved, "single-arg findOperation should resolve via receiver lookup")
        assertSame(expectedOp, resolved, "should be the registered factory's instance")
    }

    @Test
    fun findOperation_singleArg_returnsNullForUnregisteredOpId() {
        val resolved = registry.findOperation<StubOperation>(
            operationId = "test/op/never-registered",
            requestChain = stubChain,
            context = registry.tContext,
        )
        assertNull(resolved)
    }

    @Test
    fun findOperation_resolvesViaReceiverDerivedTypeId() {
        // The schema-derived dispatch is the only path now that Step 4.2 has deleted the
        // explicit two-arg overload. Verifies the single-arg form picks up the registered
        // factory keyed by the receiver's `baseSchema.id`.
        val opSchema = operation(
            id = "test/op/agreement",
            receiver = listType("E"),
            typeParams = { type("E") },
        )
        val expectedOp = StubOperation(opSchema)
        registry.registerConfig(tModule {
            registerOperation(
                interfaceClass = StubOperation::class,
                protocolSchema = opSchema,
                roFactory = { _, _ -> expectedOp }
            )
        })

        val resolved = registry.findOperation<StubOperation>(
            operationId = opSchema.id.get(),
            requestChain = stubChain,
            context = registry.tContext,
        )

        assertNotNull(resolved)
        assertSame(expectedOp, resolved)
    }

    @Test
    fun registerOperation_noBaseTypeId_derivesFromReceiver() {
        val opSchema = operation(
            id = "test/op/derived-base",
            receiver = listType("E"),
            typeParams = { type("E") },
        )

        val module = tModule {
            registerOperation(
                interfaceClass = StubOperation::class,
                protocolSchema = opSchema,
                roFactory = { _, _ -> StubOperation(opSchema) }
            )
        }
        registry.registerConfig(module)

        val derivedTypeId = org.tenshu.core.collection.list.TList.ID
        val operations = module.operations[derivedTypeId]
        assertNotNull(
            operations,
            "registerOperation should auto-slot under derived type id $derivedTypeId; " +
                    "module operations: ${module.operations.keys}"
        )
        assertNotNull(
            operations[opSchema.id.get()],
            "factory should be queryable under the derived type id + op id"
        )
    }

    @Test
    fun registerOperation_noBaseTypeId_andNoReceiver_doesNotSlotIntoOperationsMap() {
        // Receiver-less op — current behaviour is preserved (factory only goes into
        // requestObjectFactories / compositeFactories, NOT into the operations map).
        val opSchema = operation(id = "test/op/no-receiver")

        val module = tModule {
            registerOperation(
                interfaceClass = StubOperation::class,
                protocolSchema = opSchema,
                roFactory = { _, _ -> StubOperation(opSchema) }
            )
        }

        // Confirm: no entry in any operations[*] sub-map references this op id.
        val matchingTypeIds = module.operations.entries
            .filter { (_, ops) -> opSchema.id.get() in ops }
            .map { it.key }
        assertEquals(
            emptyList(),
            matchingTypeIds,
            "receiver-less op should NOT auto-derive a baseTypeId; got: $matchingTypeIds"
        )
    }

    // Step 4.2 of `docs/adr/0009-operation-schema-typing.md`: the legacy two-arg
    // `registerOperation(baseTypeId, ...)` and `findOperation(typeId, opId, ...)`
    // overloads have been deleted now that the `Select` polymorphic-receiver carve-out
    // is gone (`Maybe<R>.select` migrated to `ifEmptyError() + direct property access`).
    // The previous `registerOperation_explicitBaseTypeId_isUnchanged` regression test
    // covered the deprecated path and was deleted alongside the overloads.
}

/** Minimal Operation impl for resolver tests — plays the role of the registered RO. */
private class StubOperation(private val opSchema: OperationSchema) : Operation {
    override val schema: OperationSchema get() = opSchema
    override fun <T : Data> getField(reference: String): T = error("stub")
    override fun <T : Data> getOptionalField(reference: String): T? = null
}
