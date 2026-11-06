package org.tenshu.sync.proxy.impl

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId

/**
 * Sees every OnDemand **pull** [OnDemandResolutionImpl] serves: a client on this device named
 * `propertyId` on `entityId` through a `select { relations(...) }`, and the proxy is about to make
 * sure the bytes are here.
 *
 * ⭐ Exists for one consumer today — `drive-synced-thumbnails.md` §15.2's *"the pull is the
 * declaration"*: the device that pulls `File.ThumbnailXLarge` is a device that renders the hero
 * class, and recording that fact at **class** granularity is what lets every byte holder in the
 * realm mint the class for the files it holds (§15.3). The observer is handed the property id
 * and nothing about what it means, so `:sync-service-proxy` learns no file-service vocabulary.
 *
 * ⚠ Fires for every pull, present or not — `SelectImplementation` leaves a placeholder for each
 * OnDemand relation it projects, and the materialiser resolves each one — so an observer must be
 * cheap in steady state: a set lookup, not a store read. ⚠ Never on the local `resolveProperty`
 * path: `GetPreview`'s ladder probes every stored class through that path and would otherwise
 * declare `XLARGE` on every device that ever rendered a preview.
 *
 * ⚠ Best-effort by contract: a throwing observer is swallowed by the resolver (cancellation
 * excepted), because a bookkeeping hook must never fail the fetch it observed.
 */
fun interface OnDemandPullObserver {
    suspend fun onPull(executionContext: ExecutionContext, entityId: DataId, propertyId: String)
}
