package org.tenshu.mia.proxy

import org.tenshu.calendar.SaveEvent
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.IssueDeviceCommand
import org.tenshu.mia.plan.QueryPlan
import org.tenshu.routine.RunRoutineNow

/**
 * The actions Mia has resolved and shown, waiting for a tap (`mia-query-layer.md` MQ-D7).
 *
 * ⚠⚠ **This is what makes the confirmation token a narrow capability.** `ConfirmMiaAction`
 * takes only a token, so it cannot name a device, a verb or a value — it can only run an
 * action the proxy already resolved *and displayed*. Had the client passed the resolved
 * fields back instead, the op would have become a second, worse `IssueDeviceCommand` and the
 * "confirm what will actually happen" property would have been a UI convention rather than a
 * structural one.
 *
 * ⚠ **Single-use.** A token is consumed by the first [take]; re-tapping a stale confirmation
 * does nothing. `DeviceCommand` rows are idempotent by `commandId` so a replay could not
 * double-actuate anyway, but "already done" is a clearer answer than silently repeating.
 *
 * ⚠ **And it expires.** A confirmation left on screen must not fire an hour later. That is a
 * *second* bound on top of `DeviceCommand.STALE_WINDOW_MS` (2 min), which stops a
 * backlog-replayed command from actuating late; this one stops a stale *intent* from being
 * issued at all.
 *
 * ⚠ In-memory, and therefore lost when the proxy restarts — which is correct rather than a
 * gap: a pending intent is not durable state, and the honest answer to a tap whose action has
 * gone is "ask me again", not a resurrected command from before the restart.
 */
class MiaActionRegistry internal constructor(
    private val nowEpochMs: () -> Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {

    private data class Pending(val action: PendingMiaAction, val expiresAtMs: Long)

    private val pending = mutableMapOf<String, Pending>()

    internal fun put(token: String, action: PendingMiaAction) {
        // Opportunistic sweep: the map is tiny and only grows when somebody asks for an
        // action and never taps, so a dedicated timer would be more machinery than the
        // problem deserves.
        val now = nowEpochMs()
        pending.entries.removeAll { it.value.expiresAtMs <= now }
        pending[token] = Pending(action, now + ttlMs)
    }

    /**
     * How many confirmations are waiting, expired ones included until the next [put] sweeps them.
     *
     * ⚠ For tests: "no token was minted" is only assertable against the registry itself, because
     * an answer with no `action` proves the cell was not *shown*, not that nothing was *resolved*.
     */
    internal val pendingCount: Int get() = pending.size

    /** The action for [token], consuming it; `null` when unknown, already used or expired. */
    internal fun take(token: String): PendingMiaAction? {
        val entry = pending.remove(token) ?: return null
        return entry.action.takeIf { entry.expiresAtMs > nowEpochMs() }
    }

    private data class Asked(val plan: QueryPlan.Clarify, val expiresAtMs: Long)

    private val asked = mutableMapOf<String, Asked>()

    /**
     * Holds a clarification Mia asked (`mia-query-layer.md` §25, CL-D4), under the same rules as an
     * action: single-use, expiring, in memory.
     *
     * ⚠ **A new clarification replaces every older one.** The proxy has no notion of a
     * conversation, so "one open clarification per conversation" is enforced as one per proxy: a
     * stale chip scrolled back to can then never fill a plan the user has moved past. Mia's two
     * surfaces (the app and Home's embed) share this proxy, so a question asked in one closes the
     * other's; the reply then gets the honest "ask me again".
     */
    internal fun putClarification(token: String, plan: QueryPlan.Clarify) {
        asked.clear()
        asked[token] = Asked(plan, nowEpochMs() + ttlMs)
    }

    /** The clarification for [token], consuming it; `null` when unknown, already answered or expired. */
    internal fun takeClarification(token: String): QueryPlan.Clarify? {
        val entry = asked.remove(token) ?: return null
        return entry.plan.takeIf { entry.expiresAtMs > nowEpochMs() }
    }

    /** For tests, as [pendingCount]. */
    internal val clarificationCount: Int get() = asked.size

    private val alternatives = mutableMapOf<String, Asked>()

    /**
     * Holds a card's alternative (`mia-query-layer.md` §25, CL-D6) under the card's own token.
     *
     * ⚠⚠ **Separate from [putClarification], on purpose.** A card's alternative belongs to its card,
     * not to "the open question": a second card, or a question asked in between, must not silently
     * disable the first card's "20:00 instead?" while the card itself is still confirmable (Codex
     * review). It lives as long as its card: the same TTL, and spent with it.
     */
    internal fun putAlternative(token: String, plan: QueryPlan.Clarify) {
        val now = nowEpochMs()
        alternatives.entries.removeAll { it.value.expiresAtMs <= now }
        alternatives[token] = Asked(plan, now + ttlMs)
    }

    /** The card alternative for [token], consuming it; `null` when unknown, spent or expired. */
    internal fun takeAlternative(token: String): QueryPlan.Clarify? {
        val entry = alternatives.remove(token) ?: return null
        return entry.plan.takeIf { entry.expiresAtMs > nowEpochMs() }
    }

    companion object {
        /**
         * Long enough to read a confirmation and decide, short enough that a forgotten one
         * cannot be tapped meaningfully later.
         */
        const val DEFAULT_TTL_MS = 5L * 60 * 1000
    }
}

/**
 * An action resolved down to exactly what will happen — what the user is being shown.
 *
 * ⚠⚠ **Fully resolved on purpose.** MQ-D4a: a device action can render its intent, because
 * "turn off the kitchen light" is the whole truth; a *write* cannot, because the row that will
 * exist is the utterance **plus the service's defaults**. So the defaults are settled here,
 * displayed, and passed back explicitly at execution — the user agrees to the row, not to the
 * sentence.
 */
internal sealed interface PendingMiaAction {

    /** The write op this action lowers to, and so its frame (`MiaFrames`, §25.3). */
    val op: String

    /**
     * Every resolved value, by param id: what the utterance filled, the defaults shown on the
     * card, and the command's fixed values. ⚠ Minted params (`eventId`, `commandId`) are absent:
     * they are generated at dispatch, never shown.
     */
    val values: Map<String, Any>

    /** The acknowledgement when the op was dispatched. */
    val done: String

    /** The honest refusal when it was not. */
    val failed: String

    data class Device(
        val deviceId: String,
        val deviceName: String,
        val on: Boolean,
        /** A dim level 0..100 (`setLevel`), or `null` for on/off (`setPower`). */
        val level: Int? = null,
    ) : PendingMiaAction {
        override val op: String get() = IssueDeviceCommand.ID
        override val values: Map<String, Any>
            get() = mapOf(
                IssueDeviceCommand.Params.TARGET_DEVICE_ID to deviceId,
                // ⚠ `setPower` / `setLevel`, never toggle: absolute intent against a possibly stale
                // reading, and the level is absolute too.
                IssueDeviceCommand.Params.VERB to if (level != null) DeviceCommand.VERB_SET_LEVEL else DeviceCommand.VERB_SET_POWER,
                IssueDeviceCommand.Params.ARG_VALUE to (level?.toString() ?: if (on) DeviceCommand.ARG_ON else DeviceCommand.ARG_OFF),
            )
        override val done: String
            get() = when {
                level != null -> "Setting $deviceName to $level%."
                on -> "Turning on $deviceName."
                else -> "Turning off $deviceName."
            }
        override val failed: String get() = "I can't reach your smart-home devices from here."
    }

    /**
     * RE-5. ⚠ Carries the resolved **id and name**: the id is what `RunRoutineNow` takes, and the
     * name is what the acknowledgement says back. Re-resolving the name at execution would let a
     * routine renamed in between fire under a title the user never agreed to.
     */
    data class Routine(
        val routineId: String,
        val routineName: String,
    ) : PendingMiaAction {
        override val op: String get() = RunRoutineNow.ID
        override val values: Map<String, Any> get() = mapOf(RunRoutineNow.Params.ROUTINE_ID to routineId)
        override val done: String get() = "Running \u201C$routineName\u201D."
        override val failed: String get() = "I couldn't run \u201C$routineName\u201D just now."
    }

    /**
     * ⚠⚠ Carries every resolved slot **explicitly** — calendar, end time, zone — rather than
     * letting `SaveEvent` re-derive its defaults. The user agreed to a specific row; re-reading
     * the preferences at execution would let a default changed in between silently move the
     * event somewhere they never saw.
     */
    data class Event(
        val title: String,
        val startLocal: String,
        val endLocal: String,
        val calendarId: String,
        val calendarName: String,
        val timeZoneId: String,
    ) : PendingMiaAction {
        override val op: String get() = SaveEvent.ID
        override val values: Map<String, Any>
            get() = mapOf(
                SaveEvent.Params.TITLE to title,
                SaveEvent.Params.START_LOCAL to startLocal,
                SaveEvent.Params.END_LOCAL to endLocal,
                SaveEvent.Params.CALENDAR_ID to calendarId,
                SaveEvent.Params.TIME_ZONE_ID to timeZoneId,
                SaveEvent.Params.ALL_DAY to false,
            )
        override val done: String get() = "Added “$title” to $calendarName."

        // ⚠ Names the failure rather than reporting success. A write that silently did nothing
        // is the worst outcome available here.
        override val failed: String get() = "I couldn't add that to your calendar."
    }
}
