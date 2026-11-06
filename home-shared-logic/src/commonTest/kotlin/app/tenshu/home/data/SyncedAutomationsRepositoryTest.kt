package app.tenshu.home.data

import app.tenshu.home.model.Action
import app.tenshu.home.model.Automation
import app.tenshu.home.model.Condition
import app.tenshu.home.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.routine.Routine
import org.tenshu.routine.common.program.Action as ProgramAction
import org.tenshu.routine.common.program.JsonRoutineProgramCodec
import org.tenshu.routine.common.program.RoutineProgram
import org.tenshu.routine.common.program.Trigger as ProgramTrigger

class SyncedAutomationsRepositoryTest {

    private val codec = JsonRoutineProgramCodec()

    private class FakeSource : RoutineSource {
        val rows = MutableStateFlow<List<SyncedRoutineInfo>>(emptyList())
        override fun routines(): Flow<List<SyncedRoutineInfo>> = rows
    }

    private class FakeControl : RoutineControlSource {
        var saved: Triple<String, String, Long>? = null
        var savedTimeZone: String? = null
        var enabledCalls = mutableListOf<Pair<String, Boolean>>()
        var deleted: String? = null
        var ranNow: String? = null
        var failWith: Throwable? = null

        override suspend fun save(
            routineId: String, name: String, timeZoneId: String,
            programJson: String, programVersion: Long,
            homeLatE5: Long, homeLonE5: Long,
        ) {
            failWith?.let { throw it }
            saved = Triple(routineId, programJson, programVersion)
            savedTimeZone = timeZoneId
        }

        override suspend fun delete(routineId: String) { deleted = routineId }
        override suspend fun setEnabled(routineId: String, enabled: Boolean) {
            enabledCalls += routineId to enabled
        }
        override suspend fun runNow(routineId: String) {
            failWith?.let { throw it }
            ranNow = routineId
        }
    }

    private class FakeMock(private val list: List<Automation>) : AutomationsRepository {
        override fun automations(): Flow<List<Automation>> = MutableStateFlow(list)
        override suspend fun addAutomation(automation: Automation) = automation.id
        override suspend fun updateAutomation(automation: Automation) = Unit
        override suspend fun removeAutomation(automationId: String) = Unit
        override suspend fun setEnabled(automationId: String, enabled: Boolean) = Unit
    }

    private fun mockAutomation(id: String = "mock-1") = Automation(
        id = id, name = "Mock", enabled = true,
        trigger = Trigger.Schedule(600), conditions = emptyList(),
        actions = listOf(Action.DeviceCommand("lamp", true)), lastRunAt = null,
    )

    private fun repo(
        source: RoutineSource,
        control: RoutineControlSource = FakeControl(),
    ) = SyncedAutomationsRepository(
        source = source, control = control,
        timeZoneId = { "Europe/Berlin" },
        homeLatE5 = { 5_252_000 }, homeLonE5 = { 1_340_500 },
        newId = { "generated-id" },
    )

    private fun syncedRow(
        routineId: String = "r1",
        name: String = "Real routine",
        enabled: Boolean = true,
        lastRunAtEpochMs: Long? = null,
        program: RoutineProgram = RoutineProgram(
            trigger = ProgramTrigger.TimeOfDay(22 * 60),
            actions = listOf(ProgramAction.SetPower("lamp", true)),
        ),
    ): SyncedRoutineInfo {
        val encoded = codec.encode(program)
        return SyncedRoutineInfo(
            routineId = routineId, name = name, enabled = enabled,
            state = Routine.STATE_ACTIVE, suspendedReason = "",
            programJson = encoded.programJson, programVersion = encoded.programVersion,
            lastRunAtEpochMs = lastRunAtEpochMs,
        )
    }

    // ------------------------------------------------------------ reads

    /**
     * ⚠⚠ **Empty is empty**, reversing what this test used to assert ("no routines for any reason
     * ⇒ the mock passes through untouched"). *No connection*, *no wiring* and *no routines* are
     * three different facts, and substituting seeded demo rows for all three made a realm with no
     * automations indistinguishable from one the app could not reach — the screen above cannot
     * tell a fallback from the truth. RE-1's "swaps from mock to real" finishing.
     */
    @Test
    fun an_empty_source_is_empty_and_does_not_invent_rows() = runTest {
        val result = repo(FakeSource()).automations().first()

        assertTrue(result.isEmpty(), "an empty realm must read as empty, not as demo content")
    }

    /** A realm with routines replaces the mock wholesale — no merged half-state. */
    @Test
    fun real_routines_replace_the_mock_entirely() = runTest {
        val source = FakeSource().apply { rows.value = listOf(syncedRow()) }

        val result = repo(source, FakeControl()).automations().first()

        assertEquals(1, result.size)
        assertEquals("r1", result.single().id)
        assertTrue(result.none { it.id == "mock-1" }, "the mock must not be merged in")
    }

    /** ⭐ `lastRunAt` is DERIVED from the run receipts, never a field on the routine. */
    @Test
    fun last_run_at_comes_through_from_the_receipts() = runTest {
        val source = FakeSource().apply {
            rows.value = listOf(syncedRow(lastRunAtEpochMs = 1_782_000_000_000))
        }

        val automation = repo(source, FakeControl()).automations().first().single()

        assertEquals(1_782_000_000_000, assertNotNull(automation.lastRunAt).toEpochMilliseconds())
    }

    @Test
    fun a_routine_that_never_ran_has_no_last_run_at() = runTest {
        val source = FakeSource().apply { rows.value = listOf(syncedRow()) }

        assertNull(repo(source, FakeControl()).automations().first().single().lastRunAt)
    }

    /**
     * ⚠ A routine this build cannot parse is dropped from the list rather than
     * rendered — showing a routine we cannot describe invites an edit that would
     * rewrite it into something the author did not mean.
     */
    @Test
    fun an_unparseable_routine_is_dropped_not_rendered() = runTest {
        val source = FakeSource().apply {
            rows.value = listOf(
                syncedRow(routineId = "good"),
                syncedRow(routineId = "future").copy(programVersion = 999),
            )
        }

        val result = repo(source, FakeControl()).automations().first()

        assertEquals(listOf("good"), result.map { it.id })
    }

    // ------------------------------------------------------------ writes

    @Test
    fun adding_an_automation_writes_a_program_and_returns_its_id() = runTest {
        val control = FakeControl()

        val id = repo(FakeSource(), control).addAutomation(mockAutomation(id = ""))

        assertEquals("generated-id", id)
        val saved = assertNotNull(control.saved)
        assertEquals("generated-id", saved.first)
        assertTrue(saved.second.contains("timeOfDay"), "the program must be encoded")
    }

    /**
     * ⚠⚠ The routine carries the **authoring device's** timezone (RE-D3), not
     * the executor's — a laptop that later travels must not shift "22:30".
     */
    @Test
    fun the_authoring_timezone_rides_on_the_routine() = runTest {
        val control = FakeControl()

        repo(FakeSource(), control).addAutomation(mockAutomation())

        assertEquals("Europe/Berlin", control.savedTimeZone)
    }

    /**
     * ⚠⚠ Reads degrade to the mock; writes do NOT. Silently writing to an
     * in-memory mock would tell the user their automation exists when nothing
     * will ever run it.
     */
    @Test
    fun a_failing_write_propagates_rather_than_falling_back() = runTest {
        val control = FakeControl().apply { failWith = IllegalStateException("proxy unreachable") }

        assertFailsWith<IllegalStateException> {
            repo(FakeSource(), control).addAutomation(mockAutomation())
        }
    }

    /** ⚠ The gate is enforced at the write, not only in the editor. */
    @Test
    fun an_unsupported_automation_is_refused_before_any_write() = runTest {
        val control = FakeControl()
        val notify = mockAutomation().copy(actions = listOf(Action.Notify("hi")))

        val failure = assertFailsWith<IllegalArgumentException> {
            repo(FakeSource(), control).addAutomation(notify)
        }

        assertTrue(failure.message!!.isNotBlank())
        assertNull(control.saved, "nothing may be written for a gated automation")
    }

    @Test
    fun a_disabled_automation_is_saved_then_disabled() = runTest {
        val control = FakeControl()

        repo(FakeSource(), control).addAutomation(mockAutomation().copy(enabled = false))

        assertNotNull(control.saved)
        assertEquals(listOf("mock-1" to false), control.enabledCalls)
    }

    @Test
    fun remove_and_set_enabled_reach_the_control_surface() = runTest {
        val control = FakeControl()
        val repository = repo(FakeSource(), control)

        repository.removeAutomation("r1")
        repository.setEnabled("r1", false)

        assertEquals("r1", control.deleted)
        assertEquals(listOf("r1" to false), control.enabledCalls)
    }

    @Test
    fun run_now_reaches_the_control_surface() = runTest {
        val control = FakeControl()

        repo(FakeSource(), control).runNow("r1")

        assertEquals("r1", control.ranNow)
    }

    /** A stand-down is a user-visible failure — they pressed a button. */
    @Test
    fun a_refused_run_now_propagates() = runTest {
        val control = FakeControl().apply { failWith = IllegalStateException("suspended") }

        assertFailsWith<IllegalStateException> { repo(FakeSource(), control).runNow("r1") }
    }

    @Test
    fun a_time_window_condition_survives_the_round_trip() = runTest {
        val control = FakeControl()
        val withWindow = mockAutomation().copy(
            conditions = listOf(Condition.TimeWindow(18 * 60, 5 * 60)),
        )

        repo(FakeSource(), control).addAutomation(withWindow)

        assertTrue(assertNotNull(control.saved).second.contains("timeWindow"))
    }
}
