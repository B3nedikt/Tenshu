package org.tenshu.core.mapregion

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.toTByteArray
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

/**
 * One piece of railway track, as it crosses the proxy → app boundary
 * (`docs/proposals/timeline-movement-capture.md` §13.3, M9-4).
 *
 * The app needs rail geometry to map-match a movement trace, but it has no `INTERNET` and cannot
 * read tiles: Control owns tile egress and is the only side with a gzip implementation. So the proxy
 * does the whole tile pipeline — cover the bbox, decompress, decode MVT, filter to *running* track —
 * and hands back only what matching consumes.
 *
 * ⚠ The **trace never leaves the app.** Sending the user's route to Control for matching there would
 * have been less data on the wire, but this direction keeps the most sensitive artefact — where the
 * person actually went — inside one process. The proxy learns only a bounding box.
 *
 * [polyline] is `LonLatPolyline`-encoded (E7 zig-zag deltas) rather than a list of coordinate
 * composites: rail geometry is dense and locally smooth, so the delta encoding costs a few bytes per
 * point where a composite-per-point would cost tens, and a bbox around a real journey can pull in
 * dozens of lines that all ride one IPC message.
 */
interface RailLineGeometry : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The `railway` value — `rail` / `tram` / `light_rail` / `subway` / `narrow_gauge`. */
    val kind: TString
        get() = getField(Fields.KIND)

    /** `LonLatPolyline`-encoded geometry. */
    val polyline: TByteArray
        get() = getField(Fields.POLYLINE)

    companion object {
        const val ID = "tenshu/mapRegion/railLineGeometry"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.KIND)
                byteArray(Fields.POLYLINE)
            }
        )

        fun create(context: TContext, kind: String, polyline: ByteArray): RailLineGeometry =
            context.create(
                type = ID,
                fields = listOf(kind, polyline.toTByteArray(context)),
            )
    }

    object Fields {
        const val KIND = "$ID/kind"
        const val POLYLINE = "$ID/polyline"
    }
}
