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
 * One face, and which group this device currently thinks it belongs to (IN-6, F3).
 *
 * ⚠⚠ **Not a person, and deliberately not called one.** IN-D5's central move is that a *person* is
 * a string the user typed, stored as a [MediaLabel]; a **group** is scaffolding the device rebuilt
 * this second from device-local templates and will happily rebuild differently after the next index
 * pass. Naming this type `Person` would invite a caller to persist [groupId], and cluster identity
 * becoming durable is precisely what forces templates onto the wire — the thing IN-D5 exists to
 * prevent. Nothing here is `Syncable`, for the same reason [SimilarPhoto] is not.
 *
 * ⚠⚠ **One row per face, not one row per group.** It mirrors [MediaLabel]'s grain: a caller groups
 * by [groupId] itself. A nested "group with its photos" shape would need a list-valued field, and
 * would still be re-derived per call — so the flat form costs a `groupBy` and buys the same
 * positional simplicity every composite here already has.
 *
 * ⚠ [groupId] is **stable only within one call.** It is derived from the group's own members, so an
 * index pass that adds a photograph can renumber everything. A caller may route on it for as long
 * as it holds the list it came from (`Destination.Smart` does exactly this for `things`); writing it
 * to disk, or into a synced row, is a bug that will look like faces changing identity.
 *
 * ⚠ The path is `:file-service`'s and stays opaque, exactly as [MediaLabel.filePath] does.
 */
interface GroupedFace : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Opaque, and see the class note: valid for this call only. */
    val groupId: TString
        get() = getField(Fields.GROUP_ID)

    val filePath: TString
        get() = getField(Fields.FILE_PATH)

    /**
     * Which face in that photograph, in the detector's own order.
     *
     * ⚠ Needed because one photograph can contribute faces to two different groups — a couple in
     * one frame is the ordinary case, not the corner one — so [filePath] alone does not identify a
     * member.
     */
    val faceIndex: TInteger
        get() = getField(Fields.FACE_INDEX)

    /**
     * Where the face is in the photograph, in **thousandths of the image's width and height**
     * (IN-6 F6a) — what a person header crops its cover face from.
     *
     * ⚠ Per mille, for [MediaLabel.confidencePerMille]'s reason: composites here carry no floats.
     * A thousandth of a 2048 px frame is two pixels, far finer than a cover crop needs. ⚠ Position,
     * never identity: a box says where a face is, not whose it is, so A40's "no biometric data
     * crosses IPC" still holds.
     */
    val boxLeftPerMille: TInteger
        get() = getField(Fields.BOX_LEFT)

    val boxTopPerMille: TInteger
        get() = getField(Fields.BOX_TOP)

    val boxRightPerMille: TInteger
        get() = getField(Fields.BOX_RIGHT)

    val boxBottomPerMille: TInteger
        get() = getField(Fields.BOX_BOTTOM)

    /**
     * How many faces the DETECTOR found in the photograph, across **every** group — including faces
     * in groups this call dropped for its limit or for `minFaces`.
     *
     * ⚠⚠ **Detected, not present.** The detector is MediaPipe's short-range model, tuned for arm's
     * length: in a group selfie it finds the face in front and misses the people behind. F6a built
     * "Only them" on this count and the device check (2026-09-27) filed photos of five and seven
     * people under it, so the chip was pulled. The field stays — counted on the proxy because a
     * client counting returned rows would also miss faces in dropped groups — for when a detector
     * that sees the people behind makes it mean what it says (`photos-people-and-pets.md` §6a).
     */
    val facesInPhoto: TInteger
        get() = getField(Fields.FACES_IN_PHOTO)

    companion object {
        const val ID = "tenshu/inference/groupedFace"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.GROUP_ID)
                string(Fields.FILE_PATH)
                int(Fields.FACE_INDEX)
                // ⚠ F6a, appended — positional composites make even an appended field a reshape,
                // which is why `InferenceService` moved to version 2 with them (House Rule 9).
                int(Fields.BOX_LEFT)
                int(Fields.BOX_TOP)
                int(Fields.BOX_RIGHT)
                int(Fields.BOX_BOTTOM)
                int(Fields.FACES_IN_PHOTO)
            },
        )

        fun create(
            context: TContext,
            groupId: String,
            filePath: String,
            faceIndex: Int,
            boxLeftPerMille: Int,
            boxTopPerMille: Int,
            boxRightPerMille: Int,
            boxBottomPerMille: Int,
            facesInPhoto: Int,
        ): GroupedFace = context.create(
            type = ID,
            fields = listOf(
                groupId,
                filePath,
                // ⚠ LONGs on the wire; an Int compiles and fails at save time (IN-3a found this).
                faceIndex.toLong(),
                boxLeftPerMille.toLong(),
                boxTopPerMille.toLong(),
                boxRightPerMille.toLong(),
                boxBottomPerMille.toLong(),
                facesInPhoto.toLong(),
            ),
        )
    }

    object Fields {
        const val GROUP_ID = "$ID/groupId"
        const val FILE_PATH = "$ID/filePath"
        const val FACE_INDEX = "$ID/faceIndex"
        const val BOX_LEFT = "$ID/boxLeftPerMille"
        const val BOX_TOP = "$ID/boxTopPerMille"
        const val BOX_RIGHT = "$ID/boxRightPerMille"
        const val BOX_BOTTOM = "$ID/boxBottomPerMille"
        const val FACES_IN_PHOTO = "$ID/facesInPhoto"
    }
}
