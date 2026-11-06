package org.tenshu.calendar.common.recurrence

import org.tenshu.core.collection.list.toList
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId

/**
 * The identity of a single **occurrence** — `(eventId, recurrenceId)` as a [DataId]
 * (`control-cross-app-open.md` CO-D5).
 *
 * ⚠⚠ **The first `DataId` in the system that is an identity with no row behind it**, and that is
 * not a contradiction: `mia-query-layer.md` §15.1 already established that an occurrence is
 * *computed* — a weekly stand-up stores one row, dated years ago, and none at all for tomorrow.
 * `database.getData()` on this will always return nothing, by construction. It names *which*
 * occurrence, which is exactly what a cross-app open has to carry and what no stored id can say.
 *
 * ⭐ **A [DataId.CompositeEntityDataId], not a composed string.** The obvious alternative was a
 * `StringDataId` over `"$eventId|$recurrenceId"`, which would match this service's own keying —
 * `Event`, `Calendar` and `EventOverride` are each keyed by one string. It was rejected on a
 * concrete risk: a composed key needs a separator no `eventId` may contain, and a silent collision
 * there is the class of bug positional composites already make this codebase careful about. Two
 * parts stay two parts.
 *
 * ⚠ [recurrenceId] must be `formatRecurrenceId`'s exact output — the same canonical string that
 * joins `EventOverride` rows to occurrences. Its KDoc already says why the format is single-sourced:
 * two sides formatting it differently silently orphans every override. A third speller here would
 * be the same bug in a new place.
 */
object OccurrenceId {

    /**
     * ⚠⚠ **Duplicated as a literal in `core-common`'s `HandlerTable` on purpose.** The platform
     * routes on this string and must not depend on `:calendar-service-common` to do it — Control
     * learning what a calendar *is* would be the coupling `control-cross-app-open.md` CO-D1 exists
     * to avoid. A test pins that the two spellings agree, which is the cheap half of what Rei's
     * declared `handles` set does properly at TT-4.
     */
    const val TYPE = "tenshu/calendar/eventOccurrence"

    /**
     * ⚠ [context] is nullable to match `DataId`'s own `create` arms. That is not only a test
     * convenience: a *receiving* app parses one of these at launch, before it has a Tenshu context
     * to build data with, so a context-free construction path has to exist.
     *
     * @param eventId the **series** row's id — the thing that does exist
     * @param recurrenceId `formatRecurrenceId(start)` for the occurrence the user pointed at
     */
    fun create(context: TContext? = null, eventId: String, recurrenceId: String): DataId =
        DataId.CompositeEntityDataId.create(
            context = context,
            type = TYPE,
            keyValues = listOf(
                DataId.StringDataId.create(context = context, type = EVENT_PART, keyValue = eventId),
                DataId.StringDataId.create(
                    context = context,
                    type = RECURRENCE_PART,
                    keyValue = recurrenceId,
                ),
            ),
        )

    /**
     * The two parts back out, or `null` if this is not an occurrence id.
     *
     * ⚠ Order is the contract — `keyValues` is positional, like every composite in this codebase.
     * The part types are checked rather than assumed so a mis-ordered id fails loudly here instead
     * of opening the wrong thing.
     */
    fun parse(dataId: DataId): Pair<String, String>? {
        val composite = dataId as? DataId.CompositeEntityDataId ?: return null
        if (composite.type.get() != TYPE) return null
        val parts = composite.keyValues.toList()
        if (parts.size != 2) return null
        val event = parts[0] as? DataId.StringDataId ?: return null
        val recurrence = parts[1] as? DataId.StringDataId ?: return null
        if (event.type.get() != EVENT_PART || recurrence.type.get() != RECURRENCE_PART) return null
        return event.keyValue.get() to recurrence.keyValue.get()
    }

    private const val EVENT_PART = "$TYPE/eventId"
    private const val RECURRENCE_PART = "$TYPE/recurrenceId"
}
