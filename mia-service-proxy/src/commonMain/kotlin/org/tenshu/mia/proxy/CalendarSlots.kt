package org.tenshu.mia.proxy

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.tenshu.calendar.Calendar
import org.tenshu.calendar.CalendarPreferences
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.get
import org.tenshu.preference.LongAdapter
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.Setting
import org.tenshu.preference.StringAdapter

/**
 * The defaults `SaveEvent` will apply, resolved **before** the confirmation so the cell can
 * show them (`mia-query-layer.md` MQ-D7 / MQ-D4a).
 *
 * ⚠⚠ **Reading the same preference rows the service reads, then passing the results back
 * explicitly.** That is what makes the confirmation *binding* rather than a good-faith
 * prediction: if the user changed the default calendar between seeing the cell and tapping it,
 * the event still lands where it said it would, because the resolved id travelled with the
 * pending action.
 *
 * ⚠ **No reminder is shown.** MQ-D4a's cell is supposed to name "the actual reminder", but
 * `SaveEventImplementation` creates none — reminders are separate `Reminder` rows written
 * elsewhere. Claiming one would be the exact failure the resolved-artifact rule exists to
 * prevent, so the cell says nothing about reminders until something actually attaches them.
 */
internal object CalendarSlots {

    /** `null` when no default calendar is set — `SaveEvent` would throw, so we refuse first. */
    fun defaultCalendar(executionContext: ExecutionContext): Pair<String, String>? {
        val id = StringAdapter
            .fromData(readRealmSetting(executionContext, CalendarPreferences.DefaultCalendarId.id))
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return id to (calendarName(executionContext, id) ?: id)
    }

    fun defaultDurationMinutes(executionContext: ExecutionContext): Long =
        LongAdapter
            .fromData(readRealmSetting(executionContext, CalendarPreferences.DefaultEventDurationMinutes.id))
            ?: CalendarPreferences.DefaultEventDurationMinutes.default

    /** ⚠ Adds the duration in the event's own zone, so a DST boundary moves the wall clock. */
    fun impliedEnd(start: LocalDateTime, minutes: Long, zone: TimeZone): LocalDateTime =
        start.toInstant(zone)
            .plus(minutes, DateTimeUnit.MINUTE, zone)
            .toLocalDateTime(zone)

    private fun calendarName(executionContext: ExecutionContext, calendarId: String): String? {
        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = Calendar.ID,
            keyValue = calendarId,
        )
        val stored = executionContext.database.getData(dataId) ?: return null
        val name = when (stored) {
            is Entity<*> -> runCatching { stored.getFieldValue<TString>(Calendar.Field.NAME) }.getOrNull()
            is Composite -> runCatching { stored.getField<TString>(Calendar.Field.NAME) }.getOrNull()
            else -> null
        }
        return name?.get()?.takeIf { it.isNotBlank() }
    }

    private fun readRealmSetting(executionContext: ExecutionContext, preferenceId: String): Data? {
        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = RealmSetting.ID,
            keyValue = preferenceId,
        )
        val stored = executionContext.database.getData(dataId) ?: return null
        return when (stored) {
            is Entity<*> -> runCatching { stored.getFieldValue<Data>(Setting.Field.VALUE) }.getOrNull()
            is Composite -> runCatching { stored.getField<Data>(Setting.Field.VALUE) }.getOrNull()
            else -> null
        }
    }
}
