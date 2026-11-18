package org.tenshu.mia.plan

import org.tenshu.calendar.SaveEvent
import org.tenshu.home.device.IssueDeviceCommand
import org.tenshu.mia.MiaSource
import org.tenshu.routine.RunRoutineNow

/**
 * Each Mia command's **frame**: the write op it executes, and who fills each of that op's params
 * (`mia-query-layer.md` §25.2, CL-D3).
 *
 * ⭐⭐ **The frame is read twice.** The planner reads it to know which params the *utterance* owes,
 * which is what a clarification can ask for. The proxy reads it to lower a confirmed command to its
 * op call, one mechanical step for every command (§25.3). A new write intent costs an entry here,
 * not a branch in either place.
 *
 * ⚠⚠ **Keyed by param id because the schema has no semantic param types yet.** Every param of
 * these ops is declared `string(...)`, so nothing in the schema says that `START_LOCAL` is a local
 * date-time. [Param.slot] says it here instead, until CL-Q4 moves the type into the DSL.
 * `MiaFramesTest` pins every id against the op's own schema, so this table cannot drift silently.
 */
object MiaFrames {

    /** Who supplies a param's value. */
    enum class Fill {
        /** What the user said. The only kind a clarification asks for. */
        UTTERANCE,

        /** A service default, resolved proxy-side and shown on the card (MQ-D7). */
        DEFAULT,

        /** Generated at dispatch (`eventId`, `commandId`). Never shown, never asked for. */
        MINTED,

        /** Settled by the command itself (`allDay = false`, `verb = setPower`). */
        FIXED,
    }

    /** How a param is asked for, parsed and suggested (§25.4, CL-D2). */
    enum class Slot { LOCAL_DATE_TIME, FREE_TEXT, ENTITY_REFERENCE }

    data class Param(val id: String, val fill: Fill, val slot: Slot? = null)

    data class Frame(val op: String, val source: MiaSource, val params: List<Param>) {
        fun param(id: String): Param = params.first { it.id == id }

        /** The params the utterance owes, in the order a clarification asks for them. */
        val owed: List<Param> get() = params.filter { it.fill == Fill.UTTERANCE }
    }

    val SAVE_EVENT = Frame(
        op = SaveEvent.ID,
        source = MiaSource.CALENDAR,
        params = listOf(
            Param(SaveEvent.Params.EVENT_ID, Fill.MINTED),
            // ⚠ Title before start: one param at a time, in frame order (CL-D1).
            Param(SaveEvent.Params.TITLE, Fill.UTTERANCE, Slot.FREE_TEXT),
            Param(SaveEvent.Params.START_LOCAL, Fill.UTTERANCE, Slot.LOCAL_DATE_TIME),
            Param(SaveEvent.Params.END_LOCAL, Fill.DEFAULT),
            Param(SaveEvent.Params.CALENDAR_ID, Fill.DEFAULT),
            Param(SaveEvent.Params.TIME_ZONE_ID, Fill.DEFAULT),
            Param(SaveEvent.Params.ALL_DAY, Fill.FIXED),
        ),
    )

    val ISSUE_DEVICE_COMMAND = Frame(
        op = IssueDeviceCommand.ID,
        source = MiaSource.DEVICES,
        params = listOf(
            Param(IssueDeviceCommand.Params.COMMAND_ID, Fill.MINTED),
            Param(IssueDeviceCommand.Params.TARGET_DEVICE_ID, Fill.UTTERANCE, Slot.ENTITY_REFERENCE),
            Param(IssueDeviceCommand.Params.VERB, Fill.FIXED),
            Param(IssueDeviceCommand.Params.ARG_VALUE, Fill.UTTERANCE),
        ),
    )

    val RUN_ROUTINE_NOW = Frame(
        op = RunRoutineNow.ID,
        source = MiaSource.ROUTINES,
        params = listOf(
            Param(RunRoutineNow.Params.ROUTINE_ID, Fill.UTTERANCE, Slot.ENTITY_REFERENCE),
        ),
    )

    val ALL: List<Frame> = listOf(SAVE_EVENT, ISSUE_DEVICE_COMMAND, RUN_ROUTINE_NOW)

    fun of(op: String): Frame = ALL.first { it.op == op }
}
