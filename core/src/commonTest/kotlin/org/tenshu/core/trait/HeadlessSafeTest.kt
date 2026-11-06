package org.tenshu.core.trait

import org.tenshu.core.schema.operation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 5 (docs/proposals/core-background-processing.md §3.2(a), §6.4): the
 * `HeadlessSafe` eligibility marker is an allowlist-by-construction. An op is
 * head-less-schedulable *only* if its author opted in by carrying the trait;
 * everything else fails closed.
 *
 * `HeadlessSafe` is orthogonal to `BackgroundOperation` (§3.2): carrying one
 * neither implies nor requires the other.
 */
class HeadlessSafeTest {

    @Test
    fun operation_withHeadlessSafe_isHeadlessSafe() {
        val schema = operation(
            id = "test/op/headless",
            traits = listOf(TerminalOperation.Schema, HeadlessSafe.Schema)
        )
        assertTrue(schema.isHeadlessSafe())
    }

    @Test
    fun operation_withoutHeadlessSafe_isNotHeadlessSafe_failsClosed() {
        val schema = operation(
            id = "test/op/plain",
            traits = listOf(TerminalOperation.Schema)
        )
        assertFalse(
            schema.isHeadlessSafe(),
            "an op that did not opt in must be unschedulable (fail closed)"
        )
    }

    @Test
    fun headlessSafe_isOrthogonalToBackgroundOperation() {
        // HeadlessSafe without BackgroundOperation is accepted (no DSL guard
        // ties them — §3.2 orthogonality) and reads back as head-less safe...
        val headlessOnly = operation(
            id = "test/op/headless-only",
            traits = listOf(TerminalOperation.Schema, HeadlessSafe.Schema)
        )
        assertTrue(headlessOnly.isHeadlessSafe())
        assertFalse(headlessOnly.isBackground())

        // ...and BackgroundOperation without HeadlessSafe is NOT head-less
        // safe (a Layer-2 long-running op is not automatically schedulable).
        val backgroundOnly = operation(
            id = "test/op/background-only",
            traits = listOf(TerminalOperation.Schema, BackgroundOperation.Schema)
        )
        assertTrue(backgroundOnly.isBackground())
        assertFalse(backgroundOnly.isHeadlessSafe())

        // Both together: a deferred upload carries each for its own reason.
        val both = operation(
            id = "test/op/deferred-upload",
            traits = listOf(
                TerminalOperation.Schema,
                BackgroundOperation.Schema,
                HeadlessSafe.Schema
            )
        )
        assertTrue(both.isBackground())
        assertTrue(both.isHeadlessSafe())
    }
}
