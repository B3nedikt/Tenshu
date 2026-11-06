package app.tenshu.home.data

import app.tenshu.home.model.Action
import app.tenshu.home.model.Automation
import app.tenshu.home.model.Condition
import app.tenshu.home.model.SunEvent
import app.tenshu.home.model.Trigger
import org.tenshu.routine.common.program.Action as ProgramAction
import org.tenshu.routine.common.program.Condition as ProgramCondition
import org.tenshu.routine.common.program.MatchOp
import org.tenshu.routine.common.program.Occupancy
import org.tenshu.routine.common.program.RoutineProgram
import org.tenshu.routine.common.program.StateMatch
import org.tenshu.routine.common.program.SunEvent as ProgramSunEvent
import org.tenshu.routine.common.program.Trigger as ProgramTrigger

/**
 * Translates between Home's UI [Automation] model (M2) and the engine's
 * `RoutineProgram` vocabulary (`routines-engine.md` §4.1).
 *
 * ⭐ Two vocabularies exist deliberately, and this is the seam between them: the
 * UI model is shaped for an editor (a `Presence` toggle, a `DeviceEventKind`
 * enum) while the program is shaped for execution and the wire. Collapsing them
 * would drag the editor's convenience types onto the sync path, which RE-D10
 * spent three arguments avoiding.
 *
 * ⚠⚠ **Not every UI construct is executable yet, and this refuses rather than
 * silently dropping.** M2 shipped an editor over a mock, so its vocabulary is
 * *wider* than the engine's: device-event triggers are RE-2, geofence + presence
 * are RE-4, voice phrases are RE-5, and `Notify` needs a proxy-authoritative
 * notification type. [supportUnsupportedReason] is what the editor must call to
 * grey those out — the HD-2 rule that Home never offers a flow it cannot honour.
 */
object AutomationProgramMapper {

    /**
     * Why this automation cannot run yet, or `null` when it can.
     *
     * The editor calls this to gate; [toProgram] calls it to refuse. One source
     * of truth, so a gap in the UI gate can never become a silently-dead routine.
     */
    fun unsupportedReason(automation: Automation): String? {
        when (automation.trigger) {
            is Trigger.Schedule, is Trigger.Sun -> Unit
            is Trigger.DeviceEvent -> return "Device triggers need a Tenshu Hub"
            is Trigger.Geofence -> return "Location triggers are coming soon"
            is Trigger.VoicePhrase -> return "Voice triggers arrive with Mia's voice support"
        }
        automation.conditions.forEach { condition ->
            when (condition) {
                is Condition.TimeWindow, is Condition.DeviceIs, is Condition.Presence -> Unit
            }
        }
        if (automation.actions.isEmpty()) return "Add at least one action"
        automation.actions.forEach { action ->
            when (action) {
                is Action.DeviceCommand, is Action.RunAutomation -> Unit
                is Action.Notify -> return "Notification actions are not available yet"
            }
        }
        return null
    }

    /** `null` when [unsupportedReason] would return a reason. */
    fun toProgram(automation: Automation): RoutineProgram? {
        if (unsupportedReason(automation) != null) return null
        return RoutineProgram(
            trigger = when (val trigger = automation.trigger) {
                is Trigger.Schedule -> ProgramTrigger.TimeOfDay(
                    minuteOfDay = trigger.minutesOfDay,
                    // ⚠ The M2 editor has no day-of-week filter, and an empty set
                    // means "every day" — so this is faithful, not a placeholder.
                    days = emptySet(),
                )

                is Trigger.Sun -> ProgramTrigger.Sun(
                    event = when (trigger.event) {
                        SunEvent.Sunrise -> ProgramSunEvent.SUNRISE
                        SunEvent.Sunset -> ProgramSunEvent.SUNSET
                    },
                    offsetMinutes = trigger.offsetMinutes,
                )

                else -> return null
            },
            conditions = automation.conditions.mapNotNull { condition ->
                when (condition) {
                    is Condition.TimeWindow -> ProgramCondition.TimeWindow(
                        fromMinuteOfDay = condition.afterMinutes,
                        toMinuteOfDay = condition.beforeMinutes,
                    )

                    is Condition.DeviceIs -> ProgramCondition.DeviceStateIs(
                        deviceId = condition.deviceId,
                        match = StateMatch(
                            field = FIELD_POWER,
                            op = MatchOp.EQ,
                            value = if (condition.on) POWER_ON else POWER_OFF,
                        ),
                    )

                    // RE-4: presence is real. ⚠ It means "none of the devices you
                    // have given location to is home" — a device with location off
                    // never contributes, and the copy must say so rather than
                    // implying the app knows where people are.
                    is Condition.Presence -> ProgramCondition.Presence(
                        expect = if (condition.nobodyHome) {
                            Occupancy.NOBODY_HOME
                        } else {
                            Occupancy.SOMEBODY_HOME
                        },
                    )
                }
            },
            actions = automation.actions.mapNotNull { action ->
                when (action) {
                    is Action.DeviceCommand -> ProgramAction.SetPower(
                        deviceId = action.deviceId,
                        on = action.turnOn,
                    )

                    is Action.RunAutomation -> ProgramAction.RunRoutine(action.automationId)
                    is Action.Notify -> null
                }
            },
        )
    }

    /**
     * The reverse trip, for rendering a synced routine in the editor.
     *
     * ⚠ Returns `null` for a program this UI cannot represent — a routine
     * authored by a newer Home. The list should show it as read-only rather
     * than offering an edit that would silently rewrite it.
     */
    fun toAutomation(
        id: String,
        name: String,
        enabled: Boolean,
        program: RoutineProgram,
        lastRunAt: kotlin.time.Instant?,
    ): Automation? {
        val trigger = when (val t = program.trigger) {
            is ProgramTrigger.TimeOfDay -> Trigger.Schedule(t.minuteOfDay)
            is ProgramTrigger.Sun -> Trigger.Sun(
                event = when (t.event) {
                    ProgramSunEvent.SUNRISE -> SunEvent.Sunrise
                    ProgramSunEvent.SUNSET -> SunEvent.Sunset
                },
                offsetMinutes = t.offsetMinutes,
            )

            else -> return null
        }

        val conditions = program.conditions.map { condition ->
            when (condition) {
                is ProgramCondition.TimeWindow ->
                    Condition.TimeWindow(condition.fromMinuteOfDay, condition.toMinuteOfDay)

                is ProgramCondition.DeviceStateIs -> Condition.DeviceIs(
                    deviceId = condition.deviceId,
                    on = condition.match.value == POWER_ON,
                )

                is ProgramCondition.Presence ->
                    Condition.Presence(nobodyHome = condition.expect == Occupancy.NOBODY_HOME)
            }
        }

        val actions = program.actions.map { action ->
            when (action) {
                is ProgramAction.SetPower -> Action.DeviceCommand(action.deviceId, action.on)
                is ProgramAction.RunRoutine -> Action.RunAutomation(action.routineId)
                is ProgramAction.Notify -> Action.Notify(action.body.ifBlank { action.title })
                // A level the M2 editor cannot express — render as "on" rather
                // than dropping the action, so the list stays truthful about
                // there being an action here.
                is ProgramAction.SetLevel -> Action.DeviceCommand(action.deviceId, action.percent > 0)
            }
        }

        return Automation(
            id = id,
            name = name,
            enabled = enabled,
            trigger = trigger,
            conditions = conditions,
            actions = actions,
            lastRunAt = lastRunAt,
        )
    }

    /** The `DeviceState` field a `DeviceIs` condition reads (the engine's snapshot key). */
    private const val FIELD_POWER = "power"
    private const val POWER_ON = "on"
    private const val POWER_OFF = "off"
}
