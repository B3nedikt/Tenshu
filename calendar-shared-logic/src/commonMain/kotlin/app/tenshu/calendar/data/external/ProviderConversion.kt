package app.tenshu.calendar.data.external

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.tenshu.calendar.common.ics.ExportableEvent
import org.tenshu.calendar.common.ics.IcsExport
import org.tenshu.calendar.common.ics.IcsParam
import org.tenshu.calendar.common.ics.IcsProperty
import org.tenshu.calendar.common.ics.ImportedEvent
import org.tenshu.calendar.common.ics.serializeIcs
import org.tenshu.calendar.common.recurrence.formatRecurrenceId

/**
 * One `CalendarContract.Calendars` row, as the Android reader hands it over.
 *
 * ⚠ Plain values, no Android types: everything that decides anything happens in
 * [ProviderConversion], in common code, where a test can reach it. The reader only copies
 * columns — the CA-6b / CA-7c discipline (the platform draws an answer it did not compute).
 */
data class ProviderCalendarRow(
    val id: Long,
    val accountName: String?,
    val accountType: String?,
    val displayName: String?,
    val color: Int?,
    /** `Calendars._SYNC_ID` — the server's id for the calendar; null for a local calendar. */
    val syncId: String?,
    val syncEvents: Boolean,
)

/** One `CalendarContract.Events` row. Column names in the KDoc of each field. */
data class ProviderEventRow(
    /** `_ID` — local to this device. */
    val id: Long,
    /** `_SYNC_ID` — the server's id (Google's event id). */
    val syncId: String? = null,
    /** `UID_2445` — the iCalendar `UID`, when the sync adapter keeps one. */
    val uid2445: String? = null,
    val title: String? = null,
    /** `DESCRIPTION`. */
    val description: String? = null,
    /** `EVENT_LOCATION`. */
    val location: String? = null,
    /** `DTSTART`, epoch milliseconds. ⚠ UTC midnight for an all-day event. */
    val dtStart: Long? = null,
    /** `DTEND`, epoch milliseconds; null for a recurring event, which carries [duration]. */
    val dtEnd: Long? = null,
    /** `DURATION`, RFC 5545-ish — Android writes `P3600S` as often as `PT1H`. */
    val duration: String? = null,
    /** `EVENT_TIMEZONE`. ⚠ `UTC` for an all-day event, whatever zone the user is in. */
    val eventTimezone: String? = null,
    val allDay: Boolean = false,
    /** `RRULE`, without the `RRULE:` prefix. */
    val rrule: String? = null,
    val rdate: String? = null,
    val exrule: String? = null,
    val exdate: String? = null,
    /** `ORIGINAL_ID` — set on an exception row: the local id of its series. */
    val originalId: Long? = null,
    /** `ORIGINAL_SYNC_ID` — the series' server id, when [originalId] is not filled. */
    val originalSyncId: String? = null,
    /** `ORIGINAL_INSTANCE_TIME` — the start of the occurrence an exception replaces, epoch ms. */
    val originalInstanceTime: Long? = null,
    /** `STATUS` — see [ProviderConversion.STATUS_CANCELED]. */
    val status: Int? = null,
    /** `DELETED` — deletion pending the sync adapter. */
    val deleted: Boolean = false,
)

/** One `CalendarContract.Reminders` row. */
data class ProviderReminderRow(
    val eventId: Long,
    val minutes: Int,
    val method: Int,
)

/**
 * Turns Android calendar-provider rows into what the importer takes
 * (`calendar-external-sources.md` §5.4, §5.5).
 *
 * Every rule below is one a naive reader gets wrong, and none of them is visible until a user
 * notices something missing or on the wrong day:
 *
 * - ⚠⚠ **All-day events are UTC midnight.** Android stores an all-day `DTSTART` as midnight UTC
 *   with `EVENT_TIMEZONE = UTC`. Its date is read **in UTC**; read in the device zone, every
 *   holiday moves back a day west of Greenwich (`calendar-app.md` §3 D3).
 * - ⚠ **Read the `Events` table, never `Instances`** — `Instances` is expanded occurrences, the
 *   materialisation §3 D2 forbids. A series is one row with an `RRULE`; an exception is a row
 *   pointing back at it through `ORIGINAL_ID`.
 * - ⚠⚠ **A cancelled exception is a deleted occurrence**, i.e. an `EXDATE` on the series — not an
 *   event, and not something to drop. Dropping it brings the deleted occurrence back.
 * - ⚠ `DELETED = 1` rows are pending deletion by the sync adapter and are skipped.
 * - ⚠ `DURATION` is how a recurring event says how long it is (`DTEND` is null), and Android
 *   writes it as `P3600S` as often as `PT1H`.
 */
object ProviderConversion {

    /** `CalendarContract.Events.STATUS_CANCELED`. */
    const val STATUS_CANCELED = 2

    /**
     * `Reminders.METHOD_DEFAULT`, `METHOD_ALERT` and `METHOD_ALARM` — the ones that mean "show me
     * something". ⚠ `METHOD_EMAIL` / `METHOD_SMS` are left out: we can only notify, and turning a
     * reminder the user asked to receive by email into a phone alert is a change they did not ask
     * for.
     */
    private val NOTIFYING_METHODS = setOf(0, 1, 4)

    /**
     * `Reminders.MINUTES_DEFAULT`: "use the calendar's default", not an offset.
     *
     * ⚠⚠ Drop exactly this value, never every negative one. Offsets are signed (see `Reminder`), and
     * Google writes an all-day event's "on the day at 9:00" as `-540` — nine hours *after* its
     * midnight start, the same number as our own `ReminderPresets.ALL_DAY` choice. A `>= 0` filter
     * silently lost every such reminder on a real phone.
     */
    private const val MINUTES_DEFAULT = -1

    fun calendar(row: ProviderCalendarRow): ExternalCalendar {
        val accountType = row.accountType.orEmpty()
        val accountName = row.accountName.orEmpty()
        return ExternalCalendar(
            kind = ExternalSourceKind.ANDROID_PROVIDER,
            // ⚠ The server's id where there is one — stable across devices and reinstalls, so a
            // second copy converges. Only a local calendar falls back to the row id, which is the
            // best a calendar that exists on one phone can offer.
            sourceKey = "$accountType|$accountName|" +
                (row.syncId?.takeIf { it.isNotBlank() } ?: "local:${row.id}"),
            handle = row.id.toString(),
            name = row.displayName?.takeIf { it.isNotBlank() } ?: accountName.ifBlank { "Calendar" },
            accountName = accountName,
            accountType = accountType,
            colorArgb = row.color,
            holdsEvents = row.syncEvents,
        )
    }

    fun snapshot(events: List<ProviderEventRow>, reminders: List<ProviderReminderRow>): ExternalSnapshot {
        val live = events.filterNot { it.deleted }
        val (exceptionRows, seriesRows) = live.partition { it.isException }
        val remindersByEvent = reminders
            .filter { it.method in NOTIFYING_METHODS && it.minutes != MINUTES_DEFAULT }
            .groupBy({ it.eventId }, { it.minutes })

        var skipped = 0
        val unreadable = mutableSetOf<String>()
        val incomplete = mutableSetOf<String>()
        val usedUids = mutableSetOf<String>()
        val series = mutableMapOf<Long, Series>()
        val seriesBySyncId = mutableMapOf<String, Series>()

        seriesRows.forEach { row ->
            // A cancelled *series* is a meeting that was called off. The source's own app does not
            // show it, so neither do we — and it is not a failure to report.
            if (row.status == STATUS_CANCELED) return@forEach
            val placed = Series.of(row, usedUids) ?: run {
                skipped++
                unreadable += Series.uidCandidates(row)
                return@forEach
            }
            usedUids += placed.uid
            series[row.id] = placed
            row.syncId?.takeIf { it.isNotBlank() }?.let { seriesBySyncId[it] = placed }
        }

        val overrides = mutableListOf<ImportedEvent>()
        exceptionRows.forEach { row ->
            val parent = row.originalId?.let(series::get)
                ?: row.originalSyncId?.let(seriesBySyncId::get)
            val instant = row.originalInstanceTime
            if (parent == null || instant == null) {
                skipped++
                // A known series with an unplaceable exception: keep its stored overrides. (No
                // parent at all means the series was not read either, and is spared as a whole.)
                parent?.let { incomplete += it.uid }
                return@forEach
            }
            val originalStart = parent.localOf(instant)
            if (row.status == STATUS_CANCELED) {
                parent.cancelled += originalStart.date
                return@forEach
            }
            val start = row.dtStart ?: run {
                skipped++
                incomplete += parent.uid
                return@forEach
            }
            val startLocal = parent.localOf(start)
            val end = row.endMillis()?.let(parent::localOf)
            overrides += ImportedEvent(
                uid = parent.uid,
                title = row.title ?: parent.row.title.orEmpty(),
                startLocal = formatRecurrenceId(startLocal),
                endLocal = end?.let(::formatRecurrenceId).orEmpty(),
                timeZoneId = parent.zoneId,
                allDay = parent.row.allDay,
                location = row.location ?: parent.row.location.orEmpty(),
                notes = row.description ?: parent.row.description.orEmpty(),
                recurrenceRule = "",
                exceptionDates = "",
                recurrenceId = formatRecurrenceId(originalStart),
                // ⚠ The series' reminders fire for this occurrence too; an override has no
                // reminder rows of its own in our model.
                reminderMinutes = emptyList(),
                raw = "",
            )
        }

        val seriesEvents = series.values.map { it.toImported(remindersByEvent[it.row.id].orEmpty()) }
        return ExternalSnapshot(
            events = seriesEvents + overrides,
            skipped = skipped,
            unreadableUids = unreadable,
            incompleteOverridesFor = incomplete,
        )
    }

    private val ProviderEventRow.isException: Boolean
        get() = originalId != null || !originalSyncId.isNullOrBlank()

    private fun ProviderEventRow.endMillis(): Long? =
        dtEnd ?: duration?.let(::parseDurationSeconds)?.let { seconds -> dtStart?.plus(seconds * 1000) }

    /** A series, placed in time, with the occurrences its exceptions cancelled. */
    private class Series(
        val row: ProviderEventRow,
        val uid: String,
        val zone: TimeZone,
        /** The zone id we store; `""` for all-day, which has none (§3 D3). */
        val zoneId: String,
        val start: LocalDateTime,
        val end: LocalDateTime?,
    ) {
        val cancelled = mutableSetOf<LocalDate>()

        fun localOf(epochMs: Long): LocalDateTime = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)

        fun toImported(reminderMinutes: List<Int>): ImportedEvent {
            val exdates = (parseExdates(row.exdate, zone) + cancelled).sorted().joinToString(",")
            val event = ImportedEvent(
                uid = uid,
                title = row.title.orEmpty(),
                startLocal = formatRecurrenceId(start),
                // ⚠ Blank rather than invented when the row gives neither end nor duration — the
                // apply op implies the same end `SaveEvent` would.
                endLocal = end?.takeIf { it > start }?.let(::formatRecurrenceId).orEmpty(),
                timeZoneId = zoneId,
                allDay = row.allDay,
                location = row.location.orEmpty(),
                notes = row.description.orEmpty(),
                recurrenceRule = row.rrule.orEmpty().trim().removePrefix("RRULE:"),
                exceptionDates = exdates,
                recurrenceId = null,
                reminderMinutes = reminderMinutes.distinct().sorted(),
                raw = "",
            )
            return event.copy(raw = synthesizeRaw(event, row))
        }

        companion object {
            fun of(row: ProviderEventRow, usedUids: Set<String>): Series? {
                val startMs = row.dtStart ?: return null
                // ⚠⚠ All-day: UTC, always — see the object KDoc.
                val zone = if (row.allDay) TimeZone.UTC else zoneOf(row.eventTimezone) ?: TimeZone.UTC
                val start = Instant.fromEpochMilliseconds(startMs).toLocalDateTime(zone)
                val end = row.endMillis()?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone) }
                return Series(
                    row = row,
                    uid = uidFor(row, usedUids),
                    zone = zone,
                    zoneId = if (row.allDay) "" else zone.id,
                    start = if (row.allDay) LocalDateTime(start.date, LocalTime(0, 0)) else start,
                    end = if (row.allDay) end?.let { LocalDateTime(it.date, LocalTime(0, 0)) } else end,
                )
            }

            /**
             * The iCalendar `UID` when the sync adapter kept one, else the server's id, else the
             * local row id — most stable first.
             *
             * ⚠ A `UID` already taken by another series in the same calendar (a "this and
             * following" edit can leave two series sharing one) falls through to the next
             * candidate, so both survive rather than the second overwriting the first.
             */
            private fun uidFor(row: ProviderEventRow, usedUids: Set<String>): String =
                listOfNotNull(
                    row.uid2445?.trim()?.takeIf { it.isNotEmpty() },
                    row.syncId?.trim()?.takeIf { it.isNotEmpty() },
                ).firstOrNull { it !in usedUids } ?: "android-event-${row.id}"

            /** Every value [uidFor] could return for [row] — see `ExternalSnapshot.unreadableUids`. */
            fun uidCandidates(row: ProviderEventRow): Set<String> = setOfNotNull(
                row.uid2445?.trim()?.takeIf { it.isNotEmpty() },
                row.syncId?.trim()?.takeIf { it.isNotEmpty() },
                "android-event-${row.id}",
            )
        }
    }

    /**
     * The `VEVENT` a copied event's sidecar holds (`calendar-external-sources.md` §4.1).
     *
     * ⚠⚠ It exists to carry the source's `UID` — the stored id is namespaced, and the export reads
     * the identity back from here — plus the two recurrence parts we cannot model (`RDATE`,
     * `EXRULE`), so an export gives them back rather than quietly losing them.
     */
    private fun synthesizeRaw(event: ImportedEvent, row: ProviderEventRow): String {
        val component = IcsExport.eventComponent(
            ExportableEvent(
                eventId = event.uid,
                title = event.title,
                startLocal = event.startLocal,
                endLocal = event.endLocal,
                timeZoneId = event.timeZoneId,
                allDay = event.allDay,
                location = event.location,
                notes = event.notes,
                recurrenceRule = event.recurrenceRule,
                exceptionDates = event.exceptionDates,
                reminderMinutes = event.reminderMinutes,
            )
        )
        val unmodelled = listOfNotNull(
            row.rdate?.let { dateListProperty("RDATE", it) },
            row.exrule?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { IcsProperty(name = "EXRULE", value = it.removePrefix("EXRULE:")) },
        )
        return serializeIcs(component.copy(properties = component.properties + unmodelled)).trimEnd()
    }

    /** Android's `TZID;v1,v2` (or bare `v1,v2`) date list, as an iCalendar property. */
    private fun dateListProperty(name: String, raw: String): IcsProperty? {
        val line = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        val (zone, values) = splitZonePrefix(line)
        return IcsProperty(
            name = name,
            params = listOfNotNull(zone?.let { IcsParam("TZID", it) }),
            value = values,
        )
    }

    /**
     * An Android `EXDATE` column as the dates it excludes, in the series' zone.
     *
     * ⚠ Tolerant of every shape the provider writes: several values per line, several lines, an
     * optional `TZID;` prefix, UTC `Z` values, and bare dates for an all-day series. A value we
     * cannot read costs only itself — and is harmless, because a missed exclusion shows an
     * occurrence the user deleted rather than hiding one they kept.
     */
    internal fun parseExdates(raw: String?, seriesZone: TimeZone): Set<LocalDate> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split('\n').flatMap { line ->
            val (prefix, values) = splitZonePrefix(line.trim())
            val valueZone = zoneOf(prefix) ?: seriesZone
            values.split(',').mapNotNull { value -> exdateOf(value.trim(), valueZone, seriesZone) }
        }.toSet()
    }

    private fun exdateOf(value: String, valueZone: TimeZone, seriesZone: TimeZone): LocalDate? {
        if (value.isEmpty()) return null
        val utc = value.endsWith("Z")
        val local = parseCompact(value.removeSuffix("Z")) ?: return null
        if (value.length == 8) return local.date
        val zone = if (utc) TimeZone.UTC else valueZone
        if (zone == seriesZone) return local.date
        return local.toInstant(zone).toLocalDateTime(seriesZone).date
    }

    /** Splits `Europe/Berlin;20260101T090000` (or `TZID=…:` / `TZID=…;`) into zone and values. */
    private fun splitZonePrefix(line: String): Pair<String?, String> {
        val cut = line.indexOfAny(charArrayOf(';', ':'))
        if (cut <= 0) return null to line
        return line.substring(0, cut).removePrefix("TZID=") to line.substring(cut + 1)
    }

    private fun parseCompact(text: String): LocalDateTime? {
        if (text.length < 8) return null
        val year = text.substring(0, 4).toIntOrNull() ?: return null
        val month = text.substring(4, 6).toIntOrNull() ?: return null
        val day = text.substring(6, 8).toIntOrNull() ?: return null
        val date = runCatching { LocalDate(year, month, day) }.getOrNull() ?: return null
        if (text.length == 8) return LocalDateTime(date, LocalTime(0, 0))
        if (text.length < 13 || text[8] != 'T') return null
        val hour = text.substring(9, 11).toIntOrNull() ?: return null
        val minute = text.substring(11, 13).toIntOrNull() ?: return null
        val second = if (text.length >= 15) text.substring(13, 15).toIntOrNull() ?: 0 else 0
        return runCatching { LocalDateTime(date, LocalTime(hour, minute, second)) }.getOrNull()
    }

    private fun zoneOf(id: String?): TimeZone? =
        id?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { TimeZone.of(it) }.getOrNull() }

    /**
     * An RFC 5545 duration in seconds — plus Android's `P3600S`, which leaves out the `T`.
     *
     * ⚠ `M` is always minutes: RFC 5545 durations have no months, and Android never writes one.
     */
    internal fun parseDurationSeconds(raw: String): Long? {
        var text = raw.trim().uppercase()
        var sign = 1L
        when {
            text.startsWith("-") -> { sign = -1L; text = text.substring(1) }
            text.startsWith("+") -> text = text.substring(1)
        }
        if (!text.startsWith("P")) return null
        var total = 0L
        var digits = ""
        var sawUnit = false
        for (c in text.substring(1)) {
            when {
                c.isDigit() -> digits += c
                c == 'T' -> if (digits.isNotEmpty()) return null
                else -> {
                    val n = digits.toLongOrNull() ?: return null
                    total += n * when (c) {
                        'W' -> 604_800L
                        'D' -> 86_400L
                        'H' -> 3_600L
                        'M' -> 60L
                        'S' -> 1L
                        else -> return null
                    }
                    digits = ""
                    sawUnit = true
                }
            }
        }
        if (digits.isNotEmpty() || !sawUnit) return null
        return sign * total
    }
}
