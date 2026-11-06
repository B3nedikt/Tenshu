package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.RealmRegistry
import org.tenshu.sync.proxy.SharedRealmBootstrapper

/**
 * SDK entry point for shared-realm creation (`CreateSharedRealm`). A thin wrapper
 * over [SharedRealmBootstrapper] (SR-3b.1): generate the realm's Ed25519 identity +
 * A12, `POST /sync/realm`, self-join with a `SharedRealmMembership(OWNER)`, and
 * register the shared realm into the [realmRegistry]. Returns the new shared realm's
 * `ownerId` as a `TString`.
 *
 * `AsyncOperationImplementation` (House Rule #3 — non-suspend `Operation` interface;
 * the async-ness rides the `IoOperation`/`TaskInteractor` layer). Reuses the device's
 * personal [relay] client for the unauthenticated `createRealm` (the bootstrapper
 * doesn't need a per-realm relay to bootstrap).
 */
internal class CreateSharedRealmImplementation(
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    private val relay: SyncRelay,
    private val realmRegistry: RealmRegistry,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val realm = SharedRealmBootstrapper(
            executionContext = executionContext,
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            relay = relay,
            realmRegistry = realmRegistry,
        ).createSharedRealm()

        return realm.ownerId.toTString(executionContext.context) as T
    }
}
