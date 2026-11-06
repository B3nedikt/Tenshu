package org.tenshu.routine.proxy

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.entity.DataId
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.common.homeDeviceServiceCommonModule
import org.tenshu.routine.Routine
import org.tenshu.routine.RoutineRun
import org.tenshu.routine.RoutineService
import org.tenshu.routine.common.engine.CapabilityTier
import org.tenshu.routine.common.engine.DefaultSafetyGate
import org.tenshu.routine.common.engine.FiringIds
import org.tenshu.routine.common.engine.RosterDevice
import org.tenshu.routine.common.program.Action
import org.tenshu.routine.common.program.JsonRoutineProgramCodec
import org.tenshu.routine.common.program.MatchOp
import org.tenshu.routine.common.program.RoutineProgram
import org.tenshu.routine.common.program.StateMatch
import org.tenshu.routine.common.program.Trigger
import org.tenshu.routine.common.routineServiceCommonModule

/**
 * The RE-2 device-event fast path, driven over a real in-process proxy.
 *
 * ⭐ Nothing here touches a relay, a hub, or a radio — the whole path is
 * bridge-event in, `DeviceCommand` row out, which is exactly the "no network in
 * the path" claim in its testable form.
 */
class RoutineDeviceEventTest {

    private lateinit var proxy: TestProxy
    private var now = 1_782_000_000_000L
    private val codec = JsonRoutineProgramCodec()
    private val deviceId = "hub-1"

    @BeforeTest
    fun setup() {
        proxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(routineServiceCommonModule)
            it.schemaRegistry.registerConfig(homeDeviceServiceCommonModule)
        }
        // The relation maintainer production wires at init — without it the run
        // rows exist as entities but never join RoutineService.routineRuns, and
        // an assertion over that relation would silently read empty.
        proxy.executionContext.initRoutineService()
    }

    private fun engine(safetyGate: DefaultSafetyGate = DefaultSafetyGate()) = RoutineEngine(
        executionContext = proxy.executionContext,
        implementationFactory = OperationImplementationFactory { null },
        nowEpochMs = { now },
        thisDeviceId = { deviceId },
        roster = { listOf(RosterDevice(deviceId, CapabilityTier.ALWAYS_ON)) },
        // ⚠⚠ The load-bearing flag: only a bridge host may observe device events.
        hostsDeviceBridge = true,
        safetyGate = safetyGate,
    )

    private fun motionLightsRoutine(
        routineId: String = "r1",
        sensorId: String = "motion-1",
        lampId: String = "lamp",
    ) {
        val encoded = codec.encode(
            RoutineProgram(
                trigger = Trigger.DeviceEvent(
                    deviceId = sensorId,
                    match = StateMatch(field = "occupancy", op = MatchOp.EQ, value = "true"),
                ),
                actions = listOf(Action.SetPower(lampId, on = true)),
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
                    routineId = routineId,
                    name = "Motion lights",
                    timeZoneId = "Europe/Berlin",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    programJson = encoded.programJson,
                    programVersion = encoded.programVersion,
                ),
            )
        }
    }

    private fun motion(
        sensorId: String = "motion-1",
        occupied: Boolean = true,
        lastCommandId: String = "",
        at: Long = now,
    ) = ObservedStateChange(
        deviceId = sensorId,
        fields = mapOf("occupancy" to occupied.toString()),
        freshAsOfEpochMs = at,
        lastCommandId = lastCommandId,
    )

    private fun commandFor(firingId: String, index: Int): DeviceCommand? =
        proxy.executionContext.database.getData(
            DataId.StringDataId.create(
                context = proxy.tContext, type = DeviceCommand.ID,
                keyValue = "firing:$firingId:$index",
            )
        ) as? DeviceCommand

    private fun firingId(routineId: String = "r1", sensorId: String = "motion-1", at: Long = now) =
        FiringIds.deviceEvent(routineId, sensorId, at)

    // ------------------------------------------------------------------ tests

    /** ⭐ The RE-2 headline: motion in, light command out, no network anywhere. */
    @Test
    fun a_matching_device_event_fires_the_routine() {
        motionLightsRoutine()

        val results = engine().onDeviceEvent(motion())

        assertEquals(1, results.filterIsInstance<FiringResult.Ran>().size)
        val command = assertNotNull(commandFor(firingId(), 0))
        assertEquals("lamp", command.targetDeviceId.get())
        assertEquals(DeviceCommand.ARG_ON, command.argValue.get())
    }

    @Test
    fun an_event_from_another_device_is_ignored() {
        motionLightsRoutine(sensorId = "motion-1")

        val results = engine().onDeviceEvent(motion(sensorId = "motion-2"))

        assertTrue(results.isEmpty())
    }

    @Test
    fun an_event_that_does_not_match_the_predicate_does_not_fire() {
        motionLightsRoutine()

        val results = engine().onDeviceEvent(motion(occupied = false))

        assertTrue(results.none { it is FiringResult.Ran })
        assertNull(commandFor(firingId(), 0))
    }

    /**
     * ⚠⚠ The feedback-loop guard (RE-D9), and the reason it costs no schema
     * change: `DeviceCommand.commandId` is caller-minted and the hub echoes it
     * back on the resulting state row, so a change this routine caused is
     * recognisable **by name**. Without this, a routine whose action changes the
     * state its own trigger watches runs forever.
     */
    @Test
    fun a_routines_own_command_does_not_re_trigger_it() {
        motionLightsRoutine()
        val engine = engine()

        // First: a genuine event fires the routine.
        assertEquals(1, engine.onDeviceEvent(motion()).filterIsInstance<FiringResult.Ran>().size)

        // Then: the echo of its OWN command comes back as a state change.
        now += 1_000
        val echo = engine.onDeviceEvent(
            motion(at = now, lastCommandId = "firing:${firingId(at = now - 1_000)}:0"),
        )

        assertEquals(1, echo.size)
        val stoodDown = assertIs<FiringResult.StoodDown>(echo.single())
        assertTrue(stoodDown.reason.contains("self-triggered"))
        assertNull(commandFor(firingId(at = now), 0), "the echo must not actuate again")
    }

    /** ⚠ A change caused by someone else — a wall switch — must still fire. */
    @Test
    fun a_command_from_another_routine_still_triggers_this_one() {
        motionLightsRoutine(routineId = "mine")

        val results = engine().onDeviceEvent(
            motion(lastCommandId = "firing:someone-else@123:0"),
        )

        assertEquals(1, results.filterIsInstance<FiringResult.Ran>().size)
    }

    /** ⚠ An event is its own occurrence — the catch-up window must not drop it. */
    @Test
    fun an_event_with_an_older_timestamp_still_fires() {
        motionLightsRoutine()
        val stale = now - DefaultSafetyGate.CATCHUP_WINDOW_MS - 60_000

        val results = engine().onDeviceEvent(motion(at = stale))

        assertEquals(1, results.filterIsInstance<FiringResult.Ran>().size)
    }

    /**
     * ⚠⚠ The reason the safety gate had to become process-scoped in this slice:
     * a flapping sensor drives many firings inside one process lifetime, and a
     * gate that forgot between them would let every one through.
     */
    @Test
    fun a_flapping_sensor_is_throttled_by_a_shared_gate() {
        motionLightsRoutine()
        val gate = DefaultSafetyGate()
        val engine = engine(safetyGate = gate)

        var ran = 0
        var throttled = 0
        repeat(DefaultSafetyGate.MAX_FIRINGS_PER_WINDOW + 10) {
            now += 1
            engine.onDeviceEvent(motion(at = now)).forEach { result ->
                when (result) {
                    is FiringResult.Ran -> ran++
                    is FiringResult.Throttled -> throttled++
                    else -> Unit
                }
            }
        }

        assertEquals(DefaultSafetyGate.MAX_FIRINGS_PER_WINDOW, ran)
        assertTrue(throttled > 0, "the rest must be refused, not run")
    }

    /** ⭐ And the refusals coalesce, so the flood does not become a row flood. */
    @Test
    fun a_flapping_sensor_does_not_flood_the_realm_with_rows() {
        motionLightsRoutine()
        val engine = engine()

        repeat(DefaultSafetyGate.MAX_FIRINGS_PER_WINDOW + 20) {
            now += 1
            engine.onDeviceEvent(motion(at = now))
        }

        val throttledRows = proxy.executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = proxy.tContext, type = TList.ID,
                    relationId = RoutineService.Relations.ROUTINE_RUNS,
                ),
                isEntity = false, elementSchemaId = RoutineRun.ID,
            )
        )?.toList().orEmpty()
            .filterIsInstance<RoutineRun>()
            .filter { it.state.get() == RoutineRun.STATE_THROTTLED }

        assertEquals(1, throttledRows.size, "refusals must coalesce into ONE row per window")
        assertTrue(throttledRows.single().coalescedCount.get() > 0)
    }

    /**
     * ⚠⚠ RE-D1: a process without a bridge cannot observe device events, so such
     * a routine never arms there. This is the whole multi-device design in one
     * assertion.
     */
    @Test
    fun a_process_without_a_bridge_never_fires_a_device_event_routine() {
        motionLightsRoutine()
        val phone = RoutineEngine(
            executionContext = proxy.executionContext,
            implementationFactory = OperationImplementationFactory { null },
            nowEpochMs = { now },
            thisDeviceId = { "phone-1" },
            roster = { listOf(RosterDevice("phone-1", CapabilityTier.MOBILE)) },
            hostsDeviceBridge = false,
        )

        assertTrue(phone.onDeviceEvent(motion()).isEmpty())
        assertNull(commandFor(firingId(), 0))
    }

    @Test
    fun a_disabled_routine_ignores_device_events() {
        motionLightsRoutine()
        proxy.executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = proxy.tContext, type = TList.ID,
                    relationId = RoutineService.Relations.ROUTINES,
                ),
                isEntity = false, elementSchemaId = Routine.ID,
            )
        )?.toList().orEmpty().filterIsInstance<Routine>().forEach { routine ->
            proxy.executionContext.saveData(
                Routine.create(
                    context = proxy.tContext,
                    routineId = routine.routineId.get(),
                    name = routine.name.get(),
                    enabled = false,
                    timeZoneId = routine.timeZoneId.get(),
                    createdAtEpochMs = routine.createdAtEpochMs.get(),
                    updatedAtEpochMs = now,
                    programJson = routine.programJson.get(),
                    programVersion = routine.programVersion.get(),
                )
            )
        }

        assertTrue(engine().onDeviceEvent(motion()).none { it is FiringResult.Ran })
    }

    /** The event source must replay a short tail — HD-2's `replay = 0` lesson. */
    @Test
    fun the_event_source_replays_a_short_tail() = runTest {
        val source = MutableLocalDeviceEventSource()

        // Emitted BEFORE anyone collects.
        assertTrue(source.emit(motion()))

        val seen = mutableListOf<ObservedStateChange>()
        kotlinx.coroutines.withTimeoutOrNull(500) {
            source.events().collect { seen += it }
        }

        assertEquals(1, seen.size, "an event emitted before the collector attached must survive")
    }
}
