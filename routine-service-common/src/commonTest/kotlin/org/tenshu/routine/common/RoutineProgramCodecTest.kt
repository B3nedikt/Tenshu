package org.tenshu.routine.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.tenshu.routine.common.program.Action
import org.tenshu.routine.common.program.Condition
import org.tenshu.routine.common.program.JsonRoutineProgramCodec
import org.tenshu.routine.common.program.PROGRAM_VERSION
import org.tenshu.routine.common.program.ProgramParse
import org.tenshu.routine.common.program.RoutineProgram
import org.tenshu.routine.common.program.SunEvent
import org.tenshu.routine.common.program.Trigger

class RoutineProgramCodecTest {

    private val codec = JsonRoutineProgramCodec()

    @Test
    fun round_trips_a_program_through_json() {
        val program = RoutineProgram(
            trigger = Trigger.Sun(SunEvent.SUNSET, offsetMinutes = -15),
            conditions = listOf(Condition.TimeWindow(fromMinuteOfDay = 1080, toMinuteOfDay = 300)),
            actions = listOf(
                Action.SetPower(deviceId = "zigbee2mqtt:0x00", on = true),
                Action.Notify(title = "Porch", body = "Light on"),
            ),
        )

        val encoded = codec.encode(program)
        assertEquals(PROGRAM_VERSION, encoded.programVersion)

        val parsed = codec.parse(encoded.programJson, encoded.programVersion)
        assertIs<ProgramParse.Parsed>(parsed)

        val trigger = assertIs<Trigger.Sun>(parsed.program.trigger)
        assertEquals(SunEvent.SUNSET, trigger.event)
        assertEquals(-15, trigger.offsetMinutes)
        assertEquals(2, parsed.program.actions.size)
    }

    /**
     * RE-D10's fence. ⚠ The verdict must be this *value*, not an exception:
     * the caller needs two distinct consequences from it — do not execute AND
     * do not claim the executor slot — so that the routine falls through to a
     * device that does understand it.
     */
    @Test
    fun a_newer_program_version_is_unsupported_not_malformed() {
        val encoded = codec.encode(
            RoutineProgram(
                trigger = Trigger.TimeOfDay(minuteOfDay = 600),
                actions = listOf(Action.Notify("t", "b")),
            )
        )

        val parsed = codec.parse(encoded.programJson, PROGRAM_VERSION + 1)

        val unsupported = assertIs<ProgramParse.UnsupportedVersion>(parsed)
        assertEquals(PROGRAM_VERSION + 1, unsupported.version)
    }

    @Test
    fun an_unknown_action_kind_is_malformed_not_a_crash() {
        val json = """
            {"trigger":{"type":"timeOfDay","minuteOfDay":600,"days":[]},
             "conditions":[],
             "actions":[{"type":"launchMissiles","deviceId":"x"}]}
        """.trimIndent()

        assertIs<ProgramParse.Malformed>(codec.parse(json, PROGRAM_VERSION))
    }

    @Test
    fun garbage_is_malformed() {
        assertIs<ProgramParse.Malformed>(codec.parse("not json at all", PROGRAM_VERSION))
    }

    @Test
    fun a_program_with_no_actions_is_refused_at_authoring_time() {
        val json = """{"trigger":{"type":"timeOfDay","minuteOfDay":600,"days":[]},"actions":[]}"""

        val parsed = codec.parse(json, PROGRAM_VERSION)

        val malformed = assertIs<ProgramParse.Malformed>(parsed)
        assertTrue(malformed.reason.contains("action"))
    }

    @Test
    fun out_of_range_values_are_refused() {
        val badMinute = codec.encode(
            RoutineProgram(
                trigger = Trigger.TimeOfDay(minuteOfDay = 99_999),
                actions = listOf(Action.Notify("t", "b")),
            )
        )
        assertIs<ProgramParse.Malformed>(codec.parse(badMinute.programJson, PROGRAM_VERSION))

        val badLevel = codec.encode(
            RoutineProgram(
                trigger = Trigger.TimeOfDay(minuteOfDay = 600),
                actions = listOf(Action.SetLevel(deviceId = "d", percent = 400)),
            )
        )
        assertIs<ProgramParse.Malformed>(codec.parse(badLevel.programJson, PROGRAM_VERSION))
    }

    /** A version below 1 is not a version — it is a zero-valued field. */
    @Test
    fun version_zero_is_malformed() {
        val encoded = codec.encode(
            RoutineProgram(
                trigger = Trigger.TimeOfDay(minuteOfDay = 600),
                actions = listOf(Action.Notify("t", "b")),
            )
        )
        assertIs<ProgramParse.Malformed>(codec.parse(encoded.programJson, 0))
    }
}
