package org.tenshu.routine.proxy

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.DataId
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.HomeDeviceService
import org.tenshu.home.device.common.homeDeviceServiceCommonModule
import org.tenshu.routine.DeleteRoutine
import org.tenshu.routine.EvaluateDueRoutines
import org.tenshu.routine.Routine
import org.tenshu.routine.RoutineRun
import org.tenshu.routine.RoutineService
import org.tenshu.routine.RunRoutineNow
import org.tenshu.routine.SaveRoutine
import org.tenshu.routine.SetRoutineEnabled
import org.tenshu.routine.common.program.Action
import org.tenshu.routine.common.program.JsonRoutineProgramCodec
import org.tenshu.routine.common.program.RoutineProgram
import org.tenshu.routine.common.program.Trigger
import org.tenshu.routine.common.routineServiceCommonModule
import org.tenshu.sync.Schedule

/**
 * [RoutineExecution.NEVER] — the iOS wiring (`routines-engine.md` RE-D13): the rows stay
 * editable, and nothing evaluates, fires, journals or arms.
 *
 * ⚠ Every refusal here has an [RoutineExecution.EXECUTES] twin over the SAME fixture. Without
 * it "no run row was written" would also pass on a routine that could never have fired.
 */
class RoutineExecutionTest {

    private lateinit var proxy: TestProxy
    private var now = 1_782_014_400_000 // 06:00 Europe/Berlin — the fixture routine is due.
    private val codec = JsonRoutineProgramCodec()
    private val requestedOps = mutableListOf<String>()

    @BeforeTest
    fun setup() {
        proxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(routineServiceCommonModule)
            it.schemaRegistry.registerConfig(homeDeviceServiceCommonModule)
        }
        proxy.executionContext.initRoutineService()
    }

    private fun factory(execution: RoutineExecution) = OperationImplementationFactory { id ->
        requestedOps += id
        routineServiceProxyImplementationFactory(
            id = id,
            nowEpochMs = { now },
            authorDeviceId = { "this-device" },
            execution = execution,
            tombstoneEmitter = null,
        )
    }

    private fun call(execution: RoutineExecution, opId: String, params: Map<String, Data> = emptyMap()) {
        val factory = factory(execution)
        val implementation = assertIs<SyncOperationImplementation>(factory(opId))
        implementation.execute<Data?>(
            executionContext = proxy.executionContext,
            operationContext = null,
            implementationFactory = factory,
            caller = null,
            params = params,
        )
    }

    private fun save(execution: RoutineExecution, routineId: String = "r1") {
        val encoded = codec.encode(
            RoutineProgram(
                trigger = Trigger.TimeOfDay(minuteOfDay = 6 * 60),
                actions = listOf(Action.SetPower("lamp", on = true)),
            )
        )
        val context = proxy.tContext
        call(
            execution, SaveRoutine.ID,
            mapOf(
                SaveRoutine.Params.ROUTINE_ID to routineId.toTString(context),
                SaveRoutine.Params.NAME to "Morning light".toTString(context),
                SaveRoutine.Params.TIME_ZONE_ID to "Europe/Berlin".toTString(context),
                SaveRoutine.Params.PROGRAM_JSON to encoded.programJson.toTString(context),
                SaveRoutine.Params.PROGRAM_VERSION to encoded.programVersion.toTInteger(context),
                SaveRoutine.Params.HOME_LAT_E5 to 5_252_000L.toTInteger(context),
                SaveRoutine.Params.HOME_LON_E5 to 1_340_500L.toTInteger(context),
            ),
        )
    }

    private fun runNow(execution: RoutineExecution) = call(
        execution, RunRoutineNow.ID,
        mapOf(RunRoutineNow.Params.ROUTINE_ID to "r1".toTString(proxy.tContext)),
    )

    private fun routines(): List<Routine> =
        proxy.executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = proxy.tContext, type = TList.ID,
                    relationId = RoutineService.Relations.ROUTINES,
                ),
                isEntity = false, elementSchemaId = Routine.ID,
            )
        )?.toList().orEmpty().filterIsInstance<Routine>()

    private fun runs(): List<RoutineRun> =
        proxy.executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = proxy.tContext, type = TList.ID,
                    relationId = RoutineService.Relations.ROUTINE_RUNS,
                ),
                isEntity = false, elementSchemaId = RoutineRun.ID,
            )
        )?.toList().orEmpty().filterIsInstance<RoutineRun>()

    private fun commands(): List<DeviceCommand> =
        proxy.executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = proxy.tContext, type = TList.ID,
                    relationId = HomeDeviceService.Relations.DEVICE_COMMANDS,
                ),
                isEntity = false, elementSchemaId = DeviceCommand.ID,
            )
        )?.toList().orEmpty().filterIsInstance<DeviceCommand>()

    private fun assertRefused(block: () -> Unit) {
        val refusal = assertFailsWith<TenshuException> { block() }
        assertEquals(
            "Routines do not run on this device",
            assertIs<GenericError>(refusal.error).message.get(),
        )
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun a_never_executing_proxy_still_saves_toggles_and_deletes_routines() {
        save(RoutineExecution.NEVER)
        assertEquals(listOf("r1"), routines().map { it.routineId.get() })
        assertEquals("this-device", routines().single().authorDeviceId.get())

        val context = proxy.tContext
        call(
            RoutineExecution.NEVER, SetRoutineEnabled.ID,
            mapOf(
                SetRoutineEnabled.Params.ROUTINE_ID to "r1".toTString(context),
                SetRoutineEnabled.Params.ENABLED to false.toTBoolean(context),
            ),
        )
        assertEquals(false, routines().single().enabled.get())

        call(
            RoutineExecution.NEVER, DeleteRoutine.ID,
            mapOf(DeleteRoutine.Params.ROUTINE_ID to "r1".toTString(context)),
        )
        assertTrue(routines().isEmpty())
    }

    @Test
    fun run_now_is_refused_with_a_typed_error_and_writes_nothing() {
        save(RoutineExecution.NEVER)

        assertRefused { runNow(RoutineExecution.NEVER) }

        assertTrue(runs().isEmpty(), "a refused run must leave no receipt")
        assertTrue(commands().isEmpty(), "a refused run must issue no command")
    }

    @Test
    fun run_now_fires_the_same_fixture_when_the_proxy_executes() {
        save(RoutineExecution.EXECUTES)

        runNow(RoutineExecution.EXECUTES)

        assertEquals(RoutineRun.STATE_RAN, runs().single().state.get())
        assertEquals("lamp", commands().single().targetDeviceId.get())
    }

    @Test
    fun the_scheduled_wake_is_refused_fires_nothing_and_arms_nothing() {
        save(RoutineExecution.NEVER)
        requestedOps.clear()

        assertRefused { call(RoutineExecution.NEVER, EvaluateDueRoutines.ID) }

        assertTrue(runs().isEmpty(), "a due routine must not fire on a never-executing proxy")
        assertTrue(commands().isEmpty())
        // ⚠ The armer reaches the scheduler through this factory, so an engine that was built
        // and re-armed would show up here even when it fired nothing.
        assertTrue(Schedule.ID !in requestedOps, "nothing may arm a next wake: $requestedOps")
    }

    @Test
    fun the_scheduled_wake_fires_and_arms_the_same_fixture_when_the_proxy_executes() {
        save(RoutineExecution.EXECUTES)
        requestedOps.clear()

        call(RoutineExecution.EXECUTES, EvaluateDueRoutines.ID)

        assertEquals(RoutineRun.STATE_RAN, runs().single().state.get())
        assertEquals("lamp", commands().single().targetDeviceId.get())
        assertTrue(Schedule.ID in requestedOps, "the engine must re-arm: $requestedOps")
    }
}
