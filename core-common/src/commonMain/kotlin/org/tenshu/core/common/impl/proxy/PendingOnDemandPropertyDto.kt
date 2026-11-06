package org.tenshu.core.common.impl.proxy

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.proxy.PendingOnDemandProperty

internal class PendingOnDemandPropertyDto(
    fieldValues: List<Data?>
) : PendingOnDemandProperty, CompositeDto(fieldValues)
