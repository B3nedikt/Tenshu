package org.tenshu.sdk.ios

import app.tenshu.control.data.PublisherClient
import app.tenshu.control.ui.screens.services.ServicesStatusSnapshot
import app.tenshu.control.ui.screens.services.StorageStatusModel
import app.tenshu.control.ui.screens.services.servicesStatusSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.tenshu.core.common.TenshuClient
import org.tenshu.storage.client.status
import org.tenshu.storage.client.storageService

/**
 * The read-only Services status for Control-on-iOS
 * (`docs/proposals/ios-services-status-and-purchase.md` S1) — the iOS stand-in for the
 * Compose `ServicesViewModel`, flattened to [ServicesStatusSnapshot] scalars for the
 * SKIE bridge. Obtained via [ControlSurface.servicesStatusModel]; SwiftUI observes
 * [snapshot] as an `AsyncSequence` (the `SyncDashboardView` pattern) and seeds from
 * `.value`.
 *
 * Combines the same two signals the Compose screen binds: the device-local commerce
 * account ([PublisherClient.account]) and the locally-derived `StorageStatus` row
 * (which already carries the live `/sync/usage`-fed `usedBytes` — retention-quota P1),
 * projected exactly as `:control-shared`'s `storageStatusModelFlow()` does.
 */
class ServicesStatusModel internal constructor(
    publisherClient: PublisherClient?,
    storageStatusFlow: Flow<StorageStatusModel?>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val snapshot: StateFlow<ServicesStatusSnapshot> =
        combine(
            publisherClient?.account ?: flowOf(null),
            storageStatusFlow,
        ) { account, status -> servicesStatusSnapshot(account, status) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ServicesStatusSnapshot.EMPTY)

    /** Stop observing; call when the view disappears for good (optional — scope is cheap). */
    fun close() {
        scope.cancel()
    }

    internal companion object {
        /** The real-runtime binding: account from the commerce client, status off the store. */
        fun over(publisherClient: PublisherClient, client: TenshuClient): ServicesStatusModel =
            ServicesStatusModel(
                publisherClient = publisherClient,
                storageStatusFlow = client.storageService.status().map { status ->
                    status?.let {
                        StorageStatusModel(
                            hasStorage = it.hasStorage.get(),
                            usedBytes = it.usedBytes.get(),
                            limitBytes = it.limitBytes.get(),
                        )
                    }
                },
            )

        /** Fakes/DEBUG screenshot path: no runtime installed → the empty floor. */
        fun empty(): ServicesStatusModel =
            ServicesStatusModel(publisherClient = null, storageStatusFlow = flowOf(null))
    }
}
