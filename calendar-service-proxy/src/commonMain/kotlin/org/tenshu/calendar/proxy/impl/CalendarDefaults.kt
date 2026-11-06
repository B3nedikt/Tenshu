package org.tenshu.calendar.proxy.impl

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.number
import kotlinx.datetime.plus
import org.tenshu.calendar.CalendarPreferences
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.number.TInteger
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.Setting

/**
 * Resolution of the §6.4 headless defaults — the values a UI would have
 * collected in a form and an assistant simply does not have.
 *
 * ⚠⚠ Read **directly off the `RealmSetting` row**, not through an IPC call into
 * `:preference-service`. Preference rows live in the same local store, so this
 * is an ordinary keyed read; routing it through the service would mean a proxy
 * calling a proxy, which the execution context has no way to do mid-operation.
 * The cost is that `:calendar-service-proxy` depends on `:preference-service`
 * for the schema, which is the smaller of the two evils and is why that
 * dependency exists.
 */
internal object CalendarDefaults {

    /**
     * The realm's default calendar id, or `""` when unset.
     *
     * ⚠ Deliberately **not** "pick the first calendar you find". An implicit
     * choice that depends on store iteration order would put events somewhere
     * different on two devices, and the user would have no way to see why.
     */
    fun defaultCalendarId(executionContext: ExecutionContext): String =
        readRealmSetting(executionContext, CalendarPreferences.DefaultCalendarId.id)
            ?.let { (it as? TString)?.get() }
            ?: CalendarPreferences.DefaultCalendarId.default

    /** The realm's default event length in minutes. */
    fun defaultDurationMinutes(executionContext: ExecutionContext): Long =
        readRealmSetting(executionContext, CalendarPreferences.DefaultEventDurationMinutes.id)
            ?.let { (it as? TInteger)?.get() }
            ?: CalendarPreferences.DefaultEventDurationMinutes.default

    /**
     * The end an event gets when the caller supplies only a start.
     *
     * ⚠ All-day is **one day**, per RFC 5545's exclusive `DTEND`, and does not
     * consult the duration preference — an "all-day event lasting 60 minutes"
     * is not a thing, and letting the preference reach this branch would make
     * one.
     */
    fun impliedEnd(startLocal: String, allDay: Boolean, durationMinutes: Long): String? {
        val start = parseLocal(startLocal) ?: return null
        val end = if (allDay) {
            LocalDateTime(start.date.plus(1, DateTimeUnit.DAY), LocalTime(0, 0))
        } else {
            start.plusMinutes(durationMinutes)
        }
        return formatLocal(end)
    }

    private fun readRealmSetting(executionContext: ExecutionContext, preferenceId: String) =
        executionContext.database
            .getData(
                DataId.StringDataId.create(
                    context = executionContext.context,
                    type = RealmSetting.ID,
                    keyValue = preferenceId,
                )
            )
            ?.let { (it as? RealmSetting)?.getField<org.tenshu.core.Data>(Setting.Field.VALUE) }

    private fun parseLocal(raw: String): LocalDateTime? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        runCatching { return LocalDateTime.parse(text) }
        runCatching { return LocalDateTime(LocalDate.parse(text), LocalTime(0, 0)) }
        return null
    }

    private fun formatLocal(value: LocalDateTime): String {
        val y = value.year.toString().padStart(4, '0')
        val mo = value.month.number.toString().padStart(2, '0')
        val d = value.day.toString().padStart(2, '0')
        val h = value.hour.toString().padStart(2, '0')
        val mi = value.minute.toString().padStart(2, '0')
        return "$y-$mo-${d}T$h:$mi"
    }

    private fun LocalDateTime.plusMinutes(minutes: Long): LocalDateTime {
        val total = date.toEpochDays().toLong() * 1440L + hour * 60L + minute + minutes
        val dayOffset = floorDivLong(total, 1440L)
        val minuteOfDay = (total - dayOffset * 1440L).toInt()
        return LocalDateTime(
            LocalDate.fromEpochDays(dayOffset.toInt()),
            LocalTime(minuteOfDay / 60, minuteOfDay % 60),
        )
    }

    /** ⚠ `Math.floorDiv` is JVM-only and would break the iOS and JS targets. */
    private fun floorDivLong(a: Long, b: Long): Long {
        val q = a / b
        return if (a % b != 0L && ((a xor b) < 0L)) q - 1 else q
    }
}
