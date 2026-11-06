package org.tenshu.sync.proxy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.HeadlessSafe
import org.tenshu.core.trait.Punctual
import org.tenshu.core.trait.TerminalOperation
import org.tenshu.core.trait.isPunctual

/** RE-D5's punctual marker, at the point the platform layer reads it. */
class PunctualSchedulingTest {

    private val punctualOp = operation(
        id = "test/punctual",
        traits = listOf(TerminalOperation.Schema, HeadlessSafe.Schema, Punctual.Schema),
    )

    private val ordinaryOp = operation(
        id = "test/ordinary",
        traits = listOf(TerminalOperation.Schema, HeadlessSafe.Schema),
    )

    @Test
    fun the_trait_is_read_off_the_operation_schema() {
        assertTrue(punctualOp.isPunctual())
    }

    /** ⚠ Maintenance ops must not accidentally acquire alarm-clock precision. */
    @Test
    fun an_ordinary_op_is_not_punctual() {
        assertFalse(ordinaryOp.isPunctual())
    }

    /**
     * ⚠⚠ A periodic item is never punctual whatever the op declares — "every 15
     * minutes, exactly" is an alarm-clock app, not a routine.
     */
    @Test
    fun a_periodic_item_is_never_punctual() {
        val plan = ScheduledWorkPlan(
            dedupKey = "k", opId = "test/punctual", periodic = true,
            minPeriodMs = 900_000, initialDelayMs = null,
            network = NetworkRequirement.NONE, requireCharging = false,
            requireDeviceIdle = false, requireStorageNotLow = false,
            requireBatteryNotLow = false, punctual = false,
        )

        assertFalse(plan.punctual)
    }
}
