package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.common.SyncManager

internal class GetSyncStatusImplementation(
    private val syncManager: SyncManager
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val status = SyncStatus.create(
            context = executionContext.context,
            deviceId = syncManager.currentDeviceId,
            // ⚠ `residentOwnerId`, NOT `currentOwnerId`: the in-memory value is null for the
            // seconds a cold-start autoload spends in Argon2id, and callers read this field to
            // decide durable things — `LandingStateRepository.detect()` routed to the
            // "Start fresh on this device" onboarding screen, and `PairingWizardViewModel`
            // to the offerer role, on a device that already held a realm. `isEnabled` below
            // remains the honest "can this device sync right now" flag.
            ownerId = syncManager.residentOwnerId,
            isEnabled = syncManager.isEnabled(),
            // Reflect any in-flight *host* pairing session. `getPairingString()` is
            // read-only — it returns the active pairing context's code or null when no
            // session exists; it does NOT generate or mutate anything (unlike
            // StartPairing's generatePairingContext()). Surfacing it here lets a
            // recreated UI (e.g. the Control window closed + reopened, destroying and
            // rebuilding SyncDashboardViewModel) discover that the durable proxy-side
            // pairing session is still live and resume polling for the joiner's request,
            // instead of silently sitting idle until the user clicks "Add device" again.
            pairingString = syncManager.getPairingString()
        )

        return status as T
    }
}
