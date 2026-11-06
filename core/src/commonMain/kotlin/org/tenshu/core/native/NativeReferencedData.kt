package org.tenshu.core.native

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite

interface NativeReferencedData : Composite {

    override val schema: CompositeSchema
        get() = Schema

    val type: NativeType
        get() = getField(Fields.TYPE)

    val dataId: TString
        get() = getField(Fields.DATA_ID)

    companion object {
        const val ID = "tenshu/core/native/referencedData"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                composite(Fields.TYPE)
                composite(Fields.DATA_ID)
            }
        )
    }

    object Fields {
        const val TYPE = "${ID}/type"
        const val DATA_ID = "${ID}/dataId"
    }
}
