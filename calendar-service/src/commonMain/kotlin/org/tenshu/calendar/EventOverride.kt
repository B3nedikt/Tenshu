package org.tenshu.calendar

import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.Composite
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.toTString
import org.tenshu.core.trait.Syncable

/**
 * One occurrence of a recurring [Event] that differs from what the rule would
 * produce — "this Tuesday's standup is at 11:00, in room B".
 *
 * ⭐ This is the half of `calendar-app.md` §3 D2 that makes *edit this
 * occurrence* representable at all, and it is why occurrences must not be
 * materialised: a store of expanded rows has nowhere to put the distinction
 * between "the series says X" and "this one was changed to Y", and so cannot
 * answer *"edit all future occurrences"* afterwards.
 *
 * ⚠ **[recurrenceId] identifies the occurrence by its ORIGINAL start**, not by
 * index and not by its new start. Index would renumber the moment an earlier
 * occurrence is deleted; the new start cannot identify what it replaced. This
 * is the RFC 5545 `RECURRENCE-ID` semantic, and it is load-bearing.
 *
 * A *deleted* occurrence is not an override — it is an entry in
 * [Event.exceptionDates], because there is nothing left to carry the fields.
 *
 * ## Per-field optionality: **`null` means "not overridden"**
 *
 * Every carried field is `isOptional = true`, so a row states exactly which
 * values it replaces and inherits the rest from the series. Reading one back is
 * `getOptionalField`, mirroring `IngestSource`'s optional columns.
 *
 * ⚠⚠ An earlier draft used two booleans (`overridesTime` / `overridesDetails`)
 * to say which *halves* were meaningful. That was worse in two ways, and both
 * are worth recording so it does not come back: it was a hand-rolled optionality
 * mechanism sitting beside the built-in one, and — the real defect — it was
 * **coarser than reality**. "This occurrence moved, kept its title, changed its
 * location" is an ordinary edit and the bundled boolean could not express it.
 *
 * ⚠ `null` and `""` are now genuinely different here, and the distinction is
 * load-bearing: `null` = *inherit the series' title*; `""` = *this occurrence
 * has no title, overriding one that exists*. A consumer that coalesces the two
 * silently loses the second.
 */
interface EventOverride : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Row key: `"$eventId|$recurrenceId"`. */
    val overrideId: TString get() = getField(Field.OVERRIDE_ID)

    /** The master [Event.eventId] this occurrence belongs to. */
    val eventId: TString get() = getField(Field.EVENT_ID)

    /** ISO local date-time of the occurrence's **original** start. */
    val recurrenceId: TString get() = getField(Field.RECURRENCE_ID)

    /** Replacement start, or `null` to keep the occurrence's computed one. */
    val startLocal: TString? get() = getOptionalField(Field.START_LOCAL)

    /** Replacement end, or `null` to keep the series' duration. */
    val endLocal: TString? get() = getOptionalField(Field.END_LOCAL)

    /** Replacement title, or `null` to inherit the series'. */
    val title: TString? get() = getOptionalField(Field.TITLE)

    /** Replacement location, or `null` to inherit the series'. */
    val location: TString? get() = getOptionalField(Field.LOCATION)

    /** Replacement notes, or `null` to inherit the series'. */
    val notes: TString? get() = getOptionalField(Field.NOTES)

    companion object {
        const val ID = "tenshu/calendar/eventOverride"

        /** Builds the [overrideId] key. Single-sourced so both sides agree. */
        fun keyOf(eventId: String, recurrenceId: String): String = "$eventId|$recurrenceId"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(EventOverrideDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Field.OVERRIDE_ID)
                string(Field.EVENT_ID)
                string(Field.RECURRENCE_ID)
                string(Field.START_LOCAL, isOptional = true)
                string(Field.END_LOCAL, isOptional = true)
                string(Field.TITLE, isOptional = true)
                string(Field.LOCATION, isOptional = true)
                string(Field.NOTES, isOptional = true)
            }
        )

        @Suppress("LongParameterList")
        fun create(
            context: TContext,
            eventId: String,
            recurrenceId: String,
            startLocal: String? = null,
            endLocal: String? = null,
            title: String? = null,
            location: String? = null,
            notes: String? = null,
        ): EventOverride = context.create(
            type = ID,
            fields = listOf(
                keyOf(eventId, recurrenceId).toTString(context),
                eventId.toTString(context),
                recurrenceId.toTString(context),
                startLocal?.toTString(context),
                endLocal?.toTString(context),
                title?.toTString(context),
                location?.toTString(context),
                notes?.toTString(context),
            ),
        )
    }

    object Field {
        const val OVERRIDE_ID = "$ID/overrideId"
        const val EVENT_ID = "$ID/eventId"
        const val RECURRENCE_ID = "$ID/recurrenceId"
        const val START_LOCAL = "$ID/startLocal"
        const val END_LOCAL = "$ID/endLocal"
        const val TITLE = "$ID/title"
        const val LOCATION = "$ID/location"
        const val NOTES = "$ID/notes"
    }
}

/**
 * Field accessors for a projected [EventOverride] row.
 *
 * ⚠⚠ The optional ones are **nullable on purpose**: `null` means the override
 * inherits that field from the series, and `""` means it overrides with an empty
 * value. A non-null accessor here would erase that distinction at the read edge,
 * undoing the schema's per-field optionality.
 */
val Entity<out EventOverride>.overrideId: TString
    get() = getFieldValue(EventOverride.Field.OVERRIDE_ID)

val Entity<out EventOverride>.eventId: TString
    get() = getFieldValue(EventOverride.Field.EVENT_ID)

val Entity<out EventOverride>.recurrenceId: TString
    get() = getFieldValue(EventOverride.Field.RECURRENCE_ID)

val Entity<out EventOverride>.startLocal: TString?
    get() = getFieldValue(EventOverride.Field.START_LOCAL)

val Entity<out EventOverride>.endLocal: TString?
    get() = getFieldValue(EventOverride.Field.END_LOCAL)

val Entity<out EventOverride>.title: TString?
    get() = getFieldValue(EventOverride.Field.TITLE)

val Entity<out EventOverride>.location: TString?
    get() = getFieldValue(EventOverride.Field.LOCATION)

val Entity<out EventOverride>.notes: TString?
    get() = getFieldValue(EventOverride.Field.NOTES)


interface EventOverrideDataIdProvider : CompositeDataIdProvider<EventOverride> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/calendar/eventOverride/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = EventOverride.Field.OVERRIDE_ID,
        )
    }
}
