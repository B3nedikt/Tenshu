package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.identity.OutboundAcceptance

/** Wire DTO for [OutboundAcceptance] (RO D7). */
class OutboundAcceptanceDto(
    fieldValues: List<Data?>,
) : OutboundAcceptance, CompositeDto(fieldValues)
