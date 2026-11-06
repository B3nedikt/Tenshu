package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.toTString
import org.tenshu.identity.EntitlementUnverifiedError

internal class EntitlementUnverifiedErrorDto(
    private val fieldValues: List<Data?>
) : EntitlementUnverifiedError, CompositeDto(fieldValues) {

    constructor(publisherId: String) : this(fieldValues = listOf(publisherId.toTString()))

    override fun toString(): String =
        "Entitlement signature did not verify for publisher ${fieldValues.firstOrNull()}"
}
