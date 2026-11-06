package org.tenshu.calendar.common.ics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.tenshu.calendar.common.recurrence.formatRecurrenceId

/**
 * One `VEVENT`, mapped onto the fields `SaveEvent` takes — plus the verbatim
 * block it came from.
 *
 * ⚠⚠ [raw] is the whole point (§3 D1). Everything this type does not model —
 * `ATTACH`, `ORGANIZER`, `ATTENDEE`, `X-` extensions, `VALARM` shapes beyond a
 * simple offset — survives in it, so an export is not lossy and a future CalDAV
 * phase is not blocked by v1 having thrown information away.
 */
data class ImportedEvent(
    /** From `UID`; blank when the file omits it (rare, and handled by the caller). */
    val uid: String,
    val title: String,
    val startLocal: String,
    val endLocal: String,
    val timeZoneId: String,
    val allDay: Boolean,
    val location: String,
    val notes: String,
    val recurrenceRule: String,
    /** `,`-joined dates, the shape `Event.exceptionDates` stores. */
    val exceptionDates: String,
    /**
     * Set when this block is an **override** of one occurrence rather than a
     * series (`RECURRENCE-ID`), formatted the way `EventOverride` stores it.
     *
     * ⚠⚠ A file that carries a series *and* its exceptions emits several
     * `VEVENT`s sharing one `UID`, distinguished only by this. Treating them all
     * as series would make the last one win and silently discard the recurrence.
     */
    val recurrenceId: String?,
    /** Minutes before the start, from `VALARM`/`TRIGGER`; see `Reminder`. */
    val reminderMinutes: List<Int>,
    val raw: String,
)

/** A parsed `.ics` payload, as an importer sees it. */
data class ImportedCalendar(
    /** From `X-WR-CALNAME`, when the exporter wrote one. Free text. */
    val name: String,
    val events: List<ImportedEvent>,
    /**
     * `VEVENT`s that carried no usable `DTSTART`.
     *
     * ⚠⚠ Counted rather than dropped silently. An import that says "42 events"
     * when the file held 44 is how a user loses two appointments and never finds
     * out; the sheet reports this number.
     */
    val skipped: Int,
)

/**
 * Reads an `.ics` payload (CA-6).
 *
 * ⚠ Tolerant throughout: a `VEVENT` we cannot place in time is skipped and
 * *counted*, never thrown on. A calendar file is something a user hands us from
 * elsewhere, so any single bad block must cost only itself.
 */
object IcsImport {

    fun read(text: String): ImportedCalendar {
        val root = parseIcs(text)
        // ⚠ `VEVENT`s are looked up at both levels: normally under `VCALENDAR`,
        // but some exporters emit bare blocks with no wrapper at all.
        val calendars = root.children("VCALENDAR")
        val blocks = (calendars.flatMap { it.children("VEVENT") } + root.children("VEVENT"))

        var skipped = 0
        val events = blocks.mapNotNull { block ->
            val event = readEvent(block)
            if (event == null) skipped++
            event
        }
        return ImportedCalendar(
            name = calendars.firstNotNullOfOrNull { it.first("X-WR-CALNAME")?.value }
                ?.let(::decodeText).orEmpty(),
            events = events,
            skipped = skipped,
        )
    }

    /**
     * The `UID` a stored sidecar carries, or `""`.
     *
     * ⚠ What the export writes for an event whose id is not its `UID` — see
     * [ExportableEvent.uid]. Reads the first `VEVENT` and nothing else, because a sidecar is one
     * block by construction.
     */
    fun uidOf(raw: String): String {
        if (raw.isBlank()) return ""
        val root = parseIcs(raw)
        val block = root.children("VEVENT").firstOrNull()
            ?: root.children("VCALENDAR").firstNotNullOfOrNull { it.children("VEVENT").firstOrNull() }
            ?: return ""
        return block.first("UID")?.value?.let(::decodeText)?.trim().orEmpty()
    }

    private fun readEvent(block: IcsComponent): ImportedEvent? {
        val dtStart = block.first("DTSTART") ?: return null
        val start = parseIcsDateTime(dtStart.value) ?: return null
        // ⚠ All-day is `VALUE=DATE` on the *property*, not a guess from a
        // midnight time: a timed event that genuinely starts at 00:00 is a real
        // thing and must not become an all-day one.
        val allDay = dtStart.isDateOnly

        val end = block.first("DTEND")?.value?.let(::parseIcsDateTime)
            ?: block.first("DURATION")?.value?.let { parseIcsDuration(it)?.let(start::plusMinutes) }

        return ImportedEvent(
            uid = block.first("UID")?.value?.let(::decodeText).orEmpty(),
            title = block.first("SUMMARY")?.value?.let(::decodeText).orEmpty(),
            startLocal = formatRecurrenceId(start),
            // ⚠ Blank rather than invented when the file gives neither `DTEND`
            // nor `DURATION`: `EventTime.parse` already implies the right end per
            // kind (one day for all-day, a point for timed), and guessing here
            // would put a second, disagreeing rule in the codebase.
            endLocal = end?.let(::formatRecurrenceId).orEmpty(),
            // ⚠ An all-day event has no zone by definition (RFC 5545 §3.8.2.4),
            // so a `TZID` on a `VALUE=DATE` property is noise and dropped.
            timeZoneId = if (allDay) "" else dtStart.tzid.orEmpty(),
            allDay = allDay,
            location = block.first("LOCATION")?.value?.let(::decodeText).orEmpty(),
            notes = block.first("DESCRIPTION")?.value?.let(::decodeText).orEmpty(),
            // ⚠ Stored *without* the `RRULE:` prefix, which is what
            // `RecurrenceRule.parse` and `Event.recurrenceRule` both expect.
            recurrenceRule = block.first("RRULE")?.value.orEmpty(),
            exceptionDates = readExceptionDates(block),
            recurrenceId = block.first("RECURRENCE-ID")?.value
                ?.let(::parseIcsDateTime)
                ?.let(::formatRecurrenceId),
            reminderMinutes = readAlarms(block),
            raw = serializeIcs(block).trimEnd(),
        )
    }

    /**
     * ⚠ `EXDATE` may appear several times *and* carry several comma-separated
     * values on each — both forms are in the wild, and handling only one drops
     * exceptions silently, which shows up as deleted occurrences coming back.
     */
    private fun readExceptionDates(block: IcsComponent): String =
        block.all("EXDATE")
            .flatMap { it.value.split(',') }
            .mapNotNull { parseIcsDateTime(it.trim()) }
            .joinToString(",") { it.date.toString() }

    /**
     * Reads `VALARM` offsets as minutes-before.
     *
     * ⚠ Only the **relative** form (`TRIGGER:-PT10M`) maps onto our `Reminder`,
     * which is an offset. An absolute trigger (`VALUE=DATE-TIME`) is a different
     * shape and is left alone — it survives in [ImportedEvent.raw], so exporting
     * gives it back, and inventing an offset from it would need a clock this
     * layer deliberately does not have.
     *
     * ⚠ `RELATED=END` triggers are skipped for the same reason: they are
     * relative to the *end*, and our offsets are from the start.
     */
    private fun readAlarms(block: IcsComponent): List<Int> =
        block.children("VALARM")
            .mapNotNull { alarm ->
                val trigger = alarm.first("TRIGGER") ?: return@mapNotNull null
                if (trigger.param("VALUE")?.uppercase() == "DATE-TIME") return@mapNotNull null
                if (trigger.param("RELATED")?.uppercase() == "END") return@mapNotNull null
                parseIcsDuration(trigger.value)
            }
            // ⚠ Sign flip: iCalendar writes "10 minutes before" as the negative
            // duration `-PT10M`, and `Reminder.minutesBefore` is positive for
            // before. Getting this backwards would fire every reminder *after*
            // the event, which is the kind of bug a user reports as "reminders
            // don't work" rather than as an inverted sign.
            .map { -it }
            .distinct()
            .sorted()
}

/**
 * Parses the iCalendar date/date-time forms into a civil value.
 *
 * ⚠⚠ **A trailing `Z` means UTC, and this returns the civil value unconverted.**
 * That is correct here only because the caller pairs it with the `TZID` it read
 * off the same property — see [IcsImport.readEvent], where a `Z` value yields an
 * empty `timeZoneId` and is therefore stored as *floating*, not as UTC-shifted
 * local time. ⚠ A real conversion needs a zone database this layer does not
 * have; carrying UTC through as floating is the honest degradation, and it is
 * exactly what the raw sidecar exists to make recoverable.
 */
internal fun parseIcsDateTime(raw: String): LocalDateTime? {
    val text = raw.trim().removeSuffix("Z")
    return when {
        // 20260803T090000
        text.length >= 15 && text[8] == 'T' -> {
            val date = parseCompactDate(text.substring(0, 8)) ?: return null
            val hour = text.substring(9, 11).toIntOrNull() ?: return null
            val minute = text.substring(11, 13).toIntOrNull() ?: return null
            runCatching { LocalDateTime(date, LocalTime(hour, minute)) }.getOrNull()
        }
        // 20260803
        text.length == 8 -> parseCompactDate(text)?.let { LocalDateTime(it, LocalTime(0, 0)) }
        else -> null
    }
}

private fun parseCompactDate(text: String): LocalDate? {
    val year = text.substring(0, 4).toIntOrNull() ?: return null
    val month = text.substring(4, 6).toIntOrNull() ?: return null
    val day = text.substring(6, 8).toIntOrNull() ?: return null
    return runCatching { LocalDate(year, month, day) }.getOrNull()
}

/**
 * Parses an RFC 5545 duration into whole minutes, signed.
 *
 * ⚠ Weeks and days are supported because "1 day before" is an ordinary reminder
 * and `-P1D` is how every exporter writes it. Seconds are truncated: our offsets
 * are minutes, and rounding a 90-second trigger to two minutes would be a lie in
 * the wrong direction.
 */
internal fun parseIcsDuration(raw: String): Int? {
    val text = raw.trim().uppercase()
    if (text.isEmpty()) return null
    val negative = text.startsWith("-")
    val body = text.removePrefix("-").removePrefix("+")
    if (!body.startsWith("P")) return null

    var minutes = 0
    var number = StringBuilder()
    var inTime = false
    for (c in body.drop(1)) {
        when {
            c.isDigit() -> number.append(c)
            c == 'T' -> inTime = true
            else -> {
                val n = number.toString().toIntOrNull() ?: return null
                number = StringBuilder()
                minutes += when (c) {
                    'W' -> n * 7 * 24 * 60
                    'D' -> n * 24 * 60
                    'H' -> if (inTime) n * 60 else return null
                    'M' -> if (inTime) n else return null   // ⚠ `M` outside T would be months
                    'S' -> 0                                 // truncated, see KDoc
                    else -> return null
                }
            }
        }
    }
    // ⚠ Trailing digits with no unit is malformed (`PT10`), not zero.
    if (number.isNotEmpty()) return null
    return if (negative) -minutes else minutes
}

private fun LocalDateTime.plusMinutes(minutes: Int): LocalDateTime {
    val total = date.toEpochDays().toLong() * 1440L + hour * 60L + minute + minutes
    val days = floorDivCompat(total, 1440L)
    val minuteOfDay = (total - days * 1440L).toInt()
    return LocalDateTime(
        LocalDate.fromEpochDays(days.toInt()),
        LocalTime(minuteOfDay / 60, minuteOfDay % 60),
    )
}

/** ⚠ `Math.floorDiv` is JVM-only; this module is multiplatform. */
private fun floorDivCompat(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) < 0) q - 1 else q
}
