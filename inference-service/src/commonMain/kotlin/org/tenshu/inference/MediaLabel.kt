package org.tenshu.inference

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.ConflictStrategy
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * **What a model thinks is in one file** (IN-3, `inference-service.md` IN-D12) — one row per
 * `(file, capability, label)`.
 *
 * ⚠⚠ **A row, never a property on `File`.** Putting labels on `File` would reshape the platform's
 * hottest composite for one feature, and `:file-service` would have to learn what a model is. The
 * `PlaceRole` argument applies verbatim: a separately-keyed row cannot be erased by an unrelated
 * write to the thing it describes.
 *
 * ⚠ **Labels sync; embeddings do not** (IN-D5). A label is small, durable and useful on every
 * device the moment one device derives it. A vector is large, model-specific and re-derivable from
 * bytes each device already has — syncing it would push megabytes per photo through the relay to
 * save work that is local anyway.
 *
 * ⚠⚠ [producedBy] is what makes a better model cheap. Without it, improving the tagger means
 * discarding every row — including ones the user corrected — because nothing can say which came
 * from where. With it, a re-index touches only the rows whose stamp is behind.
 *
 * ⚠ [confidence] is stored as **per-mille** (0–1000), not a float: the schema DSL's numeric field
 * is an integer, and a float smuggled through a string would sort and compare wrong on the far
 * side of the wire.
 */
interface MediaLabel : Composite {

    override val schema: CompositeSchema get() = Schema

    /** `"$file/$capability/$label"` — the primary key, so re-indexing folds rather than piling up. */
    val labelKey: TString
        get() = getField(Fields.LABEL_KEY)

    /** The file this is about — `:file-service`'s own path, kept opaque here. */
    val filePath: TString
        get() = getField(Fields.FILE_PATH)

    /** Which capability produced it ([InferenceCapability.id]) — a label is not context-free. */
    val capability: TString
        get() = getField(Fields.CAPABILITY)

    val label: TString
        get() = getField(Fields.LABEL)

    /** 0–1000. See the class note for why not a float. */
    val confidencePerMille: TInteger
        get() = getField(Fields.CONFIDENCE_PER_MILLE)

    /** [ModelProvenance.asString] — what produced this, so a re-index can skip what is current. */
    val producedBy: TString
        get() = getField(Fields.PRODUCED_BY)

    companion object {
        const val ID = "tenshu/inference/mediaLabel"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                MediaLabelDataIdProvider.Schema,
                // ⚠ Eager: a label is tiny and immediately useful on every device — the whole
                // reason IN-D5 syncs labels while refusing to sync vectors.
                Syncable(SyncStrategy.Eager, ConflictStrategy.LastWriterWins),
            ),
            fields = {
                string(Fields.LABEL_KEY)
                string(Fields.FILE_PATH)
                string(Fields.CAPABILITY)
                string(Fields.LABEL)
                int(Fields.CONFIDENCE_PER_MILLE)
                string(Fields.PRODUCED_BY)
            },
        )

        fun create(
            context: TContext,
            filePath: String,
            capability: InferenceCapability,
            label: String,
            confidencePerMille: Int,
            producedBy: ModelProvenance,
        ): MediaLabel = context.create(
            type = ID,
            fields = listOf(
                labelKey(filePath, capability, label),
                filePath,
                capability.id,
                label,
                // ⚠ The DSL's `int` field is a LONG on the wire; passing an Int fails at save time
                // with "Value 0 is not a long", not at compile time.
                confidencePerMille.toLong(),
                producedBy.asString(),
            ),
        )

        /** Built here so a reader can construct the key without materialising the row. */
        fun labelKey(filePath: String, capability: InferenceCapability, label: String): String =
            "$filePath/${capability.id}/$label"
    }

    object Fields {
        const val LABEL_KEY = "$ID/labelKey"
        const val FILE_PATH = "$ID/filePath"
        const val CAPABILITY = "$ID/capability"
        const val LABEL = "$ID/label"
        const val CONFIDENCE_PER_MILLE = "$ID/confidencePerMille"
        const val PRODUCED_BY = "$ID/producedBy"
    }
}

interface MediaLabelDataIdProvider : CompositeDataIdProvider<MediaLabel> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/inference/mediaLabel/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = MediaLabel.Fields.LABEL_KEY,
        )
    }
}

// Projected-row accessors, added when Photos' search became the **first** consumer of these rows
// (`photos-app.md` S2 / §18). ⚠ A `select { fields(...) }` chain yields `Entity<MediaLabel>`, not
// `MediaLabel`, so a reader needs these rather than the interface's own getters — the same gap
// `Place` had until PH-3, and the `Calendar` block is the precedent for keeping the set complete
// rather than adding one accessor per caller.

val Entity<out MediaLabel>.labelKey: TString
    get() = getFieldValue(MediaLabel.Fields.LABEL_KEY)

val Entity<out MediaLabel>.filePath: TString
    get() = getFieldValue(MediaLabel.Fields.FILE_PATH)

val Entity<out MediaLabel>.capability: TString
    get() = getFieldValue(MediaLabel.Fields.CAPABILITY)

val Entity<out MediaLabel>.label: TString
    get() = getFieldValue(MediaLabel.Fields.LABEL)

/** 0–1000. See [MediaLabel.confidencePerMille] for why this is not a float. */
val Entity<out MediaLabel>.confidencePerMille: TInteger
    get() = getFieldValue(MediaLabel.Fields.CONFIDENCE_PER_MILLE)

val Entity<out MediaLabel>.producedBy: TString
    get() = getFieldValue(MediaLabel.Fields.PRODUCED_BY)
