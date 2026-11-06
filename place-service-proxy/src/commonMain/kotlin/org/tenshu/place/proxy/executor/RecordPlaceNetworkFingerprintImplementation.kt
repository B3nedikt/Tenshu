package org.tenshu.place.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.place.PlaceNetworkFingerprint
import org.tenshu.core.place.RecordPlaceNetworkFingerprint

internal class RecordPlaceNetworkFingerprintImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val fingerprint: PlaceNetworkFingerprint = params.read(RecordPlaceNetworkFingerprint.Params.FINGERPRINT)

        // `ExecutionContext.saveData` — fires the SyncObserver (outbound SyncBlock) and the generic
        // RelationMaintainer. Never `Database.saveData`, which bypasses both.
        executionContext.saveData(fingerprint)

        return null as T
    }
}
