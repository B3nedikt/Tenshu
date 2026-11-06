package org.tenshu.routine.proxy

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.entity.DataId
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.common.homeDeviceServiceCommonModule
import org.tenshu.routine.PresenceState
import org.tenshu.routine.Routine
import org.tenshu.routine.RoutineService
import org.tenshu.routine.common.engine.CapabilityTier
import org.tenshu.routine.common.engine.FiringIds
import org.tenshu.routine.common.engine.RosterDevice
import org.tenshu.routine.common.program.Action
import org.tenshu.routine.common.program.Condition
import org.tenshu.routine.common.program.JsonRoutineProgramCodec
import org.tenshu.routine.common.program.Occupancy
import org.tenshu.routine.common.program.RoutineProgram
import org.tenshu.routine.common.program.Trigger
import org.tenshu.routine.common.routineServiceCommonModule

/** RE-4: the `Presence` condition, over real rows, still failing closed. */
class RoutinePresenceTest {

    private lateinit var proxy: TestProxy
    private var now = 1_782_000_000_000L
    private val codec = JsonRoutineProgramCodec()

    private val hub = RosterDevice("hub-1", CapabilityTier.ALWAYS_ON)
    private val phone = RosterDevice("phone-1", CapabilityTier.RESIDENT)

    @BeforeTest
    fun setup() {
        proxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(routineServiceCommonModule)
            it.schemaRegistry.registerConfig(homeDeviceServiceCommonModule)
        }
        proxy.executionContext.initRoutineService()
    }

    private fun engine(roster: List<RosterDevice> = listOf(hub, phone)) = RoutineEngine(
        executionContext = proxy.executionContext,
        implementationFactory = OperationImplementationFactory { null },
        nowEpochMs = { now },
        thisDeviceId = { "hub-1" },
        roster = { roster },
    )

    private fun awayRoutine(expect: Occupancy = Occupancy.NOBODY_HOME) {
        val encoded = codec.encode(
            RoutineProgram(
                trigger = Trigger.TimeOfDay(minuteOfDay = 6 * 60),
                conditions = listOf(Condition.Presence(expect)),
                actions = listOf(Action.SetPower("lamp", on = false)),
            )
        )
        proxy.executionContext.database.transaction {
            proxy.executionContext.database.addToArray(
                ArrayDatabaseReference(
                    arrayId = DataId.RelationDataId.create(
                        context = proxy.tContext, type = TList.ID,
                        relationId = RoutineService.Relations.ROUTINES,
                    ),
                    isEntity = false, elementSchemaId = Routine.ID,
                ),
                Routine.create(
                    context = proxy.tContext,
                    routineId = "r1", name = "Away lights",
                    timeZoneId = "Europe/Berlin",
                    createdAtEpochMs = now, updatedAtEpochMs = now,
                    programJson = encoded.programJson,
                    programVersion = encoded.programVersion,
                ),
            )
        }
    }

    private fun presence(deviceId: String, state: String, ageMs: Long = 0) {
        proxy.executionContext.saveData(
            PresenceState.create(
                context = proxy.tContext,
                deviceId = deviceId,
                state = state,
                freshAsOfEpochMs = now - ageMs,
            )
        )
    }

    private fun commandFor(firingId: String) =
        proxy.executionContext.database.getData(
            DataId.StringDataId.create(
                context = proxy.tContext, type = DeviceCommand.ID,
                keyValue = "firing:$firingId:0",
            )
        )

    @Test
    fun the_routine_runs_when_everyone_is_out() {
        awayRoutine()
        presence("phone-1", PresenceState.OUTSIDE)

        assertIs<FiringResult.Ran>(engine().runNow("r1"))
    }

    @Test
    fun the_routine_skips_when_somebody_is_home() {
        awayRoutine()
        presence("phone-1", PresenceState.INSIDE)

        assertIs<FiringResult.Skipped>(engine().runNow("r1"))
    }

    /** ⚠⚠ The assertion that matters: a dead phone must never read as "out". */
    @Test
    fun a_stale_presence_row_fails_closed() {
        awayRoutine()
        presence("phone-1", PresenceState.OUTSIDE, ageMs = PresenceState.STALE_WINDOW_MS + 1)

        val result = engine().runNow("r1")

        assertIs<FiringResult.Skipped>(result)
        assertNull(commandFor(FiringIds.manual("r1", now)), "a stale signal must not actuate")
    }

    @Test
    fun no_presence_rows_at_all_fails_closed() {
        awayRoutine()

        assertIs<FiringResult.Skipped>(engine().runNow("r1"))
    }

    /** ⚠ A hub is always home; counting it would make nobody's-home impossible. */
    @Test
    fun the_hub_is_excluded_from_the_verdict() {
        awayRoutine()
        presence("hub-1", PresenceState.INSIDE)
        presence("phone-1", PresenceState.OUTSIDE)

        assertIs<FiringResult.Ran>(engine().runNow("r1"))
    }

    /** ⚠ A revoked phone's last position must not keep deciding the house. */
    @Test
    fun a_revoked_device_is_excluded_from_the_verdict() {
        awayRoutine()
        presence("old-phone", PresenceState.INSIDE)
        presence("phone-1", PresenceState.OUTSIDE)

        val roster = listOf(hub, phone, RosterDevice("old-phone", CapabilityTier.RESIDENT, revoked = true))

        assertIs<FiringResult.Ran>(engine(roster).runNow("r1"))
    }

    @Test
    fun the_citation_records_what_presence_was_read() {
        awayRoutine()
        presence("phone-1", PresenceState.OUTSIDE)

        engine().runNow("r1")

        val run = proxy.executionContext.database.getData(
            DataId.StringDataId.create(
                context = proxy.tContext,
                type = org.tenshu.routine.RoutineRun.ID,
                keyValue = FiringIds.manual("r1", now),
            )
        ) as? org.tenshu.routine.RoutineRun
        assertNotNull(run)
        kotlin.test.assertTrue(run.conditionCitations.get().contains("presence"))
    }
}
