package app.tenshu.home.data

import app.tenshu.home.model.Automation
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.tenshu.routine.common.program.JsonRoutineProgramCodec

/**
 * The real [AutomationsRepository], over `:routine-service` (`routines-engine.md`
 * RE-1).
 *
 * ⭐ **Fallback is the mock, honestly** — the [ClientDeviceSource] contract:
 * no proxy connection, an older Control without the routine wiring, or a realm
 * with no routines all arrive as an empty list, and an empty list passes the
 * mock through untouched rather than showing a user with real automations an
 * empty screen. A realm that *has* routines replaces the mock wholesale; there
 * is no merged half-state.
 *
 * ⚠⚠ **Writes never fall back.** A save that cannot reach the proxy must fail
 * loudly — silently writing to an in-memory mock would tell the user their
 * automation exists when nothing will ever run it. That asymmetry (reads
 * degrade, writes throw) is the same one `DeviceControlSource` draws.
 */
class SyncedAutomationsRepository(
    private val source: RoutineSource,
    private val control: RoutineControlSource,
    /**
     * The zone a newly authored routine is anchored in.
     *
     * ⚠⚠ This is the routine's own timezone, not a display concern (RE-D3). It
     * is captured **at authoring time** on the authoring device, because a
     * laptop that later travels must not shift "22:30"; the alternative — the
     * executing device's zone — is the single most likely way to build a subtly
     * broken engine.
     */
    private val timeZoneId: () -> String,
    /** Home coordinates for sun triggers; absent means sun routines cannot be authored. */
    private val homeLatE5: () -> Long = { NO_COORDINATE },
    private val homeLonE5: () -> Long = { NO_COORDINATE },
    private val newId: () -> String,
) : AutomationsRepository {

    private val codec = JsonRoutineProgramCodec()

    /**
     * ⚠⚠ **No mock fallback, as of 2026-08-26.** This used to read
     * `if (synced.isEmpty()) mocked else synced` — "empty means nothing real to show for every
     * reason at once, so the mock passes through untouched". That reasoning is exactly backwards:
     * *no connection*, *no wiring* and *no routines* are three different facts, and substituting
     * seeded demo rows for all three made a realm with no automations indistinguishable from one
     * the app could not reach. The screen above cannot tell a fallback from the truth.
     *
     * Empty is now empty. RE-1 always said this seam "swaps from mock to real"; this is that swap
     * finishing. ⚠ Demo content, if it returns, belongs behind the proxy as seeded rows a real read
     * returns — never a client-side repository that substitutes itself.
     */
    override fun automations(): Flow<List<Automation>> =
        source.routines().map { synced -> synced.mapNotNull { it.toAutomation() } }

    override suspend fun addAutomation(automation: Automation): String {
        val id = automation.id.ifBlank { newId() }
        save(automation.copy(id = id))
        return id
    }

    override suspend fun updateAutomation(automation: Automation) = save(automation)

    override suspend fun removeAutomation(automationId: String) = control.delete(automationId)

    override suspend fun setEnabled(automationId: String, enabled: Boolean) =
        control.setEnabled(automationId, enabled)

    /**
     * The manual gesture. Not on [AutomationsRepository] because the mock cannot
     * honour it — a caller holding only the seam should not be able to ask.
     */
    suspend fun runNow(automationId: String) = control.runNow(automationId)

    /**
     * ⚠ Refuses before writing, with the same reason the editor should already
     * have shown. One source of truth ([AutomationProgramMapper.unsupportedReason])
     * means a gap in the UI gate surfaces as a visible error rather than a
     * routine that silently never runs.
     */
    private suspend fun save(automation: Automation) {
        AutomationProgramMapper.unsupportedReason(automation)?.let { reason ->
            throw IllegalArgumentException(reason)
        }
        val program = AutomationProgramMapper.toProgram(automation)
            ?: throw IllegalArgumentException("This automation cannot run yet")
        val encoded = codec.encode(program)

        control.save(
            routineId = automation.id,
            name = automation.name,
            timeZoneId = timeZoneId(),
            programJson = encoded.programJson,
            programVersion = encoded.programVersion,
            homeLatE5 = homeLatE5(),
            homeLonE5 = homeLonE5(),
        )
        if (!automation.enabled) control.setEnabled(automation.id, false)
    }

    private fun SyncedRoutineInfo.toAutomation(): Automation? {
        val parse = codec.parse(programJson, programVersion)
        val program = (parse as? org.tenshu.routine.common.program.ProgramParse.Parsed)?.program
            // ⚠ A routine this build cannot parse — authored by a newer Home, or
            // malformed. Dropping it from the list is deliberate: rendering a
            // routine we cannot describe invites an edit that would rewrite it
            // into something the author did not mean.
            ?: return null

        return AutomationProgramMapper.toAutomation(
            id = routineId,
            name = name,
            enabled = enabled,
            program = program,
            lastRunAt = lastRunAtEpochMs?.let { Instant.fromEpochMilliseconds(it) },
        )
    }

    companion object {
        const val NO_COORDINATE = -1_000_000_000L
    }
}
