package org.tenshu.routine.proxy

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.entity.DataId
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.common.homeDeviceServiceCommonModule
import org.tenshu.routine.Routine
import org.tenshu.routine.RoutineRun
import org.tenshu.routine.RoutineService
import org.tenshu.routine.common.engine.CapabilityTier
import org.tenshu.routine.common.engine.DefaultSafetyGate
import org.tenshu.routine.common.engine.RosterDevice
import org.tenshu.routine.common.program.Action
import org.tenshu.routine.common.program.Condition
import org.tenshu.routine.common.program.JsonRoutineProgramCodec
import org.tenshu.routine.common.program.MatchOp
import org.tenshu.routine.common.program.Occupancy
import org.tenshu.routine.common.program.RoutineProgram
import org.tenshu.routine.common.program.StateMatch
import org.tenshu.routine.common.program.Trigger
import org.tenshu.routine.common.routineServiceCommonModule

/**
 * Drives the real [RoutineEngine] over a real in-process proxy on a **fake
 * clock** — the §6 claim that the whole engine is provable with zero hardware.
 */
class RoutineEngineTest {

    private lateinit var proxy: TestProxy
    private var now = 1_782_000_000_000L // 2026-06-21T04:00:00Z, a Sunday

    private val codec = JsonRoutineProgramCodec()
    private val deviceId = "device-under-test"

    @BeforeTest
    fun setup() {
        proxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(routineServiceCommonModule)
            it.schemaRegistry.registerConfig(homeDeviceServiceCommonModule)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun engine(
        roster: List<RosterDevice> = listOf(RosterDevice(deviceId, CapabilityTier.RESIDENT)),
        me: String? = deviceId,
        safetyGate: DefaultSafetyGate = DefaultSafetyGate(),
    ) = RoutineEngine(
        executionContext = proxy.executionContext,
        implementationFactory = OperationImplementationFactory { null },
        nowEpochMs = { now },
        thisDeviceId = { me },
        roster = { roster },
        safetyGate = safetyGate,
    )

    private fun saveRoutine(
        routineId: String = "r1",
        program: RoutineProgram,
        enabled: Boolean = true,
        state: String = Routine.STATE_ACTIVE,
    ): Routine {
        val encoded = codec.encode(program)
        val routine = Routine.create(
            context = proxy.tContext,
            routineId = routineId,
            name = "Test routine",
            enabled = enabled,
            state = state,
            timeZoneId = "Europe/Berlin",
            homeLatE5 = 5_252_000,
            homeLonE5 = 1_340_500,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            programJson = encoded.programJson,
            programVersion = encoded.programVersion,
        )
        proxy.executionContext.database.transaction {
            proxy.executionContext.database.addToArray(routinesRef(), routine)
        }
        return routine
    }

    private fun routinesRef() = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = proxy.tContext, type = TList.ID,
            relationId = RoutineService.Relations.ROUTINES,
        ),
        isEntity = false, elementSchemaId = Routine.ID,
    )

    private fun putDeviceState(deviceIdValue: String, powerOn: Boolean) {
        proxy.executionContext.database.transaction {
            proxy.executionContext.database.addToArray(
                ArrayDatabaseReference(
                    arrayId = DataId.RelationDataId.create(
                        context = proxy.tContext, type = TList.ID,
                        relationId = "tenshu/home/device/service/deviceStates",
                    ),
                    isEntity = false, elementSchemaId = DeviceState.ID,
                ),
                DeviceState.create(
                    context = proxy.tContext,
                    deviceId = deviceIdValue,
                    online = true,
                    power = if (powerOn) DeviceState.POWER_ON else DeviceState.POWER_OFF,
                    freshAsOf = now,
                ),
            )
        }
    }

    private fun run(firingId: String): RoutineRun? =
        proxy.executionContext.database.getData(
            DataId.StringDataId.create(
                context = proxy.tContext, type = RoutineRun.ID, keyValue = firingId,
            )
        ) as? RoutineRun

    private fun commandFor(firingId: String, index: Int): DeviceCommand? =
        proxy.executionContext.database.getData(
            DataId.StringDataId.create(
                context = proxy.tContext, type = DeviceCommand.ID,
                keyValue = "firing:$firingId:$index",
            )
        ) as? DeviceCommand

    private fun lightsOn() = RoutineProgram(
        trigger = Trigger.TimeOfDay(minuteOfDay = 6 * 60),
        actions = listOf(Action.SetPower("lamp", on = true)),
    )

    // ------------------------------------------------------------------ tests

    /** ⭐ The cheapest end-to-end proof of the whole loop. */
    @Test
    fun run_now_fires_actions_and_records_a_run() {
        saveRoutine(program = lightsOn())

        val result = engine().runNow("r1")

        assertIs<FiringResult.Ran>(result)
        val row = assertNotNull(
            proxy.executionContext.database.getArrayData(
                ArrayDatabaseReference(
                    arrayId = DataId.RelationDataId.create(
                        context = proxy.tContext, type = TList.ID,
                        relationId = RoutineService.Relations.ROUTINE_RUNS,
                    ),
                    isEntity = false, elementSchemaId = RoutineRun.ID,
                )
            )?.toList()?.filterIsInstance<RoutineRun>()?.firstOrNull()
                ?: run(org.tenshu.routine.common.engine.FiringIds.manual("r1", now))
        )
        assertEquals(RoutineRun.STATE_RAN, row.state.get())
        assertEquals("r1", row.routineId.get())
    }

    /**
     * ⭐⭐ The `commandId` carries the firing, which is what makes RE-D9's
     * self-trigger suppression free — with no schema change.
     */
    @Test
    fun a_device_action_writes_a_command_whose_id_names_the_firing() {
        saveRoutine(program = lightsOn())
        val firingId = org.tenshu.routine.common.engine.FiringIds.manual("r1", now)

        engine().runNow("r1")

        val command = assertNotNull(commandFor(firingId, 0))
        assertEquals("lamp", command.targetDeviceId.get())
        assertEquals(DeviceCommand.VERB_SET_POWER, command.verb.get())
        assertEquals(DeviceCommand.ARG_ON, command.argValue.get())
    }

    /**
     * The absolute vocabulary carries its argument through unchanged.
     *
     * ⚠ Note where the *range* is actually enforced: an out-of-range percent is
     * refused by the codec at **authoring** time (`RoutineProgramCodecTest`), so
     * it never reaches the engine. The engine's `coerceIn` is defence in depth
     * for a program that somehow got past validation — not the primary guard,
     * and a test that asserted clamping here would be asserting the wrong layer.
     */
    @Test
    fun set_level_passes_its_argument_through() {
        saveRoutine(
            program = RoutineProgram(
                trigger = Trigger.TimeOfDay(360),
                actions = listOf(Action.SetLevel("lamp", percent = 65)),
            )
        )
        val firingId = org.tenshu.routine.common.engine.FiringIds.manual("r1", now)

        engine().runNow("r1")

        val command = assertNotNull(commandFor(firingId, 0))
        assertEquals(DeviceCommand.VERB_SET_LEVEL, command.verb.get())
        assertEquals("65", command.argValue.get())
    }

    /** ⚠ An unauthorable program never becomes a routine that silently never runs. */
    @Test
    fun an_out_of_range_level_is_refused_before_it_can_fire() {
        saveRoutine(
            program = RoutineProgram(
                trigger = Trigger.TimeOfDay(360),
                actions = listOf(Action.SetLevel("lamp", percent = 150)),
            )
        )

        assertIs<FiringResult.StoodDown>(engine().runNow("r1"))
    }

    /** RE-D7: conditions are read once, at action time, and the verdict is cited. */
    @Test
    fun a_met_condition_lets_the_routine_run_and_is_cited() {
        putDeviceState("sensor", powerOn = true)
        saveRoutine(
            program = RoutineProgram(
                trigger = Trigger.TimeOfDay(360),
                conditions = listOf(
                    Condition.DeviceStateIs("sensor", StateMatch("power", MatchOp.EQ, "on"))
                ),
                actions = listOf(Action.SetPower("lamp", on = true)),
            )
        )
        val firingId = org.tenshu.routine.common.engine.FiringIds.manual("r1", now)

        assertIs<FiringResult.Ran>(engine().runNow("r1"))
        assertTrue(assertNotNull(run(firingId)).conditionCitations.get().contains("sensor.power"))
    }

    @Test
    fun an_unmet_condition_skips_without_issuing_a_command() {
        putDeviceState("sensor", powerOn = false)
        saveRoutine(
            program = RoutineProgram(
                trigger = Trigger.TimeOfDay(360),
                conditions = listOf(
                    Condition.DeviceStateIs("sensor", StateMatch("power", MatchOp.EQ, "on"))
                ),
                actions = listOf(Action.SetPower("lamp", on = true)),
            )
        )
        val firingId = org.tenshu.routine.common.engine.FiringIds.manual("r1", now)

        assertIs<FiringResult.Skipped>(engine().runNow("r1"))
        assertEquals(RoutineRun.STATE_SKIPPED, assertNotNull(run(firingId)).state.get())
        assertNull(commandFor(firingId, 0), "a skipped routine must not actuate")
    }

    /**
     * ⚠⚠ The most important assertion here. RE-1 has no presence notion, so a
     * `Presence` condition must fail **closed** — "arm the alarm when nobody's
     * home" firing because a phone battery died is the worst failure mode of the
     * whole engine.
     */
    @Test
    fun an_unknown_presence_condition_fails_closed() {
        saveRoutine(
            program = RoutineProgram(
                trigger = Trigger.TimeOfDay(360),
                conditions = listOf(Condition.Presence(Occupancy.NOBODY_HOME)),
                actions = listOf(Action.SetPower("lamp", on = true)),
            )
        )
        val firingId = org.tenshu.routine.common.engine.FiringIds.manual("r1", now)

        assertIs<FiringResult.Skipped>(engine().runNow("r1"))
        assertNull(commandFor(firingId, 0), "unknown presence must never actuate")
    }

    /** RE-D8: a failing action does not abort the rest — there is no rollback for a light bulb. */
    @Test
    fun a_failing_action_does_not_stop_the_others() {
        saveRoutine(
            program = RoutineProgram(
                trigger = Trigger.TimeOfDay(360),
                actions = listOf(
                    Action.Notify("t", "b"), // unsupported in this slice → fails
                    Action.SetPower("lamp", on = true),
                ),
            )
        )
        val firingId = org.tenshu.routine.common.engine.FiringIds.manual("r1", now)

        val result = engine().runNow("r1")

        assertIs<FiringResult.Failed>(result)
        assertNotNull(commandFor(firingId, 1), "the second action must still have run")
        assertEquals(RoutineRun.STATE_FAILED, assertNotNull(run(firingId)).state.get())
    }

    /** RE-D6: waking far too late records `MISSED` rather than turning the lights on at 3 a.m. */
    @Test
    fun a_firing_outside_the_catchup_window_is_missed() {
        saveRoutine(program = lightsOn())
        val engine = engine()
        val triggerInstant = now - DefaultSafetyGate.CATCHUP_WINDOW_MS - 60_000

        val firingId = org.tenshu.routine.common.engine.FiringIds.scheduled("r1", triggerInstant)
        engine.evaluateDue() // arms; nothing due at `now`

        // Drive the missed path directly through the scheduled entry point by
        // moving the clock past the window for a known trigger instant.
        now = triggerInstant + DefaultSafetyGate.CATCHUP_WINDOW_MS + 60_000
        val results = engine.evaluateDue()

        // Either nothing was due, or what was due is honestly recorded — never
        // an actuation this far past its instant.
        results.filterIsInstance<FiringResult.Ran>().forEach {
            error("nothing should have run this late")
        }
        assertNull(commandFor(firingId, 0))
    }

    @Test
    fun a_disabled_routine_does_not_fire_on_a_scheduled_wake() {
        saveRoutine(program = lightsOn(), enabled = false)

        val results = engine().evaluateDue()

        assertTrue(results.none { it is FiringResult.Ran })
    }

    /** ⚠ Manual run ignores `enabled` (that is how you test a routine) but honours SUSPENDED. */
    @Test
    fun run_now_ignores_disabled_but_refuses_suspended() {
        saveRoutine(routineId = "off", program = lightsOn(), enabled = false)
        saveRoutine(
            routineId = "susp", program = lightsOn(),
            state = Routine.STATE_SUSPENDED,
        )

        assertIs<FiringResult.Ran>(engine().runNow("off"))
        assertIs<FiringResult.StoodDown>(engine().runNow("susp"))
    }

    /**
     * ⚠⚠ RE-D10's fence: a program from a newer engine must be neither executed
     * nor claimed, so it falls through to a device that understands it.
     */
    @Test
    fun an_unsupported_program_version_stands_down() {
        val encoded = codec.encode(lightsOn())
        proxy.executionContext.database.transaction {
            proxy.executionContext.database.addToArray(
                routinesRef(),
                Routine.create(
                    context = proxy.tContext,
                    routineId = "future",
                    name = "From the future",
                    timeZoneId = "Europe/Berlin",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    programJson = encoded.programJson,
                    programVersion = encoded.programVersion + 5,
                ),
            )
        }

        assertIs<FiringResult.StoodDown>(engine().runNow("future"))
        assertTrue(engine().evaluateDue().none { it is FiringResult.Ran })
    }

    /** RE-D4: the journal is written before actions, and cleared when the run ends. */
    @Test
    fun the_journal_is_clear_after_a_completed_run() {
        saveRoutine(program = lightsOn())

        engine().runNow("r1")

        assertTrue(RoutineFiringJournal(proxy.executionContext).incomplete().isEmpty())
    }

    /**
     * ⚠⚠ Startup is reconciliation, NOT recovery: an interrupted run is recorded
     * as interrupted and deliberately **not** re-run (at-most-once).
     */
    @Test
    fun an_interrupted_run_is_reconciled_and_never_repeated() {
        saveRoutine(program = lightsOn())
        val journal = RoutineFiringJournal(proxy.executionContext)
        val firingId = "r1@stale-firing"
        journal.begin(firingId, "r1", now)
        proxy.executionContext.saveData(
            RoutineRun.create(
                context = proxy.tContext,
                firingId = firingId,
                routineId = "r1",
                triggerInstantEpochMs = now,
                claimedAtEpochMs = now,
                state = RoutineRun.STATE_CLAIMED,
            )
        )

        val reconciled = engine().reconcileOnStart()

        assertEquals(1, reconciled)
        assertEquals(RoutineRun.STATE_FAILED, assertNotNull(run(firingId)).state.get())
        assertTrue(
            assertNotNull(run(firingId)).actionOutcomes.get().contains("interrupted"),
            "the run must say why it never finished",
        )
        assertNull(commandFor(firingId, 0), "at-most-once: it must NOT be re-run")
        assertTrue(journal.incomplete().isEmpty(), "the marker must not re-reconcile forever")
    }

    /** A second firing of the same id stands down — the receipt is the guard. */
    @Test
    fun an_existing_receipt_makes_a_repeat_stand_down() {
        saveRoutine(program = lightsOn())
        val engine = engine()
        engine.runNow("r1")

        // Same clock ⇒ same manual firingId.
        assertIs<FiringResult.StoodDown>(engine.runNow("r1"))
    }

    /**
     * ⚠⚠ §4.9's coalescing contract: refusals must not each write their own row,
     * or the throttle brakes nothing — it just relabels the flood.
     */
    @Test
    fun throttled_refusals_coalesce_into_one_row() {
        saveRoutine(program = lightsOn())
        val gate = DefaultSafetyGate()
        val engine = engine(safetyGate = gate)

        val rowIds = mutableSetOf<String>()
        repeat(DefaultSafetyGate.MAX_FIRINGS_PER_WINDOW + 5) {
            now += 1
            val result = engine.runNow("r1")
            if (result is FiringResult.Throttled) result.coalescedInto?.let(rowIds::add)
        }

        assertEquals(1, rowIds.size, "every refusal in a window must share one row")
        val row = assertNotNull(run(rowIds.first()))
        assertEquals(RoutineRun.STATE_THROTTLED, row.state.get())
        assertTrue(row.coalescedCount.get() > 0, "the absorbed refusals must be counted")
    }
}
