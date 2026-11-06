package org.tenshu.calendar

import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.Composite
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * The **lossless interop sidecar** for an imported [Event] — the raw `VEVENT`
 * text it came from, kept verbatim (`calendar-app.md` §3 D1).
 *
 * ⭐ This is the decision that lets [Event] be a *native* composite rather than
 * an opaque iCal blob without that being a lossy choice. `:contact-service`
 * resolved the identical fork the same way — `Person` is deliberately not
 * vCard-shaped, and the raw vCard lives on a `ContactSource` — and the argument
 * transfers exactly:
 *
 * - A native [Event] is queryable through the chain DSL, mergeable field by
 *   field, and covered by the schema-evolution machinery. ⚠ It is also what
 *   makes the event store legible to Mia's query layer at all, since `MQ-D3`
 *   lowers every plan to a chain and a blob has nothing to lower onto.
 * - But a native schema models the properties we chose to model, and a real
 *   `.ics` file carries more — `ATTACH`, `ORGANIZER`, `X-` extensions, alarm
 *   shapes we do not support. Discarding those would make export lossy and
 *   would silently foreclose a future CalDAV phase.
 *
 * So: **native fields win, unknown properties survive.** An export re-emits the
 * stored [raw] with the fields we own written back over it, rather than
 * regenerating a `VEVENT` from scratch and dropping everything else.
 *
 * ⚠ A hand-created event has no source row. Absence is normal and means
 * "authored here", not "lost".
 */
interface EventSource : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Row key; equals the [Event.eventId] this describes. */
    val eventId: TString get() = getField(Field.EVENT_ID)

    /** The verbatim `VEVENT` block, `\n`-joined, unfolded but otherwise untouched. */
    val raw: TString get() = getField(Field.RAW)

    /**
     * Where it came from — a file name, or a subscription URL once CA-7 lands.
     * Free text, shown to the user; never parsed.
     */
    val origin: TString get() = getField(Field.ORIGIN)

    companion object {
        const val ID = "tenshu/calendar/eventSource"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(EventSourceDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Field.EVENT_ID)
                string(Field.RAW)
                string(Field.ORIGIN)
            }
        )

        fun create(
            context: TContext,
            eventId: String,
            raw: String,
            origin: String = "",
        ): EventSource = context.create(
            type = ID,
            fields = listOf(eventId, raw, origin),
        )
    }

    object Field {
        const val EVENT_ID = "$ID/eventId"
        const val RAW = "$ID/raw"
        const val ORIGIN = "$ID/origin"
    }
}

interface EventSourceDataIdProvider : CompositeDataIdProvider<EventSource> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/calendar/eventSource/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = EventSource.Field.EVENT_ID,
        )
    }
}

/**
 * Chain-DSL accessors (CA-6).
 *
 * ⚠ Added with the import that first *reads* a sidecar through a projection.
 * [EventSource] shipped in CA-1 with a type, a DTO, an Ro and a service relation
 * — and neither a writer nor these, because nothing had reason to touch a row
 * that nothing could create.
 */
val Entity<out EventSource>.eventId: TString get() = getFieldValue(EventSource.Field.EVENT_ID)

val Entity<out EventSource>.raw: TString get() = getFieldValue(EventSource.Field.RAW)

val Entity<out EventSource>.origin: TString get() = getFieldValue(EventSource.Field.ORIGIN)
