package org.tenshu.core.common.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.SyntheticTestData.Companion.Field
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.reference.FieldReferenceDto
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.DataId
import org.tenshu.core.get
import org.tenshu.core.id
import org.tenshu.core.schema.fieldRef
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.toTString
import org.tenshu.core.MissingParameter
import org.tenshu.core.TenshuException
import org.tenshu.core.trait.TerminalOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EvaluateCallstackTest {

    @Test
    fun evaluateCallstack_FieldReferenceOnSimpleComposite() = runTest {
        val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val context = testProxy.tContext

        val composite = SyntheticTestData.Factory.create(context, "value1", 42L)

        val callstack = listOf(
            fieldRef(Field.NAME)
        )

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = composite,
            callstack = callstack
        )

        assertEquals("value1", (result as TString).get())
    }

    @Test
    fun evaluateCallstack_FieldReferenceOnTListDto_ThrowsFieldNotFound() = runTest {
        val testProxy = TestProxy()
        val context = testProxy.tContext

        val list = listOf("a", "b").toTList(context)

        val callstack = listOf(
            FieldReferenceDto("someField")
        )

        val e = assertFailsWith<Throwable> {
            evaluateCallstack(
                executionContext = testProxy.executionContext,
                contextData = list,
                callstack = callstack
            )
        }

        val message = e.message ?: ""
        assertTrue(message.contains("Field someField not found"), "Actual message was: $message")
    }

    @Test
    fun evaluateCallstack_DefersNonTerminalOperations() = runTest {
        val testProxy = TestProxy()
        val context = testProxy.tContext

        val nonTerminalOp = operation(id = "test/nonTerminal")

        var execCount = 0
        testProxy.additionalImplementations["test/nonTerminal"] =
            object : SyncOperationImplementation {
                override fun <T : Data?> execute(
                    executionContext: ExecutionContext,
                    operationContext: OperationContext?,
                    implementationFactory: OperationImplementationFactory,
                    caller: Any?,
                    params: Map<String, Data>?
                ): T {
                    execCount++
                    @Suppress("UNCHECKED_CAST")
                    return caller as T
                }
            }

        testProxy.schemaRegistry.registerConfig(tModule {
            operation(nonTerminalOp)
            operation(interfaceClass = Operation::class, protocolSchema = nonTerminalOp)
        })

        // 1. Create a root with an OperationContext
        val rootRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(context, TList.ID, "test/array"),
            isEntity = false
        )

        val callstack = listOf(
            OperationDto(params = emptyMap(), schema = nonTerminalOp)
        )

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = rootRef,
            callstack = callstack
        )

        assertEquals(0, execCount)
        assertTrue(result is DatabaseReference)
        val stack = result.getOperationContext().executionStack
        assertEquals(1, stack.size)
        assertEquals("test/nonTerminal", (stack[0] as Operation).id)
    }

    @Test
    fun evaluateCallstack_ExecutesTerminalOperationAndClearsStack() = runTest {
        val testProxy = TestProxy()
        val context = testProxy.tContext

        val terminalOp = operation(
            id = "test/terminal",
            traits = listOf(TerminalOperation.Schema)
        )
        val deferredOp = operation(id = "test/deferred")

        var capturedStackSize = -1
        testProxy.additionalImplementations["test/terminal"] =
            object : SyncOperationImplementation {
                override fun <T : Data?> execute(
                    executionContext: ExecutionContext,
                    operationContext: OperationContext?,
                    implementationFactory: OperationImplementationFactory,
                    caller: Any?,
                    params: Map<String, Data>?
                ): T {
                    capturedStackSize = operationContext?.executionStack?.size ?: 0
                    @Suppress("UNCHECKED_CAST")
                    return "done".toTString(context) as T
                }
            }

        testProxy.additionalImplementations["test/deferred"] =
            object : SyncOperationImplementation {
                override fun <T : Data?> execute(
                    executionContext: ExecutionContext,
                    operationContext: OperationContext?,
                    implementationFactory: OperationImplementationFactory,
                    caller: Any?,
                    params: Map<String, Data>?
                ): T {
                    @Suppress("UNCHECKED_CAST")
                    return caller as T
                }
            }

        testProxy.schemaRegistry.registerConfig(tModule {
            operation(terminalOp)
            operation(interfaceClass = Operation::class, protocolSchema = terminalOp)
            operation(deferredOp)
            operation(interfaceClass = Operation::class, protocolSchema = deferredOp)
        })

        val rootRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(context, TList.ID, "test/array"),
            isEntity = false
        )

        // Chain: [Non-Terminal, Terminal]
        val callstack = listOf(
            OperationDto(params = emptyMap(), schema = deferredOp),
            OperationDto(params = emptyMap(), schema = terminalOp)
        )

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = rootRef,
            callstack = callstack
        )

        assertEquals(0, capturedStackSize)
        assertEquals("done", (result as TString).get())
    }

    /**
     * Phase 2 of `docs/proposals/core-error-channel.md`: the dispatcher's
     * required-param presence check at execute time. A terminal op whose schema
     * declares a non-optional param fails fast with `TenshuException(MissingParameter)`
     * when the caller's `params` map omits it. Partial chain entries don't reach
     * dispatch (the deferral branch handles them) — this only fires on terminal
     * dispatch, where impls used to fall back to `params.require(KEY)` themselves.
     */
    @Test
    fun evaluateCallstack_RequiredParameterMissing_throwsMissingParameter() = runTest {
        val testProxy = TestProxy()
        val context = testProxy.tContext

        val op = operation(
            id = "test/requires-name",
            traits = listOf(TerminalOperation.Schema),
            params = {
                string("test/requires-name/name")
            }
        )

        var executed = false
        testProxy.additionalImplementations["test/requires-name"] =
            object : SyncOperationImplementation {
                override fun <T : Data?> execute(
                    executionContext: ExecutionContext,
                    operationContext: OperationContext?,
                    implementationFactory: OperationImplementationFactory,
                    caller: Any?,
                    params: Map<String, Data>?
                ): T {
                    executed = true
                    @Suppress("UNCHECKED_CAST")
                    return "ok".toTString(context) as T
                }
            }

        testProxy.schemaRegistry.registerConfig(tModule {
            operation(op)
            operation(interfaceClass = Operation::class, protocolSchema = op)
        })

        val callstack = listOf(
            OperationDto(params = emptyMap(), schema = op)
        )

        val ex = assertFailsWith<TenshuException> {
            evaluateCallstack(
                executionContext = testProxy.executionContext,
                contextData = null,
                callstack = callstack
            )
        }

        assertEquals(false, executed)
        val error = assertIs<MissingParameter>(ex.error)
        assertEquals("test/requires-name/name", error.name.get())
    }

    /**
     * Optional params don't trigger the missing-param check — they're allowed to
     * be absent from `protocol.params` by construction.
     */
    @Test
    fun evaluateCallstack_OptionalParameterMissing_executes() = runTest {
        val testProxy = TestProxy()
        val context = testProxy.tContext

        val op = operation(
            id = "test/optional-param",
            traits = listOf(TerminalOperation.Schema),
            params = {
                string("test/optional-param/name", isOptional = true)
            }
        )

        var executed = false
        testProxy.additionalImplementations["test/optional-param"] =
            object : SyncOperationImplementation {
                override fun <T : Data?> execute(
                    executionContext: ExecutionContext,
                    operationContext: OperationContext?,
                    implementationFactory: OperationImplementationFactory,
                    caller: Any?,
                    params: Map<String, Data>?
                ): T {
                    executed = true
                    @Suppress("UNCHECKED_CAST")
                    return "ok".toTString(context) as T
                }
            }

        testProxy.schemaRegistry.registerConfig(tModule {
            operation(op)
            operation(interfaceClass = Operation::class, protocolSchema = op)
        })

        evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = null,
            callstack = listOf(OperationDto(params = emptyMap(), schema = op))
        )

        assertEquals(true, executed)
    }

    @Test
    fun evaluateCallstack_MaterializesResultIfNoTerminalOp() = runTest {
        val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val context = testProxy.tContext

        // Root array
        val rootRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(context, TList.ID, "test/array"),
            isEntity = false
        )

        // Add data to it
        val item = SyntheticTestData.Factory.create(context, "item1", 123L)
        testProxy.executionContext.database.addToArray(rootRef, item)

        val callstack = emptyList<Data>()

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = rootRef,
            callstack = callstack
        )

        val list = result as TList<*>
        assertEquals(1, list.toList().size)
        assertEquals("item1", (list.toList()[0] as SyntheticTestData).name.get())
    }
}
