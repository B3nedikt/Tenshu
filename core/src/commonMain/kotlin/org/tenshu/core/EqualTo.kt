package org.tenshu.core

import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType

infix fun Data.equalTo(other: Any): TBoolean {
    return if (this is RequestObject) {
        findOperation<EqualTo>(EqualTo.ID).equalTo(other)
    } else {
        equalToLogic(this, other)
    }
}

interface EqualTo : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun equalTo(other: Any): TBoolean

    companion object {
        const val ID = "tenshu/core/protocol/equalTo"

        val Schema = operation(
            id = ID,
            receiver = Data.Schema.toType(),
            params = {
                composite(id = Params.OTHER)
            }
        )
    }

    object Params {
        const val OTHER: String = "${ID}/other"
    }
}
