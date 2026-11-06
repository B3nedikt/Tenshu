package org.tenshu.routine.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.tenshu.routine.common.engine.Admission
import org.tenshu.routine.common.engine.DefaultSafetyGate
import org.tenshu.routine.common.engine.Firing

class SafetyGateTest {

    private fun firing(
        routineId: String = "r1",
        depth: Int = 0,
        chain: List<String> = listOf(routineId),
        id: String = "f-$routineId-$depth",
    ) = Firing(
        id = id,
        routineId = routineId,
        triggerInstantEpochMs = 0,
        chainRootFiringId = id,
        depth = depth,
        chain = chain,
    )

    @Test
    fun firings_within_the_bucket_are_admitted() {
        val gate = DefaultSafetyGate()

        repeat(DefaultSafetyGate.MAX_FIRINGS_PER_WINDOW) {
            assertIs<Admission.Admit>(gate.admit(firing(id = "f$it"), nowEpochMs = 1_000L + it))
        }
    }

    @Test
    fun the_bucket_throttles_beyond_its_limit() {
        val gate = DefaultSafetyGate()
        repeat(DefaultSafetyGate.MAX_FIRINGS_PER_WINDOW) {
            gate.admit(firing(id = "f$it"), nowEpochMs = 1_000L + it)
        }

        val verdict = gate.admit(firing(id = "over"), nowEpochMs = 1_100L)

        assertIs<Admission.Throttled>(verdict)
        assertTrue(verdict.retryAfterMs > 0)
    }

    @Test
    fun the_bucket_refills_in_the_next_window() {
        val gate = DefaultSafetyGate()
        repeat(DefaultSafetyGate.MAX_FIRINGS_PER_WINDOW + 1) {
            gate.admit(firing(id = "f$it"), nowEpochMs = 1_000L + it)
        }

        val laterWindow = 1_000L + DefaultSafetyGate.WINDOW_MS + 1
        assertIs<Admission.Admit>(gate.admit(firing(id = "next"), nowEpochMs = laterWindow))
    }

    /**
     * ⚠⚠ The coalescing contract (§4.9). RE-D9 calls the token bucket a *sync*
     * brake — but if every refused firing wrote its own `RoutineRun` row, the
     * brake would only change a state field and a flapping sensor would flood
     * the realm with `THROTTLED` rows instead of `RAN` rows. The first refusal
     * in a window opens one row; the rest coalesce into it.
     */
    @Test
    fun only_the_first_refusal_in_a_window_opens_a_row() {
        val gate = DefaultSafetyGate()
        repeat(DefaultSafetyGate.MAX_FIRINGS_PER_WINDOW) {
            gate.admit(firing(id = "f$it"), nowEpochMs = 1_000L + it)
        }

        gate.admit(firing(id = "over-1"), nowEpochMs = 1_100L)
        assertTrue(gate.isFirstRefusalInWindow("r1"), "no row opened yet")
        gate.recordRefusalRow("r1", "throttle-row-1")

        gate.admit(firing(id = "over-2"), nowEpochMs = 1_101L)
        assertFalse(gate.isFirstRefusalInWindow("r1"), "second refusal must coalesce")
        assertEquals("throttle-row-1", gate.openRefusalRowFor("r1"))
    }

    @Test
    fun a_new_window_opens_a_new_refusal_row() {
        val gate = DefaultSafetyGate()
        repeat(DefaultSafetyGate.MAX_FIRINGS_PER_WINDOW + 1) {
            gate.admit(firing(id = "f$it"), nowEpochMs = 1_000L + it)
        }
        gate.recordRefusalRow("r1", "throttle-row-1")

        val laterWindow = 1_000L + DefaultSafetyGate.WINDOW_MS + 1
        gate.admit(firing(id = "next"), nowEpochMs = laterWindow)

        assertTrue(gate.isFirstRefusalInWindow("r1"))
    }

    @Test
    fun depth_beyond_the_guard_is_refused_by_name() {
        val gate = DefaultSafetyGate()

        val verdict = gate.admit(
            firing(depth = DefaultSafetyGate.MAX_CHAIN_DEPTH + 1),
            nowEpochMs = 1_000L,
        )

        val refused = assertIs<Admission.DepthExceeded>(verdict)
        assertEquals(DefaultSafetyGate.MAX_CHAIN_DEPTH + 1, refused.depth)
    }

    /**
     * ⭐ A cycle is an authoring mistake the user should be *told* about, so the
     * refusal names the chain rather than silently throttling.
     */
    @Test
    fun a_routine_already_in_the_chain_is_refused_as_a_cycle() {
        val gate = DefaultSafetyGate()

        val verdict = gate.admit(
            Firing(
                id = "f",
                routineId = "r1",
                triggerInstantEpochMs = 0,
                chainRootFiringId = "root",
                depth = 2,
                chain = listOf("r1", "r2", "r1"),
            ),
            nowEpochMs = 1_000L,
        )

        val cycle = assertIs<Admission.CycleRefused>(verdict)
        assertEquals(listOf("r1", "r2", "r1"), cycle.chain)
    }

    @Test
    fun a_plain_chain_is_not_mistaken_for_a_cycle() {
        val gate = DefaultSafetyGate()

        val verdict = gate.admit(
            Firing(
                id = "f",
                routineId = "r3",
                triggerInstantEpochMs = 0,
                chainRootFiringId = "root",
                depth = 2,
                chain = listOf("r1", "r2", "r3"),
            ),
            nowEpochMs = 1_000L,
        )

        assertIs<Admission.Admit>(verdict)
    }

    /**
     * ⚠⚠ Sustained throttling escalates to suspension — and the engine must
     * express it as `Routine.state = SUSPENDED`, never `enabled = false`.
     */
    @Test
    fun sustained_throttling_escalates_to_suspension() {
        val gate = DefaultSafetyGate()

        repeat(DefaultSafetyGate.THROTTLED_WINDOWS_BEFORE_SUSPEND) { window ->
            val base = 1_000L + window * (DefaultSafetyGate.WINDOW_MS + 1)
            repeat(DefaultSafetyGate.MAX_FIRINGS_PER_WINDOW + 1) {
                gate.admit(firing(id = "w$window-f$it"), nowEpochMs = base + it)
            }
        }

        assertTrue(gate.shouldSuspend("r1"))
        assertIs<Admission.Suspended>(gate.admit(firing(id = "after"), nowEpochMs = 10_000_000L))
    }

    @Test
    fun a_quiet_window_breaks_the_escalation_streak() {
        val gate = DefaultSafetyGate()
        repeat(DefaultSafetyGate.MAX_FIRINGS_PER_WINDOW + 1) {
            gate.admit(firing(id = "f$it"), nowEpochMs = 1_000L + it)
        }

        // A calm window, then a busy one: not consecutive, so no suspension.
        val calm = 1_000L + DefaultSafetyGate.WINDOW_MS + 1
        gate.admit(firing(id = "calm"), nowEpochMs = calm)
        val busy = calm + DefaultSafetyGate.WINDOW_MS + 1
        repeat(DefaultSafetyGate.MAX_FIRINGS_PER_WINDOW + 1) {
            gate.admit(firing(id = "b$it"), nowEpochMs = busy + it)
        }

        assertFalse(gate.shouldSuspend("r1"))
    }

    @Test
    fun buckets_are_per_routine() {
        val gate = DefaultSafetyGate()
        repeat(DefaultSafetyGate.MAX_FIRINGS_PER_WINDOW + 1) {
            gate.admit(firing(routineId = "noisy", id = "f$it"), nowEpochMs = 1_000L + it)
        }

        assertIs<Admission.Admit>(gate.admit(firing(routineId = "quiet"), nowEpochMs = 1_100L))
    }
}
