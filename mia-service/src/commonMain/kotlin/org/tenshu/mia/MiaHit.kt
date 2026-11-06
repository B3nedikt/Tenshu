package org.tenshu.mia

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.entity.DataId
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant

/**
 * One row Mia is citing (`mia-query-layer.md` MQ-D1) — something that really exists, which
 * the user can tap through to the app that owns it.
 *
 * ⚠⚠ **One shape for every source, discriminated by [target]'s type, and that is the MQ-2
 * decision as TT-1c revised it.**
 * MQ-1 shipped a file-specific `MiaFileHit`; carrying that forward would have meant a new
 * list field on [MiaAnswer] per source — five positional fields on a composite that grows
 * every time Mia learns to read something, each one a reshape of a wire type. Collapsing to
 * a discriminated hit makes **adding a source cost zero wire shape**, which is what a layer
 * whose whole job is to widen needs.
 *
 * ⭐ The collapse is honest rather than lossy because of what a citation is *for*: the chat
 * cells render a title, a subtitle and a glyph, and the tap-through needs an address. Nothing
 * downstream wanted the typed richness — `PlaceCard` wants a name and a detail line, exactly
 * like a file row does.
 *
 * ⚠ Never stored, never synced — a return shape only. No `DataIdProvider`, no `Syncable`
 * trait, no row anywhere; the `CapturedPhoto` precedent.
 */
interface MiaHit : Composite {

    override val schema: CompositeSchema get() = Schema

    /**
     * The row's own identity — what a tap opens (`control-cross-app-open.md` CO-D1, TT-1c).
     *
     * ⚠⚠ **This replaced a `kind` string and a `reference` string, and collapsing them was the
     * point.** A [DataId] carries a `type` *and* a key, so it addresses the row **and** says what
     * kind of thing it is — which is everything the two fields did, in the vocabulary the platform
     * already uses to name every stored row. MQ-2's own rule was that *"Mia never mints a routing
     * vocabulary of its own"*; a bare `reference` string was that rule half-kept, because only the
     * pairing with `kind` made it resolvable and nothing enforced the pairing.
     *
     * ⭐ It is also what makes the citation **tappable at all**. `OpenTarget` takes a `DataId` and
     * nothing else, so a hit that carried a path and the word "file" had to be re-assembled into
     * one by a caller guessing at the mapping — exactly the "second naming system" CO-D1 rejects.
     *
     * ⚠ The chat cell's glyph now switches on `target.type`, which is a schema constant
     * (`tenshu/file`, `tenshu/timeline/place`, …) rather than Mia's own six-word enum.
     */
    val target: DataId
        get() = getField(Fields.TARGET)

    /** The row's own name, as its app would show it. */
    val title: TString
        get() = getField(Fields.TITLE)

    /**
     * One line of context under [title] — a file's folder, a place's city, a workout's
     * distance, a device's room and power state. May be empty; never a duplicate of
     * [title].
     */
    val subtitle: TString
        get() = getField(Fields.SUBTITLE)

    /**
     * When this row happened, or epoch zero when it carries no time at all.
     *
     * ⚠ Epoch zero reads as **unknown**, not 1970 — a caller that sorts or groups by date
     * must treat it as absent. An optional field would cost an optional slot on a positional
     * composite for a value every consumer would have to null-check anyway.
     */
    val at: UnixInstant
        get() = getField(Fields.AT)

    companion object {
        const val ID = "tenshu/mia/hit"

        /** [at] when the underlying row carries no timestamp. */
        const val UNKNOWN_AT_MS = 0L

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                composite(Fields.TARGET, type = DataId.Schema)
                string(Fields.TITLE)
                string(Fields.SUBTITLE)
                timestamp(Fields.AT)
            },
        )

        fun create(
            context: TContext,
            target: DataId,
            title: String,
            subtitle: String = "",
            atMs: Long = UNKNOWN_AT_MS,
        ): MiaHit = context.create(
            type = ID,
            fields = listOf(target, title, subtitle, atMs),
        )
    }

    object Fields {
        const val TARGET = "$ID/target"
        const val TITLE = "$ID/title"
        const val SUBTITLE = "$ID/subtitle"
        const val AT = "$ID/at"
    }
}
