package org.tenshu.calendar

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A calendar kept in step with one the OS already holds — *Keep in sync*
 * (`calendar-external-sources.md` CX-1b).
 *
 * ⚠⚠ **The binding is the election.** A CA-7 feed is fetched by every device for itself,
 * because every device can reach it. An OS calendar is visible on **one** device — the one with
 * the account — so [deviceId] names the only device that refreshes this mirror, and every other
 * device just sees the events arrive by sync. Two devices that both claim one source resolve by
 * last-writer-wins on this row: the loser stops refreshing, and nothing else is needed. This is
 * the `ExecutorClaim` problem `calendar-app.md` §4.2 refused for *reminders*, and correctly —
 * here exactly one device can do the work at all.
 *
 * ⚠ **Read-only is derived, not stored** — the [CalendarSubscription] rule: "some mirror points at
 * this calendar" is the whole test. A refresh makes the calendar match its source, so an edit
 * made here would be overwritten.
 *
 * ⚠ **Deleting this row stops the syncing and keeps the events**: the calendar becomes an
 * ordinary, editable one. Deleting the *calendar* takes this row with it.
 */
interface CalendarMirror : Composite {

    override val schema: CompositeSchema get() = Schema

    /**
     * The row key, **derived from the source** — see [idFor].
     *
     * ⭐ Two devices that start mirroring the same calendar offline converge on one row rather
     * than two calendars, and choosing *Keep in sync* on a second phone *rebinds* the mirror
     * there (calendar-external-sources.md Q6) instead of starting another.
     */
    val mirrorId: TString get() = getField(Field.MIRROR_ID)

    /** The [Calendar.calendarId] this mirror fills. */
    val calendarId: TString get() = getField(Field.CALENDAR_ID)

    /** The sync id of the one device that refreshes this mirror. */
    val deviceId: TString get() = getField(Field.DEVICE_ID)

    /** Which kind of store the source lives in, e.g. `ANDROID_PROVIDER`. */
    val sourceKind: TString get() = getField(Field.SOURCE_KIND)

    /** The source's stable key within its kind — what the reader finds it by again. */
    val sourceKey: TString get() = getField(Field.SOURCE_KEY)

    /** What the user knows the source as, e.g. `Work · me@gmail.com`. Free text. */
    val sourceLabel: TString get() = getField(Field.SOURCE_LABEL)

    /**
     * Whether Tenshu also reminds for these events.
     *
     * ⚠ Off by default (calendar-external-sources.md §5.7, Q2): the source's own app already
     * alerts for them, and Tenshu reminders fire on every device — on, by default, every alert
     * would arrive twice on the phone.
     */
    val remindersEnabled: TBoolean get() = getField(Field.REMINDERS_ENABLED)

    // ⚠⚠ No freshness stamp here — it is a [CalendarMirrorStamp] row of its own (P2-a, v4). Recording
    // a refresh re-saved this whole row, so a bound phone that missed a stop elsewhere brought the
    // mirror back. Nothing the bound device writes routinely may live on this row.

    companion object {
        const val ID = "tenshu/calendar/mirror"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                CalendarMirrorDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager),
            ),
            fields = {
                string(Field.MIRROR_ID)
                string(Field.CALENDAR_ID)
                string(Field.DEVICE_ID)
                string(Field.SOURCE_KIND)
                string(Field.SOURCE_KEY)
                string(Field.SOURCE_LABEL)
                boolean(Field.REMINDERS_ENABLED)
            }
        )

        fun create(
            context: TContext,
            mirrorId: String,
            calendarId: String,
            deviceId: String,
            sourceKind: String,
            sourceKey: String,
            sourceLabel: String,
            remindersEnabled: Boolean = false,
        ): CalendarMirror = context.create(
            type = ID,
            fields = listOf(
                mirrorId, calendarId, deviceId, sourceKind, sourceKey, sourceLabel,
                remindersEnabled,
            ),
        )

        /** The stable id for a source. ⚠ Never the device: the binding is a field, not the key. */
        fun idFor(sourceKind: String, sourceKey: String): String =
            "mirror-" + "$sourceKind|$sourceKey".hashCode().toUInt().toString(16)

        /**
         * The calendar a mirror fills.
         *
         * ⚠ Not the calendar a *Copy once* of the same source lands in: a copy is editable, and
         * turning it into a mirror would let the first refresh delete whatever the user added.
         */
        fun calendarIdFor(mirrorId: String): String = "$mirrorId-cal"
    }

    object Field {
        const val MIRROR_ID = "$ID/mirrorId"
        const val CALENDAR_ID = "$ID/calendarId"
        const val DEVICE_ID = "$ID/deviceId"
        const val SOURCE_KIND = "$ID/sourceKind"
        const val SOURCE_KEY = "$ID/sourceKey"
        const val SOURCE_LABEL = "$ID/sourceLabel"
        const val REMINDERS_ENABLED = "$ID/remindersEnabled"
    }
}

interface CalendarMirrorDataIdProvider : CompositeDataIdProvider<CalendarMirror> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/calendar/mirror/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = CalendarMirror.Field.MIRROR_ID,
        )
    }
}

val Entity<out CalendarMirror>.mirrorId: TString
    get() = getFieldValue(CalendarMirror.Field.MIRROR_ID)

val Entity<out CalendarMirror>.mirrorCalendarId: TString
    get() = getFieldValue(CalendarMirror.Field.CALENDAR_ID)

val Entity<out CalendarMirror>.mirrorDeviceId: TString
    get() = getFieldValue(CalendarMirror.Field.DEVICE_ID)

val Entity<out CalendarMirror>.sourceKind: TString
    get() = getFieldValue(CalendarMirror.Field.SOURCE_KIND)

val Entity<out CalendarMirror>.sourceKey: TString
    get() = getFieldValue(CalendarMirror.Field.SOURCE_KEY)

val Entity<out CalendarMirror>.sourceLabel: TString
    get() = getFieldValue(CalendarMirror.Field.SOURCE_LABEL)

val Entity<out CalendarMirror>.remindersEnabled: TBoolean
    get() = getFieldValue(CalendarMirror.Field.REMINDERS_ENABLED)
