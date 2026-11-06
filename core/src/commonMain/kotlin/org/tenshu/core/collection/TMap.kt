package org.tenshu.core.collection

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.list
import org.tenshu.core.schema.of
import org.tenshu.core.schema.type
import org.tenshu.core.schema.typeOf
import org.tenshu.core.schema.typeVar

interface TMap<K : Data, V : Data> : Composite, Map<K, V> {

    override val schema: CompositeSchema
        get() = Schema

    val pairs: TList<TPair<K, V>>
        get() = getField(Fields.PAIRS)

    companion object {
        const val ID = "tenshu/core/map"

        val Schema: CompositeSchema = composite(
            id = ID,
            typeParams = {
                type("K")
                type("V")
            },
            fields = {
                list(
                    id = Fields.PAIRS,
                    of = typeOf(TList.Schema).of(
                        typeOf(TPair.Schema).of(typeVar("K"), typeVar("V"))
                    )
                )
            }
        )
    }

    object Fields {
        const val PAIRS = "$ID/pairs"
    }
}
