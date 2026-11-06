package app.tenshu.calendar.data.external

import org.tenshu.calendar.common.ics.ImportedEvent

/**
 * Splits a copy into `ApplyExternalEvents` calls that each fit one Binder transaction.
 *
 * ⚠⚠ **Bounded by bytes, not by count.** Android passes a request as one parcel through a buffer of
 * about 1 MB that the whole process shares, and the transport has no large-request fallback. A
 * description travels twice — as the event's notes and inside its source sidecar — so 200 events
 * with 3 KB descriptions already pass it, and the copy would fail on every retry, after earlier
 * batches had landed.
 *
 * ⚠ An event too big for a batch on its own first loses its sidecar (the raw copy kept only for
 * export fidelity; the event's own fields still carry the description). If it is still too big it
 * is left out and counted in [Plan.tooLarge], which the result reports as skipped — one oversized
 * event must not fail the rest of the calendar.
 */
object ExternalCopyBatches {

    /** Per call. Well under the shared ~1 MB buffer, which other in-flight calls also use. */
    const val BUDGET_BYTES: Int = 200 * 1024

    /** Per call, as before: keeps one batch's work on the proxy bounded too. */
    const val MAX_EVENTS: Int = 200

    /** Field names, ids and framing — generous, so the estimate errs towards smaller batches. */
    private const val PER_EVENT_OVERHEAD = 1024
    private const val PER_REMINDER = 128

    /** One event in a batch, and whether its sidecar rides along. */
    data class Planned(val event: ImportedEvent, val withSource: Boolean)

    data class Plan(val batches: List<List<Planned>>, val tooLarge: List<ImportedEvent>)

    /** The estimated wire size of [event], with or without its sidecar. */
    fun sizeOf(event: ImportedEvent, withSource: Boolean): Int =
        PER_EVENT_OVERHEAD +
            listOf(
                event.uid, event.title, event.location, event.notes, event.startLocal, event.endLocal,
                event.timeZoneId, event.recurrenceRule, event.exceptionDates, event.recurrenceId.orEmpty(),
            ).sumOf { it.utf8Size() } +
            event.reminderMinutes.size * PER_REMINDER +
            if (withSource) event.raw.utf8Size() + PER_EVENT_OVERHEAD else 0

    /**
     * @param attachedSize bytes that must travel in the same call as an event — a mirror sends a
     *   series together with its overrides, because the op *sets* them.
     */
    fun plan(
        events: List<ImportedEvent>,
        budget: Int = BUDGET_BYTES,
        maxEvents: Int = MAX_EVENTS,
        attachedSize: (ImportedEvent) -> Int = { 0 },
    ): Plan {
        val batches = mutableListOf<List<Planned>>()
        val tooLarge = mutableListOf<ImportedEvent>()
        var current = mutableListOf<Planned>()
        var currentSize = 0

        events.forEach { event ->
            val attached = attachedSize(event)
            val withSource = event.raw.isNotBlank() && sizeOf(event, withSource = true) + attached <= budget
            val size = sizeOf(event, withSource) + attached
            if (size > budget) {
                tooLarge += event
                return@forEach
            }
            if (current.isNotEmpty() && (currentSize + size > budget || current.size >= maxEvents)) {
                batches += current
                current = mutableListOf()
                currentSize = 0
            }
            current += Planned(event, withSource)
            currentSize += size
        }
        if (current.isNotEmpty()) batches += current
        return Plan(batches, tooLarge)
    }

    private fun String.utf8Size(): Int = encodeToByteArray().size
}
