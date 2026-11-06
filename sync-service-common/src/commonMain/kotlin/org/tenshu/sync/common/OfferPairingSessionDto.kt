package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.OfferPairingSession

internal class OfferPairingSessionDto(
    fieldValues: List<Data?>
) : OfferPairingSession, CompositeDto(fieldValues)
