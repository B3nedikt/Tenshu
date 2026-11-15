package org.tenshu.inference

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string

/**
 * One "this face is not [name]" the user said on THIS device (IN-6 F6c, name suggestions) — the
 * answer to *"Is this Anna?"* that must stop the question coming back.
 *
 * ⚠⚠ **Device-local, never realm data** (`photos-people-and-pets.md` §6a F6c.9 D2). It is a
 * statement about a person in a photograph, so it has the face templates' custody (threat model
 * A40 *Name suggestions*): held beside them in the proxy, cleared with them, returned only to the
 * app on this device. It appears in no relation and no `SyncBlock`.
 *
 * ⚠⚠ **Keyed by the face's BOX, not its index** (F6c.1): a `faceIndex` is a position in one
 * pipeline's output and a re-index renumbers it, while the box of the same face in the same
 * photograph moves little. A reader matches a stored box to a current face by overlap.
 *
 * ⚠ A return shape like [GroupedFace]: built per call, never stored as a row.
 */
interface RejectedFace : Composite {

    override val schema: CompositeSchema get() = Schema

    val filePath: TString
        get() = getField(Fields.FILE_PATH)

    /** The name the user said this face is NOT — exactly as the label carries it. */
    val name: TString
        get() = getField(Fields.NAME)

    val boxLeftPerMille: TInteger
        get() = getField(Fields.BOX_LEFT)

    val boxTopPerMille: TInteger
        get() = getField(Fields.BOX_TOP)

    val boxRightPerMille: TInteger
        get() = getField(Fields.BOX_RIGHT)

    val boxBottomPerMille: TInteger
        get() = getField(Fields.BOX_BOTTOM)

    companion object {
        const val ID = "tenshu/inference/rejectedFace"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.FILE_PATH)
                string(Fields.NAME)
                int(Fields.BOX_LEFT)
                int(Fields.BOX_TOP)
                int(Fields.BOX_RIGHT)
                int(Fields.BOX_BOTTOM)
            },
        )

        fun create(
            context: TContext,
            filePath: String,
            name: String,
            boxLeftPerMille: Int,
            boxTopPerMille: Int,
            boxRightPerMille: Int,
            boxBottomPerMille: Int,
        ): RejectedFace = context.create(
            type = ID,
            fields = listOf(
                filePath,
                name,
                // ⚠ LONGs on the wire, as `GroupedFace`'s.
                boxLeftPerMille.toLong(),
                boxTopPerMille.toLong(),
                boxRightPerMille.toLong(),
                boxBottomPerMille.toLong(),
            ),
        )
    }

    object Fields {
        const val FILE_PATH = "$ID/filePath"
        const val NAME = "$ID/name"
        const val BOX_LEFT = "$ID/boxLeftPerMille"
        const val BOX_TOP = "$ID/boxTopPerMille"
        const val BOX_RIGHT = "$ID/boxRightPerMille"
        const val BOX_BOTTOM = "$ID/boxBottomPerMille"
    }
}
