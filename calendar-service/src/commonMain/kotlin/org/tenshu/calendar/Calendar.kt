package org.tenshu.calendar

import org.tenshu.core.TBoolean
import org.tenshu.core.number.TInteger
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.Composite
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A named, coloured collection of [Event]s — "Work", "Personal", "Family"
 * (`calendar-app.md` §2.1 row 7, §3 D4).
 *
 * ⚠ A calendar is a **row, not a realm**. Sharing a calendar with a contact
 * (§2.3) is a v2 concern, and when it lands it means moving these rows into a
 * shared realm — a migration of the *reference*, which `DataIdMigrator`
 * (`file-content-addressing.md` Phase 2) already exists to do. Modelling
 * calendars as realms now would buy nothing and cost the simple case.
 *
 * [colorArgb] is a plain packed ARGB int rather than a theme token: a calendar
 * colour is user-chosen from an arbitrary palette and has to survive round
 * trips through ICS (`X-APPLE-CALENDAR-COLOR` and friends), so it cannot be an
 * enum over our own design system.
 *
 * ⚠ [sensitive] is the **lock-screen control** settled as Q10: reminders for a
 * sensitive calendar are posted without their title, location or notes, so a
 * "Health" calendar does not announce itself to everyone who can see the phone.
 * Details are shown by default because a reminder that will not say what it is
 * for reads as broken; this is the opt-in for the cases where that trade is
 * wrong. The threat-model amendment lands with CA-5.
 *
 * ## ⭐ What is *not* here: visibility
 *
 * An earlier draft carried a `visible` boolean on this row. It was wrong twice
 * over, and the split it forced is worth stating as the rule for this schema:
 * **what a calendar *is* syncs; how *this device* displays it does not.**
 *
 * - It was a `Syncable(Eager)` field described as per-device UI state — so
 *   hiding *Work* on the phone would have hidden it on the desktop.
 * - ⚠ And on this platform it was the cheapest possible gesture wired to the
 *   most expensive plumbing: every toggle would mint an encrypted block, push
 *   it to the relay and replay it on every paired device.
 *
 * Visibility now lives in `CalendarPreferences.HiddenCalendars`, a DEVICE-scoped
 * preference. ⚖️ Note there is no industry standard to conform to here — Google
 * syncs visibility per account (`CalendarList.selected`), Apple keeps it
 * per-client, and CalDAV specifies nothing — so this is a choice, made on the
 * sync-cost argument above.
 */
interface Calendar : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Stable opaque id; the row key, and what [Event.calendarId] points at. */
    val calendarId: TString get() = getField(Field.CALENDAR_ID)

    val name: TString get() = getField(Field.NAME)

    /** Packed ARGB, e.g. `0xFF4285F4.toInt()`. */
    val colorArgb: TInteger get() = getField(Field.COLOR_ARGB)

    /** Q10: suppress event text in this calendar's OS reminders. */
    val sensitive: TBoolean get() = getField(Field.SENSITIVE)

    companion object {
        const val ID = "tenshu/calendar/calendar"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(CalendarDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Field.CALENDAR_ID)
                string(Field.NAME)
                int(Field.COLOR_ARGB)
                boolean(Field.SENSITIVE)
            }
        )

        fun create(
            context: TContext,
            calendarId: String,
            name: String,
            colorArgb: Int,
            sensitive: Boolean = false,
        ): Calendar = context.create(
            type = ID,
            // ⚠ `colorArgb.toLong()`, not the bare `Int`. `TInteger` is
            // Long-backed, and the data factory rejects an `Int` for that field
            // with "Value … is not a long" — a runtime failure, not a compile
            // one, because `create` takes untyped `Any?` field values. The
            // public parameter stays `Int` because that is what an ARGB colour
            // is; only the storage conversion happens here.
            fields = listOf(calendarId, name, colorArgb.toLong(), sensitive),
        )
    }

    object Field {
        const val CALENDAR_ID = "$ID/calendarId"
        const val NAME = "$ID/name"
        const val COLOR_ARGB = "$ID/colorArgb"
        const val SENSITIVE = "$ID/sensitive"
    }
}

/**
 * Field accessors for a projected [Calendar] row.
 *
 * ⚠ A `select { fields(...) }` chain yields `Entity<Calendar>`, not `Calendar`,
 * so a reader needs these rather than the interface's own getters — the
 * `Person` precedent.
 */
val Entity<out Calendar>.calendarId: TString get() = getFieldValue(Calendar.Field.CALENDAR_ID)

val Entity<out Calendar>.name: TString get() = getFieldValue(Calendar.Field.NAME)

val Entity<out Calendar>.colorArgb: TInteger get() = getFieldValue(Calendar.Field.COLOR_ARGB)

val Entity<out Calendar>.sensitive: TBoolean get() = getFieldValue(Calendar.Field.SENSITIVE)


interface CalendarDataIdProvider : CompositeDataIdProvider<Calendar> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/calendar/calendar/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = Calendar.Field.CALENDAR_ID,
        )
    }
}
