package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.identity.RealmKeyDelivery

/** Wire DTO for [RealmKeyDelivery] (GR-7 catch-up). */
class RealmKeyDeliveryDto(
    fieldValues: List<Data?>,
) : RealmKeyDelivery, CompositeDto(fieldValues)
