package app.tenshu.sdk.internal

import kotlinx.browser.localStorage
import kotlinx.coroutines.test.runTest
import org.khronos.webgl.Int8Array
import kotlin.js.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The landing page's runtime-free session hint (`SessionHint`, read by
// `web/apex/src/lib/sessionHint.ts`). ⚠ The JSON keys are a contract with that reader — a rename
// here makes every returning visitor look first-time, with nothing failing.
class SessionHintTest {
    private var saved: String? = null

    @BeforeTest
    fun save() {
        saved = localStorage.getItem(SessionHint.KEY)
    }

    @AfterTest
    fun restore() {
        val previous = saved
        if (previous == null) localStorage.removeItem(SessionHint.KEY) else localStorage.setItem(SessionHint.KEY, previous)
    }

    @Test
    fun theSessionStoreMirrorsItselfIntoTheHint_andClearDropsIt() = runTest {
        val store = IndexedDbSessionStore.open("test-session-hint-${Date.now().toLong()}")

        store.setDeviceName("Test Browser")
        store.setSessionToken(Int8Array(4), 4_102_444_800_000.0)
        store.flush()

        val hint = JSON.parse<dynamic>(localStorage.getItem(SessionHint.KEY) ?: error("no hint written"))
        assertEquals("Test Browser", hint.deviceName as String)
        assertEquals(4_102_444_800_000.0, (hint.expiresAtMs as Number).toDouble())

        store.clear()
        assertNull(localStorage.getItem(SessionHint.KEY), "a cleared session leaves no hint")
    }
}
