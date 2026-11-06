package app.tenshu.control.ui.lock

import app.tenshu.control.data.AppLockAuthResult
import app.tenshu.control.data.AppLockAuthResult.Cancelled
import app.tenshu.control.data.AppLockAuthResult.Failure
import app.tenshu.control.data.AppLockAuthResult.Success
import app.tenshu.control.data.DesktopAppLock
import app.tenshu.control.data.EmbeddedAuthSlot
import app.tenshu.control.data.EmbeddedLocalAuth
import app.tenshu.control.data.InProcessLocalAuth
import app.tenshu.control.ui.lock.LockPromptFlow.Request
import app.tenshu.control.ui.lock.LockPromptFlow.Route
import app.tenshu.control.ui.lock.LockPromptFlow.TouchIdOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * When the desktop lock overlay shows what — above all, that **a Mac with usable Touch ID never
 * lands on the *Unlock* button** ([Route.Retry]), which only re-asked for the fingerprint
 * (reported 2026-10-22). Driven through a real [DesktopAppLock] over fake backends.
 */
class LockPromptFlowTest {

    /** Answers from a queue; an exhausted queue keeps answering its last entry. */
    private class Queue(vararg results: AppLockAuthResult) {
        private val left = ArrayDeque(results.toList())
        var calls = 0
        fun next(): AppLockAuthResult {
            calls++
            return if (left.size > 1) left.removeFirst() else left.first()
        }
    }

    private class FakeEmbedded(var available: Boolean, val results: Queue) : EmbeddedLocalAuth {
        override fun isAvailable() = available
        override fun authenticate(slot: EmbeddedAuthSlot, reason: String, isCancelled: () -> Boolean) = results.next()
        override fun move(slot: EmbeddedAuthSlot) = Unit
        override fun cancel() = Unit
    }

    private class FakeSheet(val results: Queue, val onShown: () -> Unit = {}) : InProcessLocalAuth {
        override fun isAvailable() = true
        override fun authenticate(reason: String): AppLockAuthResult {
            onShown()
            return results.next()
        }
    }

    private val slot = EmbeddedAuthSlot("Tenshu Control", 800.0, 600.0, 368.0, 300.0, 64.0, 64.0)
    private var unlocked = 0

    private fun flow(embedded: FakeEmbedded?, sheet: FakeSheet) = LockPromptFlow(
        DesktopAppLock(helperPath = null, inProcess = sheet, embedded = embedded, raiseToForeground = {}),
    ) { unlocked++ }

    @Test
    fun touchIdUsable_goesStraightToTouchId_withNoSheet() = runTest {
        val sheet = FakeSheet(Queue(Success))
        val flow = flow(FakeEmbedded(available = true, results = Queue(Success)), sheet)
        flow.choose("r", Request.Auto)
        assertEquals(Route.TouchId, flow.route)
        assertEquals(0, sheet.results.calls)
    }

    /** ⚠⚠ The reported case: macOS cancelled the check (app not active) — ask again, no button. */
    @Test
    fun aCancelWhileFocused_asksAgain_ratherThanWaitingForAButton() = runTest {
        val embedded = FakeEmbedded(available = true, results = Queue(Cancelled, Cancelled, Success))
        val flow = flow(embedded, FakeSheet(Queue(Success)))
        flow.choose("r", Request.Auto)
        assertEquals(TouchIdOutcome.Unlocked, flow.touchId("r") { slot })
        assertEquals(3, embedded.results.calls)
        assertEquals(1, unlocked)
        assertEquals(Route.TouchId, flow.route)
    }

    /** ⚠⚠ Five wrong fingers lock Touch ID — the password is the only way on. */
    @Test
    fun aFailure_needsThePassword() = runTest {
        val flow = flow(FakeEmbedded(available = true, results = Queue(Failure)), FakeSheet(Queue(Success)))
        flow.choose("r", Request.Auto)
        assertEquals(TouchIdOutcome.NeedsPassword, flow.touchId("r") { slot })
        assertEquals(0, unlocked)
    }

    @Test
    fun closingThePasswordSheet_returnsToTouchId_notToAButton() = runTest {
        val flow = flow(FakeEmbedded(available = true, results = Queue(Success)), FakeSheet(Queue(Cancelled)))
        flow.choose("r", Request.UserPassword)
        assertEquals(Route.TouchId, flow.route)
        assertEquals(0, unlocked)
    }

    /**
     * ⚠⚠ Review finding, 2026-10-22: Touch ID that fails at once while still reporting itself
     * available (can't bind, window not found) re-opened the sheet the user had just closed, forever.
     * A sheet that Touch ID's failure opened ends on the button when closed.
     */
    @Test
    fun closingTheSheetThatTouchIdsFailureOpened_endsOnTheButton_notInALoop() = runTest {
        val embedded = FakeEmbedded(available = true, results = Queue(Failure))
        val sheet = FakeSheet(Queue(Cancelled))
        val flow = flow(embedded, sheet)
        flow.choose("r", Request.Auto)
        assertEquals(TouchIdOutcome.NeedsPassword, flow.touchId("r") { slot })
        flow.choose("r", Request.TouchIdFailed)
        assertEquals(Route.Retry, flow.route)
        assertEquals(1, sheet.results.calls)
    }

    /** While the sheet is up nothing is drawn under the title — no button behind the dialog. */
    @Test
    fun whileTheSheetIsUp_theRouteIsPassword() = runTest {
        var routeDuringSheet: Route? = null
        lateinit var flow: LockPromptFlow
        flow = flow(
            FakeEmbedded(available = true, results = Queue(Success)),
            FakeSheet(Queue(Success)) { routeDuringSheet = flow.route },
        )
        flow.choose("r", Request.UserPassword)
        assertEquals(Route.Password, routeDuringSheet)
        assertEquals(1, unlocked)
    }

    /** A correct password after a lockout unlocks; it is not followed by Touch ID. */
    @Test
    fun afterALockout_thePasswordUnlocks() = runTest {
        val embedded = FakeEmbedded(available = false, results = Queue(Success))
        val flow = flow(embedded, FakeSheet(Queue(Success)))
        flow.choose("r", Request.Auto)
        assertEquals(1, unlocked)
        assertEquals(0, embedded.results.calls)
    }

    /** The one place the button stays: no usable Touch ID, and the user closed the sheet. */
    @Test
    fun noTouchId_andTheSheetClosed_isTheOnlyRouteToTheButton() = runTest {
        val flow = flow(FakeEmbedded(available = false, results = Queue(Success)), FakeSheet(Queue(Cancelled)))
        flow.choose("r", Request.Auto)
        assertEquals(Route.Retry, flow.route)

        val noBackend = flow(null, FakeSheet(Queue(Cancelled)))
        noBackend.choose("r", Request.Auto)
        assertEquals(Route.Retry, noBackend.route)
    }
}
