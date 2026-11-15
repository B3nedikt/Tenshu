package app.tenshu.sdk.internal

import kotlinx.browser.localStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The browser's pause switch (`SyncPause`, `web-apex-home.md` §4.6). ⚠ Device-local: it lives in
// this origin's `localStorage`, and a reload must come back paused — otherwise a user who paused,
// then refreshed, is silently syncing again.
class SyncPauseTest {
    private var saved: String? = null

    @BeforeTest
    fun save() {
        saved = localStorage.getItem(SyncPause.KEY)
        localStorage.removeItem(SyncPause.KEY)
        SyncPause.reloadForTest()
        SyncPause.onResumed = null
    }

    @AfterTest
    fun restore() {
        val previous = saved
        if (previous == null) localStorage.removeItem(SyncPause.KEY) else localStorage.setItem(SyncPause.KEY, previous)
        SyncPause.reloadForTest()
        SyncPause.onResumed = null
    }

    @Test
    fun pausingClosesTheGate_persists_andSurvivesAReload() = runTest {
        assertTrue(SyncPause.gate.shouldSync(), "a browser that never paused syncs")

        SyncPause.setPaused(true)
        assertTrue(SyncPause.paused.value)
        assertFalse(SyncPause.gate.shouldSync(), "paused: the scheduler's pause gate is closed")
        assertEquals("1", localStorage.getItem(SyncPause.KEY))

        SyncPause.reloadForTest() // as a fresh page would
        assertTrue(SyncPause.paused.value, "a reload must come back paused")

        SyncPause.setPaused(false)
        assertTrue(SyncPause.gate.shouldSync())
        assertNull(localStorage.getItem(SyncPause.KEY), "resuming leaves nothing behind")
    }

    @Test
    fun resumingWakesTheLoopOnce_andPausingDoesNot() = runTest {
        var wakes = 0
        SyncPause.onResumed = { wakes++ }
        SyncPause.setPaused(true)
        assertEquals(0, wakes, "pausing must not wake the loop")
        SyncPause.setPaused(false)
        assertEquals(1, wakes, "resuming wakes it, so sync does not wait out a tick")
        SyncPause.setPaused(false)
        assertEquals(1, wakes, "a repeated resume is a no-op")
    }
}
