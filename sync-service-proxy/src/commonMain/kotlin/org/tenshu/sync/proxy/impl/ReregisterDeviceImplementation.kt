package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.RealmRegistrar

/**
 * Re-assert this device's relay registration after the relay forgot it (e.g. an
 * in-memory relay restarted under a long-lived daemon, dropping its `KnownDevicesStore`).
 * See `ReregisterDevice` (sync-service) for the contract + rationale; threat-model
 * §"Relay re-registration" for the trust analysis.
 *
 * Unchanged trust surface: this reuses the **existing** unauthenticated-by-JWT,
 * master-capability endpoints (`POST /sync/realm` + `POST /sync/admit-device`). The
 * master self-signature / delegation is the capability the relay already verifies; no
 * new authority is granted and the relay still only sees what those endpoints saw
 * before.
 */
internal class ReregisterDeviceImplementation(
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    private val relay: SyncRelay,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val ctx = executionContext.context
        val registrar = RealmRegistrar(syncManager, cryptoManager, relay)

        // Master-identity (A1) holder only. The owner (CreateRealm) and any recovered
        // device hold A1; a pairing JOINER does not — it received derivedKeys + A13 +
        // a one-shot admission delegation, never the master key, so it can neither
        // re-bootstrap the realm record nor sign a fresh self-admission. Fail loudly
        // rather than silently no-op: a joiner's recourse is re-pairing or the owner
        // re-admitting it.
        val masterKeyPair = registrar.masterIdentity()
            ?: throw TenshuException(
                GenericError.create(
                    ctx,
                    "Device cannot self-reregister without the master identity; re-pair required",
                )
            )

        // The two-step repair (re-assert genesis, then self-admit on a 409) lives in
        // `RealmRegistrar` so the `SyncScheduler`'s sustained-401 recovery runs the *same*
        // sequence without routing through the operation layer. This op is now the
        // user/client-facing entry point to it, and owns only the A1 precondition above.
        val result = registrar.reregisterThisDevice(masterKeyPair)
        if (result is TResult.Failure<*>) throw TenshuException(result.error)
        return null as T
    }
}
