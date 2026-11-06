package org.tenshu.calendar.common.ics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number
import org.tenshu.calendar.common.recurrence.parseExceptionDates

/**
 * One event on its way out: the native fields, plus the raw `VEVENT` it was
 * imported from if there is one.
 *
 * ⚠ [raw] blank means *authored here*, not *lost* — a hand-created event has no
 * `EventSource` row, and `EventSource`'s own KDoc says so.
 */
data class ExportableEvent(
    val eventId: String,
    val title: String,
    val startLocal: String,
    val endLocal: String,
    val timeZoneId: String,
    val allDay: Boolean,
    val location: String,
    val notes: String,
    val recurrenceRule: String,
    val exceptionDates: String,
    val reminderMinutes: List<Int> = emptyList(),
    /** The stored `EventSource.raw`, or blank. */
    val raw: String = "",
    /** Set when this is an override of one occurrence rather than a series. */
    val recurrenceId: String? = null,
    /**
     * The `UID` to write, or blank for [eventId].
     *
     * ⚠⚠ Not always the event id. An event copied from another calendar is stored under an id
     * *namespaced* by the calendar it landed in (`calendar-external-sources.md` §5.2), so writing
     * [eventId] would export our key in place of the source's `UID` — an exported invite would no
     * longer match its meeting, and re-importing the file elsewhere would duplicate instead of
     * converging. ⚠ An override must carry its **series'** value here, or it detaches.
     */
    val uid: String = "",
)

/**
 * Writes `.ics` (CA-6).
 *
 * ⚠⚠ **This is where §3 D1's promise is kept or broken.** For an imported event
 * the export *re-emits the stored `VEVENT` with our fields written back over it*
 * rather than regenerating from scratch — so `ATTACH`, `ORGANIZER`, `ATTENDEE`,
 * `X-` extensions and alarm shapes we never modelled come back out. Regenerating
 * would be simpler, would pass every test that only checks our own fields, and
 * would quietly make the sidecar pointless.
 */
object IcsExport {

    /** ⚠ Ours, and stable: a consumer uses it to recognise files we wrote. */
    const val PRODUCT_ID = "-//Tenshu//Calendar//EN"

    fun write(events: List<ExportableEvent>, calendarName: String = ""): String {
        val properties = buildList {
            add(IcsProperty(name = "VERSION", value = "2.0"))
            add(IcsProperty(name = "PRODID", value = PRODUCT_ID))
            add(IcsProperty(name = "CALSCALE", value = "GREGORIAN"))
            if (calendarName.isNotBlank()) {
                add(IcsProperty(name = "X-WR-CALNAME", value = encodeText(calendarName)))
            }
        }
        return serializeIcs(
            IcsComponent(
                name = "VCALENDAR",
                properties = properties,
                components = events.map(::eventComponent),
            )
        )
    }

    /**
     * ⚠ Public so a caller can export one event without a wrapper — and so the
     * round-trip test can assert on the block rather than on the file.
     */
    fun eventComponent(event: ExportableEvent): IcsComponent {
        val base = event.raw
            .takeIf { it.isNotBlank() }
            ?.let { parseIcs(it).children("VEVENT").firstOrNull() }
            ?: IcsComponent(name = "VEVENT")

        return base.copy(
            name = "VEVENT",
            properties = mergeProperties(base.properties, event),
            // ⚠⚠ The imported component's children ride through **unchanged**,
            // which is what carries a `VALARM` we could not map (an absolute
            // trigger, a `RELATED=END` one) back out intact. Our own reminders
            // are folded into that list by `mergeAlarms`, not appended blindly.
            components = mergeAlarms(base.components, event.reminderMinutes),
        )
    }

    /**
     * ⚠⚠ **Overwrite in place, keep position, append only what is new.** Order
     * matters to some consumers and, more importantly, rebuilding the list would
     * drop every property not named here — which is the whole failure this
     * function exists to avoid.
     */
    private fun mergeProperties(
        existing: List<IcsProperty>,
        event: ExportableEvent,
    ): List<IcsProperty> {
        val ours = ownProperties(event).associateBy { it.name.uppercase() }
        val used = mutableSetOf<String>()
        // ⚠⚠ `DURATION` is owned only when we are writing a `DTEND`. RFC 5545
        // forbids both on one event, so we must strip it when we emit an end —
        // but stripping it when we *cannot* (an end we failed to compute) would
        // silently export an event with no length at all.
        val owned = if ("DTEND" in ours) OWNED + "DURATION" else OWNED

        val merged = existing.mapNotNull { property ->
            val key = property.name.uppercase()
            // ⚠ A property we own but no longer have a value for is *removed*,
            // not left stale: clearing an event's location must clear it in the
            // export too, or the file disagrees with the app.
            if (key !in owned) return@mapNotNull property
            if (!used.add(key)) return@mapNotNull null   // a duplicate we now own
            ours[key]
        }
        val appended = ours.filterKeys { it !in used }.values
        return merged + appended
    }

    private fun ownProperties(event: ExportableEvent): List<IcsProperty> = buildList {
        add(IcsProperty(name = "UID", value = encodeText(event.uid.ifBlank { event.eventId })))
        add(IcsProperty(name = "SUMMARY", value = encodeText(event.title)))
        add(dateProperty("DTSTART", event.startLocal, event.timeZoneId, event.allDay))
        // ⚠ A blank end is omitted rather than written as the start: RFC 5545
        // implies the right end per kind, and `EventTime.parse` implies the same
        // one — writing a guess would freeze it into the file.
        if (event.endLocal.isNotBlank()) {
            add(dateProperty("DTEND", event.endLocal, event.timeZoneId, event.allDay))
        }
        if (event.location.isNotBlank()) {
            add(IcsProperty(name = "LOCATION", value = encodeText(event.location)))
        }
        if (event.notes.isNotBlank()) {
            add(IcsProperty(name = "DESCRIPTION", value = encodeText(event.notes)))
        }
        if (event.recurrenceRule.isNotBlank()) {
            // ⚠ Never escaped: an `RRULE` value is structured, and escaping its
            // `;` and `,` would produce a rule no parser can read.
            add(IcsProperty(name = "RRULE", value = event.recurrenceRule.removePrefix("RRULE:")))
        }
        exdateProperty(event)?.let(::add)
        event.recurrenceId?.let {
            add(dateProperty("RECURRENCE-ID", it, event.timeZoneId, event.allDay))
        }
    }

    private fun exdateProperty(event: ExportableEvent): IcsProperty? {
        val dates = parseExceptionDates(event.exceptionDates)
        if (dates.isEmpty()) return null
        return IcsProperty(
            name = "EXDATE",
            // ⚠ `VALUE=DATE`: `Event.exceptionDates` stores dates, and emitting
            // them as date-times would make them fail to match a timed series'
            // occurrences in a stricter consumer.
            params = listOf(IcsParam("VALUE", "DATE")),
            value = dates.sorted().joinToString(",") { formatCompactDate(it) },
        )
    }

    private fun dateProperty(
        name: String,
        local: String,
        timeZoneId: String,
        allDay: Boolean,
    ): IcsProperty {
        val parsed = parseLocal(local)
        return when {
            parsed == null -> IcsProperty(name = name, value = local)
            allDay -> IcsProperty(
                name = name,
                params = listOf(IcsParam("VALUE", "DATE")),
                value = formatCompactDate(parsed.date),
            )
            // ⚠ No `TZID` and no trailing `Z` for a floating event — that is
            // exactly what floating *means* in RFC 5545, and stamping `Z` on it
            // would move the event by the reader's offset.
            timeZoneId.isBlank() -> IcsProperty(name = name, value = formatCompact(parsed))
            else -> IcsProperty(
                name = name,
                params = listOf(IcsParam("TZID", timeZoneId)),
                value = formatCompact(parsed),
            )
        }
    }

    /**
     * ⚠⚠ Replaces the alarms we can express and keeps the ones we cannot.
     * An imported absolute-trigger `VALARM` survives because it is not in the
     * relative set; our own offsets are re-emitted from the stored reminders,
     * so a reminder deleted in the app disappears from the file too.
     */
    private fun mergeAlarms(
        existing: List<IcsComponent>,
        reminderMinutes: List<Int>,
    ): List<IcsComponent> {
        val kept = existing.filterNot { it.name.equals("VALARM", true) && it.isRelativeAlarm() }
        return kept + reminderMinutes.map(::alarmComponent)
    }

    private fun IcsComponent.isRelativeAlarm(): Boolean {
        val trigger = first("TRIGGER") ?: return false
        if (trigger.param("VALUE")?.uppercase() == "DATE-TIME") return false
        if (trigger.param("RELATED")?.uppercase() == "END") return false
        return true
    }

    private fun alarmComponent(minutesBefore: Int) = IcsComponent(
        name = "VALARM",
        properties = listOf(
            IcsProperty(name = "ACTION", value = "DISPLAY"),
            // ⚠ Required by RFC 5545 for a DISPLAY alarm; consumers reject the
            // alarm without it. Deliberately generic — the event's own SUMMARY is
            // what a reader shows, and duplicating the title here would leak it
            // into a second place for a sensitive calendar.
            IcsProperty(name = "DESCRIPTION", value = "Reminder"),
            IcsProperty(name = "TRIGGER", value = formatTrigger(minutesBefore)),
        ),
    )
}

/**
 * ⚠ Sign flip, the mirror of the import side: "10 minutes before" is `-PT10M`.
 * A negative `minutesBefore` (an all-day reminder, "on the day at 9 a.m.") is
 * therefore a *positive* duration.
 */
internal fun formatTrigger(minutesBefore: Int): String {
    val minutes = -minutesBefore
    val sign = if (minutes < 0) "-" else ""
    val abs = if (minutes < 0) -minutes else minutes
    val days = abs / (24 * 60)
    val rest = abs % (24 * 60)
    val hours = rest / 60
    val mins = rest % 60
    return buildString {
        append(sign).append('P')
        if (days > 0) append(days).append('D')
        // ⚠ `PT0M`, not a bare `P`, when the offset is zero: an empty duration is
        // not valid and "at start" is a real, offered choice.
        if (hours > 0 || mins > 0 || days == 0) {
            append('T')
            if (hours > 0) append(hours).append('H')
            if (mins > 0 || (hours == 0 && days == 0)) append(mins).append('M')
        }
    }
}

internal fun formatCompact(value: LocalDateTime): String =
    formatCompactDate(value.date) +
        "T" + value.hour.toString().padStart(2, '0') +
        value.minute.toString().padStart(2, '0') + "00"

internal fun formatCompactDate(date: LocalDate): String =
    date.year.toString().padStart(4, '0') +
        date.month.number.toString().padStart(2, '0') +
        date.day.toString().padStart(2, '0')

private fun parseLocal(raw: String): LocalDateTime? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    runCatching { return LocalDateTime.parse(text) }
    runCatching { return LocalDateTime(LocalDate.parse(text), kotlinx.datetime.LocalTime(0, 0)) }
    return null
}

/**
 * The properties this exporter owns; everything else rides through untouched.
 *
 * ⚠ `RECURRENCE-ID` is owned even though it is only *emitted* for an override —
 * that is deliberate. An event with no `recurrenceId` is a series, and leaving a
 * stale one on it would export an override of nothing. ⚠ `DURATION` is
 * conditionally owned; see `mergeProperties`.
 */
private val OWNED = setOf(
    "UID", "SUMMARY", "DTSTART", "DTEND", "LOCATION",
    "DESCRIPTION", "RRULE", "EXDATE", "RECURRENCE-ID",
)
