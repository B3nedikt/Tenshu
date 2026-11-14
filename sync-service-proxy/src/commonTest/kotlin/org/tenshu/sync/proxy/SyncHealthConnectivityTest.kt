package org.tenshu.sync.proxy

import org.tenshu.sync.SyncHealthStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [connectivity] is what the store's health row says AND what an iOS app's strip reads in-process
 * (`suite-sync-status.md` SS-1b), so its ranks are pinned here once for both.
 */
class SyncHealthConnectivityTest {

    @Test
    fun aFreshHolder_readsOnline_soAnUnstartedRuntimeShowsNoStrip() {
        assertEquals(SyncHealthStatus.CONNECTIVITY_ONLINE, SyncHealthFigures().connectivity())
    }

    @Test
    fun theRanks() {
        val all = SyncHealthFigures(
            online = false,
            locked = true,
            deviceUnrecognized = true,
            entitlementBlocked = true,
            storageRejected = true,
            deferred = true,
            contentRetrying = true,
        )
        assertEquals(SyncHealthStatus.CONNECTIVITY_LOCKED, all.connectivity())
        assertEquals(SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED, all.copy(locked = false).connectivity())
        val unblocked = all.copy(locked = false, deviceUnrecognized = false)
        assertEquals(SyncHealthStatus.CONNECTIVITY_NEEDS_ATTENTION, unblocked.connectivity())
        val credentialed = unblocked.copy(entitlementBlocked = false)
        assertEquals(SyncHealthStatus.CONNECTIVITY_STORAGE_FULL, credentialed.connectivity())
        assertEquals(SyncHealthStatus.CONNECTIVITY_DEFERRED, credentialed.copy(storageRejected = false).connectivity())
        val moving = credentialed.copy(storageRejected = false, deferred = false)
        assertEquals(SyncHealthStatus.CONNECTIVITY_RELAY_RETRYING, moving.connectivity())
        assertEquals(SyncHealthStatus.CONNECTIVITY_OFFLINE, moving.copy(contentRetrying = false).connectivity())
    }
}
