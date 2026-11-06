package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.UnannouncedSessionError
import org.tenshu.core.toTString

data class UnannouncedSessionErrorDto(
    private val fieldValues: List<Data?>
) : UnannouncedSessionError, CompositeDto(fieldValues) {

    constructor(
        message: String
    ) : this(
        fieldValues = listOf(message.toTString())
    )

    override fun toString(): String = fieldValues.firstOrNull()?.toString() ?: "Unannounced session"
}
