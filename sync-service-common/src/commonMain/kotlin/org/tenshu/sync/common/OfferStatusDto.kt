package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.OfferStatus

internal class OfferStatusDto(
    fieldValues: List<Data?>
) : OfferStatus, CompositeDto(fieldValues)
