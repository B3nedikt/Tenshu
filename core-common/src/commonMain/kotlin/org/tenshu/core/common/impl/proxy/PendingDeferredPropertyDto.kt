package org.tenshu.core.common.impl.proxy

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.proxy.PendingDeferredProperty

internal class PendingDeferredPropertyDto(
    fieldValues: List<Data?>
) : PendingDeferredProperty, CompositeDto(fieldValues)
