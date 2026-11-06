package org.tenshu.core.file

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.number.TInteger
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant

/**
 * One image file, seen through the narrow window a photo timeline needs
 * (`docs/proposals/timeline-photos.md` T-P2): where it is, when it was captured, and whether it
 * came from a folder the user chose to back up.
 *
 * ⚠ **Never stored, never synced.** This is the return shape of
 * [PhotosCapturedBetween][org.tenshu.core.file.system.PhotosCapturedBetween] and nothing else — it
 * carries no `DataIdProvider`, no `Syncable` trait, and no row anywhere. A photo *is* a `File`; this
 * is a projection of one, computed per call, so it can never disagree with the file it describes or
 * outlive it. Deriving beats storing here for the same reason it does one layer up: both halves are
 * already in the store, and a stored copy could only drift.
 *
 * Deliberately not a `File`: the caller wants three scalars per photo, and shipping whole entities
 * across IPC to read three fields is what this op exists to stop.
 */
interface CapturedPhoto : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Absolute path of the image file, e.g. `/Backup/Phone/Camera/IMG_0421.jpg`. */
    val path: TString
        get() = getField(Fields.PATH)

    /**
     * When the photograph was taken, as `File.CreatedAt` reports it.
     *
     * ⚠ That is the **capture** time for a backed-up gallery item — Android seeds `CreatedAt` from
     * MediaStore `DATE_TAKEN` — and the source file's mtime for anything that arrived another way.
     * A file carrying no `CreatedAt` at all cannot be placed in time and is never returned here.
     */
    val capturedAt: UnixInstant
        get() = getField(Fields.CAPTURED_AT)

    /**
     * Whether this file sits under [BACKUP_ROOT] — i.e. it reached the realm because the user
     * pointed a folder backup at the album it came from.
     *
     * The closest thing to provenance the file plane actually has, and it is a real user signal
     * rather than a guess about pixels or filenames: somebody chose that bucket. It ranks results
     * (a photograph you took beats a screenshot that happens to share the minute) but never filters
     * them — a moment whose only image is a screenshot still shows something.
     */
    val backedUp: TBoolean
        get() = getField(Fields.BACKED_UP)

    /**
     * Where the photograph was taken, in E7 degrees, or `0`/`0` when unknown (T-P4).
     *
     * ⚠ `(0, 0)` is the Atlantic and is also what a camera with no fix writes, so the rest of this
     * codebase already reads it as "no coordinate" — agreeing here keeps one meaning for one value
     * and spares the wire two optional fields.
     */
    val latE7: TInteger
        get() = getField(Fields.LAT_E7)

    val lngE7: TInteger
        get() = getField(Fields.LNG_E7)

    companion object {
        const val ID = "tenshu/file/capturedPhoto"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.PATH)
                timestamp(Fields.CAPTURED_AT)
                boolean(Fields.BACKED_UP)
                int(Fields.LAT_E7)
                int(Fields.LNG_E7)
            },
        )

        fun create(
            context: TContext,
            path: String,
            capturedAtMs: Long,
            backedUp: Boolean,
            latE7: Long = 0,
            lngE7: Long = 0,
        ): CapturedPhoto = context.create(
            type = ID,
            fields = listOf(path, capturedAtMs, backedUp, latE7, lngE7),
        )
    }

    object Fields {
        const val PATH = "$ID/path"
        const val CAPTURED_AT = "$ID/capturedAt"
        const val BACKED_UP = "$ID/backedUp"
        const val LAT_E7 = "$ID/latE7"
        const val LNG_E7 = "$ID/lngE7"
    }
}

/**
 * Where Drive's folder backup puts what it uploads: `/Backup/<device>/<bucket>`.
 *
 * ⚠ It lives here, in the module that owns the path namespace, because **two** components now need
 * to recognise it — Drive, which writes there (`SyncedFolderController.destinationFor`), and the
 * photo read, which uses it as its provenance signal. A convention that two modules must agree on
 * and that neither owns is exactly the join that degrades silently when one of them changes it.
 */
const val BACKUP_ROOT: String = "/Backup"
