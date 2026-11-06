package org.tenshu.notifications.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.util.toTString
import org.tenshu.core.notifications.DismissedRequest
import org.tenshu.core.schema.CompositeSchema

internal class DismissedRequestDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), DismissedRequest {

    override val schema: CompositeSchema
        get() = DismissedRequest.Schema

    constructor(requestId: String) : this(
        fieldValues = listOf(requestId.toTString())
    )
}
