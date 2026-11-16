package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.identity.AwaitedAcceptance

/** Wire DTO for [AwaitedAcceptance] (RO D7). */
class AwaitedAcceptanceDto(
    fieldValues: List<Data?>,
) : AwaitedAcceptance, CompositeDto(fieldValues)
