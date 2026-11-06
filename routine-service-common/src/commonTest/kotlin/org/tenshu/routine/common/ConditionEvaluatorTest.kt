package org.tenshu.routine.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.tenshu.routine.common.engine.ConditionSnapshot
import org.tenshu.routine.common.engine.ConditionVerdict
import org.tenshu.routine.common.engine.DefaultConditionEvaluator
import org.tenshu.routine.common.engine.ObservedState
import org.tenshu.routine.common.engine.RoutineLocale
import org.tenshu.routine.common.program.Condition
import org.tenshu.routine.common.program.MatchOp
import org.tenshu.routine.common.program.Occupancy
import org.tenshu.routine.common.program.StateMatch

class ConditionEvaluatorTest {

    private val evaluator = DefaultConditionEvaluator()

    private val berlin = RoutineLocale("Europe/Berlin", 5_252_000, 1_340_500)

    /** 2026-07-01T20:00:00Z = 22:00 Berlin (CEST). */
    private val tenPmBerlin = 1_782_072_000_000L

    private class Snapshot(
        override val takenAtEpochMs: Long,
        private val states: Map<String, ObservedState> = emptyMap(),
        private val presence: Occupancy? = null,
    ) : ConditionSnapshot {
        override fun deviceState(deviceId: String) = states[deviceId]
        override fun presence() = presence
    }

    private fun stateOf(deviceId: String, vararg fields: Pair<String, String>) =
        ObservedState(deviceId, fields.toMap(), freshAsOfEpochMs = tenPmBerlin - 5_000)

    @Test
    fun no_conditions_means_met() {
        val verdict = evaluator.evaluate(emptyList(), Snapshot(tenPmBerlin), berlin)

        assertIs<ConditionVerdict.Met>(verdict)
    }

    @Test
    fun a_time_window_that_wraps_midnight_matches_the_evening_side() {
        // "after 18:00 until 05:00" — the common "only after sunset" shape.
        val condition = Condition.TimeWindow(fromMinuteOfDay = 18 * 60, toMinuteOfDay = 5 * 60)

        val verdict = evaluator.evaluate(listOf(condition), Snapshot(tenPmBerlin), berlin)

        assertIs<ConditionVerdict.Met>(verdict)
    }

    @Test
    fun a_time_window_that_wraps_midnight_rejects_the_afternoon() {
        val condition = Condition.TimeWindow(fromMinuteOfDay = 18 * 60, toMinuteOfDay = 5 * 60)
        // 2026-07-01T12:00:00Z = 14:00 Berlin.
        val twoPmBerlin = 1_782_043_200_000L

        val verdict = evaluator.evaluate(listOf(condition), Snapshot(twoPmBerlin), berlin)

        assertIs<ConditionVerdict.NotMet>(verdict)
    }

    @Test
    fun a_device_state_condition_reads_the_snapshot() {
        val condition = Condition.DeviceStateIs("lamp", StateMatch(field = "power", value = "off"))
        val snapshot = Snapshot(tenPmBerlin, mapOf("lamp" to stateOf("lamp", "power" to "off")))

        assertIs<ConditionVerdict.Met>(evaluator.evaluate(listOf(condition), snapshot, berlin))
    }

    @Test
    fun numeric_comparison_works_on_string_fields() {
        val condition = Condition.DeviceStateIs(
            "sensor",
            StateMatch(field = "temperatureDeciC", op = MatchOp.LT, value = "180"),
        )
        val snapshot = Snapshot(tenPmBerlin, mapOf("sensor" to stateOf("sensor", "temperatureDeciC" to "165")))

        assertIs<ConditionVerdict.Met>(evaluator.evaluate(listOf(condition), snapshot, berlin))
    }

    /** ⚠ A missing state row is *unknown*, not false — the engine must not guess. */
    @Test
    fun an_absent_device_row_is_unknown_not_not_met() {
        val condition = Condition.DeviceStateIs("ghost", StateMatch(field = "power", value = "on"))

        val verdict = evaluator.evaluate(listOf(condition), Snapshot(tenPmBerlin), berlin)

        val unknown = assertIs<ConditionVerdict.Unknown>(verdict)
        assertTrue(unknown.reason.contains("ghost"))
    }

    @Test
    fun a_device_that_does_not_report_the_field_is_unknown() {
        val condition = Condition.DeviceStateIs("lamp", StateMatch(field = "humidity", value = "50"))
        val snapshot = Snapshot(tenPmBerlin, mapOf("lamp" to stateOf("lamp", "power" to "on")))

        assertIs<ConditionVerdict.Unknown>(evaluator.evaluate(listOf(condition), snapshot, berlin))
    }

    /**
     * ⚠⚠ The single most important assertion in this file. RE-1 has no presence
     * notion at all, so an editor-gate bypass must fail **closed**: "arm the
     * alarm when nobody's home" firing because a phone battery died is the worst
     * failure mode of the entire engine.
     */
    @Test
    fun unknown_presence_fails_closed() {
        val condition = Condition.Presence(Occupancy.NOBODY_HOME)

        val verdict = evaluator.evaluate(listOf(condition), Snapshot(tenPmBerlin, presence = null), berlin)

        val unknown = assertIs<ConditionVerdict.Unknown>(verdict)
        assertTrue(unknown.reason.contains("presence"))
    }

    @Test
    fun known_presence_is_evaluated_normally() {
        val condition = Condition.Presence(Occupancy.NOBODY_HOME)

        val away = Snapshot(tenPmBerlin, presence = Occupancy.NOBODY_HOME)
        val home = Snapshot(tenPmBerlin, presence = Occupancy.SOMEBODY_HOME)

        assertIs<ConditionVerdict.Met>(evaluator.evaluate(listOf(condition), away, berlin))
        assertIs<ConditionVerdict.NotMet>(evaluator.evaluate(listOf(condition), home, berlin))
    }

    /** An unknown short-circuits: a later false cannot rescue it, and the unknown is the useful report. */
    @Test
    fun an_unknown_wins_over_a_later_not_met() {
        val conditions = listOf(
            Condition.DeviceStateIs("ghost", StateMatch(field = "power", value = "on")),
            Condition.TimeWindow(fromMinuteOfDay = 0, toMinuteOfDay = 1),
        )

        assertIs<ConditionVerdict.Unknown>(evaluator.evaluate(conditions, Snapshot(tenPmBerlin), berlin))
    }

    /** ⚠ Citations ride on every verdict — an unexplained non-run is a support ticket. */
    @Test
    fun every_verdict_carries_citations() {
        val met = evaluator.evaluate(
            listOf(Condition.TimeWindow(18 * 60, 5 * 60)), Snapshot(tenPmBerlin), berlin,
        )
        val notMet = evaluator.evaluate(
            listOf(Condition.DeviceStateIs("lamp", StateMatch(field = "power", value = "on"))),
            Snapshot(tenPmBerlin, mapOf("lamp" to stateOf("lamp", "power" to "off"))),
            berlin,
        )
        val unknown = evaluator.evaluate(
            listOf(Condition.Presence(Occupancy.NOBODY_HOME)), Snapshot(tenPmBerlin), berlin,
        )

        assertTrue(met.citations.isNotEmpty())
        assertTrue(notMet.citations.isNotEmpty())
        assertTrue(unknown.citations.isNotEmpty(), "an unknown must still say what it read")
    }

    @Test
    fun a_citation_records_how_fresh_the_reading_was() {
        val condition = Condition.DeviceStateIs("lamp", StateMatch(field = "power", value = "on"))
        val snapshot = Snapshot(tenPmBerlin, mapOf("lamp" to stateOf("lamp", "power" to "on")))

        val verdict = evaluator.evaluate(listOf(condition), snapshot, berlin)

        val citation = verdict.citations.single()
        assertEquals("lamp.power", citation.what)
        assertEquals("on", citation.read)
        assertEquals(tenPmBerlin - 5_000, citation.freshAsOfEpochMs)
    }

    @Test
    fun an_unresolvable_timezone_makes_a_time_window_unknown() {
        val bogus = RoutineLocale("Mars/Olympus_Mons", 0, 0)

        val verdict = evaluator.evaluate(
            listOf(Condition.TimeWindow(0, 1440)), Snapshot(tenPmBerlin), bogus,
        )

        assertIs<ConditionVerdict.Unknown>(verdict)
    }
}
