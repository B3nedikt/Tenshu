package org.tenshu.place.proxy

import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.place.RecordPlace
import org.tenshu.core.place.RecordPlaceMerge
import org.tenshu.core.place.RecordPlaceNetworkFingerprint
import org.tenshu.core.place.RecordPlaceRole
import org.tenshu.place.proxy.executor.RecordPlaceImplementation
import org.tenshu.place.proxy.executor.RecordPlaceMergeImplementation
import org.tenshu.place.proxy.executor.RecordPlaceNetworkFingerprintImplementation
import org.tenshu.place.proxy.executor.RecordPlaceRoleImplementation

fun placeServiceProxyImplementationFactory(
    id: String
): OperationImplementation? {
    return when (id) {
        RecordPlace.ID -> RecordPlaceImplementation()
        RecordPlaceNetworkFingerprint.ID -> RecordPlaceNetworkFingerprintImplementation()
        RecordPlaceMerge.ID -> RecordPlaceMergeImplementation()
        RecordPlaceRole.ID -> RecordPlaceRoleImplementation()
        else -> null
    }
}
