package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.PairingRequest

class PairingRequestDto(
    fieldValues: List<Data?>
) : PairingRequest, CompositeDto(fieldValues)
