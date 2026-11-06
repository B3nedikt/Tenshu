package org.tenshu.calendar.proxy.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.tenshu.calendar.Calendar
import org.tenshu.calendar.Event
import org.tenshu.calendar.EventOverride
import org.tenshu.calendar.Reminder
import org.tenshu.calendar.ReminderState
import org.tenshu.core.Data
import org.tenshu.core.id
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity

/**
 * Re-arms the reminder wake whenever the calendar changes.
 *
 * ⚠⚠ **A `DataObserver`, not a hook on the write ops, and that is the whole
 * point.** A reminder created on the laptop has to arm an alarm on the *phone*,
 * and that row arrives by **sync**, not by a local call — so hooking the seven
 * calendar write ops would have covered exactly the case that needs no help and
 * missed the one that does. `WriteOrigin` is not filtered here for the same
 * reason: both origins change what is due.
 *
 * ⚠⚠ **Coalesced, and it has to be.** Every re-arm re-plans the whole calendar,
 * so re-arming per row would make a first sync of N events O(N²). A debounce
 * turns any burst — an initial sync, a bulk import, a multi-row edit — into one
 * plan. ⚠ The window is short enough to be imperceptible for a single edit;
 * arming is idempotent by `dedupKey`, so the only cost of a redundant one is
 * work.
 */
internal class ReminderRearmObserver(
    scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val rearm: () -> Unit,
) : DataObserver {

    // ⚠ `CONFLATED`: a burst collapses to one pending signal by construction, so
    // the channel cannot grow and a slow re-plan cannot be back-pressured by
    // writes it is trying to keep up with.
    private val signals = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in signals) {
                delay(debounceMs)
                // ⚠ Swallowed: a failure to re-arm must never propagate into
                // the write that triggered it, or a scheduler outage would start
                // failing the user's edits.
                runCatching { rearm() }
            }
        }
    }

    // ⚠ The `Entity` cast then `data.id` fallback is the `RelationMaintainer`
    // shape: a saved row may arrive either as a typed entity or as a bare
    // composite, and only one of those carries an explicit type field.
    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        val typeId = (data as? Entity<*>)?.type?.get() ?: data.id
        if (typeId in WATCHED_TYPES) signals.trySend(Unit)
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        if (dataId.type.get() in WATCHED_TYPES) signals.trySend(Unit)
    }

    private companion object {
        /**
         * ⚠ `Calendar` is watched too, though it carries no times: the
         * `sensitive` flag changes what a reminder's text may *say*, and an
         * already-armed alert on an Apple platform holds text the OS will not
         * let us edit later — so flipping the flag has to re-plan, not just
         * affect the next one.
         */
        val WATCHED_TYPES = setOf(
            Event.ID,
            EventOverride.ID,
            Reminder.ID,
            ReminderState.ID,
            Calendar.ID,
        )

        const val DEFAULT_DEBOUNCE_MS = 250L
    }
}
