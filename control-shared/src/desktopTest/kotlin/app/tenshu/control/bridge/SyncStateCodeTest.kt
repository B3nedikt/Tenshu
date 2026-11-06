package app.tenshu.control.bridge

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Precedence truth table for [syncStateCode] — the bgproc §5.3 tray-state
 * derivation ([ControlBridge]'s `getSyncState`). Verifies each state wins over
 * every state below it (§5.3 order: DISABLED > LOCKED > PAUSED > DIVERGENCE >
 * NEEDS_UPDATE > OFFLINE > SYNCING > SYNCED).
 *
 * ⚠ `divergence` here is the **recency-bounded** flag the bridge computes
 * ([divergenceIsFresh]), not the raw presence of a warning — precedence is unchanged, but
 * what feeds it is. `DivergenceFreshnessTest` owns that half.
 */
class SyncStateCodeTest {

    // A fully-healthy baseline: enabled, unlocked, not paused, no divergence,
    // online, idle.
    private fun code(
        enabled: Boolean = true,
        locked: Boolean = false,
        paused: Boolean = false,
        divergence: Boolean = false,
        schemaFenced: Boolean = false,
        online: Boolean = true,
        syncing: Boolean = false,
        pendingBlockCount: Int = 0,
    ) = syncStateCode(enabled, locked, paused, divergence, schemaFenced, online, syncing, pendingBlockCount)

    @Test
    fun healthyIdleIsSynced() {
        assertEquals(SyncStateCode.SYNCED, code())
    }

    @Test
    fun disabledWinsOverEverything() {
        // Even when locked/paused/diverging/offline/syncing, "sync off" is what shows.
        assertEquals(
            SyncStateCode.DISABLED,
            code(enabled = false, locked = true, paused = true, divergence = true, online = false, syncing = true, pendingBlockCount = 9),
        )
    }

    @Test
    fun lockedWinsOverPausedAndBelow() {
        assertEquals(
            SyncStateCode.LOCKED,
            code(locked = true, paused = true, divergence = true, online = false, syncing = true, pendingBlockCount = 3),
        )
    }

    @Test
    fun pausedWinsOverDivergenceAndBelow() {
        // The user's explicit pause reads as Paused even if a stale divergence
        // warning or backlog lingers.
        assertEquals(
            SyncStateCode.PAUSED,
            code(paused = true, divergence = true, online = false, syncing = true, pendingBlockCount = 5),
        )
    }

    @Test
    fun divergenceWinsOverOfflineAndSyncing() {
        // A relay HLC-divergence warning is shown even while otherwise active.
        assertEquals(
            SyncStateCode.DIVERGENCE,
            code(divergence = true, online = false, syncing = true, pendingBlockCount = 5),
        )
    }

    @Test
    fun divergenceWinsOverNeedsUpdate() {
        // The fresh-divergence headline is time-bounded, so the fence still surfaces once it ages.
        assertEquals(SyncStateCode.DIVERGENCE, code(divergence = true, schemaFenced = true))
        assertEquals(SyncStateCode.PAUSED, code(paused = true, schemaFenced = true))
    }

    /**
     * A schema-fenced device reads NEEDS_UPDATE. It keeps pushing and its relay is reachable, so
     * without this state it reads SYNCED (idle) or SYNCING (backlog) indefinitely.
     */
    @Test
    fun needsUpdateWinsOverOfflineSyncingAndSynced() {
        assertEquals(SyncStateCode.NEEDS_UPDATE, code(schemaFenced = true))
        assertEquals(SyncStateCode.NEEDS_UPDATE, code(schemaFenced = true, syncing = true, pendingBlockCount = 4))
        assertEquals(SyncStateCode.NEEDS_UPDATE, code(schemaFenced = true, online = false))
    }

    @Test
    fun offlineWinsOverSyncing() {
        assertEquals(
            SyncStateCode.OFFLINE,
            code(online = false, syncing = true, pendingBlockCount = 2),
        )
    }

    @Test
    fun syncingWhenActivelyDraining() {
        assertEquals(SyncStateCode.SYNCING, code(syncing = true))
    }

    @Test
    fun syncingWhenBlocksAreQueued() {
        // A backlog with the tick idle still reads as Syncing (work pending).
        assertEquals(SyncStateCode.SYNCING, code(syncing = false, pendingBlockCount = 1))
    }

    @Test
    fun onlineIdleNoBacklogIsSynced() {
        assertEquals(SyncStateCode.SYNCED, code(online = true, syncing = false, pendingBlockCount = 0))
    }
}
