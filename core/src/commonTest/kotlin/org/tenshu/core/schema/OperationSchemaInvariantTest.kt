package org.tenshu.core.schema

import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guards the `operation(...)` builder invariant: an op declaring [IoOperation] without
 * [TerminalOperation] is rejected at construction time. Inline non-terminal IO ops leak
 * `AsyncReference` through sync entry points like `resolveDynamicProperty`; the invariant
 * exists to fail loudly until those entry points learn to drive AsyncReferences.
 */
class OperationSchemaInvariantTest {

    @Test
    fun ioOperation_withoutTerminal_isRejected() {
        val ex = assertFailsWith<IllegalStateException> {
            operation(
                id = "test/op/io-without-terminal",
                traits = listOf(IoOperation.Schema)
            )
        }
        assertTrue(
            ex.message?.contains("declares IoOperation but not TerminalOperation") == true,
            "Expected invariant error, got: ${ex.message}"
        )
        assertTrue(
            ex.message?.contains("test/op/io-without-terminal") == true,
            "Error should mention the offending op id, got: ${ex.message}"
        )
    }

    @Test
    fun ioOperation_withTerminal_isAccepted() {
        operation(
            id = "test/op/io-with-terminal",
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    @Test
    fun terminalOperation_alone_isAccepted() {
        operation(
            id = "test/op/terminal-only",
            traits = listOf(TerminalOperation.Schema)
        )
    }

    @Test
    fun operation_withoutTraits_isAccepted() {
        operation(id = "test/op/no-traits")
    }

    @Test
    fun ioOperation_traitOrderDoesNotMatter() {
        operation(
            id = "test/op/order-a",
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
        operation(
            id = "test/op/order-b",
            traits = listOf(TerminalOperation.Schema, IoOperation.Schema)
        )
    }
}
