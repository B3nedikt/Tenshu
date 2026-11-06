package org.tenshu.core.mapregion

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * A region bundle currently present in the proxy's canonical region store (D5) — the return
 * shape of [Installed]. The proxy owns the store; this is the per-device view the picker uses
 * to render install state and the app-side terminus uses to resolve a `pmtiles://file://…`
 * path.
 *
 * [localPath] is the proxy-side path of the bundle bytes; the app copies them over the IPC
 * pull range-source into its own `filesDir` on open (D6, slice 2b). [sha256]/[byteSize]/
 * [buildDate] mirror the catalog entry the bundle was verified against, so the picker can
 * offer an update when the catalog advertises a newer [buildDate].
 */
interface InstalledRegion : Composite {

    override val schema: CompositeSchema get() = Schema

    val regionId: TString
        get() = getField(Fields.REGION_ID)

    val name: TString
        get() = getField(Fields.NAME)

    /** Proxy-side path of the verified bundle bytes in the canonical store. */
    val localPath: TString
        get() = getField(Fields.LOCAL_PATH)

    val byteSize: TInteger
        get() = getField(Fields.BYTE_SIZE)

    val sha256: TString
        get() = getField(Fields.SHA256)

    /** The build stamp this installed copy came from — compared against the catalog for updates. */
    val buildDate: TString
        get() = getField(Fields.BUILD_DATE)

    companion object {
        const val ID = "tenshu/mapRegion/installedRegion"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.REGION_ID)
                string(Fields.NAME)
                string(Fields.LOCAL_PATH)
                int(Fields.BYTE_SIZE)
                string(Fields.SHA256)
                string(Fields.BUILD_DATE)
            }
        )

        fun create(
            context: TContext,
            regionId: String,
            name: String,
            localPath: String,
            byteSize: Long,
            sha256: String,
            buildDate: String,
        ): InstalledRegion = context.create(
            type = ID,
            fields = listOf(
                regionId.toTString(context),
                name.toTString(context),
                localPath.toTString(context),
                byteSize.toTInteger(context),
                sha256.toTString(context),
                buildDate.toTString(context),
            )
        )
    }

    object Fields {
        const val REGION_ID = "$ID/regionId"
        const val NAME = "$ID/name"
        const val LOCAL_PATH = "$ID/localPath"
        const val BYTE_SIZE = "$ID/byteSize"
        const val SHA256 = "$ID/sha256"
        const val BUILD_DATE = "$ID/buildDate"
    }
}
