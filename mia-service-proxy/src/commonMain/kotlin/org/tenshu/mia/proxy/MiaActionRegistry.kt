package org.tenshu.mia.proxy

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

    data class Device(
        val deviceId: String,
        val deviceName: String,
        val on: Boolean,
    ) : PendingMiaAction

    /**
     * RE-5. ⚠ Carries the resolved **id and name**: the id is what `RunRoutineNow` takes, and the
     * name is what the acknowledgement says back. Re-resolving the name at execution would let a
     * routine renamed in between fire under a title the user never agreed to.
     */
    data class Routine(
        val routineId: String,
        val routineName: String,
    ) : PendingMiaAction

    data class Event(
        val title: String,
        val startLocal: String,
        val endLocal: String,
        val calendarId: String,
        val calendarName: String,
        val timeZoneId: String,
    ) : PendingMiaAction
}
