package app.tenshu.home.data

import app.tenshu.home.model.Action
import app.tenshu.home.model.Automation
import app.tenshu.home.model.Condition
import app.tenshu.home.model.DeviceEventKind
import app.tenshu.home.model.GeofenceDirection
import app.tenshu.home.model.SunEvent
import app.tenshu.home.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.routine.common.program.Action as ProgramAction
import org.tenshu.routine.common.program.Condition as ProgramCondition
import org.tenshu.routine.common.program.SunEvent as ProgramSunEvent
import org.tenshu.routine.common.program.Trigger as ProgramTrigger

class AutomationProgramMapperTest {

    private fun automation(
        trigger: Trigger = Trigger.Schedule(minutesOfDay = 22 * 60 + 30),
        conditions: List<Condition> = emptyList(),
        actions: List<Action> = listOf(Action.DeviceCommand("lamp", turnOn = true)),
    ) = Automation(
        id = "a1",
        name = "Porch light",
        enabled = true,
        trigger = trigger,
        conditions = conditions,
        actions = actions,
        lastRunAt = null,
    )

    // ------------------------------------------------------------ what maps

    @Test
    fun a_schedule_trigger_maps_to_time_of_day_every_day() {
        val program = assertNotNull(AutomationProgramMapper.toProgram(automation()))

        val trigger = assertIs<ProgramTrigger.TimeOfDay>(program.trigger)
        assertEquals(22 * 60 + 30, trigger.minuteOfDay)
        // ⚠ Empty means "every day" — faithful to an editor with no day filter,
        // not a placeholder that silently drops a user's choice.
        assertTrue(trigger.days.isEmpty())
    }

    @Test
    fun a_sun_trigger_maps_with_its_offset() {
        val program = assertNotNull(
            AutomationProgramMapper.toProgram(
                automation(trigger = Trigger.Sun(SunEvent.Sunset, offsetMinutes = -30))
            )
        )

        val trigger = assertIs<ProgramTrigger.Sun>(program.trigger)
        assertEquals(ProgramSunEvent.SUNSET, trigger.event)
        assertEquals(-30, trigger.offsetMinutes)
    }

    @Test
    fun a_device_condition_becomes_a_power_state_match() {
        val program = assertNotNull(
            AutomationProgramMapper.toProgram(
                automation(conditions = listOf(Condition.DeviceIs("sensor", on = true)))
            )
        )

        val condition = assertIs<ProgramCondition.DeviceStateIs>(program.conditions.single())
        assertEquals("sensor", condition.deviceId)
        assertEquals("power", condition.match.field)
        assertEquals("on", condition.match.value)
    }

    @Test
    fun a_device_command_becomes_an_absolute_set_power() {
        val program = assertNotNull(AutomationProgramMapper.toProgram(automation()))

        val action = assertIs<ProgramAction.SetPower>(program.actions.single())
        assertEquals("lamp", action.deviceId)
        assertTrue(action.on)
    }

    @Test
    fun a_chained_automation_becomes_a_run_routine() {
        val program = assertNotNull(
            AutomationProgramMapper.toProgram(
                automation(actions = listOf(Action.RunAutomation("other")))
            )
        )

        assertEquals("other", assertIs<ProgramAction.RunRoutine>(program.actions.single()).routineId)
    }

    // ------------------------------------------------------------ what is gated

    /**
     * ⚠⚠ The M2 editor's vocabulary is WIDER than the engine's. Each of these
     * must refuse with a reason the editor can show — the HD-2 rule that Home
     * never offers a flow it cannot honour. A silent drop here is a routine that
     * never runs and never says why.
     */
    @Test
    fun unsupported_constructs_are_refused_with_a_reason() {
        val cases = listOf(
            automation(trigger = Trigger.DeviceEvent("d", DeviceEventKind.MotionDetected)),
            automation(trigger = Trigger.Geofence(GeofenceDirection.Arrive)),
            automation(trigger = Trigger.VoicePhrase("goodnight")),
            automation(actions = listOf(Action.Notify("hello"))),
        )

        cases.forEach { case ->
            val reason = AutomationProgramMapper.unsupportedReason(case)
            assertNotNull(reason, "must be gated: ${case.trigger} / ${case.actions}")
            assertTrue(reason.isNotBlank())
            assertNull(
                AutomationProgramMapper.toProgram(case),
                "a gated automation must never produce a program",
            )
        }
    }

    /** ⭐ RE-4 un-gated this: presence is now executable, and fails closed at run time. */
    @Test
    fun a_presence_condition_is_no_longer_gated() {
        val presence = automation(conditions = listOf(Condition.Presence(nobodyHome = true)))

        assertNull(AutomationProgramMapper.unsupportedReason(presence))
        val program = assertNotNull(AutomationProgramMapper.toProgram(presence))
        assertIs<ProgramCondition.Presence>(program.conditions.single())
    }

    @Test
    fun an_automation_with_no_actions_is_refused() {
        assertNotNull(AutomationProgramMapper.unsupportedReason(automation(actions = emptyList())))
    }

    @Test
    fun a_supported_automation_has_no_reason() {
        assertNull(AutomationProgramMapper.unsupportedReason(automation()))
    }

    /**
     * ⭐ The gate and the writer share one source of truth, so a gap in the UI
     * can only ever produce a visible error — never a silently dead routine.
     */
    @Test
    fun the_gate_and_the_mapper_never_disagree() {
        val cases = listOf(
            automation(),
            automation(trigger = Trigger.Sun(SunEvent.Sunrise, 0)),
            automation(trigger = Trigger.VoicePhrase("x")),
            automation(actions = listOf(Action.Notify("x"))),
            automation(actions = emptyList()),
        )

        cases.forEach { case ->
            val gated = AutomationProgramMapper.unsupportedReason(case) != null
            val produced = AutomationProgramMapper.toProgram(case) != null
            assertEquals(!gated, produced, "gate and mapper disagree for $case")
        }
    }

    // ------------------------------------------------------------ the reverse trip

    @Test
    fun a_program_round_trips_back_into_the_editor_model() {
        val original = automation(
            trigger = Trigger.Sun(SunEvent.Sunset, offsetMinutes = -15),
            conditions = listOf(Condition.TimeWindow(18 * 60, 5 * 60)),
            actions = listOf(Action.DeviceCommand("lamp", turnOn = true)),
        )
        val program = assertNotNull(AutomationProgramMapper.toProgram(original))

        val back = assertNotNull(
            AutomationProgramMapper.toAutomation(
                id = "a1", name = "Porch light", enabled = true,
                program = program, lastRunAt = null,
            )
        )

        assertEquals(original.trigger, back.trigger)
        assertEquals(original.conditions, back.conditions)
        assertEquals(original.actions, back.actions)
    }

    /** A program shape this editor cannot represent is `null`, not a lossy guess. */
    @Test
    fun an_unrepresentable_program_does_not_become_an_automation() {
        val program = org.tenshu.routine.common.program.RoutineProgram(
            trigger = ProgramTrigger.VoicePhrase("goodnight"),
            actions = listOf(ProgramAction.SetPower("lamp", true)),
        )

        assertNull(
            AutomationProgramMapper.toAutomation("a", "n", true, program, null),
            "rendering a routine we cannot describe invites an edit that rewrites it",
        )
    }
}
