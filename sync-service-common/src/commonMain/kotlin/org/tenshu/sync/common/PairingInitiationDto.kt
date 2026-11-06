package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.PairingInitiation

internal class PairingInitiationDto(
    fieldValues: List<Data?>
) : PairingInitiation, CompositeDto(fieldValues)
