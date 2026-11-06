package org.tenshu.place.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.place.PlaceNetworkFingerprint
import org.tenshu.core.schema.CompositeSchema

internal class PlaceNetworkFingerprintDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), PlaceNetworkFingerprint {

    override val schema: CompositeSchema get() = PlaceNetworkFingerprint.Schema
}
