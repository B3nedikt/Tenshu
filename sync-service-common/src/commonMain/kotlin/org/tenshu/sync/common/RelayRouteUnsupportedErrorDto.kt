package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.toTString
import org.tenshu.sync.RelayRouteUnsupportedError

internal class RelayRouteUnsupportedErrorDto(
    private val fieldValues: List<Data?>
) : RelayRouteUnsupportedError, CompositeDto(fieldValues) {

    constructor(route: String) : this(fieldValues = listOf(route.toTString()))

    override fun toString(): String = "Relay does not implement ${fieldValues.firstOrNull()}"
}
