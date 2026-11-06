package org.tenshu.routine.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.trait.RealmData
import org.tenshu.core.trait.Syncable
import org.tenshu.routine.Routine
import org.tenshu.routine.RoutineRun
import org.tenshu.routine.common.program.Action
import org.tenshu.routine.common.program.JsonRoutineProgramCodec
import org.tenshu.routine.common.program.RoutineProgram
import org.tenshu.routine.common.program.SunEvent
import org.tenshu.routine.common.program.Trigger

/**
 * Wire round-trip for the two new composites.
 *
 * ⚠ In-process tests do not exercise serialization, so a type that is
 * registered in the module but broken on the wire passes everything else and
 * fails only on a real peer. This is that gate.
 */
class RoutineSerializationTest {

    private val registry = createCoreTestRegistry().also {
        it.registerConfig(routineServiceCommonModule)
    }
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(registry, registry)

    @Test
    fun routine_roundTripsAllFields() {
        val program = JsonRoutineProgramCodec().encode(
            RoutineProgram(
                trigger = Trigger.Sun(SunEvent.SUNSET, offsetMinutes = -10),
                actions = listOf(Action.SetPower("zigbee2mqtt:0x01", on = true)),
            )
        )

        val original = Routine.create(
            context = context,
            routineId = "routine-1",
            name = "Porch light at sunset",
            enabled = true,
            state = Routine.STATE_ACTIVE,
            suspendedReason = "",
            timeZoneId = "Europe/Berlin",
            homeLatE5 = 5_252_000,
            homeLonE5 = 1_340_500,
            authorDeviceId = "phone-7",
            createdAtEpochMs = 1_720_000_000_000,
            updatedAtEpochMs = 1_720_000_100_000,
            programJson = program.programJson,
            programVersion = program.programVersion,
        )

        val parsed = parser.parse(serializer.serialize(original)) as Routine

        assertEquals("routine-1", parsed.routineId.get())
        assertEquals("Porch light at sunset", parsed.name.get())
        assertTrue(parsed.enabled.get())
        assertEquals(Routine.STATE_ACTIVE, parsed.state.get())
        assertEquals("Europe/Berlin", parsed.timeZoneId.get())
        assertEquals(5_252_000L, parsed.homeLatE5.get())
        assertEquals(1_340_500L, parsed.homeLonE5.get())
        assertEquals("phone-7", parsed.authorDeviceId.get())
        assertEquals(program.programJson, parsed.programJson.get())
        assertEquals(program.programVersion, parsed.programVersion.get())
    }

    /** ⭐ The program survives the wire as a string and re-parses on the far side. */
    @Test
    fun the_program_is_still_parseable_after_a_wire_round_trip() {
        val codec = JsonRoutineProgramCodec()
        val encoded = codec.encode(
            RoutineProgram(
                trigger = Trigger.TimeOfDay(minuteOfDay = 1350, days = setOf(1, 2, 3, 4, 5)),
                actions = listOf(Action.Notify("Bedtime", "Lights out")),
            )
        )
        val original = Routine.create(
            context = context,
            routineId = "r",
            name = "n",
            timeZoneId = "Europe/Berlin",
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
            programJson = encoded.programJson,
            programVersion = encoded.programVersion,
        )

        val parsed = parser.parse(serializer.serialize(original)) as Routine
        val reparsed = codec.parse(parsed.programJson.get(), parsed.programVersion.get())

        val ok = reparsed as? org.tenshu.routine.common.program.ProgramParse.Parsed
        assertTrue(ok != null, "program must survive the wire")
        assertEquals(1, ok.program.actions.size)
    }

    @Test
    fun routineRun_roundTripsAllFields() {
        val original = RoutineRun.create(
            context = context,
            firingId = "routine-1@1720000000000",
            routineId = "routine-1",
            triggerInstantEpochMs = 1_720_000_000_000,
            executorDeviceId = "hub-1",
            claimedAtEpochMs = 1_720_000_000_100,
            finishedAtEpochMs = 1_720_000_000_500,
            state = RoutineRun.STATE_RAN,
            wasFallback = true,
            chainRootFiringId = "routine-1@1720000000000",
            depth = 0,
            conditionCitations = "timeWindow: local minuteOfDay=1320",
            actionOutcomes = "setPower(zigbee2mqtt:0x01)=Pending",
            coalescedCount = 3,
        )

        val parsed = parser.parse(serializer.serialize(original)) as RoutineRun

        assertEquals("routine-1@1720000000000", parsed.firingId.get())
        assertEquals("routine-1", parsed.routineId.get())
        assertEquals("hub-1", parsed.executorDeviceId.get())
        assertEquals(RoutineRun.STATE_RAN, parsed.state.get())
        assertTrue(parsed.wasFallback.get())
        assertEquals(0L, parsed.depth.get())
        assertEquals(3L, parsed.coalescedCount.get())
        assertEquals("timeWindow: local minuteOfDay=1320", parsed.conditionCitations.get())
    }

    @Test
    fun both_types_are_syncable_and_realm_data() {
        listOf(Routine.Schema, RoutineRun.Schema).forEach { schema ->
            assertTrue(schema.hasTrait(Syncable.ID), "${schema.id} must be Syncable")
            assertTrue(
                schema.hasTrait(RealmData.ID),
                "${schema.id} must be RealmData so Control's Data section enumerates it",
            )
        }
    }

    /**
     * ⚠⚠ Suspension is a distinct state, never `enabled = false` (RE-D9). Pinned
     * here so a "simplification" that folds them together fails a test rather
     * than silently destroying the user's own toggle.
     */
    @Test
    fun suspension_is_a_state_not_a_disabled_flag() {
        val suspended = Routine.create(
            context = context,
            routineId = "r",
            name = "n",
            enabled = true,
            state = Routine.STATE_SUSPENDED,
            suspendedReason = "runaway",
            timeZoneId = "Europe/Berlin",
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
            programJson = "{}",
            programVersion = 1,
        )

        val parsed = parser.parse(serializer.serialize(suspended)) as Routine

        assertTrue(parsed.enabled.get(), "the user's switch is untouched by suspension")
        assertEquals(Routine.STATE_SUSPENDED, parsed.state.get())
        assertEquals("runaway", parsed.suspendedReason.get())
    }
}
